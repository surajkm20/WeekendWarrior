package com.rcb.notifier;

/**
 * One-off test — runs the full login flow.
 * Opens a visible browser, auto-fills your mobile number,
 * then waits for you to type the OTP in this terminal.
 *
 * Run with:
 *   java -cp target/ticket-notifier-1.0.0.jar com.rcb.notifier.TestLogin
 */
public class TestLogin {

    public static void main(String[] args) {
        System.out.println("=== RCB Login Test ===");

        Config config         = new Config();
        CookieStore cookieStore = new CookieStore();
        LoginFlow loginFlow   = new LoginFlow(config, cookieStore);

        boolean success = loginFlow.run();

        if (success) {
            System.out.println("\n=== Login successful! Session saved to session.json ===");
            System.out.println("You won't need to login again until the session expires.");
        } else {
            System.out.println("\n=== Login failed. Check the error above. ===");
        }
    }
}
