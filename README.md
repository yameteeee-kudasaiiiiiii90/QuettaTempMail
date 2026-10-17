# Quetta Temp Mail — Classic Interface, iOS 18 Dark Mode (v6.1)

The familiar classic layout (title bar + status pill, address box,
**Copy / Refresh / New / Exit** row, inbox card with search) —
restyled in iOS 18 dark mode. 100% native Android Views, zero WebView/HTML.

## The interface

1. **Title bar** — ⚡ Quetta Temp Mail + live status pill (🟢 watching / 🔴 error).
2. **Address box** — current `@quetta.email` (tap to copy).
3. **Action row** — 📋 Copy (iOS blue) · 🔄 Refresh · 🗑✨ New (deletes old first) · ✖ Exit (red).
4. **Inbox card** — `INBOX (n)` header + 🔋 Keep Alive pill, live search
   (sender, subject, body, OTP), message list.
5. **Tap any message to expand it inline** — full selectable body, big OTP banner
   with Copy button, "Open Verification Link" button, attachment count, Delete.

## Background engine (all features kept)

- **Foreground service** (`QuettaMailService.kt`) — survives minimization, lock screen
  and Doze (`START_STICKY`, wake locks); persistent notification with **Copy**,
  **New Mail** and **Exit** actions.
- **Instant network recovery** — polls immediately when Wi-Fi/mobile data reconnects,
  with adaptive backoff while offline.
- **Smart OTP extractor** — 4–8 digit codes + verification URLs auto-detected;
  incoming OTPs are **auto-copied to the clipboard** and shown in notifications.
- **Address lifecycle** — auto-creates a new address on start, **deletes the old one
  first** on New, sweeps leftover remote addresses, deletes on Exit.

## Build the `.apk` via GitHub Actions

1. Push this folder to your GitHub repository.
2. Open the **Actions** tab → **Build Quetta Temp Mail APK (v6.1)** → **Run workflow**.
3. Download **`QuettaTempMail-debug-apk`** from the run artifacts and install
   `app-debug.apk` on your phone.
