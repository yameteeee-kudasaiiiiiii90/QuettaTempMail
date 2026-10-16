# Quetta Temp Mail — Ultimate Native Android APK Project (v3)

## What's Upgraded in v3
1. **True Android Foreground Service (`QuettaMailService.kt`)**:
   - Survives app minimization, screen lock, and Doze mode using `START_STICKY`, `PARTIAL_WAKE_LOCK`, and `WIFI_MODE_FULL_LOW_LATENCY`.
   - Persistent status bar notification displays your active `@quetta.email` address with 1-tap action buttons right in the notification shade: **Copy**, **New Mail** (deletes old address first), and **Exit**.
2. **Instant Network Recovery (`ConnectivityManager.NetworkCallback`)**:
   - Immediately polls the inbox the moment Wi-Fi or mobile data reconnects, with adaptive exponential backoff when offline.
3. **Smart OTP & Verification Link Extractor**:
   - Automatically detects 4-8 digit OTP/verification codes and confirmation URLs in incoming emails.
   - Auto-copies incoming OTPs to the clipboard and adds a **`Copy OTP (123456)`** button directly to the incoming mail notification and inbox list.
4. **Full Inbox Management**:
   - Real-time search bar (filter by sender, subject, body, or OTP), delete individual emails (`Email/set` destroy), and 1-tap unrestricted battery optimization toggle (`🔋 Keep Alive`).
