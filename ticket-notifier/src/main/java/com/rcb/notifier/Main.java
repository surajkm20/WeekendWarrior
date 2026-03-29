package com.rcb.notifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point. Wires all classes together and runs the polling loop.
 *
 * Flow every N minutes:
 *   1. TicketScraper  → check if /tickets page is live
 *   2. StateManager   → compare with last known state
 *   3. WhatsAppNotifier → notify if state changed to available
 *   4. StateManager   → save new state to disk
 */
public class Main {

    public static void main(String[] args) {

        System.out.println("=== RCB Ticket Notifier Starting ===");

        // --- Wire up all components ---
        // Config is created first because everything else depends on it
        Config config = new Config();

        TicketScraper    scraper  = new TicketScraper(config);
        StateManager     state    = new StateManager();
        TelegramNotifier notifier = new TelegramNotifier(config);

        // Build one BookingFlow per mobile number — all run in parallel when tickets go live
        List<String>      mobileNumbers = config.getMobileNumbers();
        List<BookingFlow> bookingFlows  = new ArrayList<>();
        for (int i = 0; i < mobileNumbers.size(); i++) {
            String label = "Account " + (i + 1) + " (" + mobileNumbers.get(i) + ")";
            bookingFlows.add(new BookingFlow(config, new CookieStore(i), notifier, label));
        }
        System.out.println("Accounts loaded: " + mobileNumbers.size() + " → " + mobileNumbers);

        int intervalMinutes = config.getPollIntervalMinutes();
        System.out.println("Polling every " + intervalMinutes + " minute(s).");
        System.out.println("Watching: " + config.getTicketUrl());
        System.out.println("=====================================\n");

        // --- Build the task that runs every N minutes ---
        // This is a Runnable: a block of code with no inputs and no return value
        // Think of it as a function you hand to the scheduler to call repeatedly
        Runnable pollTask = () -> {
            System.out.println("[Poll] Running check at " + java.time.LocalTime.now());

            try {
                // Step 1 — ask the scraper: are tickets available right now?
                boolean currentlyAvailable = scraper.checkAvailability();

                // Step 3 — notify
                if (currentlyAvailable) {
                    // Tickets are live — trigger the full auto-booking flow:
                    //   opens browser with saved session → clicks Book Now →
                    //   selects quantity → proceeds to payment →
                    //   sends QR code + UPI deep link to Telegram
                    System.out.println("[Poll] *** TICKETS ARE LIVE — STARTING AUTO-BOOKING ON ALL ACCOUNTS! ***");
                    // Run all accounts in parallel — each gets its own thread
                    for (BookingFlow bf : bookingFlows) {
                        new Thread(bf::run).start();
                    }
                } else {
                    System.out.println("[Poll] No change. Tickets not available yet.");
                }

                // Step 4 — always save the latest state, even if unchanged
                // This keeps state.json fresh and handles restarts cleanly
                state.save(currentlyAvailable);

            } catch (Exception e) {
                // Safety net: if anything unexpected goes wrong in one poll,
                // log it and let the scheduler run the next poll normally
                System.err.println("[Poll] Unexpected error: " + e.getMessage());
            }

            System.out.println(); // blank line between polls for readability
        };

        // --- Set up the scheduler ---
        // newSingleThreadScheduledExecutor: one background thread runs the task
        // "Single thread" means polls never overlap — if one takes longer than
        // the interval, the next one waits instead of running in parallel
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleAtFixedRate(
            pollTask,        // the task to run
            0,               // initial delay: 0 = run immediately on startup
            intervalMinutes, // then repeat every N...
            TimeUnit.MINUTES // ...minutes
        );

        // --- Keep the main thread alive ---
        // scheduleAtFixedRate runs the task on a background thread.
        // Without this, main() would finish and the JVM would exit,
        // killing the background thread with it.
        // awaitTermination with Long.MAX_VALUE = wait forever (until Ctrl+C)
        try {
            scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            System.out.println("\n[Main] Interrupted — shutting down.");
            scheduler.shutdown();
        }
    }
}
