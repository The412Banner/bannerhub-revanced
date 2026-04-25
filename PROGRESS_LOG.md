# BannerHub ReVanced Progress Log

Tracks every branch, patch, fix, and release on The412Banner/bannerhub-revanced.
Goal: reproduce BannerHub as true ReVanced patches on top of playday's GameHub 5.3.5 base.

---

### [bh-phase3] — Bytecode hooks: menu routing + pending-launch (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `0d56766`  |  **CI:** run 24936864656 ✅
#### What changed
- **`BannerHubPatch.kt`** — added two private `bytecodePatch` sub-patches:
  - **`bannerHubMenuPatch`**: injects an if-chain BEFORE the `packed-switch` in `HomeLeftMenuDialog.o1()` to intercept menu item IDs 10 (GOG), 11 (Amazon), 12 (Epic), 13 (BhGameConfigs) and start their respective Activities via `p2` (FragmentActivity as Context). Uses existing registers v0/v1 (method has `.locals 2`).
  - **`bannerHubPendingLaunchPatch`**: injects AFTER `invoke-super onResume()` in `LandscapeLauncherMainActivity.onResume()` to read `pending_gog_exe` / `pending_amazon_exe` / `pending_epic_exe` from SharedPreferences (`bh_gog_prefs` / `bh_amazon_prefs` / `bh_epic_prefs`), clear the pref on hit, then call `this.B3(exePath)`. Uses existing registers v0/v1/v2 (method has `.locals 3`).
- Both sub-patches added to `bannerHubPatch.dependsOn()`
- New imports: `addInstructionsWithLabels`, `indexOfFirstInstructionOrThrow`, `Opcode`, `ReferenceInstruction`, `MethodReference`, `firstMethod`

---

### [bh-phase2] — Extension Java files + manifest registration (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `b5f72c5` + lint fixes `df228ad`/`78b5eab`  |  **CI:** run 24936539339 ✅
#### What changed
- **41 Java extension classes** copied from BannerHub v3.4.0 into `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/`
  - GOG: GogMainActivity, GogLoginActivity, GogGamesActivity, GogGameDetailActivity, GogGame, GogApiClient, GogAuthClient, GogCredentialStore, GogTokenRefresh, GogInstallPath, GogLaunchHelper, GogCloudSaveManager, GogDownloadManager (13 files)
  - Epic: EpicMainActivity, EpicLoginActivity, EpicGamesActivity, EpicGameDetailActivity, EpicGame, EpicApiClient, EpicAuthClient, EpicCredentialStore, EpicDownloadManager, EpicFreeGamesActivity, EpicCloudSaveManager (11 files)
  - Amazon: AmazonMainActivity, AmazonLoginActivity, AmazonGamesActivity, AmazonGameDetailActivity, AmazonGame, AmazonApiClient, AmazonAuthClient, AmazonCredentialStore, AmazonDownloadManager, AmazonManifest, AmazonPKCEGenerator, AmazonSdkManager, AmazonLaunchHelper (13 files)
  - BH utilities: FolderPickerActivity, BhGameConfigsActivity, BhSettingsExporter, BhWineLaunchHelper, BhDownloadService, BhDownloadsActivity, BhDashboardDownloadBtn (7 files — Feature 47 FolderPickerActivity is Phase 2)
  - Skipped (Phase 4): BhDetailedHud.java, BhFrameRating.java, BhKonkrHud.java (use `com.xj.winemu.sidebar` package — need special treatment)
- **`BannerHubPatch.kt`** — added `bannerHubManifestPatch` registering all 16 new Activities + BhDownloadService + permissions (FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS)
- Activities not reachable yet (HomeLeftMenuDialog + LandscapeLauncherMainActivity bytecode hooks come in Phase 3)

---

### [bh-phase1] — BannerHub monolithic patch created; Phase 1 features (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `b50d84a`  |  **CI:** run 24936106576 ✅
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
