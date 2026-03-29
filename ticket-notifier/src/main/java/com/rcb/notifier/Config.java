package com.rcb.notifier;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Loads all configuration from the .env file once at startup.
 * Every other class asks Config for values — nothing reads .env directly.
 */
public class Config {

    // Dotenv is the library that reads your .env file from disk
    private final Dotenv dotenv;

    public Config() {
        // Looks for .env in the current working directory (where you run the app from)
        // ignoreIfMissing() means: don't crash if .env doesn't exist
        // (useful when env vars are set directly in the OS instead)
        this.dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        // Validate required fields immediately at startup
        // Better to crash early with a clear message than fail silently later
        requireNonEmpty("TELEGRAM_BOT_TOKEN");
        requireNonEmpty("TELEGRAM_CHAT_ID");
    }

    // --- Getters — each method returns one config value ---

    public String getTelegramBotToken() {
        return dotenv.get("TELEGRAM_BOT_TOKEN");
    }

    public String getTelegramChatId() {
        return dotenv.get("TELEGRAM_CHAT_ID");
    }

    public String getTicketUrl() {
        // dotenv.get(key, defaultValue) — use default if not set in .env
        return dotenv.get("TICKET_URL", "https://shop.royalchallengers.com/tickets");
    }

    public int getPollIntervalMinutes() {
        String value = dotenv.get("POLL_INTERVAL_MINUTES", "5");
        return Integer.parseInt(value);
    }

    public String getMobileNumber() {
        return dotenv.get("MOBILE_NUMBER", "");
    }

    /**
     * Returns all mobile numbers to use for simultaneous booking.
     * Reads MOBILE_NUMBERS (comma-separated) first, falls back to MOBILE_NUMBER.
     * e.g. MOBILE_NUMBERS=9113573894,9876543210
     */
    public List<String> getMobileNumbers() {
        String raw = dotenv.get("MOBILE_NUMBERS", getMobileNumber());
        return Arrays.stream(raw.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .collect(Collectors.toList());
    }

    public int getTicketQuantity() {
        String value = dotenv.get("TICKET_QUANTITY", "2");
        return Integer.parseInt(value);
    }

    public int getMinTicketPrice() {
        String value = dotenv.get("MIN_TICKET_PRICE", "3500");
        return Integer.parseInt(value);
    }

    public int getMaxTicketPrice() {
        String value = dotenv.get("MAX_TICKET_PRICE", "5000");
        return Integer.parseInt(value);
    }

    // --- Private helper ---

    private void requireNonEmpty(String key) {
        String value = dotenv.get(key, "");
        if (value == null || value.isBlank()) {
            // isBlank() returns true for "" and "   " (whitespace only)
            throw new IllegalStateException(
                "Missing required config: " + key + " — check your .env file"
            );
        }
    }
}
