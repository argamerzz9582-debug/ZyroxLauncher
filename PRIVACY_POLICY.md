# Privacy Policy — Zyrox Launcher

**Effective Date:** November 16, 2026  
**Application:** Zyrox Launcher (`com.zyrox.launcher`)  
**Official Repository:** <https://github.com/argamerzz9582-debug/ZyroxLauncher>

## 1. Zero Hidden Telemetry & Zero Trackers

Zyrox Launcher is an open-source client application. It contains:
- **No** advertising SDKs or behavioral tracking libraries.
- **No** hidden analytics or silent usage telemetry.
- **No** automatic background crash or log uploading.
- **No** developer-operated server collecting your personal information, accounts, files, worlds, or gameplay history.

## 2. Data Stored Locally on Your Device

All launcher data stays locally inside Zyrox Launcher's Android app storage directory on your device:
- **Account sessions:** Microsoft/Xbox/Minecraft or Ely.by tokens (stored strictly inside internal app-private storage, excluded from cloud backup/device transfer rules) and offline profile names.
- **Game & instance data:** Installed Minecraft versions, OpenJDK runtimes, instances, singleplayer worlds, backups, mods, resource packs, shader packs, configs, saved multiplayer servers (`servers.dat`), local skin PNGs, and control layouts.
- **Diagnostic logs & optional session metrics:** Local runtime log (`latestlog.txt`) and last-session performance metrics summary (when the optional in-game Performance HUD is enabled).

## 3. External Network Connections (User-Initiated)

Zyrox Launcher connects over the network only to perform launcher functions you use:
- **Microsoft / Xbox Live / Minecraft Services (`login.live.com`, `xboxlive.com`, `api.minecraftservices.com`, `mojang.com`, `minecraft.net`):** Official account sign-in, official skin upload (when explicitly chosen), and official Minecraft version, library, and asset downloads.
- **Ely.by (optional):** Only if you choose to sign in with an Ely.by account.
- **Modrinth & CurseForge APIs:** Only when browsing/installing mods or modpacks, or when you tap **Check Updates** in Mod Manager.
- **GitHub / Runtime Mirrors:** Downloading OpenJDK runtimes or opening documentation links you tap.
- **Multiplayer Servers:** Connecting to or pinging Minecraft servers you add and query.

Each third-party service governs its own requests under its respective privacy policy (Microsoft/Mojang, Modrinth, CurseForge, Ely.by, GitHub, and any multiplayer servers you join).

## 4. Log Sharing & Automatic Redaction

Logs and crash diagnostics are shared **only** when you explicitly tap **Copy Log**, **Share Log**, or **Copy Diagnostics**. Before copying or exporting, Zyrox Launcher automatically runs `LogRedactor` to redact access tokens, refresh tokens, JWTs, authorization headers, OAuth query codes, email addresses, and account UUIDs. Always review shared logs before posting them in public forums.

## 5. Android Permissions

- **Internet & Network State:** Downloading game files, authenticating accounts, browsing mods, connecting to multiplayer servers, and detecting metered mobile data.
- **Foreground Service & Notifications:** Keeping active Minecraft sessions and file downloads running cleanly with visible status notifications.
- **Microphone (optional, runtime-prompted):** Requested only if you explicitly enable microphone access for in-game voice chat mods; never recorded or transmitted by the launcher itself.
- **Sensors (Gyroscope):** Used locally on-device only when optional gyroscope camera control is enabled.

## 6. User Control & Data Deletion

You have full control over all data stored by Zyrox Launcher. You can remove accounts, delete instances/worlds/skins/logs within the app at any time, or uninstall the app / clear app storage in Android Settings to erase all local data permanently.
