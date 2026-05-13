<p align="center">
  <img src="assets/bannerhub-v6-logo.png" alt="BannerHub v6" width="180"/>
</p>

<h1 align="center">BannerHub v6 for ReVanced</h1>

<p align="center">
  Pre-built APKs and the patch bundle that produces them — built on top of <a href="https://www.gamehubglobal.com/">XiaoJi GameHub</a> 6.0.4 (<code>com.xiaoji.egggame</code>).
</p>

<p align="center">
  <a href="https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.1.0-604"><strong>📥 Latest stable: v1.1.0-604</strong></a>
  ·
  <a href="#patches-applied">Patches</a>
  ·
  <a href="#signing">Signing</a>
  ·
  <a href="#build-it-yourself">Build it yourself</a>
</p>

---

**What it does** — removes the login requirement, redirects the catalog API to the BannerHub Cloudflare Worker, ships PC-accurate XInput rumble for Wine games (with a per-game settings dialog injected into both popup menus), mutes the UI feedback sounds, and rebrands the launcher icon + in-app artwork as BannerHub v6. Nine APK variants install side-by-side on the same device.

> ✅ **In-place updates** — from `v1.1.0-604` onward, BannerHub releases are signed with a stable test keystore ([`keystore/README.md`](keystore/README.md)) so every future stable installs on top of the previous one with no uninstall. **One-time migration**: if you're still on `v1.0.0-604` or older (those used per-run ephemeral keys), uninstall your current BannerHub-ReVanced variant once before installing `v1.1.0-604`. From there on, regular Android updates flow normally.

## What's new in v1.1.0-604

The **first BannerHub v6 stable** on the new build pipeline. Four headline changes vs `v1.0.0-604`:

- **🎮 PC-accurate XInput rumble for Wine games** — ported from [TideGear/GameHub-Vibration-Fix](https://github.com/TideGear/GameHub-Vibration-Fix) (itself a 6.0.2 port of BannerHub PR #80). Dual-motor independent dispatch on multi-motor controllers, sustained rumble past SDL2's 1 s auto-stop (via the guest-side `libevshim.so` LD_PRELOAD shim that re-issues `SDL_JoystickRumble` every 500 ms with a 2 s duration), and instant release on let-go. **On by default** — `MODE_CONTROLLER` at 100% intensity, no UI tweaking required. Confirmed working on GTA 5 Enhanced with a physical controller.
- **🎛 PC Vibration Settings menu row in both per-game popups** — a new 5th row labelled **PC Vibration Settings** in both (1) the game-details "More Menu" (3-dot from inside a game's detail screen) and (2) the library-tile 3-dot popup. Tapping launches a per-game mode/intensity dialog (off / device / controller / both); global defaults apply to games without per-game overrides.
- **🎨 BannerHub v6 visual rebrand** — new launcher icon (adaptive-icon foreground, masked-shape friendly on every launcher), refreshed in-app `wine_logo`, and rebranded auth-screen + splash-screen banners. Five drawables touched; per-variant package names + side-by-side install behaviour unchanged.
- **🔐 Stable signing — in-place updates from now on** — first release signed with a stable test keystore (cert SHA-256 `10:89:5A:31:1F:E0:4F:95:F8:2E:4D:A5:C9:A6:C0:41:BA:92:82:BF:21:1F:1B:57:8F:E1:CB:EB:89:4C:E0:BA`). Future BannerHub v6 stables update in-place — no more uninstall-between-versions. One-time migration required if you're on `v1.0.0-604` or older (different cert). See the [signing section](#signing) for the full keystore details.

The patch source is in `patches/.../gamehub/vibration/` (4 patches: bytecode, manifest, native lib, menu row, label resource). The engineering deep-dive on injecting custom rows into either popup is in [`project_bannerhub_revanced_menu_injection_playbook.md`](../bannerhub-revanced/PROGRESS_LOG.md) (memory store; not in-repo).

For the full release-note style breakdown of every patch + per-variant filenames + cert fingerprints, see the [v1.1.0-604 release page](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.1.0-604).

> 📜 Past-release notes for `v1.0.0-604`, `v1.0.0-602`, `v1.0.1-601`, `v1.0.0-601`, and `v1.0.1-600` are preserved on their respective [release pages](https://github.com/The412Banner/bannerhub-revanced/releases). The README now keeps only the latest release in this section to stay focused on what's current.

---

## What this is

GameHub 6.0 (the KMP rewrite under the package `com.xiaoji.egggame`) gates the entire game-library flow behind a login screen, ships with bundled UI feedback sounds, and hits XiaoJi's `landscape-api-{cn,oversea}.vgabc.com` catalog endpoints for the component (driver / DXVK / FEX / Wine prefix / firmware) registry that drives every game launch. This patch bundle changes all three:

- **No login** — six bytecode rewrites short-circuit the auth gate so a fresh install lands on the home screen, the **Import → Save** dialog persists rows to the on-device Room database (`db_game_library.db`), and the imported games appear in the library list — all without ever logging in or hitting the upstream auth endpoint.
- **Catalog redirect to the BannerHub Cloudflare Worker** — both `landscape-api-*.vgabc.com` hosts on the `xrj` `Online` enum value are swapped for `bannerhub-api.the412banner.workers.dev`, and a single chokepoint helper (`vob.b`) is hooked to prefix every relative API call with `v6/`. The Worker uses the prefix to serve 6.0-specific response shapes (firmware 1.3.5, `EnvListData` wrapper required by 6.0's kotlinx-strict deserializer, etc.) while a parallel 5.x branch keeps the upstream shape for older clients.
- **Muted UI sounds** — bundled menu/click `.wav` assets are replaced with silent PCM at packaging time, no runtime audio routing is touched.

It also fixes a launch-time `VerifyError` that the original 5.x `Disable Crashlytics` patch caused on 6.0, ships a diagnostic `Debug logging` probe (kept for ongoing triage convenience even though the import flow is confirmed stable end-to-end), and includes an unrelated convenience patch (`File manager access`) that exposes a content provider for browsing GameHub's data dir from external file managers.

## Source

- **Base APK:** `GameHub_6.0.4.apk` — the official 6.0.4 global build (versionCode 114), attached unmodified to the [`base-apk-604`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-604) release for reproducibility. Earlier base APKs remain attached to [`base-apk-602`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-602) (6.0.2), [`base-apk-601`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-601) (6.0.1) and [`base-apk-600`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-600) (6.0.0) for older releases.
- **Patcher:** [ReVanced CLI 6.0.0](https://github.com/ReVanced/revanced-cli/releases/tag/v6.0.0) + the bundle built from this repo's `gamehub-604-build` branch (`gamehub-602-build`, `gamehub-601-build`, and `gamehub-600-build` remain in place for older 6.0.x work).
- **Catalog backend:** [`The412Banner/bannerhub-api`](https://github.com/The412Banner/bannerhub-api) — Cloudflare Worker source, deployed at `bannerhub-api.the412banner.workers.dev`. Serves the curated component catalog from GitHub Pages and forwards unallowlisted paths back to upstream `landscape-api.vgabc.com` with the original signed-request behavior preserved.
- **Build environment:** GitHub Actions, Ubuntu 24.04 runner, Temurin JDK 17. The full pipeline is [`.github/workflows/release.yml`](.github/workflows/release.yml): a `build` job produces the `.rvp` patch bundle, a 9-way matrix patches the base APK in parallel (one variant per matrix entry), and a final `release` job globs all artefacts into a single GitHub Release when triggered with `stable=true`.

## Variants

The same patch bundle is applied to the same base APK 9 times, each time with a different package name + launcher label so the variants install **side-by-side** on the same device. The `Original` variant keeps the upstream package name `com.xiaoji.egggame` and so **replaces** an installed GameHub on install; everything else coexists.

| Variant | APK file | Package | Launcher label |
| --- | --- | --- | --- |
| Normal | `BannerHub-V6-<version>-Patched-Normal.apk` | `banner.hub` | BannerHub v6 |
| Normal-GHL | `BannerHub-V6-<version>-Patched-Normal-GHL.apk` | `gamehub.lite` | BannerHub v6 |
| PuBG | `BannerHub-V6-<version>-Patched-PuBG.apk` | `com.tencent.ig` | BannerHub v6 PuBG |
| AnTuTu | `BannerHub-V6-<version>-Patched-AnTuTu.apk` | `com.antutu.ABenchMark` | BannerHub v6 AnTuTu |
| alt-AnTuTu | `BannerHub-V6-<version>-Patched-alt-AnTuTu.apk` | `com.antutu.benchmark.full` | BannerHub v6 AnTuTu |
| PuBG-CrossFire | `BannerHub-V6-<version>-Patched-PuBG-CrossFire.apk` | `com.tencent.tmgp.cf` | BannerHub v6 PuBG CrossFire |
| Ludashi | `BannerHub-V6-<version>-Patched-Ludashi.apk` | `com.ludashi.aibench` | BannerHub v6 Ludashi |
| Genshin | `BannerHub-V6-<version>-Patched-Genshin.apk` | `com.miHoYo.GenshinImpact` | BannerHub v6 Genshin |
| Original | `BannerHub-V6-<version>-Patched-Original.apk` | `com.xiaoji.egggame` | BannerHub v6 |

Three variants (Normal, Normal-GHL, Original) share the bare "BannerHub v6" launcher label and the two AnTuTu variants share "BannerHub v6 AnTuTu" — they install side-by-side via different package names, so the shared labels are intentional.

## Signing

From `v1.1.0-604` onward, every release is signed with the **public test keystore** at [`keystore/bannerhub.keystore`](keystore/bannerhub.keystore). The keystore + passwords are intentionally committed to the repo so the signing cert is reproducible across releases — that's what enables in-place Android updates between BannerHub stables.

- **Alias:** `bannerhub`
- **Store/key password:** `bannerhub`
- **Cert SHA-256:** `10:89:5A:31:1F:E0:4F:95:F8:2E:4D:A5:C9:A6:C0:41:BA:92:82:BF:21:1F:1B:57:8F:E1:CB:EB:89:4C:E0:BA`
- **Cert SHA-1:** `1F:51:B2:5E:5C:9F:58:08:E0:CF:45:17:4F:CC:B3:8D:67:CA:6D:E5`
- **Schemes:** v1 + v2 + v3 (v4 disabled — needs a `.idsig` sidecar we don't ship)
- **Validity:** 100 years (until 2126-04-19)

Every CI release run prints the cert SHA-256 via `apksigner verify --print-certs` so the same fingerprint can be cross-checked against this README. See [`keystore/README.md`](keystore/README.md) for the full security model + the one-time migration note from `v1.0.0-604`-or-older.

## Patches applied

<details>
<summary><strong>📦 Click to expand the full patch list (17 patches + disabled-by-default options)</strong></summary>

This bundle ships only patches that successfully apply against GameHub 6.0. Every patch below appears as an individually-named, individually-toggleable entry in the published `.rvp` bundle (`revanced-cli list-patches patches.rvp` to enumerate; `--include` / `--exclude` to pick).

### `Bypass login`

Skips the login screen entirely and makes the library system function under a synthetic identity. Six bytecode rewrites cooperate (the walkthrough below uses the 6.0.2 R8 letter names for historical continuity; the 6.0.4 mappings are listed in the "What's new in v1.0.0-604" section above, and every prior version's letters are recorded in the per-patch source comments. The patch *mechanics* are identical across versions — only the class letters differ.):

1. **`xle.i(gi0)` and `xle.r(gi0)`** — the navigator methods that gate Login routing. Original logic does `iget Lxle;->b:Lct0;` → `invoke-interface Lct0;->a()Z` → `if-nez :skipLogin` → otherwise build a `Lsa0;` Login navigation intent. Patch removes the `invoke-interface`/`move-result` pair and substitutes `const/4 vN, 0x1` so the branch is always taken.
2. **`rr0.a(...)`** — a separate `NavigationInterceptor` (`getOrder()==10`) added in 6.0.1 that gates on `Lct0;->a()Z` independently of the navigator. Same iget+invoke-interface+if-nez pattern; bypassed identically with `const/4 vN, 0x1`.
3. **`it0.h()`** — the real DB-backed `Lct0;` implementation's `isLoggedIn` `StateFlow<Boolean?>`. Body replaced to return `FakeStateFlow.boolTrue()` (a host-compatible `Lhzh;` wrapping `Ltjk;(Boolean.TRUE)`, built via reflection in the Java extension and cached) so every collector — `NavHost.collectAsState`, the listener, the analytics pipeline — sees a logged-in state.
4. **`it0.e()`** — the user-account `StateFlow<fpm?>`. Without an `auth_token` row in the DB this emits `null` and the library-list reader's `flatMapLatest` collapses to an empty `Flow`. Patch replaces the body with `FakeStateFlow.userFlow()` where the underlying value is `FakeUserAccount.get()`, a Java extension that reflectively constructs `Lfpm;` via `Class.forName("fpm").getDeclaredConstructor(...)`, with `a="99999"` and every other field zeroed/empty. Result: the library reader's pipeline `it0.e().flatMapLatest { fpm -> dao.subjectAllByUserId(fpm.a) }` always queries with `user_id="99999"` and returns the imported rows.
5. **`uu7.e()`** — the `GameLibraryRepository`'s user-id getter (was `hp7.f()` in 6.0.1; the method was renamed between minor versions). Returns `"99999"` directly so it matches the synthetic user account.
6. **`ct0.f()`** — the interface default method that returns the current `kpm` auth token. Body replaced to call `FakeAuthToken.get()`, a Java extension that reflectively constructs `Lkpm;` with `a="99999"` and `b=""`. Several lambdas use this directly to read the user-id for network-prep and lambda capture; under the bypass they all see the same synthetic identity.

End-to-end consequence: a fresh install lands on the home screen, the **Import** dialog opens, picking an APK + metadata + tapping **Save** persists a row into `/data/data/<pkg>/databases/db_game_library.db` (`t_game_library_base`, `t_game_launch_method`), and the row appears in the library list immediately because the read pipeline is now keyed off the matching synthetic user-id.

### `Disable Firebase Crashlytics`

Removes the Firebase Crashlytics initialisation block. Without this, GameHub 6.0 crashes on launch with `VerifyError`. Root cause: the upstream 5.x patch used `goto` to skip the Crashlytics call site, which in 6.0 leaves a join-point where the same register holds either `String` (goto path) or `Boolean` (fall-through path) and the ART verifier rejects it. The 6.0-compatible patch removes the three Crashlytics instructions in **reverse index order** (`setCrashlyticsCollectionEnabled`, `move-result-object`, `invoke-static getInstance`) so the intermediate `const/4 v2, 0x0` redefines the register with a consistent `Boolean` type at the join point. Anchored on `Lcom/xiaoji/egggame/BaseAndroidApp;->onCreate` plus full Firebase class names, so it ports across versions without source change.

### `Mute UI sounds`

Replaces the bundled UI feedback sounds (`assets/composeResources/com.xiaoji.egggame.core/files/sound/*.wav`) with silent PCM. Menu navigation and button taps stop clicking. The patch substitutes the resource at packaging time — no runtime audio routing is changed, so game audio is unaffected. The patch's resource lookup is anchored on a Kotlin `object {}` to give the classloader a stable handle (the alternative — anchoring on the patch class itself — fails when ReVanced's class loader can't see the patches module's resources from inside the runner JVM).

### `PC-accurate vibration` ⭐ *new in v1.1.0-604*

Four bytecode hooks (`GamepadServerManager.onRumble` entry + per-controller dispatch + stop + Wine env-builder LD_PRELOAD inject) route XInput rumble from Wine games into Android's `VibratorManager` with dual-motor independent dispatch on multi-motor pads, intensity blending on single-motor pads, sustained-hold keepalive, and instant release on let-go. The `BhVibrationController` Java extension owns the state machine (per-slot motor amplitudes, keepalive worker thread refreshing controller rumble every 1.5 s before SDL2's internal 2 s expiry, mode dispatch: off/device/controller/both, per-game intensity scaling). Adapted verbatim from [TideGear/GameHub-Vibration-Fix](https://github.com/TideGear/GameHub-Vibration-Fix) (BannerHub PR #80 ported to 6.0.2) with the class-letter map re-derived for 6.0.4.

### `Vibration native shim` ⭐ *new in v1.1.0-604*

Ships an `arm64-v8a` `libevshim.so` (~41 KB) into the APK's `lib/` directory; loaded into Wine via the LD_PRELOAD inject of `PC-accurate vibration` Hook 4. The shim (`native/evshim/evshim.c`, ~700 lines of C) intercepts Wine's `winebus.so` calls to `pSDL_JoystickRumble` and `pSDL_JoystickClose` and re-issues the rumble every 500 ms with a 2 s duration so SDL2's internal `rumble_expiration` timer never fires during sustained holds. The CI workflow builds the `.so` via the runner's NDK (cmake + ninja, android-29 ABI level) before the gradle patch build, so the binary ships in the same `.rvp` bundle as the bytecode patches.

### `Vibration settings activity` ⭐ *new in v1.1.0-604*

Registers `com.xj.winemu.vibration.BhVibrationSettingsActivity` in the patched manifest (`exported="false"`, translucent theme). The activity hosts a programmatic dialog (mode picker: off / device / controller / both + intensity slider 0–100%) that writes to `bh_vibration_prefs` SharedPreferences (global defaults) and to the per-game `pc_g_setting<gameId>` JSON file when scoped to a specific game. Internal-only — no `<intent-filter>` — launched only by the menu-row patch below via explicit `Intent`.

### `PC Vibration Settings menu row` ⭐ *new in v1.1.0-604*

Injects a 5th row labelled **PC Vibration Settings** into both per-game popup menus in GameHub 6.0.4:

- **Game-details "More Menu"** — patched in `Lx57.a()` at the tail of the row-list builder. Each row is an `Iae(icon, label, onClick)` with `Lpw6;` (`Function1`) onClick.
- **Library-tile 3-dot popup** — patched in `Lpzc.j0()` by hooking the list's return. Each row is a `Lz4e(Lell label, Lnw6 onClick, int)` — different row class, different click-handler interface (`Lnw6;` = `Function0`), and the label is a Compose Multiplatform resource descriptor (`Lell`) not a raw String.

Both injections route through a single Java helper (`BhMenuRowClick`) that walks `ActivityThread.mActivities` to find the current top Activity and fires `startActivity(BhVibrationSettingsActivity, gameId)` — gameId sniffed from any running `WineActivity`'s Intent extras so the settings dialog scopes per-game when possible. Three architectural curiosities solved along the way:

- **R8 renamed `kotlin.jvm.functions.Function0/Function1`** to `Lnw6;` / `Lpw6;` everywhere in the host APK. The extension's own `implements Function1` is a different JVM class at runtime — fails `pw6Cls.isInstance()`. Fix: wrap each click handler in a `java.lang.reflect.Proxy` that actually implements the renamed interface.
- **`Lell` is a Kotlin empty subclass** of abstract `Ltdi(String key, Set<String> locales)` and declares zero constructors of its own. `getDeclaredConstructor(...)` returns nothing. Fix: `sun.misc.Unsafe.allocateInstance` skips ctor invocation; then reflect-set the inherited `Ltdi.a` (key) and `Ltdi.b` (locale set) fields.
- **`Lxd3.l1` resolver throws on unknown Compose resource keys** — and the runtime requires a manifest registration the bare `.cvr` append doesn't provide. Fix: a third bytecode injection at the head of `Lxd3.l1` short-circuits our sentinel key `bh_pc_vibration_label` and returns the literal string `"PC Vibration Settings"` before the stock resource lookup runs.

The 10-iteration debugging trail behind landing this patch is recorded in `project_bannerhub_revanced_menu_injection_playbook.md` (auto-memory) and `PROGRESS_LOG.md`. Future menu-row additions should start there.

### `PC Vibration Settings label resource` ⭐ *new in v1.1.0-604*

Appends a `bh_pc_vibration_label = "PC Vibration Settings"` entry to `features.home`'s Compose Multiplatform resource bundle (`.cvr` file). Documentation patch — the runtime resolution actually goes through the `Lxd3.l1` short-circuit described above because Compose's resource manifest needs entries the bare `.cvr` doesn't register. Kept anyway so the resource is reachable by any future patch that goes through the proper manifest registration path.

### `Change app icon` ⭐ *new in v1.1.0-604*

Replaces five in-APK drawables with BannerHub v6 branding:

- **Launcher adaptive-icon foreground** (`res/drawable-xxxhdpi/ic_launcher_foreground.png`) — 432×432 raster with BannerHub logo content in the inner 288×288 safe zone. The stock GameHub vector at `res/drawable/ic_launcher_foreground.xml` is *deleted* so the new raster wins on every device density (Android downsamples from xxxhdpi for lower buckets — imperceptible at icon sizes; without the delete, lower-density devices would silently fall back to the stock vector).
- **In-app `wine_logo`** (`res/drawable-xxhdpi/wine_logo.png`) — 240×72 rebrand, dimensions matching stock so any `wrap_content` ImageView measuring against the resource keeps its 80×24 dp intrinsic size.
- **Auth-screen landscape logo** (`assets/composeResources/com.xiaoji.egggame.features.auth/drawable/features_auth_ic_logo_landscape.png`) — 96×96 square (the "landscape" in the name refers to auth-screen orientation, not image aspect).
- **Auth-screen overseas logo** (`.../features_auth_ic_logo_overseas.png`) — 366×72 wide rectangle.
- **Splash-screen banner** (`assets/composeResources/com.xiaoji.egggame.features.splash/drawable/splash_logo.png`) — 996×200 with 2 px transparent top/bottom pad for aspect preservation; RGBA so a future splash-background change can bleed through cleanly.

Background drawable (`res/drawable/ic_launcher_background.xml`) and CN-locale variants are left untouched — most launchers mask the adaptive icon's foreground so the background only shows at the masked edge, and the CN drawables aren't displayed on overseas builds.

### `Redirect catalog API`

Patches the `xrj` environment enum's `Online` value so the catalog API's `cnHost` and `overseaHost` both point at the BannerHub Cloudflare Worker (`bannerhub-api.the412banner.workers.dev`) instead of `landscape-api-{cn,oversea}.vgabc.com`. The Worker:

- Serves a curated component catalog from `the412banner.github.io/bannerhub-api/` for `simulator/v2/*` and other allowlisted paths (drivers, DXVK, VKD3D, FEX, Box64, Wine prefix, firmware metadata).
- Reshapes responses for 6.0's kotlinx-strict deserializer (wraps `getAllComponentList` data in `EnvListData` `{list, page, page_size, total}` instead of a bare array — without this the cast silently fails and the in-memory COMPONENT registry stays empty, breaking game launch at "Download Game Config").
- Token-injects + signature-regens forwards for any unallowlisted path back to `landscape-api.vgabc.com` so anything not curated still works against the original upstream.
- Branches 6.0-only response variants behind a `/v6/` path prefix (see next patch); 5.x clients hitting the same Worker without the prefix get the upstream-shaped pass-through.

The Beta + Test enum values, the analytics hosts (`landscape-api-*-*.vgabc.com/events`), `clientgsw.vgabc.com`, and the bigeyes CDN are all intentionally untouched — only the curated-catalog hosts are swapped.

**Side benefit (PC game settings orientation):** the per-game **PC game settings** screen now renders correctly in both landscape *and* portrait orientation. Upstream's catalog response carried a constraint that the host honored by locking that screen to landscape only — the BannerHub Worker's payload doesn't include that constraint, so the picker is usable from a portrait-held phone for the first time. This is a behavioral byproduct of the catalog redirect, not a separate patch.

### `Prefix API path with /v6`

Hooks `vob.b(m7a builder, String path)` — the single static Ktor URL-builder helper through which every relative GameHub API request flows — and prepends `v6/` via the small `V6PathPrefix.prefix()` Java extension. The Worker strips the prefix and uses it as a feature gate so the same backend can serve 6.0 and 5.x clients side-by-side without divergent state:

- `/v6/simulator/v2/getAllComponentList` → `EnvListData`-wrapped response, reshaped for 6.0 (`is_ui` / `gpu_range` stripped, `fileType` / `framework` / `framework_type` / `is_steam` / `status` / `blurb` / `upgrade_msg` / `sub_data` / `base` injected, `base.fileType=0`).
- `/simulator/v2/getAllComponentList` (no prefix, from a 5.x client) → native upstream catalog passed through with `is_ui` / `gpu_range` preserved.
- `/v6/simulator/v2/getImagefsDetail` → firmware 1.3.5. Without prefix → firmware 1.3.3.

Full URLs (paths already starting with `http://` or `https://`) are short-circuited by the helper and pass through untouched, so direct downloads from the catalog's `download_url` fields still resolve to the Worker-authored GitHub-release URLs without the prefix being injected into them.

### `Offline component cache fallback` ⚠ *currently broken on 6.0.4*

> **Status:** The patch applies cleanly and is included in v1.0.0-604, but at runtime it doesn't deliver the intended cached-entry fallback. Online behavior is unchanged (the hook only fires on the network-returned-empty path), so this only impacts offline picker contents. Investigation pending — the structural anchors and reflective field lookups apparently survived the 6.0.4 R8 reshuffle but something downstream broke. Safe to leave enabled; safe to disable via `revanced-cli -d "Offline component cache fallback"` if you want it out of a custom build.

Lets the per-game pickers (GPU driver, DXVK, VKD3D, FEXCore, Box64, container) render cached entries when the device is offline. Without this patch, an offline `eci.a(RepoCategory, Continuation)` falls through to Kotlin's `EmptyList` sentinel and the pickers show only the embedded built-in versions — even though `u6o.<init>` already hydrated `xxo.c` (a `ConcurrentHashMap`) from `sp_winemu_unified_resources.xml` at app start with every catalog entry the user has ever seen online.

The patch swaps the `sget-object Lz85;->a:Lz85;` instruction at the method's empty-return `:goto_2` block for an `invoke-static` into `PickerCacheFallback.fromXxo(p0, p1)`. The helper:

1. Reflects through `eci.a` (selected by single-letter name plus runtime-type check) to reach the `xxo` registry instance.
2. Reflects through `xxo.c` (same name + Map sanity check) to reach the cached map.
3. Filters entries by the requested `RepoCategory.name() + ":"` key prefix (matching the host's `xxo.y(category, name)` key-builder format) and returns an `ArrayList<WinEmuRepo>`.
4. Returns an empty `ArrayList` (preserving the original method's `Serializable` contract) on any failure, so a malformed or missing cache silently degrades to upstream behavior.

Online behavior is unchanged: the hook only fires on the original empty-list return path; any non-empty `Uaa` list still flows through the host's existing filter loop. 5.x impact is zero — 5.3.5 BannerHub uses a different `EmuComponents`-based picker that already works offline via `sp_winemu_all_components12`.

### `Debug logging`

A diagnostic patch that:

- Sets `android:debuggable="true"` in the `<application>` manifest so `Log.d` / `Log.v` lines from the patched APK reach `logcat`.
- Inserts `Log.i("GH600-DEBUG", ...)` markers along the import code path: Save ENTRY/CATCH (now keyed on `uu7.v` — was `xm7.u` in 6.0.0/6.0.1), transaction body ENTRY (`vs7.invokeSuspend` — was `el7.invokeSuspend`), both Room DAO insert PRE markers (`GameLaunchMethodDao.insert`, `GameLibraryBaseDao.insert`), and per-call markers in `FakeAuthToken.get()`, `FakeUserAccount.get()`, and `FakeStateFlow.{boolTrue,userFlow}()`.
- Hooks the global `i86.e()` `Throwable` swallower (was `odb.e()` in 6.0.0/6.0.1) to surface every exception that the app's Kotlin coroutine state machines would otherwise eat silently.

Kept in this release for ongoing device-side triage; safe to drop in a future release if you want a leaner build.

### `File manager access`

Adds an exposed `MTDataFiles` content provider (Java extension class shipped in the patches `.rve`) so external file managers like MT Manager can browse GameHub's per-app data directory without needing root. The provider's `android:authorities` and the wake-up activity's `android:taskAffinity` are derived from `packageNameOption.value` so each variant gets its own values — required to avoid `INSTALL_FAILED_CONFLICTING_PROVIDER` when two variants are installed side-by-side.

### `Rewrite custom permissions per variant`

Iterates the manifest's `<permission>` and `<uses-permission>` elements and rewrites any name starting `com.xiaoji.egggame.permission.` so the prefix matches the variant's package. The notable case is the Mob Push SDK's `C2D_MESSAGE` permission, which upstream declares directly. Without this rewrite, two installed variants both declaring `com.xiaoji.egggame.permission.C2D_MESSAGE` violate Android 7+'s rule against multiple packages declaring the same custom permission, and the second-installed variant gets rejected with `INSTALL_FAILED_DUPLICATE_PERMISSION` (UI surfaces this as the unhelpful "package conflicts with a current package" dialog). Reads `packageNameOption.value` directly rather than relying on patcher ordering against `Change package name`.

### `Change package name` *(per variant)*

Rewrites the APK's `<manifest package=…>` and `<application>` references to the variant's value listed in the table above, plus rewrites compatibility receiver permissions and exported provider authorities so they don't collide with the upstream package. Driven by the `packageName` option, set per matrix entry in `release.yml`. The workflow also passes `updatePermissions=true` and `updateProviders=true` so the upstream-baked `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and the 10 inherited provider authorities are renamed to the variant package as well.

### `Change app name` *(per variant)*

Rewrites `<application android:label=…>` to the variant's value listed in the table above. Driven by the `appName` option, set per matrix entry.

### Disabled-by-default options

A handful of generic patches from upstream `patches/all/misc/` are included but `use = false` (must be opted in via `revanced-cli -e <name>`):

- `Custom network security`, `Enable Android debugging`, `Override certificate pinning`, plus the `Change app name` / `Change package name` patches we explicitly enable per variant.

Available for ad-hoc CLI use; have no effect on the released APKs unless explicitly enabled.

</details>

## Build it yourself

```sh
git clone https://github.com/The412Banner/bannerhub-revanced.git
cd bannerhub-revanced
git checkout gamehub-604-build

# 1. Build the patch bundle
./gradlew build

# 2. Get the base APK
gh release download base-apk-604 \
  --repo The412Banner/bannerhub-revanced \
  --pattern "GameHub_6.0.4.apk" \
  --output GameHub_6.0.4.apk

# 3. Get ReVanced CLI
curl -L https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar \
  -o revanced-cli.jar

# 4. Patch it (single-variant example: Normal)
java -jar revanced-cli.jar patch GameHub_6.0.4.apk \
  --patches "$(find patches/build/libs -name '*.rvp' ! -name '*-sources*' ! -name '*-javadoc*' | head -1)" \
  --bypass-verification \
  -e "Change package name" -O 'packageName="banner.hub"' \
  -e "Change app name"     -O 'appName="GameHub"' \
  --out GameHub-6.0.4-Patched-Normal.apk
```

> **Note on `-O` quoting:** the JSON-string quotes around the value (`"…"` inside the single-quoted shell argument) are required. Picocli's `Map<String,Object>` parser auto-coerces values and trips on package names ending in `f`/`d`/`l` (Java numeric-literal suffixes — `com.tencent.tmgp.cf` is the canonical example).

## Releases

### Naming & versioning scheme

APK files follow the pattern **`BannerHub-V6-{version}-Patched-{variant}.apk`** — e.g. `BannerHub-V6-1.1.0-604-Patched-Normal.apk`. The version string has three parts:

- **BannerHub v6** — product name. Fixed; aligned with GameHub's 6.x series and stays put across upstream patch-version bumps.
- **`1.1.0`** — BannerHub-side semver (`major.minor.patch`). Tracks our own changes: new patches, infrastructure work, bug fixes. Bumps on every release.
- **`-604`** — GameHub base version with the dots stripped (`6.0.4` → `604`). Tells you which upstream GameHub APK was patched. When GameHub releases `6.0.5`, the suffix becomes `-605` and the same patch set can be retargeted (e.g. `v1.1.0-605`) without otherwise changing.

The release tag (`v1.1.0-604`) is the version string with a leading `v`. The `{variant}` slot in the filename identifies which of the 9 side-by-side packagings you grabbed.

### Pipeline modes

The release pipeline has two modes:

- **Prerelease (default)** — every tag push and every `workflow_dispatch` run with `stable=false` produces the 9 variant APKs as Actions artifacts only (14-day retention). Useful for device-testing without cluttering the Releases page.
- **Stable** — `workflow_dispatch` from `Actions → Run workflow` with the **`stable`** checkbox ticked and a version (e.g. `1.1.0-604`) populated. The matrix runs as normal, then a final `release` job creates a GitHub Release with the 9 APKs, `.rvp` bundle, `.rve` extension files, and the release notes (sourced verbatim from `release.yml`). All 9 APKs are re-signed with the BannerHub keystore (`v1`+`v2`+`v3` schemes) before upload so the cert is stable across releases.

## Repo layout

- `patches/src/main/kotlin/app/revanced/patches/` — patch sources. The active GameHub-6.0 patches live under `gamehub/`:
  - `misc/login/BypassLoginPatch.kt` — the bypass-login bytecode rewrites.
  - `misc/analytics/DisableCrashlyticsPatch.kt` — reverse-order Crashlytics removal.
  - `misc/sound/MuteUiSoundsPatch.kt` — silent-PCM resource swap.
  - `misc/apiredirect/RedirectCatalogApiPatch.kt` and `misc/apiredirect/PrefixApiPathPatch.kt` — Worker redirect + `/v6/` prefix.
  - `misc/offlinecache/OfflineComponentCachePatch.kt` — per-game-picker offline cache fallback.
  - `misc/debuglog/DebugLogPatch.kt` — debug-log probes.
  - `misc/permissions/RewriteCustomPermissionsPatch.kt` — per-variant rewrite of `com.xiaoji.egggame.permission.*`.
  - `filemanager/FileManagerAccessPatch.kt` — MTDataFiles provider patch.
  - `vibration/VibrationPatch.kt` — 4 bytecode hooks for XInput rumble routing (new in v1.1.0-604).
  - `vibration/VibrationManifestPatch.kt` — registers `BhVibrationSettingsActivity` (new in v1.1.0-604).
  - `vibration/VibrationLibPatch.kt` — copies `libevshim.so` into the APK's `lib/arm64-v8a/` (new in v1.1.0-604).
  - `vibration/VibrationMenuRowPatch.kt` — injects the PC Vibration Settings row into both per-game popups + the `Lxd3.l1` resolver short-circuit (new in v1.1.0-604).
  - `vibration/VibrationMenuLabelPatch.kt` — appends the menu-row label string to the `features.home` Compose resource bundle (new in v1.1.0-604).
  - `icon/ChangeAppIconPatch.kt` — replaces 5 in-APK drawables with BannerHub branding (new in v1.1.0-604).
  - `misc/extension/` — internal shared dependency that wires the `.rve` extension dex into the patched APK.
  - `all/misc/` — upstream ReVanced disabled-by-default patches (`appname`, `customcertificates`, `debugging`, `network`, `packagename`); the per-variant `Change app name` and `Change package name` are explicitly enabled by the workflow.
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/` — Java extension classes compiled into the `.rve` and injected into the patched APK at build time:
  - `login/FakeAuthToken.java`, `login/FakeUserAccount.java`, `login/FakeStateFlow.java` — reflective constructors used by `Bypass login`.
  - `api/V6PathPrefix.java` — the `Prefix API path with /v6` runtime helper.
  - `winemu/PickerCacheFallback.java` — the offline cache reader called by `Offline component cache fallback` (unreleased).
  - `debug/DebugTrace.java` — the `Log.i` helper used by `Debug logging` and the offline-cache patch.
  - `util/GHLog.java` — categorized `Log` tag helper (`GHL/Token`, `GHL/Net`, etc.) for selective debug logging.
  - `filemanager/MTDataFilesProvider.java`, `filemanager/MTDataFilesWakeUpActivity.java` — the file-manager content provider plus the immediate-finish activity that pre-creates the data dir.
- `extensions/gamehub/src/main/java/com/xj/winemu/vibration/` — vibration extension classes (new in v1.1.0-604):
  - `BhVibrationController.java` — the dispatcher state machine; per-slot motor amplitudes, keepalive worker, mode/intensity policy.
  - `BhVibrationSettingsActivity.java` — the per-game mode/intensity dialog.
  - `BhMenuRowClick.java` — reflective row constructors for both popup menus + resolver short-circuit + click handler.
- `native/evshim/` — the LD_PRELOAD shim that defeats SDL2's 1 s rumble auto-stop (new in v1.1.0-604). `evshim.c` (~700 lines C) + `CMakeLists.txt`; CI builds it via the runner's NDK before gradle.
- `keystore/` — checked-in public test keystore + README documenting alias, passwords, cert SHA-256/SHA-1, and the security model (new in v1.1.0-604).
- `assets/` — README assets (logo image).
- `extensions/gamehub/stub/` — compile-only host stubs (`com.winemu.openapi.WinUIBridge`, `com.xj.pcvirtualbtn.inputcontrols.InputControlsView`, `com.blankj.utilcode.util.Utils`, `com.winemu.core.server.XServer`) so the extension module type-checks against host symbols without packaging them into the `.rve`.
- `.github/workflows/release.yml` — the 3-job CI pipeline (`build` → 9-way `patch` matrix → `release`). The `patch` job re-signs every variant with the BannerHub keystore via `apksigner` (v1+v2+v3 schemes) so the cert is stable across releases. `.github/workflows/build_pull_request.yml` mirrors the build job for PRs.
- `PROGRESS_LOG.md` — chronological notes from the 6.0 port: every CI run, every patched smali method, every device-test result, every dead-end. The full investigation that produced this build.

## License

GPLv3 — same as upstream ReVanced.
