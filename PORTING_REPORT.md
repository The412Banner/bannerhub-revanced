I now have a comprehensive picture of the entire BannerHub codebase. Let me compile the full feature inventory report.

---

# BannerHub — Comprehensive Feature Inventory for ReVanced Porting

**Target:** GameHub 5.3.5 (package `gamehub.lite`)
**Source:** `patches/` (smali + resources), `extension/` (Java), `patches/AndroidManifest.xml`
**Features:** 56 user-facing features (+ 1 build-infrastructure feature listed for completeness)

Features are ordered chronologically by first introduction (earliest first).

---

## FEATURE 1: "My Games" Tab Rename (UI Tweak)

**Feature name:** Rename the bottom-nav "My" tab to "My Games" for clarity.

**When added:** v1.0.3 / v1.0.0 (2026-03-12) — first patch ever applied, first successful build.

**What it touches:**
- `patches/res/values/strings.xml` — string key `llauncher_main_page_title_my` changed `"My"` → `"My Games"`

**Injection type:** Resource patch (string override only).

**Dependencies:** None.

**Complexity estimate for ReVanced porting:** Low. Single `resourcePatch` that replaces one string value. Fingerprint: string resource key `llauncher_main_page_title_my`.

---

## FEATURE 2: BCI Launcher Button

**Feature name:** One-tap button in the top-right toolbar that launches the BannersComponentInjector companion app. Shows a toast if BCI (`com.banner.inject`) is not installed.

**When added:** v1.0.5 (2026-03-12) — second feature, first stable release after initial CI fixes.

**What it touches:**
- `patches/res/layout/llauncher_activity_new_launcher_main.xml` — adds `ImageView iv_bci_launcher` to toolbar
- `patches/res/values/ids.xml` — new resource ID `iv_bci_launcher = 0x7f0a0ef9`
- `patches/res/values/public.xml` — public ID declaration
- `patches/smali_classes9/com/xj/landscape/launcher/R$id.smali` — adds `iv_bci_launcher` field
- `patches/smali_classes11/com/xj/landscape/launcher/ui/main/BciLauncherClickListener.smali` — new click listener class
- `patches/smali_classes11/com/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity.smali` — `initView()` hook: `findViewById(iv_bci_launcher).setOnClickListener(new BciLauncherClickListener())`

**Injection type:** Mixed — resource overlay (layout + IDs) + bytecode hook in `LandscapeLauncherMainActivity.initView()` + new smali class.

**Dependencies:** None (BCI app itself optional; missing handled gracefully).

**Complexity estimate:** Medium. Layout overlay plus a hook into `LandscapeLauncherMainActivity.initView()` (stable public method). Fingerprint: `LandscapeLauncherMainActivity` + `initView` method string reference to `iv_bci_launcher`. The `R$id` smali patch to add the field ID is the tricky part — in ReVanced this would be handled by `resourcePatch` automatically generating the ID via `ids.xml`.

---

## FEATURE 3: Component Manager — Core UI (Browse, Backup, Inject)

**Feature name:** "Components" entry in the left side menu opens a full-screen manager. Lists installed Wine components (DXVK, VKD3D, Box64, FEXCore, GPU Drivers). Per-component: Inject (SAF file picker → WCP/ZIP extract), Backup (recursive copy to Downloads/BannerHub/).

**When added:** v1.0.7 / v2.0.0 stable (2026-03-12) — third major feature.

**What it touches:**
- `patches/smali_classes5/com/xj/landscape/launcher/ui/menu/HomeLeftMenuDialog.smali` — adds menu item ID=9 "Components", extends packed-switch to route to `ComponentManagerActivity`
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentManagerActivity.smali` — new `AppCompatActivity` listing `getFilesDir()/usr/home/components/`
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentManagerActivity$1.smali` — background WCP extract thread
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentManagerActivity$2.smali` — UI result runnable
- `patches/AndroidManifest.xml` — `ComponentManagerActivity` declaration (`sensorLandscape`, `exported=false`)

**Injection type:** Mixed — bytecode hook in `HomeLeftMenuDialog` (side menu add + switch extension) + new Activity in `smali_classes16` + manifest entry.

**Dependencies:** WcpExtractor (Feature 4).

**Complexity estimate:** High. `HomeLeftMenuDialog` fingerprint (packed-switch extending a menu router) is moderately fragile — obfuscated method names change between GameHub versions. The Activity itself is entirely new code (Low in isolation). Together: High.

---

## FEATURE 4: WCP/ZIP Component Extraction Pipeline

**Feature name:** Auto-detects and extracts WCP (Zstd-tar or XZ-tar) and ZIP component archives. Registers components with GameHub's `EmuComponents` system so they appear immediately in settings dropdowns.

**When added:** v2.0.3 through v2.0.6 (2026-03-12). Finalized when GameHub's own bundled `commons-compress` and `zstd-jni`/`tukaani-xz` were discovered and used instead of injected JARs.

**What it touches:**
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/WcpExtractor.smali` — static extraction class: detects magic bytes (ZIP=`50 4B`, Zstd=`28 B5 2F FD`, XZ=`FD 37 7A 58`), extracts via GameHub's bundled `TarArchiveInputStream` (obfuscated: `.s()` for `getNextTarEntry()`), routes FEXCore to flat extraction vs system32/syswow64 structure
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentInjectorHelper.smali` — static helper: reads `profile.json`/`meta.json` from archive, registers `EnvLayerEntity` + `ComponentRepo` with `EmuComponents.D()`, `state=Extracted`

**Injection type:** New smali classes only (no hooks into existing code beyond what Feature 3 already added). The registration call into `EmuComponents.D()` is a cross-dex call to GameHub's own class.

**Dependencies:** Component Manager (Feature 3) for the entry point. Requires GameHub's bundled `commons-compress` (obfuscated), `libzstd-jni`, `tukaani-xz`. This is the most brittle dependency — the obfuscated method names (`s()` for `getNextTarEntry()`) will change every GameHub version.

**Complexity estimate:** Very High. The `EmuComponents` registration chain (finding the right obfuscated methods for `D()`, `EnvLayerEntity` constructor, `ComponentRepo` constructor, `State.Extracted` enum reference) requires careful fingerprinting of an obfuscated Kotlin class. The TarArchiveInputStream method name (`s()`) will need re-identification on any GameHub update.

---

## FEATURE 5: Component Manager — RecyclerView Card UI + Search + Swipe

**Feature name:** Upgraded Component Manager from basic ListView to full card-based RecyclerView with color-coded type badges (DXVK blue, VKD3D purple, Box64 green, FEX/GPU), live search bar, swipe LEFT=remove / swipe RIGHT=backup, header with install count badge and "Remove All". Empty state with help text.

**When added:** v2.6.2-pre / v2.6.3 stable (2026-03-20).

**What it touches:**
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentManagerActivity.smali` — full rewrite: `buildUI()`, `buildHeader()`, `buildSearchBar()`, `showComponents()`, `removeComponent()`, `removeAllComponents()`, `backupFiltered()`, `getFileName()`
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/BhComponentAdapter.smali` — `RecyclerView.Adapter` with type badge detection + color assignment
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/BhComponentAdapter$ViewHolder.smali` — ViewHolder with click delegation
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/BhSwipeCallback.smali` — `ItemTouchHelper.SimpleCallback(0, LEFT|RIGHT)`
- Inner classes `ComponentManagerActivity$1` through `$7`, `$BhBackListener`, `$BhRemoveAllListener`, `$BhAddListener`, `$BhDownloadListener` — listeners for all actions

**Injection type:** New smali classes (programmatic UI — no XML layouts). Entirely within `smali_classes16`.

**Dependencies:** Features 3, 4 (WCP extraction), Feature 6 (source tracking SP).

**Complexity estimate:** High. Entirely new code — straightforward in principle, but the programmatic RecyclerView in smali is verbose. For ReVanced: this entire Activity should be moved to extension Java (which it almost is — the smali is effectively hand-compiled Java). Complexity comes from the `EmuComponents` fingerprinting needed for `removeComponent()`.

---

## FEATURE 6: Component Source Tracking + Download Indicator

**Feature name:** SharedPreferences (`banners_sources`) records which online repo each component was downloaded from. Downloaded components show source badge ("Arihany", "The412Banner Nightlies", etc.) and ✓ checkmark in the online repo browser once installed. Remove clears all SP entries including the source.

**When added:** v2.6.2-pre4 (2026-03-21).

**What it touches:**
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity.smali` — `mCurrentRepo` field, writes `dirName→repoName` and `dl:url→1` after inject; reads in `showAssets()` to show ✓ prefix
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$5.smali` — `InjectRunnable`: writes `url_for:{dirName}→url` reverse-key after inject
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/BhComponentAdapter.smali` — reads `banners_sources` SP in `onBindViewHolder` to show source badge below component name

**Injection type:** New smali logic within existing new classes.

**Dependencies:** Component Manager (Feature 3, 5), In-App Downloader (Feature 7).

**Complexity estimate:** Low. Pure extension-class logic — SharedPreferences read/write, no GameHub fingerprinting needed.

---

## FEATURE 7: In-App Component Downloader

**Feature name:** "Download" button in Component Manager opens a 3-level navigation screen: Repo selector → Category picker (DXVK/VKD3D/Box64/FEXCore/GPU Driver) → Asset list with file sizes. Downloads from Arihany WCPHub, The412Banner Nightlies, Kimchi, StevenMXZ, MTR, Whitebelyash GPU Drivers. Auto-injects after download. Progress indicator shown during fetch and download.

**When added:** v2.3.1-beta1 / v2.3.2-pre / v2.3.4-pre (2026-03-15 through 2026-03-16).

**What it touches:**
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity.smali` — 3-mode Activity; `showRepos()`, `showCategories()`, `showAssets()`, `startFetch()`, `startFetchPackJson()`, `startFetchGpuDrivers()`, `detectType()`, `onBackPressed()`, `mProgressBar`
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$1.smali` — FetchRunnable (GitHub Releases API)
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$2.smali` — ShowCategoriesRunnable
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$3.smali` — DownloadRunnable (stream to cacheDir)
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$4.smali` — CompleteRunnable
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$5.smali` — InjectRunnable (UI thread, calls `ComponentInjectorHelper.injectComponent()`)
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$6.smali` — PackJsonFetchRunnable (flat JSON array: type/verName/remoteUrl — Arihany/The412Banner format)
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$7.smali` — KimchiDriversRunnable (releases[] JSON format)
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$8.smali` — SingleReleaseRunnable
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$9.smali` — GpuDriversFetchRunnable (flat array format)
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$BhBackBtn.smali` — back button listener
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentDownloadActivity$DarkAdapter.smali` — ListView adapter
- `patches/AndroidManifest.xml` — `ComponentDownloadActivity` declaration

**Injection type:** New Activity + new smali classes + manifest entry. No hooks into existing GameHub code.

**Dependencies:** Feature 4 (WcpExtractor for injection step), Feature 6 (source tracking), internet access, GitHub API.

**Complexity estimate:** High. The Activity itself is straightforward new code (should move to extension Java). The GitHub API integration and multi-format JSON parsing are non-trivial but self-contained. Risk: repo URLs and JSON formats are external contracts that may break independently of GameHub updates.

---

## FEATURE 8: Component Injection into GameHub Settings Menus (appendLocalComponents)

**Feature name:** Components installed via BannerHub appear alongside server-provided components in every GameHub per-game settings dropdown (DXVK, VKD3D, Box64, FEXCore, GPU Driver pickers). Without this, injected components only appear on disk — GameHub's UI wouldn't list them.

**When added:** v2.2.6-pre (2026-03-15).

**What it touches:**
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentInjectorHelper.smali` — `appendLocalComponents(List<DialogSettingListItemEntity>, int contentType)` static method: iterates `EmuComponents` HashMap, appends matching components; `TRANSLATOR(32)` maps to `BOX64(94)` and `FEXCORE(95)`; also `getBlurb()` for description display (Feature 51 addition)
- `patches/smali_classes3/com/xj/winemu/settings/GameSettingViewModel$fetchList$1.smali` — **+2 lines** injected just before the server callback: reads `$contentType` from coroutine continuation state, calls `ComponentInjectorHelper.appendLocalComponents(v7, contentType)`
- `patches/smali_classes6/app/revanced/extension/gamehub/prefs/GameHubPrefs.smali` — lazy-init `EmuComponents` via `Companion.b(Application)` for offline case (v2.7.6-pre fix)

**Injection type:** Bytecode hook — coroutine continuation patch in `GameSettingViewModel$fetchList$1`. This is the most precise smali injection: identifying the right resume point (pswitch_8 / pswitch_6) in a Kotlin coroutine suspension table. High fragility across GameHub versions.

**Dependencies:** Feature 4 (WcpExtractor, `EmuComponents` registration). Requires `DialogSettingListItemEntity` and `EmuComponents` fingerprinting.

**Complexity estimate:** Very High. Fingerprinting a Kotlin coroutine continuation class's `invokeSuspend()` method and finding the correct injection anchor in its state machine is the hardest class of smali injection. The `$contentType` field name and the `EmuComponents` HashMap accessor are both obfuscated and will change between GameHub versions.

---

## FEATURE 9: Component Descriptions in Settings Picker

**Feature name:** BannerHub-managed components show their description text (from `profile.json` or `meta.json`) beneath the component name in per-game settings dropdowns.

**When added:** v2.5.5-pre (2026-03-20).

**What it touches:**
- `patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentInjectorHelper.smali` — `appendLocalComponents()`: after `setDownloaded()`, calls `entity.getBlurb()` then `DialogSettingListItemEntity.setDesc(blurb)`

**Injection type:** Extension to existing new-class method. Pure new-code addition.

**Dependencies:** Feature 8 (appendLocalComponents). `EnvLayerEntity.getBlurb()` is unobfuscated in 5.3.5.

**Complexity estimate:** Low. One-line addition within existing extension code. Risk: `getBlurb()` could be renamed in future GameHub versions.

---

## FEATURE 10: EmuReady API Toggle (Default Off)

**Feature name:** The "Compatibility API" toggle in GameHub Settings defaults to OFF (GameHub's own server) instead of ON (EmuReady external API). Prevents unnecessary external API calls on fresh installs.

**When added:** v2.2.11-pre (2026-03-15).

**What it touches:**
- `patches/smali_classes6/app/revanced/extension/gamehub/prefs/GameHubPrefs.smali` — new class overriding `isExternalAPI()`: `getBoolean("use_external_api", false)` (default changed from `true` to `false`)

**Injection type:** New smali class that overrides a GameHub preferences method. The `GameHubPrefs` smali is in the `app.revanced.extension.gamehub` namespace — already a ReVanced-style extension class in the original patched APK.

**Dependencies:** None.

**Complexity estimate:** Low. A simple constant swap on a SharedPreferences default boolean value. In ReVanced: a single `bytecodePatch` replacing a `const/4 0x1` with `const/4 0x0` at the `getBoolean` call site, or a full method replacement.

---

## FEATURE 11: Offline Steam Skip

**Feature name:** When Steam auto-login fails at cold start because there is no network, the login screen is bypassed and the game launch pipeline proceeds with the cached session. Auto-login screen only shown when network IS available.

**When added:** v2.2.4-pre (2026-03-14).

**What it touches:**
- `patches/smali_classes10/com/xj/landscape/launcher/launcher/strategy/SteamGameByPcEmuLaunchStrategy$execute$3.smali` — modified lambda: on auto-login failure, checks `NetworkUtils.r()` (returns boolean); if `false` (offline) → skips login screen; if `true` (online) → shows login screen as normal

**Injection type:** Bytecode hook — modifies a Kotlin lambda inside `SteamGameByPcEmuLaunchStrategy.execute()`. The `$execute$3` lambda is the failure callback of the auto-login coroutine.

**Dependencies:** GameHub's `NetworkUtils.r()` method (unobfuscated utility).

**Complexity estimate:** Low. A simple branch condition added to an existing failure callback. Fingerprint: `SteamGameByPcEmuLaunchStrategy` class + error callback lambda + `NetworkUtils` call.

---

## FEATURE 12: RTS Touch Controls

**Feature name:** Gesture overlay for PC strategy/RTS games inside Wine. Maps touch gestures to mouse actions: single tap → left-click, drag → box-select hold, long press (300ms) → right-click, double-tap → double-click, two-finger pan → camera pan (configurable direction), pinch-to-zoom → mouse wheel. Toggle switch in the in-game sidebar Controls tab. Gear icon opens per-gesture configuration dialog.

**When added:** v2.2.1 (2026-03-14). Ported from gamehub-lite PR #73 by Nightwalker743.

**What it touches:**
- `patches/smali_classes14/com/xj/winemu/sidebar/SidebarControlsFragment.smali` — modified: adds RTS toggle switch + gear button to Controls tab; wires `RtsSwitchClickListener` and `RtsGestureSettingsClickListener`
- `patches/smali_classes15/com/xj/winemu/WineActivity.smali` — modified: attaches `RtsTouchOverlayView` to the DecorView when RTS mode enabled
- `patches/smali_classes15/com/xj/pcvirtualbtn/inputcontrols/InputControlsManager.smali` — modified: routes multi-touch events to `RangeScrollerRtsTask`
- `patches/smali_classes15/com/winemu/core/controller/X11Controller.smali` — modified: RTS touch gesture routing to Wine input injection
- `patches/smali_classes16/com/xj/winemu/sidebar/RtsSwitchClickListener.smali` — toggle RTS mode on/off
- `patches/smali_classes16/com/xj/winemu/sidebar/RtsGestureSettingsClickListener.smali` — opens `RtsGestureConfigDialog`
- `patches/smali_classes16/com/xj/winemu/sidebar/RtsGestureConfigDialog.smali` — config dialog (gesture picker → action selector) + 10 inner-class listeners
- `patches/smali_classes16/com/xj/winemu/view/RtsTouchOverlayView.smali` — transparent touch-capture overlay
- `patches/smali_classes16/com/xj/winemu/view/RtsTouchOverlayView$RightClickReleaseRunnable.smali` — delayed right-click release
- `patches/smali_classes16/com/xj/pcvirtualbtn/inputcontrols/RangeScrollerRtsTask.smali` — RTS scroll area handler
- `patches/res/layout/winemu_sidebar_controls_fragment.xml` — Controls tab layout with RTS toggle and gear icon
- `patches/res/layout/rts_gesture_config_dialog.xml` — gesture config dialog layout
- `patches/res/layout/rts_action_picker_dialog.xml`, `rts_action_picker_item.xml` — action picker layouts
- `patches/res/layout/control_element_settings.xml` — RTS control element settings
- `patches/res/drawable/rts_checkbox_button.xml`, `rts_checkbox_checked.xml`, `rts_checkbox_unchecked.xml`, `rts_dialog_background.xml`
- `patches/res/color/rts_checkbox_tint.xml`
- `patches/res/values/ids.xml`, `strings.xml`, `styles.xml`, `public.xml` — RTS IDs, strings, styles

**Injection type:** Mixed — 4 bytecode hooks in existing GameHub classes (SidebarControlsFragment, WineActivity, InputControlsManager, X11Controller) + 10 new smali classes in classes16 + 13 resource files.

**Dependencies:** None beyond GameHub's Wine system. GameHub's sidebar fragment must be correctly identified.

**Complexity estimate:** Very High. Modifies 4 separate existing GameHub classes across 3 dex zones (classes14, classes15, classes16). `X11Controller` and `InputControlsManager` hooks are particularly fragile — they patch into the Wine touch event dispatch pipeline whose obfuscated method names change between GameHub versions. The resource overlay is substantial. This is likely the most complex patch in terms of fingerprint count.

---

## FEATURE 13: Sustained Performance Mode

**Feature name:** Toggle in the in-game Performance sidebar tab. ON: sets all CPU cores to `performance` governor via `su -c`. OFF: restores to `schedutil`. Requires root. Greyed out on non-rooted devices. State persists and re-applied on sidebar reopen. Also calls `Window.setSustainedPerformanceMode()` (no-op on most devices) as no-root bonus.

**When added:** v2.4.6-pre / v2.5.0 stable (2026-03-18).

**What it touches:**
- `patches/smali_classes16/com/xj/winemu/sidebar/BhPerfSetupDelegate.smali` — new class: self-wiring `View` in the sidebar's Performance tab layout; adds "Sustained Perf (Root+)" switch; reads `root_granted` pref for enable/disable; routes to `SustainedPerfSwitchClickListener`; `isRootAvailable()` check with alpha greying
- `patches/smali_classes16/com/xj/winemu/sidebar/SustainedPerfSwitchClickListener.smali` — `OnClickListener`; calls `WineActivity.toggleSustainedPerf()`
- `patches/smali_classes15/com/xj/winemu/WineActivity.smali` — +static `toggleSustainedPerf()` and `toggleMaxAdreno()` methods; `onCreate` re-apply blocks (try-caught for unsupported devices)
- `patches/res/layout/winemu_sidebar_hub_type_fragment.xml` — Performance tab layout: HUD switch + "Sustained Perf" switch + "Max Adreno Clocks" switch + opacity slider
- `patches/res/values/strings.xml`, `ids.xml`, `public.xml` — new string/ID resources

**Injection type:** Mixed — new smali classes in classes16 + static method additions to `WineActivity` (classes15) + resource overlay for sidebar layout.

**Dependencies:** Feature 17 (Grant Root Access button stores `root_granted` pref). Root access (`su`).

**Complexity estimate:** Medium. The `BhPerfSetupDelegate` pattern (self-wiring via layout XML tag) avoids directly patching `SidebarControlsFragment`. `WineActivity` method additions need careful register accounting but are appended (not modified) methods. The `try/catch` around `setSustainedPerformanceMode()` is a non-standard smali construct but straightforward.

---

## FEATURE 14: Max Adreno Clocks

**Feature name:** Toggle in the in-game Performance sidebar. ON: reads `kgsl-3d0/devfreq/max_freq`, writes it to `min_freq` (locks GPU clock floor = ceiling). OFF: writes 0 to `min_freq`. Requires root. Greyed out on non-rooted devices.

**When added:** v2.4.6-pre / v2.5.0 stable (2026-03-18), same commit as Feature 13.

**What it touches:**
- `patches/smali_classes16/com/xj/winemu/sidebar/MaxAdrenoClickListener.smali` — `OnClickListener`; calls `WineActivity.toggleMaxAdreno()`
- `patches/smali_classes15/com/xj/winemu/WineActivity.smali` — `toggleMaxAdreno()` static method: `su -c "cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq"`

**Injection type:** New smali class (classes16) + static method addition to `WineActivity` (classes15).

**Dependencies:** Feature 13 (shares `BhPerfSetupDelegate`, `WineActivity` method additions, root check).

**Complexity estimate:** Low (in isolation). Shares all scaffolding with Feature 13.

---

## FEATURE 15: Settings — Grant Root Access (Advanced Tab)

**Feature name:** "Grant Root Access" button in Settings → Advanced tab. Shows a warning dialog; on confirmation runs `su -c id` on a background thread and stores `root_granted = true` in `bh_prefs`. Enables the Sustained Performance and Max Adreno Clocks toggles across the app.

**When added:** v2.5.2-pre / v2.6.0 stable (2026-03-20).

**What it touches:**
- `patches/smali_classes16/com/xj/winemu/sidebar/BhRootGrantHelper.smali` — helper class
- `patches/smali_classes16/com/xj/winemu/sidebar/BhRootGrantHelper$1.smali`, `$2.smali`, `$2$1.smali`, `$2$1$1.smali` — async root command runner, warning dialog listeners
- CI Python patches (in `.github/workflows/build.yml`) for 3 smali injection targets:
  - `SettingBtnHolder.w()` — intercepts `contentType=0x64` to show Grant Root Access button
  - `SettingItemEntity.getContentName()` — returns `"Grant Root Access"` for the new content type
  - `SettingItemViewModel.k()` — adds the new item to the Advanced settings list

**Injection type:** Mixed — 5 new smali classes in classes16 + 3 Python-driven CI string replacements in existing Settings classes (classes not checked in to repo; patched at build time via sed/Python). The Settings integration is a CI-level patch, not a static smali file.

**Dependencies:** Shared `bh_prefs` SharedPreferences (used by Features 13, 14 to check `root_granted`).

**Complexity estimate:** High. The Settings integration requires fingerprinting `SettingBtnHolder.w()`, `SettingItemEntity.getContentName()`, and `SettingItemViewModel.k()` — three obfuscated classes that are patched dynamically by CI rather than static smali. For ReVanced: these need proper `bytecodePatch` fingerprints for a stable hook.

---

## FEATURE 16: 3-Way Compatibility API Selector

**Feature name:** Settings "Compatibility API" row shows a 3-option AlertDialog: GameHub (native, source 0), EmuReady (external API, source 1), BannerHub (community Cloudflare Worker, source 2). Replaces the previous toggle. Saves `api_source` int pref; clears caches on switch. BannerHub URL: `https://bannerhub-api.the412banner.workers.dev/`.

**When added:** v2.7.6-pre / v2.7.7-pre (2026-03-27).

**What it touches:**
- `patches/smali_classes6/app/revanced/extension/gamehub/prefs/GameHubPrefs.smali` — `setApiSource(I)V` saves `api_source` + `last_api_source`, clears cache, shows toast; `getApiSource()I` reads pref; `isExternalAPI()` delegates to `getApiSource() == 1`
- `patches/smali_classes16/com/xj/winemu/sidebar/BhApiSelectorListener.smali` — `DialogInterface.OnClickListener` for the 3-option dialog
- `patches/smali_classes10/com/xj/landscape/launcher/ui/setting/holder/SettingSwitchHolder.smali` — modified: intercepts `CONTENT_TYPE_API` (0x1a), builds 3-button AlertDialog pre-selected from `getApiSource()`, shows dialog, returns `Unit` early

**Injection type:** Mixed — modified existing `GameHubPrefs` smali + new listener class + bytecode modification of `SettingSwitchHolder.w()` to intercept the API toggle row.

**Dependencies:** Feature 10 (GameHubPrefs foundation).

**Complexity estimate:** Medium. `SettingSwitchHolder` fingerprint (intercept a specific `contentType` constant) is fairly stable. The dialog logic is pure new code.

---

## FEATURE 17: Per-Game CPU Core Affinity Picker

**Feature name:** Replaces the single-select CPU core count dropdown with a multi-select checkbox dialog. Shows 8 individual core checkboxes (Core 0–7) grouped by type: Efficiency (0–3), Performance (4–6), Prime (7). "Apply" saves bitmask; "No Limit" saves 0. Pin-specific cores via `WINEMU_CPU_AFFINITY` env var. Settings row label dynamically reflects selection (e.g. "Core 4 + Core 7 (Prime)").

**When added:** v2.4.1-beta1 / v2.4.2 / final stable v2.5.0 (2026-03-17).

**What it touches:**
- `patches/smali_classes16/com/xj/winemu/settings/CpuMultiSelectHelper.smali` — main helper: `show(View, DialogSettingListItemEntity, Function1)`, builds `AlertDialog.setMultiChoiceItems()` with 8 cores, pre-checks from current bitmask, "Apply"/"No Limit" buttons, `D(I)` label generator
- `patches/smali_classes16/com/xj/winemu/settings/CpuMultiSelectHelper$1.smali` — multi-choice listener (bitmask accumulation)
- `patches/smali_classes16/com/xj/winemu/settings/CpuMultiSelectHelper$2.smali` — Apply click: saves bitmask via `SPUtils.m()`, constructs `DialogSettingListItemEntity` with new value, calls `callback.invoke(entity)`
- `patches/smali_classes16/com/xj/winemu/settings/CpuMultiSelectHelper$3.smali` — No Limit click: saves 0, calls callback
- `patches/smali_classes16/com/xj/winemu/settings/CpuMultiSelectHelper$4.smali` — (inner helper)
- `patches/smali_classes4/com/xj/winemu/settings/PcGameSettingOperations.smali` — modified: `A()` replaced with bitmask-based 11-entry list; `D(I)` returns display label
- `patches/smali_classes2/com/xj/winemu/settings/SelectAndSingleInputDialog$Companion.smali` — modified: intercepts `CONTENT_TYPE_CORE_LIMIT` → routes to `CpuMultiSelectHelper.show()` instead of `OptionsPopup`
- `patches/smali_classes6/com/winemu/core/controller/EnvironmentController.smali` — new class: patches `d()` method to pass stored bitmask directly as `WINEMU_CPU_AFFINITY` (bypassing GameHub's `(1<<count)-1` formula)

**Injection type:** Mixed — new smali classes in classes16 + bytecode hooks in `PcGameSettingOperations` (classes4), `SelectAndSingleInputDialog$Companion` (classes2), and new `EnvironmentController` patch (classes6).

**Dependencies:** `SPUtils.m()` (GameHub's settings save utility), `DialogSettingListItemEntity` constructor (needs defaults constructor fingerprint), `CONTENT_TYPE_CORE_LIMIT` constant value.

**Complexity estimate:** High. `SelectAndSingleInputDialog$Companion.d()` fingerprint (intercept a specific dialog type constant) combined with the `DialogSettingListItemEntity` defaults-constructor call (ART cross-dex private field access workaround) and `EnvironmentController` env-var injection. The `SPUtils.m()` call requires identifying the correct overloaded write method.

---

## FEATURE 18: VRAM Limit Unlock

**Feature name:** Extends the VRAM Limit picker in PC game settings from 512MB–4GB to include 6GB, 8GB, 12GB, 16GB options.

**When added:** v2.3.8-pre / v2.4.0 stable (2026-03-17).

**What it touches:**
- `patches/smali_classes4/com/xj/winemu/settings/PcGameSettingOperations.smali` — `l0()`: appends 4 new `DialogSettingListItemEntity` entries (0x1800=6144, 0x2000=8192, 0x3000=12288, 0x4000=16384 MB); `F0()`: adds display string cases for new values; `l0()` `isSelected` check via `G0()` for each new value

**Injection type:** Bytecode hook — appends entries to an existing list-building method in `PcGameSettingOperations`.

**Dependencies:** `DialogSettingListItemEntity` (Kotlin data class; uses defaults constructor to avoid cross-dex field access).

**Complexity estimate:** Low. A known constant-append pattern: find `l0()` via its method signature and append to its return list. Fingerprint: `PcGameSettingOperations` + `l0()` + `CONTENT_TYPE_VRAM` constant.

---

## FEATURE 19: Offline PC Game Settings Access

**Feature name:** PC game settings remain fully accessible and editable while offline. No blocking spinner or crash from `NoCacheException`.

**When added:** v2.3.7-pre (2026-03-17).

**What it touches:**
- `patches/smali_classes3/com/xj/winemu/settings/GameSettingViewModel$fetchList$1.smali` — wraps `ResultKt.throwOnFailure()` at 2 coroutine resume points (pswitch_8 for getContainerList, pswitch_6 for getComponentList) in try-catch returning empty ArrayList / `"{}"` fallback

**Injection type:** Bytecode hook — coroutine continuation exception silencing. Same `$fetchList$1` class as Feature 8.

**Dependencies:** None.

**Complexity estimate:** Medium. Same coroutine fingerprinting challenge as Feature 8 (`GameSettingViewModel$fetchList$1` suspension points). Wrapping in try-catch in smali requires careful label placement.

---

## FEATURE 20: Virtual Container Cleanup on Uninstall

**Feature name:** When a game is uninstalled via GameHub, its Wine virtual container directory (`virtual_containers/{gameId}/`) is recursively deleted. No orphaned container directories.

**When added:** v2.8.4-pre (2026-04-02).

**What it touches:**
- `extension/BhContainerCleanup.java` — static helper: deletes `virtual_containers/{gameId}/` recursively; uses reflection for `ActivityThread.currentApplication()` (not in public SDK)
- `patches/smali_classes3/com/xj/game/UninstallGameHelper.smali` — `h()` method: +2 lines calling `BhContainerCleanup.cleanup(gameId)` before existing uninstall logic

**Injection type:** Mixed — new Java extension class + bytecode hook in `UninstallGameHelper.h()`.

**Dependencies:** None.

**Complexity estimate:** Medium. `UninstallGameHelper.h()` fingerprint (method that performs the actual game deletion) + reflection for `ActivityThread.currentApplication()`. The extension class itself is Low complexity.

---

## FEATURE 21: Touch Button Scale Cap Raised

**Feature name:** The touch button scale slider in RTS controls settings is raised from 150% to 300% (then 500% in a later update) maximum.

**When added:** v2.8.5-pre (300%, 2026-04-02); updated to 500% later (v2.8.4 stable notes).

**What it touches:**
- `patches/res/layout/control_element_settings.xml` — `SBScale` Slider: `valueTo` attribute changed `150 → 300 → 500`

**Injection type:** Resource patch (XML attribute change).

**Dependencies:** Feature 12 (RTS Controls layout).

**Complexity estimate:** Low. Single XML attribute change in a BannerHub-owned layout file.

---

## FEATURE 22: GOG Games Library, Authentication & Launch

**Feature name:** Full GOG.com integration: OAuth2 login (implicit flow via WebView), library sync (Gen1/Gen2 badge, cover art), game detail dialog, install, launch via GameHub's `EditImportedGameInfoDialog` (`B3(exePath)`). Accessible from the left side menu (ID=10).

**When added:** v2.7.0-beta3 / v2.7.2-pre (2026-03-21/25) — migrated from smali approach to full Java extension.

**What it touches:**
- `extension/GogMainActivity.java` — entry point; login/logout card; side menu ID=10
- `extension/GogLoginActivity.java` — OAuth2 implicit flow WebView; intercepts redirect fragment `#access_token=...`; stores tokens to `bh_gog_prefs`
- `extension/GogGamesActivity.java` — library list/grid/poster views; search; install/launch/uninstall; `pending_gog_exe` flag
- `extension/GogGame.java` — data model
- `extension/GogApiClient.java` — REST: `embed.gog.com/user/data/games`, `api.gog.com/products/{id}?expand=downloads,description`
- `extension/GogAuthClient.java` — OAuth2 token management
- `extension/GogCredentialStore.java` — `bh_gog_prefs` SharedPreferences
- `extension/GogTokenRefresh.java` — silent token refresh via GET `auth.gog.com/token?grant_type=refresh_token`
- `extension/GogInstallPath.java` — `getFilesDir()/gog_games/{installDir}` path resolution
- `extension/GogLaunchHelper.java` — resolves exe path, triggers `pending_gog_exe` → `LandscapeLauncherMainActivity.B3()`
- `patches/smali_classes5/com/xj/landscape/launcher/ui/menu/HomeLeftMenuDialog.smali` — ID=10 "GOG" menu item + pswitch_10 routing
- `patches/smali_classes11/com/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity.smali` — `onResume()`: reads `pending_gog_exe` from `bh_gog_prefs`, calls `B3(exePath)`
- `patches/AndroidManifest.xml` — `GogMainActivity`, `GogLoginActivity`, `GogGamesActivity`, `GogGameDetailActivity` registered

**Injection type:** Mixed — 10 new Java extension classes + 2 bytecode hooks (`HomeLeftMenuDialog`, `LandscapeLauncherMainActivity.onResume()`) + manifest entries.

**Dependencies:** GameHub's `B3(String)` method on `LandscapeLauncherMainActivity` (opens `EditImportedGameInfoDialog`). This is the bridge from BannerHub to GameHub's game registration system.

**Complexity estimate:** High. The 10 Java classes are pure extension code (Low in isolation). The smali hooks (`HomeLeftMenuDialog` switch extension + `LandscapeLauncherMainActivity.onResume()` pending-launch check) require stable fingerprints. The `B3()` method reference is obfuscated and will change between GameHub versions.

---

## FEATURE 23: GOG Download Pipeline (Gen 2)

**Feature name:** Downloads Gen2 GOG games using the modern depot manifest system. 8 parallel threads, 3-retry + resume, `.bhtmp` atomic write, Zstd chunk decompression, SHA validation. Progress notification + cancel.

**When added:** v2.7.0-beta32 / v2.7.2-pre → refactored as Java extension (2026-03-21/25).

**What it touches:**
- `extension/GogDownloadManager.java` — full download pipeline: builds API → gzip/zlib/plain manifest → depot manifests (lang filter) → secure CDN link → Akamai URL fix (`?` insertion before chunk path) → parallel chunk download + Inflater assembly → `gog_build_` pref storage; `fetchInstallSizeBytes()`; `fetchGameSize()`; cancel support; debug file `bh_gog_debug.txt`

**Injection type:** New Java extension class. No direct GameHub hooks.

**Dependencies:** GOG Auth (Feature 22). Requires GOG API access. `GogInstallPath` for directory management.

**Complexity estimate:** High. Parallel download with Zstd decompression, chunk assembly, retry logic, and Akamai CDN URL construction are non-trivial. The Gen1 fallback (byte-range downloads) adds further complexity. Self-contained in extension Java — no fingerprinting needed.

---

## FEATURE 24: GOG Download Pipeline (Gen 1 & Installer Fallback)

**Feature name:** For legacy GOG games: Gen1 byte-range HTTP downloads, parallel with 8 threads, retry+resume. Fallback for games with no Gen2 build.

**When added:** v2.7.0-beta62 / v2.7.2-pre (2026-03-22/25), same `GogDownloadManager.java`.

**What it touches:**
- `extension/GogDownloadManager.java` — `runGen1()`: `builds?generation=1` → depot manifests → `secure_link` → `downloadRange()` per file (byte-range HTTP)

**Injection type:** Extension of existing Java class. No GameHub hooks.

**Dependencies:** Feature 23 (GogDownloadManager scaffold).

**Complexity estimate:** Medium. Adds a second download path to an existing class. Byte-range HTTP handling is well-understood.

---

## FEATURE 25: GOG Post-Install Management (exe picker, uninstall, copy)

**Feature name:** After install: auto-select or show exe picker dialog (filters redist/helper/setup executables, sorts shallowest first). "Set .exe" button rescans. "Copy to Downloads" copies install folder recursively. "Uninstall" deletes directory and clears all prefs.

**When added:** v2.7.3 (2026-03-26).

**What it touches:**
- `extension/GogDownloadManager.java` — `collectExeCandidates()`: gathers qualifying .exes with scoring heuristic; `showExePicker()` AlertDialog
- `extension/GogGamesActivity.java` — install confirmation dialog (size + free storage check), cancel button (red → stops thread + deletes partials), "Set .exe" button, uninstall confirmation + recursive delete, install/launch UI state management

**Injection type:** New/extended Java extension classes.

**Dependencies:** Features 22, 23, 24.

**Complexity estimate:** Medium. Executable scoring heuristic and SAF-free file management. All self-contained extension code.

---

## FEATURE 26: GOG Cloud Saves

**Feature name:** Upload/download game save files to GOG's cloudstorage API (`cloudstorage.gog.com/v1/{userId}/{gameId}`). Game-scoped token obtained via `clientSecret` + Galaxy `refresh_token` exchange. Upload uses newer-wins timestamp comparison. Folder path persists per game in `gog_save_dir_{gameId}` pref.

**When added:** v3.0.4-pre (2026-04-14).

**What it touches:**
- `extension/GogCloudSaveManager.java` — `uploadSaves()`, `downloadSaves()`, `getGameScopedToken()` (exchanges Galaxy refresh_token + game clientId/clientSecret for scoped token), `getOrFetchClientId()` (fetches + caches `gog_client_id_{gameId}` and `gog_client_secret_{gameId}` from build manifest)
- `extension/GogDownloadManager.java` — `runGen2()`: extracts and caches `clientId` and `clientSecret` from manifest JSON header during install
- `extension/GogGameDetailActivity.java` — CLOUD SAVES section: Browse → `FolderPickerActivity`; Upload/Download buttons; live status line
- `patches/AndroidManifest.xml` — `FolderPickerActivity` declaration

**Injection type:** New Java extension classes + new Activity + manifest entry.

**Dependencies:** Feature 22 (GOG auth/tokens), Feature 23 (GogDownloadManager for clientSecret extraction), Feature 46 (FolderPickerActivity).

**Complexity estimate:** High. Game-scoped token exchange (non-standard OAuth2 flow with clientSecret from binary manifest) is complex. The cloudstorage API `not_enabled_for_client` error handling adds additional paths. All self-contained but requires correct GOG API reverse-engineering.

---

## FEATURE 27: GOG Update Checker

**Feature name:** Game detail → UPDATES section shows installed build ID and "Check for Updates" button. Fetches latest build ID from `content-system.gog.com/builds`, compares with installed (`gog_build_{gameId}` pref). "Update Now" re-runs install pipeline.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/GogDownloadManager.java` — `runGen2()`: stores `gog_build_{gameId}` to `bh_gog_prefs` after install
- `extension/GogGamesActivity.java` — stores `gog_release_{id}` during `fetchGame()`
- `extension/GogGameDetailActivity.java` — `makeUpdatesCard()`, `doCheckUpdate()`, `formatDate()`, "Released: MMM D, YYYY" row

**Injection type:** New Java extension class methods.

**Dependencies:** Features 22, 23, 25 (install pipeline).

**Complexity estimate:** Medium. Straightforward HTTP + JSON comparison. All extension code.

---

## FEATURE 28: GOG DLC Management

**Feature name:** Game detail → DLC section lists owned DLC (detected via `game_type == "dlc"` in sync). Gen2 installs include owned DLC depots automatically.

**When added:** v3.0.3-pre (2026-04-14).

**What it touches:**
- `extension/GogGamesActivity.java` — `fetchGame()`: detects DLCs, extracts base game ID, stores associations in prefs
- `extension/GogGameDetailActivity.java` — DLC section with "Owned" badge + Gen2 install note

**Injection type:** New Java extension class methods.

**Dependencies:** Features 22, 23.

**Complexity estimate:** Low. DLC detection via API field + install pipeline reuse.

---

## FEATURE 29: GOG Release Date Display

**Feature name:** "Released: MMM D, YYYY" row in the GAME INFO card on GOG game detail screen.

**When added:** v3.0.1-pre (2026-04-14), same commit as Feature 27.

**What it touches:**
- `extension/GogGamesActivity.java` — stores `gog_release_{id}` during sync
- `extension/GogGameDetailActivity.java` — shows "Released: ..." row if pref present

**Injection type:** Extension Java method additions.

**Dependencies:** Feature 22, 27 (detail activity scaffold).

**Complexity estimate:** Low.

---

## FEATURE 30: GOG Game Detail Full-Screen Activity

**Feature name:** Full-screen `GogGameDetailActivity` replacing the previous AlertDialog. Shows cover art, HTML-stripped description, GAME INFO card (install size, release date, GOG ratings), UPDATES, DLC, CLOUD SAVES sections. Started via `startActivityForResult()`.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/GogGameDetailActivity.java` — new Activity: fixed header, scrollable body, cover art, all sections
- `extension/GogGamesActivity.java` — launches `GogGameDetailActivity` via `startActivityForResult()`; `onActivityResult()` refreshes card state
- `patches/AndroidManifest.xml` — `GogGameDetailActivity` registered with `excludeFromRecents=true` (v3.1.1-pre6 fix)

**Injection type:** New Java extension Activity + manifest entry.

**Dependencies:** Features 22, 26, 27, 28, 29.

**Complexity estimate:** Medium. Entirely new Activity code.

---

## FEATURE 31: GOG Install Size Display

**Feature name:** "Install size: X.X GB" in the GAME INFO card. GOG size fetched during library sync and cached as `gog_size_{gameId}`.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/GogDownloadManager.java` — `fetchInstallSizeBytes()`: fetches Gen2 builds + top-level manifest, sums `depot.size` for en/all depots
- `extension/GogGamesActivity.java` — calls `fetchInstallSizeBytes()` during `fetchGame()`; caches to prefs
- `extension/GogGameDetailActivity.java` — `sizeTV`, `loadInstallSize()`, `formatBytes()`

**Injection type:** Extension Java methods.

**Dependencies:** Features 22, 23, 30.

**Complexity estimate:** Low.

---

## FEATURE 32: Epic Games Store Library, Authentication & Launch

**Feature name:** Full Epic Games Store integration: OAuth2 authorization_code flow (WebView), library sync with catalog enrichment (description, cover art, DLC detection, CanRunOffline), Windows-only filter, launch via `pending_epic_exe` flag. Accessible from left side menu (ID=12/0xc).

**When added:** epic-integration branch / merged v2.7.6 (2026-03-29).

**What it touches:**
- `extension/EpicMainActivity.java` — entry point (ID=0xc)
- `extension/EpicLoginActivity.java` — WebView OAuth2 authorization_code flow
- `extension/EpicGamesActivity.java` — library list/grid/poster views; install/launch/uninstall; `pending_epic_exe`
- `extension/EpicGame.java` — data model (includes `releaseDate` field)
- `extension/EpicApiClient.java` — GraphQL + REST: library, catalog enrichment, free games, manifest API; `viewableDate`/`effectiveDate` for release date; `versionId` in manifest wrapper
- `extension/EpicAuthClient.java` — authorization_code + refresh OAuth2; Legendary credentials format
- `extension/EpicCredentialStore.java` — `bh_epic_prefs` SharedPreferences; `getValidAccessToken()` with auto-refresh
- `patches/smali_classes5/com/xj/landscape/launcher/ui/menu/HomeLeftMenuDialog.smali` — ID=0xc "Epic" menu item + pswitch_12 routing
- `patches/smali_classes11/com/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity.smali` — `onResume()`: reads `pending_epic_exe` → calls `B3(exePath)`
- `patches/AndroidManifest.xml` — Epic activities registered

**Injection type:** Mixed — 7 new Java extension classes + 2 bytecode hooks (same as GOG pattern) + manifest entries.

**Dependencies:** GameHub's `B3()` method. Shares `HomeLeftMenuDialog` and `LandscapeLauncherMainActivity` hooks with GOG/Amazon.

**Complexity estimate:** High. Same fingerprinting needs as GOG (Feature 22) but independent. The Epic auth flow (authorization_code + Legendary credentials format) is more complex than GOG's implicit flow.

---

## FEATURE 33: Epic Download Pipeline (Chunked Binary Manifest)

**Feature name:** Downloads Epic games using the Epic CDN chunk manifest system: 8 parallel threads, per-chunk SHA-1 validation, silent truncation fix (validates `writtenBytes` against `chunk.windowSize`). Caches `epic_manifest_version_{appName}` after install.

**When added:** epic-integration branch / merged v2.7.6 (2026-03-29). Chunk truncation fix v3.1.1-pre (2026-04-16).

**What it touches:**
- `extension/EpicDownloadManager.java` — `downloadChunkStreaming()`, `fetchInstallSizeBytes()`, `downloadBytes()` with 128KB buffer, chunk truncation validation, CDN URL construction
- `extension/EpicApiClient.java` — `getManifestApiJson()`: includes `versionId` from `elements[0]`

**Injection type:** New Java extension classes.

**Dependencies:** Feature 32 (Epic auth). No GameHub hooks.

**Complexity estimate:** High. Chunked manifest parsing, parallel CDN streaming with Inflater decompression, SHA-1 validation, and silent truncation detection. Self-contained but algorithmically complex.

---

## FEATURE 34: Epic Free Games Browser

**Feature name:** Dedicated `EpicFreeGamesActivity` full-screen activity showing currently-free and upcoming-free Epic games. Accessed via green "FREE" button in Epic library header. Each card tappable → opens Epic Store page in browser.

**When added:** v3.0.2-pre (2026-04-14).

**What it touches:**
- `extension/EpicFreeGamesActivity.java` — new Activity: "FREE THIS WEEK" + "FREE NEXT WEEK" sections
- `extension/EpicGamesActivity.java` — "FREE" header button → starts `EpicFreeGamesActivity`
- `patches/AndroidManifest.xml` — `EpicFreeGamesActivity` registered

**Injection type:** New Java extension Activity + manifest entry.

**Dependencies:** Feature 32 (Epic API client for free game data; no auth needed).

**Complexity estimate:** Low. Entirely new Activity with simple API call and card display.

---

## FEATURE 35: Epic Update Checker

**Feature name:** Game detail → UPDATES section. Fetches manifest `buildVersion`/`versionId`, compares with installed (`epic_manifest_version_{appName}` pref). "Update Now" re-runs install pipeline.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/EpicGameDetailActivity.java` — `makeUpdatesCard()`, `doCheckUpdate()`, `formatDate()`; stores `epic_manifest_version_{appName}` after install
- `extension/EpicApiClient.java` — `versionId` field in manifest JSON wrapper

**Injection type:** Extension Java methods.

**Dependencies:** Features 32, 33.

**Complexity estimate:** Medium.

---

## FEATURE 36: Epic Cloud Saves

**Feature name:** Upload/download game saves to Epic's cloud datastorage API. POST writeLink + PUT for upload; GET readLink for download. Folder path persists per game as `epic_save_dir_{appName}`.

**When added:** v3.0.4-pre (2026-04-14).

**What it touches:**
- `extension/EpicCloudSaveManager.java` — `uploadSaves()`, `downloadSaves()`, `getValidAccessToken()` via `EpicCredentialStore.getValidAccessToken(ctx)` (auto-refresh)
- `extension/EpicGameDetailActivity.java` — CLOUD SAVES section UI
- `patches/AndroidManifest.xml` — (FolderPickerActivity already registered)

**Injection type:** New Java extension class + Activity method additions.

**Dependencies:** Feature 32 (Epic auth, `EpicCredentialStore`), Feature 46 (FolderPickerActivity).

**Complexity estimate:** Medium. Similar structure to GOG cloud saves but simpler auth (no game-scoped token needed).

---

## FEATURE 37: Epic DLC Management

**Feature name:** Game detail → DLC section. Lists owned Epic DLC with Install buttons + inline progress.

**When added:** v3.0.3-pre (2026-04-14).

**What it touches:**
- `extension/EpicGame.java` — `baseGameCatalogItemId` field
- `extension/EpicApiClient.java` — `mainGameItem.id` from catalog enrichment; DLC→base mapping stored in prefs
- `extension/EpicGamesActivity.java` — DLC detection during sync
- `extension/EpicGameDetailActivity.java` — DLC section with Install buttons + inline progress

**Injection type:** Extension Java class additions.

**Dependencies:** Features 32, 33.

**Complexity estimate:** Low.

---

## FEATURE 38: Epic Release Date Display

**Feature name:** "Released: MMM D, YYYY" in GAME INFO card on Epic detail screen.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/EpicApiClient.java` — `viewableDate`/`effectiveDate` from catalog enrichment
- `extension/EpicGamesActivity.java` — stores `epic_release_{appName}` during sync
- `extension/EpicGameDetailActivity.java` — "Released:" row

**Injection type:** Extension Java.

**Dependencies:** Features 32, 35.

**Complexity estimate:** Low.

---

## FEATURE 39: Epic Install Size Display

**Feature name:** "Install size: X.X GB" from lazy-fetched manifest, cached as `epic_size_{appName}`. Shows "Fetching…" on first open.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/EpicDownloadManager.java` — `fetchInstallSizeBytes()`
- `extension/EpicGameDetailActivity.java` — `loadInstallSize()`, `formatBytes()`

**Injection type:** Extension Java.

**Dependencies:** Features 32, 33.

**Complexity estimate:** Low.

---

## FEATURE 40: Epic Game Detail Full-Screen Activity

**Feature name:** Full-screen `EpicGameDetailActivity` with cover art, HTML-stripped description, all sections (GAME INFO, UPDATES, DLC, CLOUD SAVES).

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/EpicGameDetailActivity.java` — new Activity
- `extension/EpicGamesActivity.java` — launches via `startActivityForResult()`
- `patches/AndroidManifest.xml` — registered

**Injection type:** New Java extension Activity + manifest entry.

**Dependencies:** Features 32–39.

**Complexity estimate:** Medium.

---

## FEATURE 41: Amazon Games Library, Authentication & Launch

**Feature name:** Full Amazon Games integration: PKCE OAuth2 device registration flow (WebView), entitlements library sync, game cards with cover art, install/launch via `pending_amazon_exe` flag, SDK DLL deployment. Accessible from left side menu (ID=11/0xb).

**When added:** amazon-integration branch / merged v2.7.6 (2026-03-29).

**What it touches:**
- `extension/AmazonMainActivity.java` — entry point (ID=11)
- `extension/AmazonLoginActivity.java` — PKCE WebView flow; 3-hook redirect capture; `AtomicBoolean` double-fire guard
- `extension/AmazonGamesActivity.java` — library list/grid/poster; install/launch/uninstall; `pending_amazon_exe`
- `extension/AmazonGame.java` — data model
- `extension/AmazonApiClient.java` — `GetEntitlements` (paginated), `GetGameDownload`, `GetLiveVersionIds`, `getSdkChannelSpec`; `amz-1.0` header format; `postGaming()`
- `extension/AmazonAuthClient.java` — PKCE `registerDevice` + `refreshAccessToken` + `deregisterDevice`
- `extension/AmazonCredentialStore.java` — `filesDir/amazon/credentials.json` persistence; `getValidAccessToken()` with auto-refresh 5min before expiry
- `extension/AmazonPKCEGenerator.java` — device serial UUID, clientId hex, code verifier (32 random bytes), code challenge (SHA-256 S256), `sha256Upper`
- `extension/AmazonLaunchHelper.java` — `fuel.json` parser, exe scoring heuristic (UE shipping +300 etc.), `buildFuelEnv()` 5 env vars
- `extension/AmazonSdkManager.java` — downloads FuelSDK_x64.dll + AmazonGamesSDK_* DLLs to `filesDir/amazon_sdk/`; deploys to Wine prefix `ProgramData`; idempotent via `.sdk_version` sentinel
- `patches/smali_classes5/com/xj/landscape/launcher/ui/menu/HomeLeftMenuDialog.smali` — ID=0xb "Amazon" menu item + pswitch_11
- `patches/smali_classes11/com/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity.smali` — `onResume()`: reads `pending_amazon_exe` → `B3(exePath)`
- `patches/AndroidManifest.xml` — Amazon activities registered

**Injection type:** Mixed — 9 new Java extension classes + 2 bytecode hooks (same pattern as GOG/Epic) + manifest entries.

**Dependencies:** GameHub's `B3()` method. Shares `HomeLeftMenuDialog` and `LandscapeLauncherMainActivity` hooks with GOG/Epic.

**Complexity estimate:** Very High. PKCE with Amazon's custom `amz-1.0` API format, `fuel.json` launcher spec parsing, the exe scoring heuristic, SDK DLL management, and 9 distinct classes. The Amazon API is less publicly documented than Epic/GOG — the implementation is based on reverse-engineering.

---

## FEATURE 42: Amazon Download Pipeline (manifest.proto)

**Feature name:** Downloads Amazon games using a binary protobuf manifest (`manifest.proto`), 8 parallel threads, SHA-256 per-file verification, resume support.

**When added:** amazon-integration branch / merged v2.7.6 (2026-03-29).

**What it touches:**
- `extension/AmazonManifest.java` — binary protobuf parser: 4-byte big-endian header + `ManifestHeader` + LZMA/XZ body; minimal ProtoReader varint decoder; uses `tukaani-xz` (XZInputStream + LZMAInputStream)
- `extension/AmazonDownloadManager.java` — `install()`: 6 parallel threads, 3-retry backoff (1s/2s/4s), SHA-256, resume, `IN_PROGRESS`/`COMPLETE` markers, manifest cache at `filesDir/manifests/amazon/`; `fetchInstallSizeBytes()`; stores `amazon_manifest_version_{productId}` after install

**Injection type:** New Java extension classes.

**Dependencies:** Feature 41 (Amazon auth). No GameHub hooks.

**Complexity estimate:** High. Binary protobuf parsing without a full protobuf library, LZMA decompression, SHA-256 verification. All self-contained but algorithmically complex.

---

## FEATURE 43: Amazon Update Checker

**Feature name:** Game detail → UPDATES section. Fetches latest `versionId` from `GetGameDownload` API, compares with installed (`amazon_manifest_version_{productId}` pref). "Update Now" re-runs install.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/AmazonDownloadManager.java` — stores `amazon_manifest_version_{productId}` after install; `AmazonApiClient.getLiveVersionId()`
- `extension/AmazonGameDetailActivity.java` — `makeUpdatesCard()`, `doCheckUpdate()`

**Injection type:** Extension Java.

**Dependencies:** Features 41, 42.

**Complexity estimate:** Low.

---

## FEATURE 44: Amazon DLC Management

**Feature name:** Game detail → DLC section. Probes multiple field names for DLC `productType` in `parseEntitlement`. Install via existing pipeline.

**When added:** v3.0.3-pre (2026-04-14).

**What it touches:**
- `extension/AmazonGame.java` — DLC fields
- `extension/AmazonApiClient.java` — DLC productType detection
- `extension/AmazonGamesActivity.java` — DLC separation from main library
- `extension/AmazonGameDetailActivity.java` — DLC section UI

**Injection type:** Extension Java.

**Dependencies:** Features 41, 42.

**Complexity estimate:** Low.

---

## FEATURE 45: Amazon Install Size Display

**Feature name:** "Install size: X.X GB" from lazy-fetched manifest, cached as `amazon_size_{productId}`.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/AmazonDownloadManager.java` — `fetchInstallSizeBytes()`: fetches `GetGameDownload` spec + `manifest.proto`, reads `totalInstallSize`
- `extension/AmazonGameDetailActivity.java` — `loadInstallSize()`, `formatBytes()`

**Injection type:** Extension Java.

**Dependencies:** Features 41, 42.

**Complexity estimate:** Low.

---

## FEATURE 46: Amazon Game Detail Full-Screen Activity

**Feature name:** Full-screen `AmazonGameDetailActivity` with all sections.

**When added:** v3.0.1-pre (2026-04-14).

**What it touches:**
- `extension/AmazonGameDetailActivity.java` — new Activity
- `extension/AmazonGamesActivity.java` — launches via `startActivityForResult()`
- `patches/AndroidManifest.xml` — registered

**Injection type:** New Java extension Activity + manifest entry.

**Dependencies:** Features 41–45.

**Complexity estimate:** Medium.

---

## FEATURE 47: In-App Folder Picker

**Feature name:** Navigate the device filesystem to select save directories. Used by GOG/Epic cloud saves. Root dropdown (App Files / Internal Storage / SD Card), "↑ Up" navigation, "+ New" folder creation, "Select this folder" confirmation. Files hidden; directories only.

**When added:** v3.0.4-pre (2026-04-14).

**What it touches:**
- `extension/FolderPickerActivity.java` — new Activity: storage root Spinner, recursive directory listing, `Environment.getExternalStoragePublicDirectory`, `mkdir()` for new folders, `setResult(RESULT_OK)` with path
- `patches/AndroidManifest.xml` — `FolderPickerActivity` registered

**Injection type:** New Java extension Activity + manifest entry.

**Dependencies:** None (general utility used by Features 26, 36).

**Complexity estimate:** Low. Standard filesystem browsing Activity.

---

## FEATURE 48: Winlator HUD — Basic Mode (BhFrameRating)

**Feature name:** Minimal in-game overlay: FPS, frame time (ms + live bar graph), render resolution/API. Tap to toggle horizontal/vertical layout. Drag to reposition (clamped to screen). Opacity slider (0–100%). Text shadow/stroke outline at low opacity. Position, orientation, opacity persisted in `bh_prefs`.

**When added:** v2.7.4-pre → v2.7.5 stable (2026-03-27/28). Long iteration (pre1–pre30).

**What it touches:**
- `extension/BhFrameRating.java` — custom `FrameLayout` injected into `WineActivity`'s DecorView at TOP|RIGHT; tag `"bh_frame_rating"`; FPS via `WineActivity.j.a()` (HudDataProvider, same source as GameHub's own HUD); GPU%, CPU% (/proc/stat diff), BAT watts, skin temp; `FpsGraphView` inner Canvas bar chart; `OutlinedTextView` inner class for text stroke; `applyBackgroundOpacity()` with 3 tiers (<10%, 10–29%, ≥30%)
- `patches/smali_classes16/com/xj/winemu/sidebar/BhHudInjector.smali` — called from `WineActivity.onResume()`; priority: konkr > detail > basic; creates/updates `BhFrameRating` / `BhDetailedHud` / `BhKonkrHud`
- `patches/smali_classes16/com/xj/winemu/sidebar/BhHudStyleSwitchListener.smali` — HUD toggle on/off; calls `BhHudInjector.injectOrUpdate(activity)`
- `patches/smali_classes16/com/xj/winemu/sidebar/BhHudOpacityListener.smali` — SeekBar listener; applies opacity to whichever HUD is active
- `patches/smali_classes16/com/xj/winemu/sidebar/BhPerfSetupDelegate.smali` — adds "Winlator HUD Style" switch + "Extra Detailed" + "Konkr Style" checkboxes + "HUD Opacity" SeekBar to Performance sidebar tab
- `patches/smali_classes15/com/xj/winemu/WineActivity.smali` — `onResume()`: +2 lines calling `BhHudInjector.injectOrUpdate(this)`
- `patches/res/layout/winemu_sidebar_hub_type_fragment.xml` — Performance tab layout additions

**Injection type:** Mixed — new Java extension class (`BhFrameRating`) + new smali classes (classes16 for HUD framework) + bytecode hook in `WineActivity.onResume()` + resource overlay.

**Dependencies:** FPS source via reflection into `WineActivity.j` (HudDataProvider — obfuscated field name). The HudDataProvider field name (`j`) is the most fragile dependency. Shared with Features 49, 50.

**Complexity estimate:** High. The `WineActivity.onResume()` hook is a simple 2-line addition (Low). The FPS source via reflection into `WineActivity.j.a()` (HudDataProvider) requires field name fingerprinting — this name will change between GameHub versions. The `BhFrameRating` custom view class itself (drag, graph, opacity, text outline) is substantial but pure extension code.

---

## FEATURE 49: Winlator HUD — Extra Detailed Mode (BhDetailedHud)

**Feature name:** Expanded HUD: FPS + frame-time graph (spanning both rows horizontal), CPU%, GPU%, RAM used/total GB, SWAP used/total GB, CPU/GPU/BAT temps. Horizontal (2-row column groups) + vertical (one per metric) layout. Reads `/proc/meminfo`, `/proc/stat`, `/sys/class/thermal/thermal_zone*/`, `/sys/kernel/gpu/gpu_busy`. Mutual exclusion with Konkr Style.

**When added:** v2.8.3-pre → v2.8.3 stable (2026-04-02).

**What it touches:**
- `extension/BhDetailedHud.java` — custom `FrameLayout`; `buildHorizontal()` as column groups with solid dividers; `buildVertical()` single column; `applyBackgroundOpacity()`; tag `"bh_detailed_hud"`
- `patches/smali_classes16/com/xj/winemu/sidebar/BhHudExtraDetailListener.smali` — checkbox listener; mutual exclusion with Konkr (clears `hud_konkr_style`, unchecks Konkr checkbox); delegates to `BhHudInjector.injectOrUpdate()`
- (Shared: `BhHudInjector`, `BhPerfSetupDelegate`, `BhHudOpacityListener` — already listed in Feature 48)

**Injection type:** New Java extension class + new smali listener class.

**Dependencies:** Feature 48 (HUD framework — `BhHudInjector`, `BhPerfSetupDelegate`, `WineActivity` hook shared).

**Complexity estimate:** Medium. Substantial new extension class but no additional GameHub fingerprinting beyond Feature 48.

---

## FEATURE 50: Winlator HUD — Konkr Style Mode (BhKonkrHud)

**Feature name:** Strategy game-style HUD reproducing the AYANEO Konkr reference layout. Vertical: 2-column table (FPS large, CPU%+temp, per-core MHz C0–C7, GPU%+temp+name+freq+res, MODE/FAN/SKN/PWR, RAM brown, SWAP gray, BAT blue proportional fill bar, TIME). Horizontal: compact multi-column strip. Tap to toggle V/H. Reads GPU name (`/sys/class/kgsl/kgsl-3d0/gpu_model`), fan speed (`hwmon` + cooling_device scan to i<50), skin temp (thermal zone scan to z<80), battery%. FPS min-tracking (reset every 60 samples). Mutual exclusion with Extra Detailed.

**When added:** v2.8.4-pre / v2.8.4 stable (2026-04-02).

**What it touches:**
- `extension/BhKonkrHud.java` — custom `FrameLayout`; `readGpuName()`, `readFanSpeed()`, `readSkinTemp()`, `readRamGb()`, `readSwapGb()`, `readBatPct()`, `readPwr()`, `readMode()`, `readGpuFreq()`; BAT proportional fill via `LinearLayout` weight update; tag `"bh_konkr_hud"`
- `patches/smali_classes16/com/xj/winemu/sidebar/BhHudKonkrListener.smali` — checkbox listener; mutual exclusion with Extra Detailed; clears `hud_extra_detail`, unchecks Extra Detail checkbox; delegates to `injectOrUpdate()`

**Injection type:** New Java extension class + new smali listener class.

**Dependencies:** Feature 48 (HUD framework — shared with Features 49, 50).

**Complexity estimate:** High. The most complex HUD — 8 separate sysfs data sources with device-specific fallback paths (KGSL, Mali, Exynos, hwmon), BAT fill proportional layout, and mutual exclusion logic. The sysfs paths are device-specific and require runtime probing. All self-contained extension code.

---

## FEATURE 51: Wine Task Manager

**Feature name:** In-game sidebar — Wine Task Manager tab: Container Info (CPU cores from `WINEMU_CPU_AFFINITY`, Sys RAM, VRAM from prefs, device/Android), Applications tab (wine* host processes + kill), Processes tab (.exe guest processes + kill), Launch tab (WINEPREFIX file browser: drives → directories → executables, tap to launch). Auto-refresh every 3 seconds. Launch guard prevents session-complete callback from terminating game when secondary exe closes.

**When added:** v2.7.4-pre (Task Manager) / v2.8.1-pre (Launch tab) (2026-03-27/30).

**What it touches:**
- `patches/smali_classes16/com/xj/winemu/sidebar/BhTaskManagerFragment.smali` — new Fragment: 4 tabs (Container Info/Applications/Processes/Launch); `getContainerCpuInfo()` (reads `WINEMU_CPU_AFFINITY` from wine child `/proc/<pid>/environ`); `getContainerRamInfo()`; `getContainerVramInfo()` (from prefs via `WineActivity.WineActivityData.a`); `ScanRunnable` (3s auto-refresh); `browseTo()` for Launch tab
- `patches/smali_classes16/com/xj/winemu/sidebar/BhTaskManagerFragment$KillListener.smali`, `$RefreshListener.smali`, `$ScanRunnable.smali`, `$UpdateRunnable.smali`, `$AutoRefreshRunnable.smali` — supporting inner classes
- `patches/smali_classes16/com/xj/winemu/sidebar/BhTabListener.smali` — generic tab switcher
- `patches/smali_classes16/com/xj/winemu/sidebar/BhTaskClickListener.smali` — task kill dispatcher
- `patches/smali_classes16/com/xj/winemu/sidebar/BhFolderListener.smali` — Launch tab directory navigation
- `patches/smali_classes16/com/xj/winemu/sidebar/BhExeLaunchListener.smali` — Launch tab exe tap → `BhWineLaunchHelper.launchExe()`
- `patches/smali_classes16/com/xj/winemu/sidebar/BhInitLaunchRunnable.smali` — opens at `WINEPREFIX/dosdevices`
- `patches/smali_classes16/com/xj/winemu/sidebar/BhBrowseToRunnable.smali` — updates Launch tab file list UI
- `extension/BhWineLaunchHelper.java` — finds Wine binary via `WINELOADER` env var; inherits environment from running .exe process `/proc/<pid>/environ`; `Runtime.exec()`
- `patches/smali_classes3/com/xj/winemu/sidebar/WineActivityDrawerContent.smali` — modified: constructor + `U()` method patched to add Task Manager tab to sidebar drawer
- `patches/res/drawable/sidebar_taskmanager.xml` — task manager icon
- `patches/res/layout/winemu_activitiy_settings_layout.xml` — tab layout additions
- `patches/res/values/public.xml` — 2 new IDs

**Injection type:** Mixed — 10 new smali classes (classes16) + 1 new Java extension class + bytecode hook in `WineActivityDrawerContent` (classes3) + resource overlay.

**Dependencies:** `WineActivity.WineActivityData.a` (obfuscated: reads `gameId` → prefs access for VRAM). `WineActivityDrawerContent` fingerprint for sidebar integration.

**Complexity estimate:** Very High. The `WineActivityDrawerContent` hook (finding correct insertion point in sidebar drawer construction), the `/proc/<pid>/environ` parsing for Wine environment, the `WINEMU_CPU_AFFINITY` bitmask reading, and the Launch tab's wine binary detection (WINELOADER env var + fallback scan) combine to require many fingerprints and runtime-dependent paths.

---

## FEATURE 52: Per-Game Config Export

**Feature name:** "Export Config" option in PC game settings menu (the `...` / settings popup on a game). Exports current `pc_g_setting<gameId>` SharedPreferences + installed component info to JSON. Preview dialog shows device/SOC/settings count/components count before save. Two options: "Save Locally" (to `/sdcard/BannerHub/configs/`) or "Save Locally + Share Online" (uploads to Cloudflare Worker → GitHub). Includes `meta.app_source = "bannerhub"` tag.

**When added:** v2.8.7-pre1 / v2.8.7 stable (2026-04-03). SOC detection, online share, component bundling added through v2.8.9.

**What it touches:**
- `extension/BhSettingsExporter.java` — `exportConfig()`, `showExportDialog()`, `detectSoc()` (kgsl sysfs → `device_info.xml` SP → `Build.SOC_MODEL` → `Build.HARDWARE`), online upload to Cloudflare Worker, `applyConfig()` (settings write + missing component download prompt)
- `patches/smali/com/xj/landscape/launcher/ui/gamedetail/BhExportLambda.smali` — synthetic lambda wired into `GameDetailSettingMenu.W()` (getPcGamesOptions Kotlin coroutine); reads `GameDetailEntity` via `L$0` from continuation; resolves gameId: `getId() > 0 → String.valueOf(getId())` else `getLocalGameId()`
- CI workflow Python patches in `.github/workflows/build.yml` and `build-quick.yml` — inject "Export Config" Option block into `GameDetailSettingMenu.W()` coroutine body

**Injection type:** Mixed — new Java extension class + new smali lambda class (`BhExportLambda`) + CI Python patch to inject Option block into `GameDetailSettingMenu.W()` coroutine.

**Dependencies:** `GameDetailSettingMenu.W()` Kotlin coroutine fingerprint (injection anchor: `XjLog` call near the end of `getPcGamesOptions`). GameID resolution logic (`getId()` / `getLocalGameId()`). Shared `BhSettingsExporter`.

**Complexity estimate:** High. The `GameDetailSettingMenu.W()` coroutine injection is a non-trivial fingerprinting problem: Kotlin coroutine bodies with suspension points require careful anchor identification. The `L$0` field access for GameDetailEntity in a continuation class is very fragile across GameHub versions.

---

## FEATURE 53: Per-Game Config Import

**Feature name:** "Import Config" option in PC game settings menu. Two tabs: "My Device" (files from `/sdcard/BannerHub/configs/`) and "Browse Community". Preview card before apply. SOC mismatch warning. Missing component auto-download prompt with "Skip" or "Download All" options. Applies settings to `pc_g_setting<gameId>` SharedPreferences.

**When added:** v2.8.7-pre1 / v2.8.7 stable (2026-04-03).

**What it touches:**
- `extension/BhSettingsExporter.java` — `showImportDialog()`, `showLocalImportDialog()`, `showCommunityImportDialog()`, `applyConfig()` with deferred settings apply after component download
- `patches/smali/com/xj/landscape/launcher/ui/gamedetail/BhImportLambda.smali` — synthetic lambda wired into `GameDetailSettingMenu.W()`; same coroutine injection as Export; same gameId resolution

**Injection type:** Same as Feature 52.

**Dependencies:** Same as Feature 52. Shares `BhSettingsExporter`.

**Complexity estimate:** High (same as Feature 52).

---

## FEATURE 54: Community Config Browser (BhGameConfigsActivity)

**Feature name:** Left side menu → "Game Configs" (ID=13) opens a 4-screen community config browser backed by a Cloudflare Worker + GitHub repo:
- **Screen 1:** Searchable games list with Steam cover art, config count badge, device/SOC header, total game count, "My Uploads" button, D-pad navigation
- **Screen 2:** Config list per game with SOC filter chips, age indicator (>6 months), ✓ My SOC badge, vote count + download count, upvote button
- **Screen 3:** Full config detail: device/SOC/BH version/settings count/components/uploader description/verified SOC badge. Download to `/sdcard/BannerHub/configs/`. Inline settings/component view. Share (GitHub URL to clipboard). Report (POST with IP dedup). Comments (view + post). "Apply to Game..." picker
- **Screen 4:** My Uploads (from `bh_config_uploads` SP): edit description (token-authenticated), long-press delete (POST /delete to worker), view counts

**When added:** v2.8.8-pre1 → v2.8.8 stable → through v2.9.1 (2026-04-03 through 2026-04-05).

**What it touches:**
- `extension/BhGameConfigsActivity.java` — new Activity: 4-screen state machine; all community features; Steam cover art via search API + in-memory Bitmap cache + SP cache; `fetchGames()`, `fetchConfigs()`, `fetchMeta()` (expanded to 11 rows in v3.1.1-pre1); `resolveGameName()` via Room DB `StarterGame` queries (2-pass: by `gameId` then by `id`); `detectSoc()`; SOC filter chips
- `extension/BhSettingsExporter.java` — `applyConfig()`, `showCommunityImportDialog()`, community upload URL construction
- `patches/smali_classes5/com/xj/landscape/launcher/ui/menu/HomeLeftMenuDialog.smali` — ID=13 "Game Configs" menu item + pswitch_13 routing
- `patches/AndroidManifest.xml` — `BhGameConfigsActivity` registered
- Cloudflare Worker (deployed separately, not in repo): `/games`, `/list`, `/download`, `/upload`, `/vote`, `/comment`, `/describe`, `/desc`, `/report`, `/delete` endpoints

**Injection type:** Mixed — new Java extension Activity + bytecode hook in `HomeLeftMenuDialog` (another switch extension) + manifest entry. The Room DB `StarterGame` query is a cross-dex call to GameHub's internal Room database.

**Dependencies:** `HomeLeftMenuDialog` switch fingerprint (shared with GOG/Epic/Amazon/Component Manager entries). GameHub's Room DB `StarterGame` DAO (obfuscated query method). Cloudflare Worker + GitHub backend (external dependency).

**Complexity estimate:** Very High. The `StarterGame` Room DB queries require fingerprinting GameHub's obfuscated DAO class and its query methods. The 4-screen state machine, Steam cover art integration, SOC detection, community voting/commenting, and the `Apply to Game` picker (which writes directly to GameHub's per-game SharedPreferences) make this the largest single Activity in the extension. The Cloudflare Worker is an external dependency.

---

## FEATURE 55: Expanded Config Detail Screen

**Feature name:** Config detail in the community browser (Screen 3, `BhGameConfigsActivity.fetchMeta()`) shows up to 11 metadata rows instead of 4: Wine/Proton, DXVK, VKD3D, GPU Driver, FEXCore, Box64, Resolution, Command Line, Env Vars (in addition to Renderer, CPU, FPS Cap, BH Version, Settings count, Components count). Resolution uses prefix scan for `pc_s_resolution_w*` key.

**When added:** v3.1.1-pre1 (2026-04-24).

**What it touches:**
- `extension/BhGameConfigsActivity.java` — `fetchMeta()` expanded; `parseSettingName()` helper (parses nested JSON `name`/`displayName`); conditional rows (only shown if field present + non-empty)

**Injection type:** Extension Java method expansion.

**Dependencies:** Feature 54.

**Complexity estimate:** Low. Pure extension code addition.

---

## FEATURE 56: Background Download Service (BhDownloadService)

**Feature name:** Epic, GOG, and Amazon downloads run in a foreground service — user can leave the detail screen while downloading. Persistent progress notification with game name, progress bar, and Cancel action. Service auto-picks best exe on completion. Reconnects live progress on resume. Completion/error posts notification when Activity not visible.

**When added:** v3.2.1-pre1 (2026-04-24).

**What it touches:**
- `extension/BhDownloadService.java` — new foreground Service: `GlobalListener` interface; `getActiveJobs()`, `getGameName()`, `getLastMsg()`, `getLastPct()`; `saveLibraryEntry()` writes to `bh_library` SP; `DownloadListener` bridge; cancel via notification action; `CountObserver` interface for badge updates
- `extension/BhDownloadsActivity.java` — new Activity: active downloads with live progress bars + Cancel; completed library rows (store badge, Launch, Uninstall, × remove); "Clear ✓" button; reconnect on `onResume`; empty state "No downloads or installed games"
- `extension/EpicGameDetailActivity.java`, `GogGameDetailActivity.java`, `AmazonGameDetailActivity.java` — `startInstall()` routes through `startViaServiceEpic/Gog/Amazon()` helpers; `requestPermissions(POST_NOTIFICATIONS)` on API 33+
- `extension/EpicGamesActivity.java`, `GogGamesActivity.java`, `AmazonGamesActivity.java` — list-page download call sites (7 total) replaced with service-routed variants; ⬇ button in header → `BhDownloadsActivity`
- `extension/BhDashboardDownloadBtn.java` — `View.OnClickListener` + `BhDownloadService.CountObserver`; attached to a container view in an existing GameHub screen; shows active download count badge; taps → `BhDownloadsActivity`
- `patches/AndroidManifest.xml` — `BhDownloadService` (foregroundServiceType="dataSync" + "specialUse" for 12 existing GameHub services), `BhDownloadsActivity` registered; `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS` permissions; `android:excludeFromRecents="true"` on `GameDetailActivity`; `android:foregroundServiceType="specialUse"` added to 12 existing GameHub services to fix `MissingForegroundServiceTypeException` on Android 14+

**Injection type:** Mixed — 2 new Java extension classes (Service + Activity) + 1 small utility class (`BhDashboardDownloadBtn`) + modifications to 6 existing extension activities + extensive `AndroidManifest.xml` changes (service declaration, permission additions, foreground service type fixes on existing services).

**Dependencies:** Features 22–42 (the 3 store download managers). The `foregroundServiceType` fix on 12 existing GameHub services is a manifest-only patch independent of other features.

**Complexity estimate:** Very High. A foreground service with a cross-process notification cancel action, the `GlobalListener` broadcast pattern to reconnect progress on Activity resume, the in-service game library (persistent across restarts), the dashboard download badge, and the non-trivial manifest changes (including the `MissingForegroundServiceTypeException` fix that touches 12 existing GameHub service declarations) make this one of the most complex patches. The manifest foreground service type fix requires identifying all 12 services by name — fragile if GameHub changes service names.

---

## FEATURE 57: Frontend Export (Beacon + ES-DE)

**Feature name:** "Frontend Export" option in PC game settings popup. Selecting Beacon writes the game's ID to `Downloads/bannerhub/frontend/Beacon/{gameName}.iso`. Selecting ES-DE writes to `Downloads/bannerhub/frontend/ES-DE/{gameName}.steam`. For catalog games: uses `getSteamAppId()`; for imported games: uses `getLocalGameId()`.

**When added:** v3.1.1-pre7/pre8 (Beacon, 2026-04-24); ES-DE added v3.3.1-pre1 (2026-04-24).

**What it touches:**
- `patches/smali/com/xj/landscape/launcher/ui/gamedetail/BhFrontendExportLambda.smali` — new synthetic lambda wired into `GameDetailSettingMenu.W()`; gameId resolution: `getSteamAppId()` for catalog (non-null) else `getLocalGameId()`
- `extension/BhSettingsExporter.java` — `showFrontendExportDialog()`, `exportForBeacon()`, `exportForEsDE()` (writes `.steam` file); dialog description shows resolved base path
- CI workflow Python patches — inject third Option block into `GameDetailSettingMenu.W()` coroutine (alongside Export/Import Config blocks)

**Injection type:** Same pattern as Features 52/53 — new smali lambda + Java extension method + CI Python injection into `GameDetailSettingMenu.W()`.

**Dependencies:** Features 52/53 (same coroutine injection anchor and gameId resolution pattern). `getSteamAppId()` method on `GameDetailEntity`.

**Complexity estimate:** Medium. Injection pattern is identical to Export/Import Config (already solved). The file-writing logic (`Environment.getExternalStoragePublicDirectory`) is straightforward extension code. The only new fingerprinting need is `getSteamAppId()` on `GameDetailEntity`.

---

## FEATURE 58: Phone-Home Version Lock + CI Version Injection

**Feature name:** GameHub always reports version `5.3.5` to its server regardless of what Android's package manager displays. The displayed version (`versionName`) is set from the git tag (e.g. `3.3.1-pre4`). CI auto-injects both values. Critical: phone-home must stay ≥4.1.5 or the GameHub server hides the Steam card.

**When added:** v3.3.1-pre4 (2026-04-24) for the dual versionName approach. Earlier fix v2.7.1 (2026-03-24) established the 5.3.5 phone-home requirement.

**What it touches:**
- `.github/workflows/build.yml`, `build-quick.yml` — CI Python: strips `v` from tag → injects as `versionName` in `apktool.yml`; replaces `AppUtils->e()` calls with `const-string "5.3.5"` in:
  - `patches/smali_classes13/com/...ClientParams.smali` — network client params (phone-home)
  - `patches/smali_classes7/com/...TokenInterceptor.smali` — auth token interceptor

**Injection type:** CI-level Python bytecode replacement (not static smali patch files). Two injection points in existing GameHub networking classes.

**Dependencies:** None (CI infrastructure only).

**Complexity estimate:** Low. Two constant-string replacements in well-identified networking classes. In ReVanced: `bytecodePatch` replacing `AppUtils.e()` call sites with the literal string `"5.3.5"`.

---

## FEATURE 59: APK Variants (9 Package Names)

**Feature name:** BannerHub ships as 9 APKs with different package names, allowing simultaneous installation with various games/benchmarks for framerate generation compatibility on specific OEM ROMs.

**When added:** Started v2.2.0 (5 variants, 2026-03-12), expanded through v2.7.1 (9 variants, 2026-03-24).

**What it touches:**
- `.github/workflows/build.yml` — matrix builds; `sed` replaces `gamehub.lite` with the variant package name in `AndroidManifest.xml` + `android:authorities` for each variant
- `apktool.yml` — `versionName` (also patched by CI)

**Injection type:** CI/build infrastructure (not a smali/resource patch in the traditional sense). Variants: `banner.hub` (Normal), `gamehub.lite` (Normal.GHL), `com.tencent.ig` (PuBG), `com.antutu.ABenchMark` (AnTuTu), `com.antutu.benchmark.full` (alt-AnTuTu), `com.tencent.tmgp.cf` (PuBG-CrossFire), `com.ludashi.aibench` (Ludashi), `com.miHoYo.GenshinImpact` (Genshin), `com.xiaoji.egggame` (Original). Signed with AOSP testkey v1/v2/v3.

**Dependencies:** None beyond CI infrastructure.

**Complexity estimate:** Low. Pure build configuration — `resourcePatch` string replacement on the package name in `AndroidManifest.xml` + `apktool.yml`.

---

## FEATURE 60: Japanese Locale Support

**Feature name:** Full Japanese translation of the entire BannerHub UI when Android system language is set to Japanese.

**When added:** Mentioned in `FEATURE_SPECS.md` Feature 52 as an existing feature. Specific commit not pinpointed in the log, but the file exists.

**What it touches:**
- `patches/res/values-ja/strings.xml` — 3,468-string Japanese translation of all BannerHub-added strings
- `patches/res/values/strings.xml` — English base strings (existing)

**Injection type:** Resource patch (locale-specific string overlay).

**Dependencies:** All features with user-visible strings.

**Complexity estimate:** Low (technically). The translation content exists. A `resourcePatch` adds `values-ja/strings.xml`. No smali work needed.

---

## FEATURE 61: Controller Navigation Support (Store Libraries)

**Feature name:** Full D-pad + A-button gamepad navigation in all store library activities (GOG/Epic/Amazon). List view: D-pad up/down, gold 3dp border + lighter background on focused card. Grid/poster view: D-pad all 4 directions, gold border via transparent `focusWrapper` FrameLayout (avoids `clipToOutline` clipping). Header buttons: focusable with gold 2dp border.

**When added:** v2.8.2-pre5 → v2.8.2 stable (2026-04-01).

**What it touches:**
- `extension/GogGamesActivity.java`, `EpicGamesActivity.java`, `AmazonGamesActivity.java` — each: `setFocusable(true)` + `FOCUS_BLOCK_DESCENDANTS` on card roots; `onFocusChangeListener` with `GradientDrawable` gold border; `focusWrapper` FrameLayout for grid/poster; `requestFocus()` on first card after list load

**Injection type:** Extension Java class method additions (entirely within BannerHub's own extension Activities).

**Dependencies:** Features 22, 32, 41 (the store activities).

**Complexity estimate:** Low. Pure extension code with no GameHub fingerprinting.

---

## Shared Dependencies & Cross-Feature Infrastructure

The following smali/Java elements are shared across multiple features and should be ported as foundational primitives before dependent features:

| Infrastructure Item | Used By |
|---|---|
| `HomeLeftMenuDialog` switch extension (smali_classes5) | Features 3, 22, 32, 41, 54 |
| `LandscapeLauncherMainActivity.onResume()` pending-launch hook | Features 22, 32, 41 |
| `GameDetailSettingMenu.W()` coroutine injection | Features 52, 53, 57 |
| `BhHudInjector` + `BhPerfSetupDelegate` | Features 48, 49, 50 |
| `BhSettingsExporter` class | Features 52, 53, 54, 57 |
| `BhDownloadService` + `BhDownloadsActivity` | Feature 56 (base for all 3 stores) |
| `FolderPickerActivity` | Features 26, 36 |
| `GameHubPrefs.smali` | Features 10, 16 |
| `ComponentInjectorHelper.appendLocalComponents()` + `GameSettingViewModel$fetchList$1` | Features 8, 9 |
| `EmuComponents` registration pattern | Features 4, 8, 9 |
| `R$id.smali` (classes9) | Features 2, 12 (RTS IDs) |

---

## Recommended ReVanced Porting Order

**Phase 1 — Foundation (Low, no fingerprinting):**
1. Feature 59 — APK Variants (build config)
2. Feature 1 — "My Games" tab rename (single string)
3. Feature 58 — Phone-home version lock (2 constant replacements)
4. Feature 21 — Touch scale cap (resource XML)
5. Feature 60 — Japanese locale (resource strings)
6. Feature 10 — EmuReady default off (single boolean)

**Phase 2 — New Activities, Low fingerprinting:**
7. Feature 47 — FolderPickerActivity
8. Feature 34 — Epic Free Games browser
9. Features 29/38 — Release date display (extensions of store activities)

**Phase 3 — Store integrations (build in order: Auth → Library → Download → Detail):**
10. Features 22–25 — GOG core (auth, library, Gen2/Gen1 downloads, post-install)
11. Features 32–33 — Epic core (auth, library, chunked download)
12. Features 41–42 — Amazon core (auth, library, manifest.proto download)
13. Features 26–31 — GOG advanced (cloud saves, updates, DLC, detail activity)
14. Features 35–40 — Epic advanced (updates, cloud saves, DLC, detail activity)
15. Features 43–46 — Amazon advanced (updates, DLC, detail activity)

**Phase 4 — Performance / HUD (Medium fingerprinting):**
16. Feature 13 — Sustained Performance (WineActivity hook)
17. Feature 14 — Max Adreno Clocks (same scaffold)
18. Feature 48 — Basic HUD / BhFrameRating (WineActivity.onResume() hook + HudDataProvider reflection)
19. Feature 49 — Extra Detailed HUD
20. Feature 50 — Konkr Style HUD

**Phase 5 — Component Manager (Very High fingerprinting):**
21. Feature 4 — WCP/ZIP extraction (EmuComponents registration — most fragile)
22. Feature 8 — GameSettingViewModel coroutine hook
23. Feature 3/5/6/7/9 — Component Manager UI, downloader, source tracking, descriptions

**Phase 6 — Settings / Sidebar (High fingerprinting):**
24. Feature 15 — Grant Root Access (SettingBtnHolder, SettingItemEntity, SettingItemViewModel)
25. Feature 16 — 3-way API selector (SettingSwitchHolder)
26. Feature 17 — CPU Core Affinity (SelectAndSingleInputDialog, PcGameSettingOperations, EnvironmentController)
27. Feature 18 — VRAM Unlock (PcGameSettingOperations)
28. Feature 19 — Offline settings (GameSettingViewModel coroutine)
29. Feature 20 — Container cleanup (UninstallGameHelper)

**Phase 7 — RTS Controls + Task Manager (Very High fingerprinting):**
30. Feature 12 — RTS Touch Controls (4 class hooks, 13 resources)
31. Feature 51 — Wine Task Manager (WineActivityDrawerContent hook)

**Phase 8 — Config Sharing (Very High — external backend dependency):**
32. Features 52/53 — Export/Import Config (GameDetailSettingMenu coroutine)
33. Feature 57 — Frontend Export
34. Feature 54/55 — Community Config Browser (StarterGame DB, Steam cover art, Cloudflare Worker)

**Phase 9 — Download Service (Very High):**
35. Feature 56 — Background Download Service + In-App Download Manager (manifest surgery on 12 services)

**Phase 10 — Navigation and Polish:**
36. Feature 11 — Offline Steam Skip
37. Feature 2 — BCI Launcher Button
38. Features 61 — Controller navigation (extension-only)
