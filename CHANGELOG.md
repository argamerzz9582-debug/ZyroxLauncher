# Changelog

All notable changes to **Zyrox Launcher** are documented in this file.

## [Unreleased]

### Security
- **Rapid Start integrity gap closed:** cached files are no longer trusted by byte size alone. A file is skipped only if a `.zyrox.verified` sidecar marker shows it previously passed SHA-1 verification and its size/last-modified time haven't changed since (`CheckFileOnDiskTask`, `CompleteMetadataTask`, `Downloader`).
- **Account token vault fails closed (API 23+):** if Keystore encryption fails, the vault drops and regenerates a corrupted key once, then stores the `"0"` sentinel (re-login required). It no longer falls back to writing the token in plaintext (`AccountTokenCipher`).

### Fixed
- **Download retries are now a bounded loop:** a failure deep in the retry chain could be caught by an earlier attempt and restart the retry budget. Progress accounting is rolled back exactly once per attempt, truncated bodies are retried, and servers that ignore `Range` fall back to a full download without spending a retry (`DownloadFileTask`).
- **Downloader coordination:** a signal-driven progress loop replaces the 40 ms sleep-poll, the metadata pool honors the thread setting, and a per-path lock stops concurrent batches from writing the same `.part` file. The part-file directory is resolved lazily rather than captured before storage init.
- **Crash auto-fix safety:** culprit extraction no longer reads mod names from ordinary prose (`Exception in thread "main"`). Quarantine refuses ambiguous matches, never touches core/library jars, and never overwrites an existing `.disabled` copy (`ZyroxCrashAnalyzer`).
- **ExtraCore:** duplicate listener registration is ignored, per-key list creation is race-free, `setValue` can't NPE on a just-collected listener, and `consumeValue` is atomic.

### Changed
- Lint now gates CI through a baseline (`app_mojo/lint-baseline.xml`): new issues and security checks fail the build. The deprecated `lintOptions` blocks were merged into one `lint {}` block.
- The deprecated `Environment.getExternalStorageDirectory()` is isolated in one documented helper, used only on API < 29.
- README/SECURITY now describe Rapid Start accurately. Removed a stale `mojolauncher.ru` link from `README_RU.md`.

### Tests
- Added `AccountTokenCipherTest`, `CheckFileOnDiskTaskTest`, `DownloadQueueManagerTest`, `ZyroxCrashAnalyzerAutoFixTest`, and `ExtraCoreTest`.

## [2.0.0] — 2026-11-16

### Highlights
Major **Zyrox Launcher 2.0** architecture, security, design, and studio release — completely upgrading security transparency, archive/download reliability, instance/world/mod/skin/server/control studios, smart crash diagnostics, performance telemetry, and a full **Liquid Glass** button and in-game control redesign while preserving backward compatibility with existing accounts, instances, worlds, mods, renderers, runtimes, and custom control profiles.

### Added
- **Liquid Glass Button & In-Game Control System:**
  - `ZyroxLiquidGlassDrawable` and `ZyroxControlGlyphRenderer` rendering neutral frosted glass, concentric crystal-white specular rims, top/bottom light-bar capsules, cool ice-blue pressed/toggled states, and crisp `#FAFCFF` vector glyphs (`UP`, `DOWN`, `LEFT`, `RIGHT`, `CENTER`, `JUMP`, `GEAR`) and adaptive typography across `ControlButton`, `ControlSubButton`, `ControlDrawer`, `ControlJoystick`, `DrawerPullButton`, `MineButton`, `LauncherMenuButton`, and all launcher XML button/chip/toggle/nav drawables.
  - Full **Reduced Motion** (plus Android system animator scale respect) and **Low Visual Effects** modes in Settings.
- **Security & Trust Center (`security/` & `AboutSecurityFragment`):**
  - `LogRedactor` automatically scrubbing access/refresh tokens, JWTs, Bearer/XBL headers, OAuth query codes, emails, and UUIDs from launch logs, crash views, clipboard copies, and exported logs.
  - `AppTrustVerifier` inspecting live APK signing certificate SHA-256 fingerprint, version metadata, debuggable state, and installer origin with 1-tap clipboard copy and honest Play Protect guidance.
  - `ArchiveSecurity` enforcing canonical Zip-Slip containment, zip-bomb compression ratio guards, bounded extraction budgets, and SHA-1/SHA-256 checksum verification.
- **Unified Download & Cache Center (`DownloadQueueManager`, `NetworkPolicyHelper`, `DownloadCenterFragment`):**
  - HTTP Range resume for `.part` downloads, bounded exponential backoff, duplicate URL suppression, configurable worker concurrency, pause/resume/cancel queue controls, orphaned temp file cleaner, and live network/storage status.
- **Instance Studio 2.0 & Java Runtime Advisor (`InstanceManager`, `JavaRuntimeAdvisor`):**
  - Backward-compatible v2 instance schema with `.bak` auto-backup and `.corrupt` quarantine recovery, per-instance JVM RAM allocation, isolated cloning, health check & non-destructive repair, SHA-256 verified backup/restore, portable config JSON export/import, last-known-good renderer fallback, impact-aware delete confirmation, and non-hardcoded Java runtime compatibility guidance.
- **World Manager & Verified Backups (`WorldManager`, `WorldManagerFragment`):**
  - World discovery, `level.dat`/`session.lock`/thumbnail inspection, atomic ZIP + `.sha256` backups with automatic retention pruning, conflict-safe non-destructive restore, SAF export/import, and canonical-path-guarded deletion.
- **Mod Manager 2.0, Packs, Shaders, Datapacks & Configs (`ModMetadataParser`, `InstanceContentManager`, `ContentManagerFragment`):**
  - Zero-execution static parser for Fabric, Quilt, Forge, NeoForge, `mcmod.info`, and `pack.mcmeta`; duplicate mod ID & loader mismatch detection; `.disabled` toggles; mod state rollback snapshots; Modrinth SHA-1 update checker; and atomic config editing with `.bak` backups.
- **Skin Studio (`SkinStudioManager`, `SkinStudioFragment`):**
  - Strict `64×64` and `64×32` PNG validation, Classic (4px) / Slim (3px) arm detection and switching, dual-layer front/back character preview, local skin library, and authorized official `api.minecraftservices.com` HTTPS upload for Microsoft accounts.
- **Graphics Center, Performance HUD & Zyrox Guard Crash Analyzer (`diagnostics/`):**
  - Five user-confirmed graphics profiles (*Safe Compatibility*, *Balanced*, *Performance*, *Quality*, *Battery Saver*), GPU/GLES/Vulkan/RAM capability report with redacted copy, optional in-game `PerformanceHudOverlay` (real display Hz, frame interval ms, free RAM, thermal status, uptime), and deterministic `ZyroxCrashAnalyzer` with confidence scoring and safe recovery shortcuts.
- **Server Manager & Control Profiles (`ServerListManager`, `ServerPinger`, `ControlProfileManager`, `ServerManagerFragment`):**
  - Native binary NBT `servers.dat` read/write, favorites/groups sidecar, host/port/IPv6 validation, on-demand SLP 1.7+ status & latency ping, `--quickPlayMultiplayer` / `--server --port` quick-connect, JSON export/import, and named control layout clone/import/conflict auditing.
- **CI/CD & Automated Test Suite:**
  - Hardened 4-job SHA-pinned GitHub Actions pipeline (`security-sast`, `build-test-verify`, `sign-release`, `publish-release`) with automated secret/cleartext/permission scanning, JUnit test execution, Android Lint reporting, `apksigner` verification, and `SHA256SUMS.txt` generation.
  - Expanded JUnit 4 suite covering security redaction, archive/JSON resilience, instance studio, world backups, static mod metadata, crash analysis, runtime advice, server NBT/SLP, skin PNG validation, control auditing, and Liquid Glass glyph resolution.

### Fixed
- Redacted raw `launchArgs` logging in `GameRunner` so authentication tokens are never printed to logs.
- Fixed landscape Y-axis drag clamping in `ControlInterface` (previously clamped against parent width instead of height).
- Added bounds guards to JVM flag preprocessing in `JavaRunner` (`-p`, `--add-*`, `-cp`) and null safety to `RendererCache.releaseRendererCache()`.
- Replaced remaining legacy `"MojoLauncher"` window title, `"punjabilauncher"` log tag, and upstream `URL_HOME` references with accurate Zyrox Launcher values.
- Synced `layout/fragment_launcher.xml` and `layout-land/fragment_launcher.xml` while keeping the home updates card removed.

## [1.0.3] — Prior Release
- Dynamic official Mojang version metadata sync (`metadata/` package + 20 JUnit tests), animated `MinecraftLoadingLandscapeView` loading backdrop, and clean open-sky home screen layout.
