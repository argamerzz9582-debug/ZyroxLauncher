# Zyrox Launcher 2.0 — Production Release & Signing Checklist

## 1. GitHub Repository Secrets (Release Signing)

Configure these four encrypted repository secrets (preferably scoped to a protected `production-release` environment in **Settings → Secrets and variables → Actions**):

| Secret Name | Description |
| :--- | :--- |
| `ZYROX_KEYSTORE_BASE64` | Base64-encoded production `.jks` / `.keystore` (`base64 -w 0 release.jks`). Never commit the keystore to Git. |
| `ZYROX_KEYSTORE_PASSWORD` | Keystore password. |
| `ZYROX_KEY_ALIAS` | Signing key alias inside the keystore. |
| `ZYROX_KEY_PASSWORD` | Private key password. |

### Keystore Safety Rules
- **Back up your production keystore offline** in a secure encrypted vault. Losing it prevents seamless in-place Android upgrades for existing users.
- **Signing continuity:** If upgrading users from `v1.0.3` (certificate SHA-256 `66:B2:F8:22:EC:EE:EA:52:BF:0B:CF:FD:E0:D5:F9:BE:36:2D:E3:04:95:23:2A:AE:7E:F1:5E:9E:8C:E6:C5:AF`), use the same release keystore for seamless updates. If rotating to a new release keystore, clearly state in the release notes that users should export/back up instances and worlds before uninstalling the older build and installing the new version.
- Pull requests never receive signing secrets; CI only signs on trusted `v*` tag pushes or explicit manual `workflow_dispatch` when all four secrets are configured, and wipes temporary decoded keystore files in an `if: always()` step.

---

## 2. Pre-Release Verification Gate

- [ ] `app_mojo/build.gradle` `versionName` and `versionCode` match the target release (e.g. `2.9.0`; `versionCode` comes from `GITHUB_RUN_NUMBER` in CI).
- [ ] `CHANGELOG.md`, `README.md`, `SECURITY.md`, and `PRIVACY_POLICY.md` are up to date.
- [ ] Zero secrets, keystores, `.jks`, `.keystore`, tokens, or private keys exist in `git status` or git history.
- [ ] CI job `Security & Manifest Static Audit` is green (no cleartext HTTP, no dangerous permissions, no leaked tokens).
- [ ] CI job `Build, JUnit Tests & Android Lint` is green (all JUnit 4 unit tests pass, Android Lint report uploaded, release APKs built with `debuggable=false`).
- [ ] Upgrade compatibility verified (v1 instances, existing worlds, mods, accounts, and `default.json` controls load cleanly without data loss).

---

## 3. Signed Release Verification Gate

- [ ] Push annotated tag `vX.Y.Z` (only after explicit maintainer approval) to trigger `sign-release` & `publish-release`.
- [ ] Confirm `apksigner verify --verbose --print-certs` reports `Verified using v1 scheme: true`, `v2 scheme: true`, `v3 scheme: true` and matches your expected production certificate SHA-256.
- [ ] Confirm `SHA256SUMS.txt` is attached to the GitHub Release and matches every uploaded APK (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, and `universal`).
- [ ] Publish the public signing certificate SHA-256 in the GitHub Release body so users can cross-check inside **Zyrox Launcher → TRUST → Security & Trust**.
