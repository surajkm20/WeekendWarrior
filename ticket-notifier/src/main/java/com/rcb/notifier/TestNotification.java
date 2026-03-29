package com.rcb.notifier;

/**
 * One-off test — sends a Telegram message immediately.
 * Run this to verify your bot token and chat ID are correct
 * before running the full app.
 *
 * Run with:
 *   java -cp target/ticket-notifier-1.0.0.jar com.rcb.notifier.TestNotification
 */
public class TestNotification {

    public static void main(String[] args) {
        System.out.println("=== Telegram Notification Test ===");

        Config config = new Config();
        TelegramNotifier notifier = new TelegramNotifier(config);

        notifier.sendTicketAlert("https://shop.royalchallengers.com/ticket");

        System.out.println("=== Done. Check your Telegram! ===");
    }
}
