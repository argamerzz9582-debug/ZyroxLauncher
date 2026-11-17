# Zyrox Launcher Security Architecture & Public Deployment Guide

Zyrox Launcher (`com.zyrox.launcher`) is hardened against OWASP MASVS / Mobile Top 10 risks and Android / Google Play Protect security requirements.

## Security Controls Implemented

1. **Release Build Integrity & APK Signing (MASVS-RESILIENCE / Play Protect)**
   - Ships strictly as non-debuggable (`android:debuggable="false"`, `jniDebuggable=false`, `testOnly=false`) production release APKs and Play Store `.aab` bundles targeting Android API 36.
   - All legacy publicly committed keystores (`debug.keystore`, `*.jks`) have been removed from version control and blocked in `.gitignore`.
   - Signed with a 4096-bit RSA (`SHA256withRSA`) certificate using **APK Signature Scheme v1 + v2 + v3 (+ v4)** and verified in CI with `apksigner`, `zipalign`, `aapt2`, and `jarsigner`.
   - Unique application identity (`com.zyrox.launcher`) and scoped provider authority (`com.zyrox.launcher.scoped.gamefolder`) prevent package/signature collisions with other launchers.

2. **Hardware-Backed Credential Protection (MASVS-STORAGE & MASVS-CRYPTO)**
   - Microsoft OAuth refresh tokens, Ely.by tokens, and Minecraft session tokens are encrypted at rest with a non-exportable 256-bit `AES/GCM/NoPadding` key in the Android hardware-backed Keystore (`AccountTokenCipher`) with owner-only (`0600`) file permissions.
   - `android:allowBackup="false"`, `@xml/backup_rules`, and `@xml/data_extraction_rules` prevent ADB/cloud extraction of authentication files.
   - Zero OAuth authorization codes, Xbox Live/XSTS tokens, or Minecraft access tokens are written to Android Logcat or `latestlog.txt`.
   - Cryptographically strong `java.security.SecureRandom` is used across PRNG call sites.

3. **100% TLS/HTTPS Network Security (MASVS-NETWORK)**
   - `android:usesCleartextTraffic="false"` and `@xml/netconfig` enforce `<base-config cleartextTrafficPermitted="false">` trusting only system root CAs.
   - All authentication, skin retrieval, and modpack download endpoints require HTTPS with bounded connect and read timeouts.

4. **Hardened OAuth WebView & Manifest Surface (MASVS-PLATFORM)**
   - OAuth WebView disables `file://` and `content://` access, universal file access, mixed content, geolocation, form/password saving, third-party cookies, and WebView debugging, while enabling Google Safe Browsing and tapjacking protection (`filterTouchesWhenObscured`).
   - Every `<activity>`, `<service>`, and `<provider>` declares explicit `android:exported`; internal components are non-exported, exported entry points enforce `singleInstance` + `taskAffinity=""` (StrandHogg mitigation), and `FolderProvider` requires signature-level `android.permission.MANAGE_DOCUMENTS`.
   - Package visibility `<queries>` is scoped exclusively to optional GPU/audio runtime plugins.

5. **Archive, Modpack & SAF Path-Traversal Protection (CWE-22 / CWE-59)**
   - `ZipUtils`, `NativesExtractor`, `MultiRTUtils` (`.tar.xz` + symlinks), `ModrinthApi`, `CurseforgeApi`, `ImportControlActivity`, `DataMigrator`, `CallbackBridge`, and `FolderProvider` enforce canonical path containment (`getCanonicalPath()`) and built-in native library overwrite protection.

6. **Continuous CI Security Gate**
   - Every GitHub Actions build runs `mobsfscan` (MobSF SAST), manifest/network/source policy checks, and post-build binary verification (`zipalign`, `apksigner`, `aapt2`, SHA-256 checksums) before publishing artifacts.

## Configuring Your Own Permanent Release Keystore (Recommended for Public Distribution)

1. Generate your private release keystore locally (keep a safe offline backup and never commit it to Git):
   ```bash
   keytool -genkeypair -storetype PKCS12 \
     -keystore zyrox-release.p12 \
     -alias zyrox_release \
     -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 \
     -dname "CN=Zyrox Launcher, OU=Release, O=Zyrox Studios, C=IN"
   base64 -w 0 zyrox-release.p12 > zyrox-release.p12.b64
   ```
2. Add these GitHub Repository Secrets (**Settings -> Secrets and variables -> Actions**):
   - `ZYROX_KEYSTORE_BASE64` — contents of `zyrox-release.p12.b64`
   - `ZYROX_KEYSTORE_PASSWORD` — your keystore password
   - `ZYROX_KEY_ALIAS` — `zyrox_release`
   - `ZYROX_KEY_PASSWORD` — your key password
   - *(Optional, for automated Google Play Console publishing)* `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` — Play Developer API service account JSON.

## Google Play Store & Play Protect Public Deployment Notes

- **Google Play Store**: Upload `ZyroxLauncher-full-release.aab` to Google Play Console (`com.zyrox.launcher`), enroll in **Play App Signing**, link `PRIVACY_POLICY.md`, and complete the Data Safety form (no third-party analytics/ad SDKs, TLS encryption in transit, Keystore encryption at rest, optional microphone permission only for in-game voice chat mods).
- **Direct APK Distribution (GitHub / Website)**: Always distribute `ZyroxLauncher-full-release.apk` signed with the same persistent key. If Google Play Protect shows an `"Unrecognized developer"` prompt for a brand-new signing certificate before reputation builds up, submit the release APK and its `SIGNING_CERTIFICATE_SHA256.txt` once via Google's official Play Protect App Verification / Appeal form (`https://support.google.com/googleplay/android-developer/contact/protectappeals`) so Google allowlists your publisher certificate globally.

## Reporting a Vulnerability

Please report security issues privately via GitHub Security Advisories on this repository before public disclosure.
