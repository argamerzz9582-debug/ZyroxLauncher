<h1 align="center">Zyrox Launcher</h1>

<p align="center">
  <strong>Minecraft: Java Edition Launcher for Android — Dark Glass &amp; Warm Amber Landscape Edition</strong>
</p>

<p align="center">
  <a href="https://github.com/argamerzz9582-debug/ZyroxLauncher/actions"><img src="https://github.com/argamerzz9582-debug/ZyroxLauncher/actions/workflows/android.yml/badge.svg" alt="Android CI"></a>
  <a href="https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest"><img src="https://img.shields.io/github/v/release/argamerzz9582-debug/ZyroxLauncher?label=Latest%20APK&color=F59E0B" alt="Latest Release"></a>
</p>

## Download Signed Release APK

Get the latest security-hardened, RSA-4096 v1/v2/v3 signed production builds directly from the **[GitHub Releases Section](https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest)**:

- **[ZyroxLauncher-full-release.apk](https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest/download/ZyroxLauncher-full-release.apk)** — Complete install-and-play signed APK with bundled OpenJDK runtime (**Recommended**)
- **[ZyroxLauncher-noruntime-release.apk](https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest/download/ZyroxLauncher-noruntime-release.apk)** — Compact signed APK (import your own `.tar.xz` JRE runtime)
- **[ZyroxLauncher-full-release.aab](https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest/download/ZyroxLauncher-full-release.aab)** — Signed Android App Bundle for Google Play Console
- **[All Releases & SHA-256 Checksums](https://github.com/argamerzz9582-debug/ZyroxLauncher/releases)**

## Highlights
- Runs Minecraft: Java Edition across classic, modern release, and snapshot versions on Android with Forge, Fabric, NeoForge, Quilt, Modrinth (`.mrpack`), and CurseForge modpack support.
- Landscape-first dark obsidian glass & warm amber UI with customizable touch/gamepad controls and multi-runtime OpenJDK management.
- Hardened for Android & Google Play Protect (`com.zyrox.launcher`, `targetSdk 36`, `debuggable=false`, 100% TLS/HTTPS network policy, Android Keystore AES-256-GCM account token encryption, hardened OAuth WebView, canonical ZipSlip/TarSlip containment, and automated MobSF SAST + `apksigner` verification in CI). See [SECURITY.md](./SECURITY.md) and [PRIVACY_POLICY.md](./PRIVACY_POLICY.md).

## Building from Source
```bash
./gradlew :app_mojo:assembleFullRelease
```
To sign release builds with your own PKCS12/JKS certificate, see the instructions in [SECURITY.md](./SECURITY.md).

## License
- Licensed under [GNU LGPLv3](./LICENSE).

## Credits & Third-Party Components
Zyrox Launcher builds upon the open-source work of [MojoLauncher](https://github.com/MojoLauncher/MojoLauncher) and [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher):
- [MojoLauncher](https://github.com/MojoLauncher/MojoLauncher) & [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher): GNU LGPLv3 License
- AndroidX / Android Support Libraries: Apache License 2.0
- [Holy GL4ES](https://github.com/artdeell/gl4es_extra_extra/): MIT License
- [OpenJDK](https://github.com/PojavLauncherTeam/openjdk-multiarch-jdk8u): GNU GPLv2 + Classpath Exception
- [GLFW](https://github.com/MojoLauncher/glfw) & [SDL](https://github.com/MojoLauncher/MojoSDL): zlib License
- [LWJGL2-GLFW](https://github.com/MojoLauncher/lwjgl2-glfw) & [LWJGL3](https://github.com/LWJGL/lwjgl3): BSD-3-Clause License
- [mojoexec](https://github.com/MojoLauncher/mojoexec) & [Mesa 3D Graphics Library](https://gitlab.freedesktop.org/mesa/mesa): MIT License
- [pro-grade](https://github.com/pro-grade/pro-grade) & [oboe](https://github.com/google/oboe): Apache License 2.0
- [Authlib-Injector](https://github.com/yushijinhun/authlib-injector): AGPL-3.0 License
- [OpenAL Soft](https://github.com/kcat/openal-soft/): GNU LGPL
- Minecraft skin avatars provided by [Mineskin](https://mineskin.eu/).
