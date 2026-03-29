package com.rcb.notifier;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Paths;
import java.util.List;

/**
 * One-off explorer — renders the page with a real browser and prints:
 * - Full visible text
 * - All buttons and links
 * - Screenshot saved to page-screenshot.png
 */
public class PageExplorer {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "https://shop.royalchallengers.com/ticket/1";
        System.out.println("Exploring: " + url + "\n");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true)
            );

            BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                                  "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                  "Chrome/124.0.0.0 Safari/537.36")
                    .setViewportSize(1440, 900)
            );

            Page page = context.newPage();
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

            // Extra wait for any delayed JS rendering
            page.waitForTimeout(3000);

            System.out.println("=== FINAL URL ===");
            System.out.println(page.url());

            System.out.println("\n=== FULL PAGE TEXT ===");
            System.out.println(page.innerText("body"));

            System.out.println("\n=== ALL BUTTONS ===");
            List<ElementHandle> buttons = page.querySelectorAll("button");
            for (ElementHandle btn : buttons) {
                System.out.println("  [button] " + btn.innerText().trim());
            }

            System.out.println("\n=== ALL LINKS ===");
            List<ElementHandle> links = page.querySelectorAll("a");
            for (ElementHandle link : links) {
                String text = link.innerText().trim();
                String href = (String) link.getAttribute("href");
                if (!text.isEmpty()) {
                    System.out.println("  [link] " + text + " → " + href);
                }
            }

            // Save screenshot so we can see the actual page visually
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("page-screenshot.png"))
                    .setFullPage(true));
            System.out.println("\n=== Screenshot saved to page-screenshot.png ===");

            browser.close();
        }
    }
}
