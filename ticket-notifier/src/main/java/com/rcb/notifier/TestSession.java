package com.rcb.notifier;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Paths;

/**
 * Tests if saved session cookies work by visiting /ticket/1.
 * Also dumps localStorage to check if auth token is stored there.
 */
public class TestSession {

    public static void main(String[] args) throws Exception {
        CookieStore cookieStore = new CookieStore();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(false)
            );

            BrowserContext context = browser.newContext(
                cookieStore.loadOptions()
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                                  "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                  "Chrome/124.0.0.0 Safari/537.36")
                    .setViewportSize(1440, 900)
            );

            Page page = context.newPage();
            page.navigate("https://shop.royalchallengers.com/ticket/1",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            page.waitForTimeout(3000);

            System.out.println("Final URL: " + page.url());

            // Dump localStorage — auth tokens are often stored here in SPAs
            Object localStorage = page.evaluate("""
                JSON.stringify(Object.entries(localStorage)
                    .reduce((acc, [k, v]) => ({ ...acc, [k]: v }), {}))
            """);
            System.out.println("localStorage: " + localStorage);

            // Dump sessionStorage too
            Object sessionStorage = page.evaluate("""
                JSON.stringify(Object.entries(sessionStorage)
                    .reduce((acc, [k, v]) => ({ ...acc, [k]: v }), {}))
            """);
            System.out.println("sessionStorage: " + sessionStorage);

            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("session-test.png")).setFullPage(true));
            System.out.println("Screenshot saved to session-test.png");

            page.waitForTimeout(5000);
            browser.close();
        }
    }
}
