# BannerHub ReVanced Progress Log

---

### [bh-fix-menu-crash] — Fix bannerHubMenuPatch dex verifier VerifyError (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `e0caa2f`  |  **CI:** run 24941343227 ✅
#### What changed
- **`BannerHubPatch.kt`** — rewrote `bannerHubMenuPatch` to use `p0`/`p1` parameter registers instead of `v0`/`v1` locals, matching BannerHub's smali exactly
#### Root cause
The previous if-ne chain used `v1` for both `const/16 v1, 0xa` (int) and `new-instance v1, Intent` (ref) on different paths converging at merge labels. The dex verifier reported `v1 (type=Conflict)` at `invoke-direct {v1, p2, v0}` in the BhConfigs block (offset `[0x44]`), causing a `VerifyError` that crashed the app when opening the side menu.
#### Fix
- Each BH case handler now uses: `new-instance p0, Intent; const-class p1, Activity; invoke-direct {p0, p2, p1}; invoke-virtual {p2, p0}; goto :bh_goto_1`
- `ExternalLabel("bh_goto_1", instructions[goto1Index])` points to `sget-object p0, Unit.a` (the existing `:goto_1` return path)
- `const/4 p1, 0x0` is appended before the packed-switch fallthrough to restore `p1=0` so existing cases 0-9 (including `pswitch_7` which passes `p1` as Object) remain unaffected
- p0 and p1 are both int at every merge point, eliminating the type conflict

---

### [v1.0.0-bh1 release] — First full BannerHub ReVanced release (2026-04-25)
**Tag:** `v1.0.0-bh1`  |  **Branch:** `bannerhub-revanced`  |  **CI:** run 24940583371 ✅
#### Assets
- `bannerhub.apk` (~136 MB) — fully patched GameHub 5.3.5, all 10 phases applied, ready to sideload
- `patches-1.0.0.rvp` (1.8 MB) — patches bundle for use with ReVanced CLI/Manager
- `patches-1.0.0-sources.rvp` (104 KB) — sources jar

---

### [bh-phase10] — Offline Steam Skip + BCI Launcher Button (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `be230b3`  |  **CI:** run 24940402140 ✅
#### What changed
- **`BannerHubPatch.kt`** — 2 new private sub-patches + 2 new imports added:
  - `import app.revanced.patcher.extensions.ExternalLabel`
  - `import app.revanced.patcher.extensions.instructions`
  - `import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction`
- **`bannerHubOfflineSteamSkipPatch`** (Feature 11 — Offline Steam Skip):
  - Targets `SteamGameByPcEmuLaunchStrategy$execute$3.invokeSuspend()`
  - Finds the unique `if-nez p1` (register 1, auto-login result) in the method
  - Finds the single existing `invoke-static NetworkUtils;->r()Z` at `:goto_5` as the ExternalLabel target
  - Injects 3 instructions after the `if-nez`: `NetworkUtils.r()` + `if-eqz → :bh_steam_offline`
  - When offline (r()=false/0), jumps to `:goto_5` which re-checks → takes `:cond_13` (direct launch)
  - When online, falls through to original TheRouter → show-login-screen block
- **`bannerHubBciLauncherPatch`** (Feature 2 — BCI Launcher Button):
  - Adds `iv_bci_launcher` id item to `res/values/ids.xml`
  - Inserts `FrameLayout @id/iv_bci_launcher` (30×30dp, marginStart 16dp) AFTER `@id/iv_search` in `llauncher_activity_new_launcher_main.xml` inside `ll_right_top_status`
  - Contains: "⬇" TextView icon + `bh_dl_badge` TextView badge (14×14dp, red #CC3333, top|end gravity, gone by default)
  - Phase 9's `bannerHubDownloadBtnPatch` now activates `BhDashboardDownloadBtn.attach()` since `getIdentifier("iv_bci_launcher")` will now resolve the id
- **Feature 61** (Controller navigation): already complete from Phase 2 — no new work needed
- **No new Java/stubs**: all needed classes existed from earlier phases
- Both patches added to `bannerHubPatch.dependsOn()`

---

### [bh-phase9] — Download Service manifest fixes + badge wiring (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `0cc9d7e`  |  **CI:** run 24939848451 ✅
#### What changed
- **`BannerHubPatch.kt`** — `bannerHubManifestPatch` extended with Phase 9 manifest fixes:
  - `foregroundServiceType="specialUse"` added to 12 existing GameHub services that lack it (fixes `MissingForegroundServiceTypeException` on Android 14+): `DeviceManagementService`, `apk.update.DownloadService`, `MappingService`, `KeyboardEditService`, `SSLClientService`, `VTouchIPCService`, `UnzipService`, `EmuFileService`, `SteamService`, `DiscoveryService`, `ComputerManagerService`, `UsbDriverService`
  - `android:excludeFromRecents="true"` added to `GameDetailActivity` (prevents stale task on re-launch after game exit)
- **`bannerHubDownloadBtnPatch`** (new bytecode sub-patch):
  - Hooks `LandscapeLauncherMainActivity.initView()` before final `return-void`
  - Calls `BhDashboardDownloadBtn.attach(ctx, rootView.findViewById(id))` where id is resolved at runtime via `Resources.getIdentifier("iv_bci_launcher", "id", pkg)` — gracefully no-ops (returns 0) until Phase 10 adds the `iv_bci_launcher` view
  - `bannerHubDownloadBtnPatch` added to `bannerHubPatch.dependsOn()`
- **No new Java/stubs**: `BhDownloadService`, `BhDownloadsActivity`, and `BhDashboardDownloadBtn` all existed from Phase 2; no new extension files needed
- **Feature coverage:** Feature 56 (BhDownloadService + foreground service type fixes)

---

### [bh-phase8] — Config Sharing: Export/Import/Frontend Export (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commit:** `6a08f67`  |  **CI:** run 24939580424 ✅
#### What changed
- **3 new stubs** added to `extensions/gamehub/stub/`:
  - `kotlin/jvm/functions/Function1.java` — minimal interface for lambda compilation
  - `com/xj/common/service/bean/GameDetailEntity.java` — `getId()I`, `getLocalGameId()String`, `getName()String`, `getSteamAppId()String`
  - `com/xj/landscape/launcher/ui/gamedetail/GameDetailSettingMenu.java` — `z()FragmentActivity` (context accessor)
- **3 new lambda extension files** in `com/xj/landscape/launcher/ui/gamedetail/`:
  - `BhExportLambda.java` — implements Function1; resolves gameId (getId>0→String.valueOf else getLocalGameId); calls `BhSettingsExporter.showExportDialog(ctx, gameId, gameName)` (Feature 52)
  - `BhImportLambda.java` — same pattern; calls `BhSettingsExporter.showImportDialog(...)` (Feature 53)
  - `BhFrontendExportLambda.java` — resolves gameId via localGameId → steamAppId; calls `BhSettingsExporter.showFrontendExportDialog(...)` (Feature 57)
- **`BannerHubPatch.kt`** changes — new `bannerHubConfigSharingPatch`:
  - Fingerprints `GameDetailSettingMenu.W()` (getPcGamesOptions Kotlin coroutine) by `definingClass + name == "W"`
  - Finds last `RETURN_OBJECT` via `indexOfFirstInstructionReversedOrThrow`
  - Injects 3 Option blocks BEFORE return: `move-object/from16 v4, p0` (p0=this at reg 19) + `iget-object v3, v5, L$0` (GameDetailEntity from continuation) + `new-instance Option; invoke-direct/range {v9..v17} Option.<init>; List.add` ×3
  - `bannerHubConfigSharingPatch` added to `bannerHubPatch.dependsOn()`
- **Feature coverage:** Feature 52 (Export Config), Feature 53 (Import Config), Feature 57 (Frontend Export)
- **Not in Phase 8:** Feature 54/55 (Community Config Browser) — fully wired from Phase 2 (Java, manifest, menu) + Phase 3 (HomeLeftMenuDialog ID=13 routing); no new patch hooks needed

---

### [bh-phase7] — Wine Task Manager sidebar tab (2026-04-25)
**Branch:** `bannerhub-revanced`  |  **Commits:** `237cb22`, `99cb8cf`, `6320f66`  |  **CI:** run 24939288310 ✅
#### What changed
- **6 new Java extension files** in `extensions/gamehub/src/main/java/com/xj/winemu/sidebar/`:
  - `BhTaskManagerFragment.java` — Fragment with 3-tab UI (Applications/Processes/Launch); reads `/proc/<pid>/comm` + `/proc/<pid>/environ` (null-byte delimited) for Wine process detection; VRAM info via reflection chain `ctx.u.a`; auto-refreshes every 3s while visible; programmatic UI (no XML layout)
  - `BhTaskClickHelper.java` — static `setup(View)` wires sidebar button click via `getIdentifier()` + `Proxy.newProxyInstance(Function0)` (avoids kotlin-stdlib compile dep); calls `U("BhTaskManagerFragment")` on click
  - `BhFolderListener.java`, `BhExeLaunchListener.java` — click listeners for file browser (folder nav + exe launch via `BhWineLaunchHelper.launchExe`)
  - `BhInitLaunchRunnable.java`, `BhBrowseToRunnable.java` — background/main-thread runnables for WINEPREFIX discovery + file browser navigation
- **2 new stub files** added to `extensions/gamehub/stub/src/main/java/androidx/fragment/app/`:
  - `Fragment.java`, `FragmentActivity.java` — minimal stubs so `BhTaskManagerFragment extends Fragment` compiles
- **1 new drawable** `patches/src/main/resources/bannerhub/res/drawable/bh_sidebar_taskmanager.xml` — vector icon (3-bar task list with dot)
- **`BannerHubPatch.kt`** changes — added `bannerHubTaskManagerResourcePatch` + `bannerHubTaskManagerPatch`:
  - Resource patch: copies drawable; adds `bh_sidebar_taskmanager` id to `ids.xml`; inserts `SidebarTitleItemView` button into `winemu_activitiy_settings_layout.xml` after `sidebar_setting`
  - Bytecode patch on `WineActivityDrawerContent.<init>`: injects `BhTaskClickHelper.setup(p0)` BEFORE first `SGET_OBJECT AppPreferences.INSTANCE`
  - Bytecode patch on `WineActivityDrawerContent.U(String)`: injects BhTaskManagerFragment factory block BEFORE first `hashCode()` call; puts fragment in `m` map + shows via `ShowHideExtKt.a()`
  - `bannerHubTaskManagerPatch` added to `bannerHubPatch.dependsOn()`
- **Feature coverage:** Feature 51 (Wine Task Manager); Feature 12 (RTS) already in repo as standalone `rtsTouchControlsPatch`

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
