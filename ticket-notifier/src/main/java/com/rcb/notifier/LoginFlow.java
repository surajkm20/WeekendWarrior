package com.rcb.notifier;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.Scanner;

/**
 * Handles the RCB shop login flow:
 *   1. Opens a VISIBLE browser (you can see it on screen)
 *   2. Auto-fills your mobile number
 *   3. Clicks Continue
 *   4. Waits for you to type the OTP in the terminal
 *   5. Enters the OTP in the browser
 *   6. Saves session cookies → you won't need to login again until session expires
 *
 * Run this once manually before tickets go live so session is ready.
 */
public class LoginFlow {

    private static final String LOGIN_URL = "https://shop.royalchallengers.com/auth?callbackUrl=/ticket/1";

    private final Config config;
    private final CookieStore cookieStore;

    public LoginFlow(Config config, CookieStore cookieStore) {
        this.config = config;
        this.cookieStore = cookieStore;
    }

    /**
     * Runs the full login flow in a visible browser.
     * Blocks until login is complete and session is saved.
     *
     * @return the BrowserContext with active session (caller must close browser)
     */
    public boolean run() {
        System.out.println("[LoginFlow] Opening browser for login...");

        try (Playwright playwright = Playwright.create()) {

            // setHeadless(false) = visible browser window on your screen
            // You'll see exactly what's happening and can interact if needed
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setSlowMo(500) // slight delay between actions so it looks natural
            );

            BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                                  "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                  "Chrome/124.0.0.0 Safari/537.36")
                    .setViewportSize(1440, 900)
            );

            Page page = context.newPage();

            // --- Step 1: Navigate to login page ---
            System.out.println("[LoginFlow] Navigating to login page...");
            page.navigate(LOGIN_URL, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));

            // --- Step 2: Fill in mobile number ---
            System.out.println("[LoginFlow] Filling mobile number: " + config.getMobileNumber());
            page.waitForSelector("input[type='tel']");

            // Click the field first to focus it
            page.click("input[type='tel']");

            // pressSequentially() simulates real keystrokes — fires React onChange events
            // fill() sets DOM value directly but React doesn't detect it, keeping button disabled
            page.locator("input[type='tel']").pressSequentially(config.getMobileNumber(),
                new com.microsoft.playwright.Locator.PressSequentiallyOptions().setDelay(100));

            // --- Step 3: Press Enter (more reliable than clicking button with reCAPTCHA) ---
            System.out.println("[LoginFlow] Submitting via Enter key...");
            page.keyboard().press("Enter");

            // Wait a moment for reCAPTCHA and network request to complete
            page.waitForTimeout(5000);

            // Screenshot after clicking Continue — helps diagnose if stuck
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(java.nio.file.Paths.get("after-continue.png")));
            System.out.println("[LoginFlow] Screenshot saved to after-continue.png");

            // Print current URL and page text for diagnostics
            System.out.println("[LoginFlow] URL after Continue: " + page.url());
            System.out.println("[LoginFlow] Page text: " + page.innerText("body").substring(0, Math.min(300, page.innerText("body").length())));

            // --- Step 4: Wait for OTP PIN boxes to appear ---
            System.out.println("[LoginFlow] Waiting for OTP screen...");
            page.waitForSelector(".chakra-pin-input",
                new Page.WaitForSelectorOptions().setTimeout(30000));

            // --- Step 5: Ask you to type the OTP in terminal ---
            System.out.print("\n>>> OTP sent to +91 " + config.getMobileNumber()
                             + "\n>>> Enter OTP here: ");
            String otp;
            try (Scanner scanner = new Scanner(System.in)) {
                otp = scanner.nextLine().trim();
            }

            // --- Step 6: Fill OTP in browser ---
            // Click the FIRST pin box, then type all digits
            // Chakra PinInput auto-advances focus to next box on each keystroke
            System.out.println("[LoginFlow] Entering OTP in browser...");
            page.click(".chakra-pin-input");
            page.keyboard().type(otp);

            // Click "Validate" button
            page.click("button:has-text('Validate')");

            // --- Step 7: Wait for redirect back to /ticket/1 ---
            System.out.println("[LoginFlow] Waiting for login to complete...");
            page.waitForURL("**/ticket/**", new Page.WaitForURLOptions().setTimeout(30000));

            System.out.println("[LoginFlow] Login successful! URL: " + page.url());

            // --- Step 8: Save cookies so we never need to login again ---
            cookieStore.save(context);

            browser.close();
            return true;

        } catch (Exception e) {
            System.err.println("[LoginFlow] Login failed: " + e.getMessage());
            return false;
        }
    }
}
