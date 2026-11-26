# Security Policy — Zyrox Launcher

## Supported Versions

| Version | Status |
| :--- | :--- |
| `2.0.x` | Supported (active security & reliability updates) |
| `<= 1.0.x` | Legacy — please upgrade to `2.0.0` or newer |

## Reporting a Vulnerability

If you discover a security vulnerability in Zyrox Launcher:

1. **Do not post unpatched exploit details publicly.**
2. Report privately via [GitHub Private Vulnerability Reporting / Security Advisories](https://github.com/argamerzz9582-debug/ZyroxLauncher/security/advisories) on `argamerzz9582-debug/ZyroxLauncher`.
3. Include affected version/commit, reproduction steps, impact assessment, and (if available) a suggested fix.

---

## Official Downloads & APK Signature Verification

- **Only official download source:** <https://github.com/argamerzz9582-debug/ZyroxLauncher/releases>
- Every official release publishes:
  - Per-ABI and universal release APKs signed with a consistent dedicated release keystore using **APK Signature Scheme v1 + v2 + v3** (verified in CI via `apksigner verify --verbose --print-certs`).
  - `SHA256SUMS.txt` containing exact SHA-256 digests for every published APK artifact.
  - The public signing certificate SHA-256 fingerprint.
- **How to verify locally:**
  ```bash
  sha256sum -c SHA256SUMS.txt
  apksigner verify --verbose --print-certs <downloaded.apk>
  ```
- **In-app verification:** Open **Home → TRUST → Security & Trust** (or **Settings → Security & GPU**) to view and copy the running APK's live SHA-256 signing certificate fingerprint and installer source.
- **Signing key continuity notice:** Upgrading over an earlier install requires the same signing certificate. If transitioning from a legacy pre-2.0 build signed with a different key, export/back up instances and worlds first before reinstalling.

---

## Google Play Protect & Android Security Transparency

Zyrox Launcher takes a strictly legitimate, transparent security posture:

- Zyrox Launcher **never** bypasses, disables, weakens, or evades Google Play Protect or Android platform security.
- Keep **Google Play Protect enabled** and never install modified or re-signed APKs from unofficial third-party websites.
- Because open-source Minecraft: Java Edition launchers execute an embedded OpenJDK runtime, native OpenGL/Vulkan translation libraries, and user-chosen game/mod JARs, Android or Play Protect may occasionally prompt to scan a newly published sideloaded build. Clean permissions, disabled cleartext HTTP, consistent release signing, open-source code, and zero hidden trackers reduce unnecessary risk signals, though no independent sideloaded launcher can claim official Play Protect certification without Google's own review.

---

## Security Architecture Highlights (v2.0.0)

1. **Network & TLS Hardening:** `android:usesCleartextTraffic="false"`, explicit connect/read timeouts, bounded response buffers, SHA-1/SHA-256 file verification (Rapid Start trusts only files with a valid local verification marker — previously SHA-1 verified and unmodified since; it never trusts files by size alone), atomic `.part` rename writes, and no `TrustManager` or `HostnameVerifier` bypasses.
2. **Archive & Path Safety (`ArchiveSecurity`):** Canonical-path containment against Zip-Slip (`../`, backslashes, drive prefixes, absolute paths, NUL bytes), entry-count/size/compression-ratio bomb guards, and zero-execution static mod/pack metadata parsing.
3. **Credential & Log Redaction (`LogRedactor`):** Launch CLI logs, crash reports, clipboard copies, and shared log files automatically redact Microsoft/Minecraft/Ely.by access tokens, refresh tokens, JWTs, Bearer/XBL headers, OAuth URL query parameters, email addresses, and UUIDs.
4. **Minimal Justified Android Permissions:**
   - `INTERNET` & `ACCESS_NETWORK_STATE` — Official auth, Minecraft version/asset/library downloads, Modrinth/CurseForge browsing, and user-selected multiplayer servers.
   - `POST_NOTIFICATIONS` & `FOREGROUND_SERVICE` (`specialUse`) — Keep active game sessions and user-started downloads alive reliably with visible Android notifications.
   - `RECORD_AUDIO` — Optional runtime permission requested only if the user explicitly enables in-game voice chat mods.
   - `HIGH_SAMPLING_RATE_SENSORS` — Optional smooth gyroscope aiming when enabled by the user.
   - No contacts, SMS, call log, camera, precise location, accessibility, device-admin, background install, or `QUERY_ALL_PACKAGES` permissions.
5. **CI/CD Supply-Chain Security:** GitHub Actions pinned to full commit SHAs, least-privilege per-job `permissions`, automated secret/cleartext/permission static checks, isolated release signing never exposed to pull requests, and zero caching of signing keys or passwords.
