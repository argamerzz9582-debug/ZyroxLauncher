# Zyrox Launcher — Sakura Edition

**Zyrox Launcher** (`com.zyrox.launcher`, v`2.2.1` — *Starlight Firefly Edition*) is an open-source Android launcher for **Minecraft: Java Edition**, engineered for security transparency, data safety, 90/120/144 Hz low-pause G1GC mobile optimization, Android 15/16 `16 KB` page alignment, log-driven launch progress, an All-in-One Modrinth/CurseForge Content & Runtime/Driver Store, seamless zero-buffer Starlight Firefly & Dawn animated scenes, and a cohesive **Starlight Firefly × Liquid Glass** user interface and in-game control system.

> **Official Repository:** <https://github.com/argamerzz9582-debug/ZyroxLauncher>  
> **Official Releases:** <https://github.com/argamerzz9582-debug/ZyroxLauncher/releases>  
> **License:** LGPL-3.0 (see [`LICENSE`](LICENSE))  
> **Disclaimer:** Not an official Minecraft product. Not approved by or associated with Mojang AB or Microsoft Corporation.

---

## Key Features in Zyrox Launcher 2.0

- **Liquid Glass UI & In-Game Controls:** Cohesive dark-obsidian shell with hardware-accelerated neutral frosted Liquid Glass buttons across every launcher screen and every in-game touch control (`ControlButton`, `ControlSubButton`, `ControlDrawer`, `ControlJoystick`, `DrawerPullButton`, `MineButton`, `LauncherMenuButton`), preserving exact touch hitboxes, multi-touch swipe D-pad support, dynamic resizing, and key mappings.
- **Accessibility & Efficiency Modes:** Full support for **Reduced Motion** (including Android system animator scale) and **Low Visual Effects** mode, plus seamless animated Minecraft sunset parallax loading screen synced to all real launch lifecycle stages.
- **Dynamic Version, Mod Loader & Java Runtime Manager:** Non-hardcoded official Mojang `version_manifest_v2.json` sync (with ETag/Last-Modified caching and offline resilience), Fabric, Quilt, Legacy Fabric, Forge, and NeoForge support, plus `JavaRuntimeAdvisor` automatic compatibility detection across current and future OpenJDK runtimes.
- **Unified Download & Cache Center:** SHA-1 verified atomic `.part` downloads, HTTP Range resume, bounded exponential backoff, duplicate URL suppression, pause/resume/cancel controls, mobile-data awareness, disk pre-flight checks, and orphaned temp file cleanup.
- **Instance Studio 2.0:** Backward-compatible schema v2 instances with `.bak` corruption recovery, per-instance RAM allocation, JRE/renderer overrides, isolated cloning, health check & non-destructive repair, verified `.zip` + `.sha256` backup/restore, config JSON export/import, and impact-aware deletion.
- **World Manager & Verified Backups:** Per-instance world inspection, SHA-256 verified ZIP backups with configurable retention, conflict-safe non-destructive restore, SAF import/export, and canonical-path-guarded deletion.
- **Mod Manager 2.0, Packs, Shaders, Datapacks & Configs:** Zero-execution static archive parser for Fabric, Quilt, Forge, NeoForge, legacy `mcmod.info`, and `pack.mcmeta`; duplicate/loader compatibility alerts; `.disabled` toggles; mod state rollback snapshots; Modrinth SHA-1 update checks; and atomic plain-text config editing with `.bak` backups.
- **Skin Studio & Local Library:** Pure-Java PNG signature and dimension validation (`64×64` Steve/Alex & `64×32` Classic), dual-layer front/back character preview, Classic 4px vs Slim 3px arm switcher, local library management, and authorized official HTTPS upload (`api.minecraftservices.com`) for signed-in Microsoft accounts.
- **Graphics Center & Truthful Performance HUD:** Five user-confirmed graphics profiles (*Safe Compatibility*, *Balanced*, *Performance*, *Quality*, *Battery Saver*), real GPU/GLES/Vulkan/RAM capability advisor, last-known-good renderer recovery, redacted diagnostics copy, and an opt-in HUD showing strictly real measured refresh rate (Hz), frame interval (ms), free RAM, thermal status (API 29+), and session uptime.
- **Zyrox Guard Smart Crash Analyzer & Log Redaction:** Local deterministic classification of OOM, Java class-file version mismatches, missing dependencies, duplicate mods, Mixin conflicts, shader failures, renderer/GL/Vulkan errors, corrupt archives, network/auth issues, and native signals — with honest confidence labels, automatic credential redaction, and safe user-confirmed recovery actions.
- **Multiplayer Server Manager & Control Profiles:** Native uncompressed NBT `servers.dat` reader/writer with favorites/groups sidecar, host/port/IPv6 validation, on-demand SLP 1.7+ status & latency ping, quick-connect launch flags, portable JSON export/import, and named control layout profiles with keybind conflict & touch-target auditing.

---

## Safe Installation & APK Verification

1. Download APKs **only** from [Official GitHub Releases](https://github.com/argamerzz9582-debug/ZyroxLauncher/releases).
2. Verify the downloaded APK against the release `SHA256SUMS.txt`:
   ```bash
   sha256sum -c SHA256SUMS.txt
   ```
3. Verify the APK signing signature (v2 + v3):
   ```bash
   apksigner verify --verbose --print-certs ZyroxLauncher-2.0.0-arm64-v8a.apk
   ```
4. Inside the app, open **TRUST → Security & Trust** (or **Settings → Security & GPU**) to inspect and copy the live APK signing certificate SHA-256 fingerprint and compare it with the official release notes.
5. **Google Play Protect Guidance:** Keep Google Play Protect enabled on your device and never install re-signed or modified APKs from third-party mirrors. Sideloaded open-source Java launchers load OpenJDK runtimes, native OpenGL/Vulkan renderers, and user-chosen game/mod JARs, so Android may occasionally offer an app scan on newly published releases. Zyrox Launcher never bypasses, disables, or interferes with Android security checks.

---

## Building & Testing from Source

### Prerequisites
- JDK 17 (compiles Android Java sources targeting Java 8 compatibility, `minSdk 21`, `targetSdk 36`)
- Android SDK Platform 36, Build-Tools, and Android NDK `29.0.14206865`

### Commands
```bash
# Run automated JUnit 4 unit test suite
./gradlew :app_mojo:testRuntimeDebugUnitTest

# Run Android Lint static analysis
./gradlew :app_mojo:lintRuntimeRelease

# Build debug APK (universal + ABI splits)
./gradlew :app_mojo:assembleRuntimeDebug

# Build unsigned release APKs (signed via environment variables when configured)
./gradlew :app_mojo:assembleRuntimeRelease
```

---

## Documentation
- [`SECURITY.md`](SECURITY.md) — Vulnerability reporting, signing verification, Android permission rationale, archive/network hardening, and Play Protect transparency.
- [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) & [`GPLAY_PRIVACY_POLICY.md`](GPLAY_PRIVACY_POLICY.md) — Zero-telemetry privacy practices, local data handling, and log redaction.
- [`CHANGELOG.md`](CHANGELOG.md) — Detailed Zyrox Launcher `v2.0.0` release changelog and upgrade notes.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — Package layout, module boundaries, data flows, and design principles.
- [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md) — Production signing setup, CI/CD release verification, and upgrade checklist.

---

## Open-Source Credits & Attribution

Zyrox Launcher builds upon the open-source **MojoLauncher** and **PojavLauncher** projects and their contributors, licensed under **GNU LGPL v3.0**, alongside open-source graphics translators (GL4ES, LTW, Mesa/Zink, Freedreno/Turnip), OpenJDK mobile runtimes, Modrinth API, and CurseForge API. All upstream copyrights and licenses remain respected and preserved.
