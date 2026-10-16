# Quetta Temp Mail — Native Android APK Project

This is a complete native Android (Kotlin) project for **Quetta Temp Mail**.

## Features Included
- **Refresh Button**: Instantly polls the JMAP inbox (`https://mx.quetta.email/jmap/`) on tap.
- **New Mail Button (Deletes Old Address First)**: Explicitly sends `DELETE https://account.quetta.net/mailboxes/{account_id}` for the current address and sweeps any leftover remote addresses before creating a brand-new `@quetta.email` inbox.
- **Auto-Cleanup on Exit**: Deletes the active address when you tap **Exit** or close the app.
- **Native Android Features**: Uses Android `ClipboardManager`, `NotificationManager` for new mail alerts, and `PowerManager.WakeLock` to keep polling alive.

## 2 Ways to Build the `.apk`

### Option A: Build on Phone / Cloud via GitHub Actions (No PC or Android Studio Needed)
1. Create a new repository on GitHub (can be Private).
2. Upload the contents of this project folder to the repository.
3. Open the **Actions** tab on GitHub -> select **Build Quetta Temp Mail APK** -> tap **Run workflow**.
4. In ~2 minutes, download **`QuettaTempMail-debug-apk.zip`** from the workflow run artifacts and install `app-debug.apk` on your Android phone.

### Option B: Build Locally in Android Studio
1. Open this folder in **Android Studio**.
2. Wait for Gradle sync to finish, then click **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
3. Your installable APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
