# Google Play & Distribution Privacy Policy — Zyrox Launcher

**Last Updated:** November 16, 2026  
**Package Name:** `com.zyrox.launcher`  
**Source Code:** <https://github.com/argamerzz9582-debug/ZyroxLauncher>

## Summary (Data Safety Disclosure)

- **Data collected by Zyrox Launcher developers:** **None.**
- **Data shared with third-party advertisers or data brokers:** **None.**
- **Analytics, crash-reporting clouds, or ad SDKs bundled:** **None.**
- **Transport security:** Cleartext HTTP traffic is disabled (`usesCleartextTraffic="false"`); web APIs use HTTPS/TLS.

## How Information Is Processed On-Device

1. **Authentication:** When signing in with a Microsoft or Ely.by account, authentication takes place directly with the official identity provider over HTTPS. Resulting session tokens are stored strictly in private on-device application storage (`noBackupFiles` / excluded from Android cloud backup and device transfer) and are never sent to Zyrox Launcher maintainers.
2. **Game Files, Worlds, Mods & Skins:** Stored locally on your device. Optional skin uploads are sent exclusively to `https://api.minecraftservices.com/minecraft/profile/skins` when you explicitly confirm upload.
3. **Optional Microphone Permission (`RECORD_AUDIO`):** Disabled by default; requested only if you actively grant it in Settings for Minecraft voice-chat mods.
4. **Logs & Crash Reports:** Analyzed strictly locally on-device by Zyrox Guard. Logs leave the device only when you manually tap Copy or Share, with automatic redaction of tokens, authorization headers, emails, and UUIDs.

For full details, see [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) and [`SECURITY.md`](SECURITY.md).
