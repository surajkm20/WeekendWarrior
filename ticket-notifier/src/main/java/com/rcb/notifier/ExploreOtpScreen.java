package com.rcb.notifier;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Paths;
import java.util.List;

/**
 * One-off explorer — fills mobile number, clicks Continue,
 * then captures the OTP screen to find exact input selectors.
 */
public class ExploreOtpScreen {

    public static void main(String[] args) throws Exception {
        Config config = new Config();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setSlowMo(500)
            );

            BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                                  "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                  "Chrome/124.0.0.0 Safari/537.36")
                    .setViewportSize(1440, 900)
            );

            Page page = context.newPage();
            page.navigate("https://shop.royalchallengers.com/auth?callbackUrl=/ticket/1",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

            // Fill mobile number and click Continue
            page.waitForSelector("input[type='tel']");
            page.fill("input[type='tel']", config.getMobileNumber());
            page.click("button:has-text('Continue')");

            // Wait for OTP screen to appear (up to 10 seconds)
            page.waitForTimeout(5000);

            // Print ALL input fields on the OTP screen
            System.out.println("=== ALL INPUTS ON OTP SCREEN ===");
            List<ElementHandle> inputs = page.querySelectorAll("input");
            for (ElementHandle input : inputs) {
                String type        = input.getAttribute("type");
                String placeholder = input.getAttribute("placeholder");
                String name        = input.getAttribute("name");
                String id          = input.getAttribute("id");
                String cls         = input.getAttribute("class");
                System.out.println("  type='" + type + "' placeholder='" + placeholder +
                                   "' name='" + name + "' id='" + id + "' class='" + cls + "'");
            }

            System.out.println("\n=== FULL PAGE TEXT ===");
            System.out.println(page.innerText("body"));

            // Screenshot of OTP screen
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("otp-screen.png"))
                    .setFullPage(true));
            System.out.println("\n=== Screenshot saved to otp-screen.png ===");

            // Keep browser open for 10 seconds so you can see it
            page.waitForTimeout(10000);
            browser.close();
        }
    }
}
