# BannerHub ReVanced Progress Log

Tracks every branch, patch, fix, and release on The412Banner/bannerhub-revanced.
Goal: reproduce BannerHub as true ReVanced patches on top of playday's GameHub 5.3.5 base.

---

### [bh-phase1] — BannerHub monolithic patch created; Phase 1 features (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** pending  |  **CI:** pending
#### What changed
- `patches/src/main/kotlin/app/revanced/patches/gamehub/bannerhub/BannerHubPatch.kt` — new monolithic patch; Phase 1 features added
  - **Feature 1**: "My Games" string rename via `bannerHubResourcesPatch` (modifies `llauncher_main_page_title_my`)
  - **Feature 10**: EmuReady API default off (GameHubPrefs.isExternalAPI default: `true` → `false`)
- `patches/api/patches.api` — added `BannerHubPatchKt` entry
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/prefs/GameHubPrefs.java` — changed `isExternalAPI()` default from `true` to `false`

---

### [docs] — PORTING_REPORT.md added to repo; progress log caught up (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** pending
#### What changed
- `PORTING_REPORT.md` — full 61-feature inventory with injection types, dependencies, complexity ratings, and 10-phase porting order; baseline is BannerHub v3.4.0
- `PROGRESS_LOG.md` — backfilled all actions since branch creation

---

### [setup] — baseline CI green on bannerhub-revanced branch (2026-04-25)
**Branch:** `bannerhub-revanced` (forked from `playday-build`)  |  **CI:** run 24934888738 ✅
#### State
- Full playday feat/gamehub patch set building and releasing cleanly
- Vanilla GameHub 5.3.5 APK stored as `base-apk` release asset
- `playday-build` → `v1.0.0-playday` release published with `bannerhub.apk` + `patches-1.0.0.rvp`
- `bannerhub-revanced` branch created from `playday-build` as clean baseline
- No BannerHub-specific patches added yet — ready to begin porting features one by one

---

### [setup] — playday-build: all build errors resolved (2026-04-25)
**Branch:** `playday-build`  |  **Final commit:** `8e1aa3f`
#### Errors fixed (in order)
1. `libs.plugins.android.library` alias missing → added `[plugins]` section to `libs.versions.toml`
2. `apiDump` task not found at root → removed `apiDump` from CI, committed playday's `patches.api`
3. `app.revanced:patcher:21.0.0` not found → bumped to `22.0.0`, switched plugin source to `revanced/gamehub-patches`
#### Key config (playday-build / bannerhub-revanced)
- Plugin: `app.revanced.patches:1.0.0-dev.11` from `maven.pkg.github.com/revanced/gamehub-patches`
- Gradle: 9.3.1
- Patcher: `app.revanced:patcher:22.0.0`
- ReVanced CLI: 6.0.0 with `--bypass-verification`
- CI env: `ORG_GRADLE_PROJECT_githubPackagesUsername/Password` required for plugin resolution

---
