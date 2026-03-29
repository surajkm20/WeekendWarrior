package com.rcb.notifier;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.HashSet;
import java.util.Set;

/**
 * Phase 2 — Auto-Booking Flow
 *
 * Once tickets are detected as live, this class:
 *   1. Loads the saved session (no OTP needed)
 *   2. Navigates to the ticket page
 *   3. Clicks "Book Now" automatically
 *   4. Selects ticket quantity from config
 *   5. Proceeds to the payment page
 *   6. Captures the UPI QR code and sends it to Telegram
 *   7. Extracts UPI deep link (upi://) and sends it as a tap-to-pay link
 *
 * The user only needs to open Telegram on their phone and either:
 *   - Scan the QR code screenshot, OR
 *   - Tap the upi:// link to open GPay/PhonePe directly
 */
public class BookingFlow {

    private final Config config;
    private final CookieStore cookieStore;
    private final TelegramNotifier notifier;
    private final String accountLabel; // e.g. "Account 1 (9113573894)"

    public BookingFlow(Config config, CookieStore cookieStore, TelegramNotifier notifier, String accountLabel) {
        this.config = config;
        this.cookieStore = cookieStore;
        this.notifier = notifier;
        this.accountLabel = accountLabel;
    }

    /**
     * Runs the full auto-booking flow.
     * Opens a VISIBLE browser so you can watch and intervene if needed.
     */
    public void run() {
        System.out.println("[BookingFlow] Starting auto-booking...");

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(false)  // visible — you can see and intervene
                    .setSlowMo(300)      // slight delay between actions
            );

            // Restore saved login session — no OTP needed
            BrowserContext context = browser.newContext(
                cookieStore.loadOptions()
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                                  "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                  "Chrome/124.0.0.0 Safari/537.36")
                    .setViewportSize(1440, 900)
            );

            Page page = context.newPage();

            // ── Step 1: Navigate to ticket page ──────────────────────────────
            String ticketUrl = config.getTicketUrl();
            System.out.println("[BookingFlow] Navigating to: " + ticketUrl);
            page.navigate(ticketUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));

            // Check if session expired — site redirected us to /auth
            if (page.url().contains("/auth")) {
                System.out.println("[BookingFlow] Session expired — need re-login.");
                notifier.sendMessage(
                    "[" + accountLabel + "] ⚠️ Session expired!\n" +
                    "Run login again, then book manually:\n" + ticketUrl
                );
                browser.close();
                return;
            }

            // Send ticket page screenshot so you can see what the bot sees
            byte[] ticketPageShot = page.screenshot();
            notifier.sendPhoto(ticketPageShot, "[" + accountLabel + "] 🎟️ Tickets LIVE! Starting auto-booking...");

            // ── Steps 2–5: Seat selection + booking with retry on "seat taken" ──
            // Tracks row letters already tried across all stands (e.g. {"F", "G"}).
            // Rows are sorted alphabetically — F before G, G before H, etc.
            // If row F is taken in any stand, the next attempt skips all row F options.
            Set<String> triedRows = new HashSet<>();

            while (true) {
                // ── Step 2: Pick best available row (skip already-tried rows) ──
                String seatResult = selectBestSeatCategory(page, triedRows);
                if (seatResult.equals("NOT_FOUND")) {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    notifier.sendPhoto(screenshot,
                        "[" + accountLabel + "] ⚠️ No seats left across all target stands " +
                        "(tried rows: " + triedRows + ").\n" +
                        "Book manually: " + ticketUrl
                    );
                    browser.close();
                    return;
                }

                // Extract row letter from "Row F — SUN PHARMA A STAND — ₹3750"
                String rowLetter = seatResult.replaceAll(".*Row\\s+([A-Z]).*", "$1");
                if (rowLetter.equals(seatResult)) rowLetter = seatResult; // fallback

                System.out.println("[BookingFlow] Trying seat: " + seatResult);
                notifier.sendMessage("[" + accountLabel + "] 🔍 Trying: " + seatResult);
                page.waitForTimeout(1500);

                // ── Step 2b: If a seat map appeared, pick 2 adjacent seats ────
                // If no seat map is shown (site assigns seats automatically), this
                // returns "NO_SEAT_MAP" and we fall through to the quantity selector.
                String seatPair = selectAdjacentSeats(page, config.getTicketQuantity());
                if (!seatPair.equals("NO_SEAT_MAP")) {
                    System.out.println("[BookingFlow] Adjacent pair selected: " + seatPair);
                    notifier.sendMessage("[" + accountLabel + "] 💺 " + seatPair);
                    page.waitForTimeout(1000);
                }

                // ── Step 3: Click "Book Now" ──────────────────────────────────
                boolean clicked = tryClickBookButton(page);
                if (!clicked) {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                    notifier.sendPhoto(screenshot,
                        "[" + accountLabel + "] ⚠️ Couldn't find 'Book Now' for " + seatResult + ".\n" +
                        "Book manually: " + ticketUrl
                    );
                    browser.close();
                    return;
                }

                page.waitForLoadState();
                page.waitForTimeout(2000);

                // Check if the site immediately says this seat is taken
                if (isSeatTakenError(page)) {
                    System.out.println("[BookingFlow] Seat taken after Book Now: " + seatResult);
                    notifier.sendMessage("[" + accountLabel + "] ⚠️ Row " + rowLetter + " taken! Trying next row...");
                    triedRows.add(rowLetter);
                    page.navigate(ticketUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                    page.waitForTimeout(1500);
                    continue;
                }

                // ── Step 4: Select ticket quantity (2 per booking) ───────────
                selectQuantity(page);

                // ── Step 5: Proceed to checkout ──────────────────────────────
                tryProceedToCheckout(page);
                page.waitForTimeout(3000);

                // Check again — some sites only reveal "taken" at checkout
                if (isSeatTakenError(page)) {
                    System.out.println("[BookingFlow] Seat taken at checkout: " + seatResult);
                    notifier.sendMessage("[" + accountLabel + "] ⚠️ Row " + rowLetter + " taken at checkout! Trying next row...");
                    triedRows.add(rowLetter);
                    page.navigate(ticketUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                    page.waitForTimeout(1500);
                    continue;
                }

                // ── Step 6: Capture payment page (QR + UPI link) ─────────────
                capturePaymentPage(page, seatResult);
                break; // success — exit retry loop
            }

            browser.close();

        } catch (Exception e) {
            System.err.println("[BookingFlow] Error: " + e.getMessage());
            notifier.sendMessage(
                "[" + accountLabel + "] ❌ Error: " + e.getMessage() + "\n" +
                "Book manually: " + config.getTicketUrl()
            );
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Finds all visible ticket category cards on the page, filters by price
     * (₹3500–₹5000), then picks the one with the best block letter (A > B > C > D).
     * Clicks the chosen card and returns a description string, or "NOT_FOUND".
     *
     * Detection strategy: evaluates JS on the page to inspect every candidate
     * element's text, extract the price and block letter, filter + sort, then click.
     */
    /**
     * Scans the seat selection page for all available row options across the 5
     * target stands. Picks the option whose row letter comes earliest in the
     * alphabet (A first, then B, C … Z), skipping any row letters that have
     * already been tried and failed.
     *
     * Row letter is the primary sort key — it is independent of the stand name.
     * e.g. if SUN PHARMA A STAND starts at row F and BOAT C STAND starts at row C,
     * BOAT C STAND row C is tried first.
     *
     * @param excludedRows  row letters already tried and taken (e.g. {"F", "G"})
     * @return description string like "Row F — SUN PHARMA A STAND — ₹3750",
     *         or "NOT_FOUND" if nothing is left
     */
    private String selectBestSeatCategory(Page page, Set<String> excludedRows) {
        int minPrice = config.getMinTicketPrice();
        int maxPrice = config.getMaxTicketPrice();
        System.out.println("[BookingFlow] Scanning rows across target stands" +
            " (₹" + minPrice + "–₹" + maxPrice + ", skipping rows: " + excludedRows + ")...");

        Object result = page.evaluate("""
            (params) => {
                const { minPrice, maxPrice, excludedRows } = params;
                const candidates = [];

                // The 5 target stands — all are valid booking targets
                const knownStands = [
                    'sun pharma a stand',
                    'puma shanta rangaswamy b stand',
                    'boat c stand',
                    'confirmtkt d corporate',
                    'e stand',
                ];

                // Cast a wide net — rows may use any of these class patterns
                const els = Array.from(document.querySelectorAll(
                    '[class*="ticket" i], [class*="seat" i], [class*="category" i], ' +
                    '[class*="stand" i], [class*="block" i], [class*="tier" i], ' +
                    '[class*="zone" i], [class*="section" i], [class*="row" i], li, .card'
                ));

                for (const el of els) {
                    const text = (el.innerText || el.textContent || '').trim();
                    if (!text) continue;

                    // Skip sold-out / unavailable / taken entries
                    if (/sold.?out|not available|unavailable|seats are taken/i.test(text)) continue;

                    const textLower = text.toLowerCase();

                    // Must belong to one of the 5 target stands
                    const matchedStand = knownStands.find(s => textLower.includes(s));
                    if (!matchedStand) continue;

                    // Must have an explicit row letter:
                    //   "Row F", "Row: F", "F Row", "Ground Row F"
                    const rowMatch =
                        text.match(/\\bRow\\s*:?\\s*([A-Z])\\b/i) ||
                        text.match(/\\b([A-Z])\\s+Row\\b/i) ||
                        text.match(/\\bGround\\s+Row\\s+([A-Z])\\b/i);
                    if (!rowMatch) continue;

                    const row = rowMatch[1].toUpperCase();

                    // Skip rows we've already tried
                    if (excludedRows.includes(row)) continue;

                    // Extract price — must be within configured range
                    // Handles ₹3750, Rs 3750, Rs.3750, 3,750 etc.
                    const priceMatch = text.match(/[₹Rs.]*\\s*([\\d,]{3,6})/);
                    if (!priceMatch) continue;
                    const price = parseInt(priceMatch[1].replace(/,/g, ''));
                    if (isNaN(price) || price < minPrice || price > maxPrice) continue;

                    candidates.push({ el, row, price, stand: matchedStand, label: text.substring(0, 80) });
                }

                if (candidates.length === 0) return 'NOT_FOUND';

                // Primary sort: earliest row letter (A < B < … < Z)
                // Tiebreaker: stand name alphabetically (arbitrary but stable)
                candidates.sort((a, b) =>
                    a.row !== b.row
                        ? a.row.localeCompare(b.row)
                        : a.stand.localeCompare(b.stand)
                );

                const best = candidates[0];
                best.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                best.el.click();

                // Return format: "Row X — STAND NAME — ₹PRICE" (price omitted if 0)
                const standDisplay = best.stand.toUpperCase();
                const priceStr = best.price > 0 ? ' — ₹' + best.price : '';
                return 'Row ' + best.row + ' — ' + standDisplay + priceStr;
            }
            """,
            java.util.Map.of(
                "minPrice",    minPrice,
                "maxPrice",    maxPrice,
                "excludedRows", new java.util.ArrayList<>(excludedRows)
            )
        );

        return result != null ? result.toString() : "NOT_FOUND";
    }

    /**
     * After a row is selected, some sites show an individual seat map.
     * This method finds all available seat numbers on that map, then picks
     * the pair of seats with the smallest gap between them (adjacent = gap 1).
     *
     * Clicks both seats and returns e.g. "Seats 14 & 15 (adjacent)".
     * Returns "NO_SEAT_MAP" if no individual seat elements are detected —
     * the caller then falls through to the quantity selector instead.
     *
     * @param qty  number of tickets to select (usually 2)
     */
    private String selectAdjacentSeats(Page page, int qty) {
        if (qty < 2) return "NO_SEAT_MAP";

        Object result = page.evaluate("""
            (params) => {
                const { qty } = params;

                // Seat elements are usually small buttons/cells with a seat number.
                // Cast a wide net across common patterns.
                const allEls = Array.from(document.querySelectorAll(
                    '[data-seat], [data-seat-number], [data-seat-id], ' +
                    '[class*="seat-btn" i], [class*="seat-cell" i], [class*="seat-item" i], ' +
                    'td[class*="seat" i], button[class*="seat" i], ' +
                    '[class*="seatmap" i] button, [class*="seat-map" i] button, ' +
                    '[class*="seatMap" i] button'
                ));

                if (allEls.length === 0) return 'NO_SEAT_MAP';

                // Build list of available seats with their numbers
                const available = [];
                for (const el of allEls) {
                    // Skip hidden elements
                    if (!el.offsetParent) continue;

                    // Skip taken/booked/disabled seats by class or attribute
                    const cls = (el.className || '').toLowerCase();
                    const ariaDisabled = el.getAttribute('aria-disabled');
                    const disabled = el.disabled;
                    if (/taken|sold|booked|unavail|occupied|blocked|disabled/i.test(cls)) continue;
                    if (ariaDisabled === 'true' || disabled) continue;

                    // Extract seat number (prefer data attributes, fall back to text)
                    const rawNum =
                        el.getAttribute('data-seat') ||
                        el.getAttribute('data-seat-number') ||
                        el.getAttribute('data-seat-id') ||
                        (el.innerText || el.textContent || '').trim();
                    const num = parseInt(rawNum);
                    if (isNaN(num) || num <= 0) continue;

                    available.push({ el, num });
                }

                if (available.length < qty) return 'NO_SEAT_MAP';

                // Sort by seat number ascending
                available.sort((a, b) => a.num - b.num);

                // Find the consecutive pair with the smallest gap
                // (gap = 1 means perfectly adjacent; higher gaps are fallback)
                let bestStart = 0;
                let bestGap = Infinity;
                for (let i = 0; i <= available.length - qty; i++) {
                    const gap = available[i + qty - 1].num - available[i].num;
                    if (gap < bestGap) {
                        bestGap = gap;
                        bestStart = i;
                    }
                }

                // Click the best pair
                const chosen = available.slice(bestStart, bestStart + qty);
                for (const s of chosen) {
                    s.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    s.el.click();
                }

                const nums = chosen.map(s => s.num).join(' & ');
                const gapLabel = bestGap === (qty - 1) ? 'adjacent' : 'gap ' + bestGap;
                return 'Seats ' + nums + ' (' + gapLabel + ')';
            }
            """,
            java.util.Map.of("qty", qty)
        );

        return result != null ? result.toString() : "NO_SEAT_MAP";
    }

    /**
     * Returns true if the current page shows a "seat already taken" style error.
     * Checked immediately after "Book Now" and again after "Proceed to Checkout".
     */
    private boolean isSeatTakenError(Page page) {
        try {
            String text = page.innerText("body").toLowerCase();
            // "seats are taken" is the exact phrase shown by this venue's site
            if (text.contains("seats are taken")) return true;
            return text.contains("seat") && (
                       text.contains("taken")           ||
                       text.contains("already booked")  ||
                       text.contains("no longer available") ||
                       text.contains("sold out")        ||
                       text.contains("not available")   ||
                       text.contains("unavailable")     ||
                       text.contains("out of stock")
                   );
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Tries a series of common "Book Now" button selectors.
     * Returns true if one was found and clicked.
     */
    private boolean tryClickBookButton(Page page) {
        String[] selectors = {
            "button:has-text('Book Now')",
            "button:has-text('Buy Now')",
            "button:has-text('Buy Tickets')",
            "button:has-text('Book Tickets')",
            "button:has-text('Add to Cart')",
            "a:has-text('Book Now')",
            "a:has-text('Buy Now')",
            "[class*='book-now']",
            "[class*='buy-now']",
            "[data-testid*='book']",
            "[data-testid*='buy']",
        };

        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0 && locator.isVisible()) {
                    System.out.println("[BookingFlow] Clicking: " + selector);
                    locator.click();
                    return true;
                }
            } catch (Exception ignored) {
                // Selector not found or not visible — try next
            }
        }

        System.err.println("[BookingFlow] No 'Book Now' button found with known selectors.");
        return false;
    }

    /**
     * Sets ticket quantity if a quantity selector exists on the page.
     * Does nothing if quantity is 1 (default) or no selector found.
     */
    private void selectQuantity(Page page) {
        int qty = config.getTicketQuantity();
        if (qty <= 1) return;

        String[] selectors = {
            "select[name*='quantity' i]",
            "select[id*='quantity' i]",
            "input[name*='quantity' i]",
            "[aria-label*='quantity' i]",
            "[placeholder*='quantity' i]",
        };

        for (String selector : selectors) {
            try {
                Locator el = page.locator(selector).first();
                if (el.count() > 0 && el.isVisible()) {
                    el.selectOption(String.valueOf(qty));
                    System.out.println("[BookingFlow] Set quantity to " + qty);
                    return;
                }
            } catch (Exception ignored) {}
        }

        System.out.println("[BookingFlow] No quantity selector found — using site default.");
    }

    /**
     * Tries to click "Proceed / Checkout / Continue / Pay" buttons.
     * Safe to call even if already on payment page.
     */
    private void tryProceedToCheckout(Page page) {
        String[] selectors = {
            "button:has-text('Proceed to Pay')",
            "button:has-text('Proceed')",
            "button:has-text('Checkout')",
            "button:has-text('Continue')",
            "button:has-text('Pay Now')",
            "button:has-text('Make Payment')",
            "button:has-text('Confirm')",
            "button[type='submit']",
        };

        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0 && locator.isVisible()) {
                    System.out.println("[BookingFlow] Proceeding with: " + selector);
                    locator.click();
                    page.waitForTimeout(3000);
                    return;
                }
            } catch (Exception ignored) {}
        }

        System.out.println("[BookingFlow] No checkout button found — may already be on payment page.");
    }

    /**
     * On the payment page:
     *   1. Sends a full-page screenshot to Telegram (with seat + price in caption)
     *   2. Extracts UPI deep link (upi://) — sends as tap-to-pay link
     *   3. Screenshots the QR code element — sends as image (with seat + price in caption)
     *
     * @param seatInfo e.g. "Block A — ₹3800" — included in every notification
     */
    private void capturePaymentPage(Page page, String seatInfo) {
        System.out.println("[BookingFlow] Capturing payment page: " + page.url());

        // Full page screenshot — caption shows exactly what was booked and the price
        byte[] fullPageShot = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
        notifier.sendPhoto(fullPageShot,
            "[" + accountLabel + "] 💳 Payment page!\n" +
            "🎟️ Seat: " + seatInfo + "\n" +
            "Scanning for UPI QR and deep link..."
        );

        // ── Try to extract UPI deep link ─────────────────────────────────────
        Object upiResult = page.evaluate("""
            (() => {
                const anchors = Array.from(document.querySelectorAll('a[href]'));
                for (const a of anchors) {
                    if (a.href.startsWith('upi://') || a.href.startsWith('intent://')) {
                        return a.href;
                    }
                }
                const elements = Array.from(document.querySelectorAll('*'));
                for (const el of elements) {
                    const attrs = ['data-upi', 'data-href', 'data-link', 'data-url'];
                    for (const attr of attrs) {
                        const val = el.getAttribute(attr) || '';
                        if (val.startsWith('upi://')) return val;
                    }
                }
                return null;
            })()
        """);

        if (upiResult != null && !upiResult.toString().equals("null")) {
            String upiLink = upiResult.toString();
            System.out.println("[BookingFlow] Found UPI deep link: " + upiLink);
            notifier.sendMessage(
                "[" + accountLabel + "] 📱 Tap to pay:\n" + upiLink + "\n\n" +
                "🎟️ Seat: " + seatInfo + "\n" +
                "(Opens GPay / PhonePe / Paytm)"
            );
        } else {
            System.out.println("[BookingFlow] No UPI deep link found in DOM.");
        }

        // ── Try to screenshot QR code element ────────────────────────────────
        String[] qrSelectors = {
            "canvas",
            "img[alt*='QR' i]",
            "img[alt*='qr' i]",
            "img[src*='qr' i]",
            "[class*='qr-code' i]",
            "[class*='qrcode' i]",
            "[id*='qr' i]",
            "[id*='qrcode' i]",
        };

        for (String selector : qrSelectors) {
            try {
                Locator el = page.locator(selector).first();
                if (el.count() > 0 && el.isVisible()) {
                    byte[] qrBytes = el.screenshot();
                    notifier.sendPhoto(qrBytes,
                        "[" + accountLabel + "] 📷 Scan to pay!\n" +
                        "🎟️ Seat: " + seatInfo
                    );
                    System.out.println("[BookingFlow] QR code sent via Telegram. Selector: " + selector);
                    return;
                }
            } catch (Exception ignored) {}
        }

        System.out.println("[BookingFlow] No QR element found — full page screenshot already sent.");
        notifier.sendMessage(
            "[" + accountLabel + "] ℹ️ Could not auto-detect QR.\n" +
            "🎟️ Seat: " + seatInfo + "\n" +
            "Check the payment screenshot above and pay manually."
        );
    }
}
