package com.rcb.notifier;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;

import java.io.File;
import java.nio.file.Paths;

/**
 * Saves and restores the full browser session (cookies + localStorage)
 * using Playwright's built-in storageState() API.
 *
 * Supports multiple accounts — each account gets its own session file:
 *   Account 0 → session_0.json
 *   Account 1 → session_1.json
 *   ...
 */
public class CookieStore {

    private final String sessionFile;
    private final int accountIndex;

    /** Multi-account constructor — use account index to separate session files. */
    public CookieStore(int accountIndex) {
        this.accountIndex = accountIndex;
        this.sessionFile  = "session_" + accountIndex + ".json";
    }

    /** Single-account backward-compatible constructor (uses session_0.json). */
    public CookieStore() {
        this(0);
    }

    public int getAccountIndex() {
        return accountIndex;
    }

    public String getSessionFile() {
        return sessionFile;
    }

    /**
     * Saves the full browser session (cookies + localStorage) to this account's session file.
     * Call this right after a successful OTP login.
     */
    public void save(BrowserContext context) {
        try {
            context.storageState(new BrowserContext.StorageStateOptions()
                    .setPath(Paths.get(sessionFile)));
            System.out.println("[CookieStore] Session saved to " + sessionFile);
        } catch (Exception e) {
            System.err.println("[CookieStore] Failed to save session: " + e.getMessage());
        }
    }

    /**
     * Creates browser context options that restore this account's saved session.
     */
    public Browser.NewContextOptions loadOptions() {
        if (!exists()) {
            System.out.println("[CookieStore] No saved session for account " + accountIndex +
                               " — run login first.");
            return new Browser.NewContextOptions();
        }
        System.out.println("[CookieStore] Loading session for account " + accountIndex +
                           " from " + sessionFile);
        return new Browser.NewContextOptions()
                .setStorageStatePath(Paths.get(sessionFile));
    }

    /**
     * Deletes this account's saved session — forces a fresh login next time.
     */
    public void clear() {
        File file = new File(sessionFile);
        if (file.exists()) {
            file.delete();
            System.out.println("[CookieStore] Session cleared: " + sessionFile);
        }
    }

    public boolean exists() {
        return new File(sessionFile).exists();
    }
}