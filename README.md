# Quetta Temp Mail — Single-Page iOS 18 Dark Mode Edition (v6.0)

Rebuilt from scratch: the **entire app lives on one page** — no separate reader screen.
Tap any email to expand it inline and read the full body, copy the OTP, open the
verification link, or delete it — all without leaving the inbox.

100% native Android Views (RecyclerView, Adapters, drawables), zero WebView/HTML.

## What's on the single page

1. **iOS large-title header** — "Quetta Mail" + live status pill (🟢 Watching Live / 🔴 error) + last-synced time.
2. **Address card** — current `@quetta.email` (tap to copy) + **Copy**, **Refresh**, **New** buttons.
3. **iOS search field** — live filtering by sender, subject, body, or OTP code.
4. **Segmented control** — Inbox | Starred | OTP Codes.
5. **Message list** — iOS-style cards with colored initial avatars, star toggle, one-tap OTP copy chips.
   Tap a card to expand: full selectable body, big OTP banner with Copy button,
   "Open Verification Link" button, attachment count, and Delete.
6. **History** — every generated address, newest first.
7. **Settings** — 🔋 Keep Alive (battery-optimization exemption), Exit App (deletes the
   address and stops the service).
8. **Bottom tab bar** — Inbox / Generate / History / Settings scroll smoothly to each
   section on the same page.

## Background engine (unchanged, all features kept)

- **Foreground service** (`QuettaMailService.kt`) — survives minimization, lock screen
  and Doze (`START_STICKY`, wake locks); persistent notification with **Copy**,
  **New Mail** and **Exit** actions.
- **Instant network recovery** — polls immediately when Wi-Fi/mobile data reconnects,
  with adaptive backoff while offline.
- **Smart OTP extractor** — 4–8 digit codes + verification URLs auto-detected;
  incoming OTPs are **auto-copied to the clipboard** and shown in notifications.
- **Full inbox management** — real-time search, star, per-email delete (`Email/set` destroy).
- **Address lifecycle** — auto-creates a new address on start, **deletes the old one
  first** on rotate, sweeps leftover remote addresses, deletes on exit.

## Build the `.apk` via GitHub Actions

1. Push this folder to your GitHub repository.
2. Open the **Actions** tab → **Build Quetta Temp Mail APK (v6)** → **Run workflow**.
3. Download **`QuettaTempMail-debug-apk`** from the run artifacts and install
   `app-debug.apk` on your phone.
