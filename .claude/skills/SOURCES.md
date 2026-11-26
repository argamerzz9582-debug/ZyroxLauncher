# Installed agent skills

Project-level [Agent Skills](https://agentskills.io) for Claude Code and other compatible agents.
Each folder here holds one skill (`<slug>/SKILL.md`). Claude Code finds them automatically when it
runs from the repository root.

| Skill(s) | Upstream | Commit | License |
|---|---|---|---|
| `android-development` | https://github.com/dpconde/claude-android-skill | `edfca5e` (2025-12-07) | MIT (`android-development/LICENSE`) |
| `liquid-glass` | https://github.com/haider-nawaz/liquid-glass-skill | `2c1b278` (2026-02-14) | No license file upstream. All rights reserved by the author. |
| 54 testing skills (`adb`, `compose`, `fundamentals`, `jvm-tests`, `instrumentation`, `kotlin`, `platform` sets) | https://github.com/skydoves/android-testing-skills | `8665ed5` (2026-05-25) | Apache-2.0 (`LICENSE` in each folder) |

## Install notes

- `android-testing-skills` stores its skills as `<set>/<category>/<slug>/`. They were flattened to
  `<slug>/`, the layout that `scripts/install-skills.sh` produces. Relative cross-links between skills
  were rewritten to `../<slug>/...`, so they still resolve after flattening.
- `liquid-glass` is a Claude Code plugin skill. Only the skill folder
  (`plugins/liquid-glass/skills/liquid-glass`) was copied.
- Scope: `liquid-glass` covers Apple SwiftUI (iOS/macOS 26). `android-development` targets
  Kotlin + Jetpack Compose + Hilt. Zyrox Launcher uses Java + XML Views, so treat both as design and
  architecture references, not as rules to apply directly.

## Updating

```bash
git clone --depth 1 https://github.com/<owner>/<repo>.git /tmp/<repo>
# then re-copy the skill folders as described above
```
