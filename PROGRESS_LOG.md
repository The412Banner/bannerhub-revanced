# BannerHub ReVanced Progress Log

---

### [bh-phase6] — CPU core limit multi-select + VRAM unlock (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `8002e38`  |  **CI:** run 24938521774 ✅
#### What changed
- **`CpuMultiSelectHelper.java`** added to `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/`:
  - Reflection-based 8-core bitmask checkbox dialog; reads current mask via `PcGameSettingOperations.H()`, writes via `SPUtils.m(key, mask)`; fires `Function1` callback with `DialogSettingListItemEntity` built via Kotlin defaults constructor (Feature 17a)
- **`BannerHubPatch.kt`** changes — added `bannerHubCpuPatch` and `bannerHubVramPatch`:
  - **`bannerHubCpuPatch`**:
    - Feature 17a: intercepts `SelectAndSingleInputDialog$Companion.d()` before the `b(int,String)` list call; if `contentType == CONTENT_TYPE_CORE_LIMIT` calls `CpuMultiSelectHelper.show()` and returns, bypassing the single-select picker
    - Feature 17b: in `EnvironmentController.d()`, after the `(1<<count)-1` formula (`shl-int` + `sub-int/2addr`), overrides `v0` with `Config.w()` (the raw bitmask stored by CpuMultiSelectHelper) so affinity is applied correctly
  - **`bannerHubVramPatch`**:
    - Feature 18 F0(): injects 4 string-return cases (0x1800=6 GB, 0x2000=8 GB, 0x3000=12 GB, 0x4000=16 GB) before the `pc_cc_cpu_core_no_limit` fallback in `PcGameSettingOperations.F0()`
    - Feature 18 l0(): injects 4 `DialogSettingListItemEntity` list entries via `invoke-direct/range {v30..v55}` (Kotlin defaults constructor) before `return-object v1` in `PcGameSettingOperations.l0()`; uses `G0()` result in v3 to set `isSelected` per entry
  - Both sub-patches added to `bannerHubPatch.dependsOn()`
  - New constants: `ENV_CONTROLLER`, `CONFIG_CLASS`, `SELECT_DIALOG_COMPANION`, `PC_GAME_SETTING_OPS`, `CPU_MULTI_SELECT_HELPER`, `DIALOG_LIST_ITEM`, `PC_SETTING_ENTITY`, `PC_SETTING_ENTITY_COMPANION`, `DIALOG_LIST_CTOR_PARAMS`
  - New import: `indexOfFirstInstructionReversedOrThrow`

---

### [bh-phase5] — Component Manager + GameSettingViewModel coroutine hook (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `31775f6`  |  **CI:** run 24937636482 ✅
#### What changed
- **3 Java extension files** added to `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/`:
  - `ComponentInjectorHelper.java` — reflection-based WCP/ZIP extraction + EmuComponents registration; `appendLocalComponents(List, int)` injected into GameSettingViewModel (Feature 4/8)
  - `ComponentManagerActivity.java` — full Component Manager UI: list installed components, add (file picker), backup, remove, remove-all, search (Features 3/5/7)
  - `ComponentDownloadActivity.java` — download components from repos (Arihany WCPHub, Kimchi/StevenMXZ/MTR/Whitebelyash GPU drivers, BH Nightlies); calls `injectFromCachedFile` after download (Feature 6/9)
- **`BannerHubPatch.kt`** changes:
  - `bannerHubManifestPatch`: added `ComponentManagerActivity` + `ComponentDownloadActivity` registrations
  - `bannerHubComponentManagerPatch`: new `bytecodePatch`; hooks `GameSettingViewModel$fetchList$1.invokeSuspend()` — after `CommResultEntity.setData(v7)` injects `ComponentInjectorHelper.appendLocalComponents(v7, $contentType)` so locally-installed BH components appear in game-settings component pickers (Feature 8)
  - New constants: `BH_COMPONENT_INJECTOR`, `FETCH_LIST_LAMBDA`, `CT_FIELD` (dollar-sign escaping for Kotlin string templates)
  - `bannerHubPatch.dependsOn()`: added `bannerHubComponentManagerPatch`
- All reflection; no stubs or build dependency changes needed


Tracks every branch, patch, fix, and release on The412Banner/bannerhub-revanced.
Goal: reproduce BannerHub as true ReVanced patches on top of playday's GameHub 5.3.5 base.

---

### [bh-phase4] — HUD overlay + WineActivity performance hooks (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `bebf2d6`  |  **CI:** run 24937127127 ✅
#### What changed
- **4 Java files** added to `extensions/gamehub/src/main/java/com/xj/winemu/sidebar/`:
  - `BhFrameRating.java` — compact Winlator-style HUD bar (Feature 48)
  - `BhDetailedHud.java` — 2-row detailed HUD (Feature 49)
  - `BhKonkrHud.java` — Konkr-style vertical HUD (Feature 50)
  - `BhHudInjector.java` — new file (translated from BannerHub smali); `injectOrUpdate(Activity)` selects and shows/hides the correct HUD overlay on the DecorView; `onWineCreate(Activity)` applies sustained performance mode + max Adreno clocks
- **`BannerHubPatch.kt`** — added `bannerHubHudPatch`:
  - `WineActivity.onResume()`: injects `BhHudInjector.injectOrUpdate(p0)` before `PcInGameDelegateManager.a.onResume()` (sget-object fingerprint)
  - `WineActivity.onCreate()`: injects `BhHudInjector.onWineCreate(v1)` before `WineActivity.R2()` call (v1 = this alias)
- **New imports**: `addInstructions`, `FieldReference`
- HUD files use `package com.xj.winemu.sidebar` — placed under `extensions/gamehub/src/main/java/com/xj/winemu/sidebar/`; no GameHub stub dependencies (all reflection)

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
