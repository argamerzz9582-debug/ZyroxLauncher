# Privacy Policy — Zyrox Launcher

**Effective Date:** November 16, 2026  
**Application Package:** `com.zyrox.launcher`

## 1. Overview
Zyrox Launcher is an open-source Minecraft: Java Edition launcher for Android. Zyrox Launcher contains **no advertising SDKs, no third-party analytics trackers, and no telemetry collection servers**.

## 2. Data Stored Locally on Your Device
- **Account Credentials**: When you sign in with Microsoft / Xbox Live or Ely.by, OAuth refresh tokens and Minecraft session tokens are stored solely on your device inside private application storage, encrypted with the Android hardware-backed Keystore (AES-256-GCM), and excluded from cloud/ADB backups. Offline/local accounts store only your chosen username locally.
- **Game Data & Settings**: Minecraft instances, mods, resource packs, shader packs, Java runtimes, control layouts, and launcher settings are stored locally in the app's scoped storage directory.
- **Crash & Runtime Logs**: Game and crash logs (`latestlog.txt`, `latestcrash.txt`) remain strictly on your device unless you explicitly choose to share them using the Android share sheet. Authentication tokens are scrubbed from logs.

## 3. Network Communication
All network traffic uses encrypted HTTPS/TLS connections directly between your device and the services you interact with:
- Microsoft / Xbox Live / Mojang (`login.live.com`, `xboxlive.com`, `minecraftservices.com`, `mojang.com`) for account authentication and official game asset downloads.
- Ely.by (`ely.by`) only if you choose to sign in with an Ely.by account.
- Modrinth (`api.modrinth.com`) and CurseForge (`api.curseforge.com`) when browsing or installing community mods and modpacks.

## 4. Permissions Explained
- **Internet & Network State**: Required for account sign-in and downloading Minecraft versions, libraries, and modpacks.
- **Microphone (`RECORD_AUDIO`, optional)**: Requested only if you explicitly enable microphone access in Settings for in-game voice chat mods (such as Simple Voice Chat). Never recorded or transmitted by the launcher itself.
- **Notifications (`POST_NOTIFICATIONS`, optional)**: Shows foreground download progress and active game session status.
- **Foreground Service**: Keeps active game asset downloads and running Minecraft gameplay alive while in use.
- **Vibration**: Optional haptic feedback for on-screen touch controls.
- **Legacy Storage (Android 9 and below only)**: Used solely to store Minecraft game files on older Android versions.

## 5. Data Deletion
You can remove any signed-in account immediately from the in-app **Account Vault**, or delete all local data at any time by uninstalling Zyrox Launcher or clearing app storage in Android Settings.

## 6. Contact
For privacy or security inquiries, please open an issue or security advisory on the official Zyrox Launcher GitHub repository.
