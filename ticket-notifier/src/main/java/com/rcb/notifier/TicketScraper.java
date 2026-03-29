package com.rcb.notifier;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * Uses a headless Chromium browser to visit the RCB tickets page
 * and detect whether tickets are available.
 *
 * Detection logic:
 *   - Navigate to /ticket
 *   - Wait for JavaScript to settle (page fully loaded)
 *   - Read visible page text and look for keywords that indicate availability
 */
public class TicketScraper {

    private final String ticketUrl;

    public TicketScraper(Config config) {
        this.ticketUrl = config.getTicketUrl();
    }

    /**
     * Performs one check — opens browser, visits page, returns result.
     * A new browser is created and closed on every call (stateless, clean).
     *
     * @return true if tickets appear to be available, false otherwise
     */
    public boolean checkAvailability() {
        System.out.println("[TicketScraper] Checking: " + ticketUrl);

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(true)
            );

            BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                                  "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                  "Chrome/124.0.0.0 Safari/537.36")
            );

            Page page = context.newPage();

            page.navigate(ticketUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));

            String finalUrl = page.url();
            System.out.println("[TicketScraper] Final URL: " + finalUrl);

            // innerText() returns all visible text on the page (no HTML tags)
            // toLowerCase() so our keyword checks are case-insensitive
            String pageText = page.innerText("body").toLowerCase();

            // Print a preview so we can see what the page currently says
            // This helps us figure out the right keywords to detect
            String preview = pageText.length() > 600 ? pageText.substring(0, 600) : pageText;
            System.out.println("[TicketScraper] Page text preview:\n---\n" + preview + "\n---");

            browser.close();

            // --- Detection logic ---
            // When tickets are NOT released, the page shows:
            //   "tickets not available" / "await for further announcements"
            // "seats are taken" means the page is live but all seats in our
            //   price range are gone — keep polling until new seats open.
            //
            // So: any of these phrases = NOT available (keep polling)
            //     absence of all       = AVAILABLE!
            boolean hasNotAvailable = pageText.contains("not available");
            boolean hasAwait        = pageText.contains("await");
            boolean hasSeatsTaken   = pageText.contains("seats are taken");

            boolean available = !hasNotAvailable && !hasAwait && !hasSeatsTaken;

            System.out.println("[TicketScraper] Keyword matches → " +
                "notAvailable=" + hasNotAvailable +
                " await=" + hasAwait +
                " seatsTaken=" + hasSeatsTaken);
            System.out.println("[TicketScraper] Tickets available: " + available);
            return available;

        } catch (Exception e) {
            System.err.println("[TicketScraper] Error during scrape: " + e.getMessage());
            return false;
        }
    }
}
