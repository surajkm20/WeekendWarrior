package com.rcb.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;

/**
 * Reads and writes state.json — the app's memory across restarts.
 * Tracks whether tickets were available the last time we checked.
 *
 * state.json looks like:  { "ticketsAvailable": false }
 */
public class StateManager {

    // The file on disk where state is persisted
    private static final String STATE_FILE = "state.json";

    // Jackson's main class — converts between Java objects and JSON
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Reads the last known ticket availability from state.json.
     * Returns false if the file doesn't exist yet (first run).
     */
    public boolean wasAvailable() {
        File file = new File(STATE_FILE);

        // First run — no state file yet, assume not available
        if (!file.exists()) {
            return false;
        }

        try {
            // path() safely navigates the JSON tree — returns MissingNode (not null)
            // if the key doesn't exist, so no NullPointerException ever
            // asBoolean(false) returns false as default if value is missing
            return mapper.readTree(file).path("ticketsAvailable").asBoolean(false);

        } catch (IOException e) {
            // If the file is corrupted or unreadable, treat as "not available"
            // and let the app continue rather than crashing
            System.err.println("[StateManager] Could not read state.json, defaulting to false: " + e.getMessage());
            return false;
        }
    }

    /**
     * Writes the current ticket availability to state.json.
     * Called after every successful scrape so state survives restarts.
     */
    public void save(boolean ticketsAvailable) {
        try {
            // createObjectNode() gives us a blank JSON object: {}
            // then we put our field in: { "ticketsAvailable": true/false }
            ObjectNode root = mapper.createObjectNode();
            root.put("ticketsAvailable", ticketsAvailable);

            // writerWithDefaultPrettyPrinter() makes the JSON human-readable
            // Writes to state.json (creates file if it doesn't exist)
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(STATE_FILE), root);

            System.out.println("[StateManager] State saved → ticketsAvailable=" + ticketsAvailable);

        } catch (IOException e) {
            // Not fatal — app can keep running even if state write fails
            // Worst case: we send a duplicate WhatsApp notification
            System.err.println("[StateManager] Could not write state.json: " + e.getMessage());
        }
    }
}
