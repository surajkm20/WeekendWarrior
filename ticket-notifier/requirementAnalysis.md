# RCB Ticket Notifier — Requirement Analysis

## Problem Statement

Automatically detect when RCB match tickets go on sale at `shop.royalchallengers.com`
and notify via WhatsApp — without manual polling.

---

## Technical Findings

The site is a **JavaScript-rendered SPA**, which means:
- Simple HTTP requests (`requests` library) won't see ticket data
- A headless browser (Playwright/Puppeteer) is required to fully render the page
- API endpoints are discovered at runtime via network interception

---

## Core Requirements

### R1 — Web Scraping

| # | Requirement |
|---|-------------|
| 1.1 | Use a headless browser to render the full JS-rendered page |
| 1.2 | Intercept XHR/fetch network calls to find the underlying tickets API |
| 1.3 | Parse ticket availability status (available / sold out / not released) |
| 1.4 | Handle anti-bot measures gracefully (retry, backoff) |

### R2 — Polling / Scheduling

| # | Requirement |
|---|-------------|
| 2.1 | Run the scraper on a configurable interval (e.g., every 5 min) |
| 2.2 | Only send notification on state **change** (not released → available) |
| 2.3 | Persist last-known state to disk to survive restarts |

### R3 — WhatsApp Notification

| # | Requirement |
|---|-------------|
| 3.1 | Send a WhatsApp message when tickets become available |
| 3.2 | Message must include: match name, ticket URL, timestamp |
| 3.3 | Use CallMeBot WhatsApp API (free, no account needed, personal use) |

### R4 — Configuration

| # | Requirement |
|---|-------------|
| 4.1 | All secrets (Twilio credentials, phone numbers) in `.env` file |
| 4.2 | Polling interval configurable via env var |
| 4.3 | Target URL configurable (in case RCB changes the path) |

---

## Tech Stack

| Layer | Choice | Reason |
|-------|--------|--------|
| Language | Java 17+ | Strongly typed, robust, user preference |
| Build tool | Maven | Standard Java dependency management |
| Headless browser | `playwright-java` | Official Playwright Java bindings |
| Scheduler | `ScheduledExecutorService` | Built-in Java, no extra deps needed |
| State persistence | JSON file (Jackson) | Simple, no DB needed for v1 |
| WhatsApp | **CallMeBot WhatsApp API** | Completely free, no account, HTTP GET only |
| Config | `dotenv-java` | Standard `.env` pattern for Java |

---

## WhatsApp Setup — What You'll Need

CallMeBot is completely free, takes 1 min to set up:

1. Add `+34 644 60 49 14` to your WhatsApp contacts (name it "CallMeBot")
2. Send this message to that number on WhatsApp: `I allow callmebot to send me messages`
3. You'll receive an **API key** back (e.g. `1234567`) — put it in `.env`

That's it. No account, no credit card.

---

## Project Structure

```
ticket-notifier/
├── pom.xml                          # Maven build + dependencies
├── .env                             # secrets (gitignored)
├── .env.example                     # template
├── state.json                       # persisted last-known ticket state
└── src/main/java/com/rcb/notifier/
    ├── Main.java                    # entry point + scheduler
    ├── TicketScraper.java           # Playwright headless browser scraper
    ├── WhatsAppNotifier.java        # CallMeBot HTTP sender
    ├── StateManager.java            # read/write state.json
    └── Config.java                  # loads .env vars
```

---

## Flow Diagram

```
[ScheduledExecutorService triggers every N min]
        ↓
[TicketScraper.java] → Playwright launches headless Chromium
        ↓
    navigates to /tickets, waits for JS to settle
        ↓
    checks page.url() — did it redirect away from /tickets?
        ↓
[Compare with state.json]
        ↓
 Changed to AVAILABLE? (URL stayed on /tickets)
    YES → [WhatsAppNotifier.java] → CallMeBot HTTP GET → WhatsApp message sent
          → update state.json
    NO  → still redirecting to merch → sleep, repeat
```

---

## Out of Scope (Phase 1)

- Auto-booking / payment (Phase 2)
- Web dashboard / UI
- Multiple user notifications
- Email fallback
