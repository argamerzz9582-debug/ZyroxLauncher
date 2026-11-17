# Zyrox Launcher 2.0 — Architecture Overview

## Design Principles
1. **Modular Single-Responsibility Engines:** Pure business/storage/protocol logic lives in focused domain packages (`security`, `metadata`, `downloader`, `instances`, `worlds`, `content`, `skins`, `diagnostics`, `servers`, `input`, `utils`) decoupled from Android Fragments so it is directly testable under JVM JUnit 4.
2. **Non-Destructive Data Safety:** All critical writes (`JSONUtils`, `Instances`, `InstanceManager`, `WorldManager`, `InstanceContentManager`, `ServerListManager`, `DownloadUtils`) write to temporary `.tmp`/`.part` files, preserve `.bak` backups or `.sha256` sidecars, validate paths through `ArchiveSecurity`, and never silently wipe user worlds, mods, accounts, or configs.
3. **Security & Privacy by Default:** Zero hidden telemetry, cleartext traffic disabled, zero arbitrary code execution when inspecting third-party archives, and automatic secret scrubbing via `LogRedactor`.
4. **Hardware-Accelerated Liquid Glass UI:** Zero-per-frame-allocation Canvas rendering in `ZyroxLiquidGlassDrawable`, `ZyroxControlGlyphRenderer`, and `MinecraftLoadingLandscapeView`, with first-class Reduced Motion and Low Visual Effects support and preserved landscape touch hitboxes.

---

## Package Map (`app_mojo/src/main/java/git/artdeell/mojo/`)

| Package | Responsibility |
| :--- | :--- |
| `security/` | `LogRedactor` (token/JWT/header/URL/email/UUID scrubbing), `AppTrustVerifier` (runtime APK signing SHA-256 & installer inspection). |
| `utils/` | `ArchiveSecurity` (Zip-Slip & zip-bomb guards, SHA-1/SHA-256), `ZipUtils`, `JSONUtils` (atomic + `.bak` recovery), `NetworkPolicyHelper` (metered/disk/backoff), `DownloadUtils`, `GpuUtils`, `JREUtils`, `jre/{GameRunner,JavaRunner}`. |
| `metadata/` | Dynamic Mojang `version_manifest_v2.json` sync, ETag/Last-Modified disk cache, version comparison/filtering, and `JavaRuntimeAdvisor` non-hardcoded JRE compatibility assessment. |
| `downloader/` | `DownloadQueueManager` (unified task queue, speed/progress, pause/resume/cancel, `.part` cleanup), `Downloader`, `DownloadFileTask` (Range resume + SHA-1 verification + deduplication). |
| `instances/` | Schema v2 `Instance`, `Instances` (with `.bak`/`.corrupt` recovery), `InstanceManager` (clone, config export/import, SHA-256 backup/restore, health/repair, delete impact, renderer startup recovery). |
| `worlds/` | `WorldManager` (`saves/` discovery, validation, SHA-256 ZIP backups + retention pruning, conflict-safe restore, SAF export/import, safe contained delete). |
| `content/` | `ModMetadataParser` (static Fabric/Quilt/Forge/NeoForge/`mcmod.info`/`pack.mcmeta` reader), `InstanceContentManager` (compatibility warnings, `.disabled` toggles, mod snapshots, Modrinth SHA-1 updates, packs/shaders/datapacks, safe config editing). |
| `skins/` | `SkinStudioManager` (`64×64`/`64×32` PNG IHDR validator, Steve/Alex model detection, local skin library, official `api.minecraftservices.com` HTTPS upload). |
| `diagnostics/` | `ZyroxCrashAnalyzer` (Zyrox Guard local crash classifier + confidence + evidence), `GraphicsAdvisor` (profiles, GPU/GLES/Vulkan/RAM report), `PerformanceHudOverlay` (truthful Choreographer Hz/ms, RAM, thermal, uptime HUD). |
| `servers/` | `ServerListManager` (binary NBT `servers.dat` + favorites/groups sidecar, host/port validation, quick-play flags, JSON export/import), `ServerPinger` (bounded SLP 1.7+ status & latency ping). |
| `input/` | `ControlLayout`, `ControlProfileManager` (profile list/clone/import/keybind & touch-target audit), `buttons/{ControlInterface,ControlButton,ControlSubButton,ControlDrawer,ControlJoystick}`, `handleview/DrawerPullButton`, gamepad mapping. |
| `ui/` | `ZyroxLiquidGlassDrawable`, `ZyroxControlGlyphRenderer`, `ZyroxStudioUi` (shared studio components + `SkinCharacterPreviewView`), `MinecraftLoadingLandscapeView`, `ZyroxShaderBackgroundView`, `ZyroxLogHighlighter`. |
| `fragments/` | `MainMenuFragment`, `InstanceEditorFragment`, `ContentManagerFragment`, `WorldManagerFragment`, `ServerManagerFragment`, `SkinStudioFragment`, `DownloadCenterFragment`, `AboutSecurityFragment`, `SearchModFragment`, `LogInspectorFragment`, auth & installer fragments. |
| `authenticator/` | Microsoft OAuth/Xbox/Minecraft authentication, Ely.by authentication, offline profiles, and private account persistence (`Accounts`). |
| `game/` | `GameActivity` (real 7-stage loading lifecycle, control overlay, `PerformanceHudOverlay`), `GameView`, `renderer/` (GL4ES, LTW, Zink, Freedreno/Turnip, Mesa), and native platform bridge (`src/main/jni/`). |
