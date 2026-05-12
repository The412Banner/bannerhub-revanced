# BannerHub for ReVanced — GameHub 6.0

> *expect issues !  this is a work in progress and incomplete!*

A ReVanced patch bundle and pre-built APKs for [XiaoJi GameHub](https://www.gamehubglobal.com/) 6.0.2 (`com.xiaoji.egggame`) that **remove the login requirement, redirect the catalog API to the BannerHub Cloudflare Worker, mute UI sound feedback, and ship a debug-logging probe**, plus build-side variants that install side-by-side on the same device.

**Latest stable release:** [`v1.0.0-602` — Gamehub 6.0.2 - BannerHub API - Patched](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.0-602) — 9 ready-to-install APK variants + the `.rvp` patch bundle and `.rve` extension files for use with `revanced-cli`.

> ⚠ **A fresh install is required if a previous release is still installed.** Each release run generates a new debug keystore, so the signing certificate differs between releases and Android refuses the upgrade with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the previous version of the same variant first, then install the new one. (Within a single release, all 9 variants are signed with the same cert.)

## Unreleased on `gamehub-602-build` (next pre-release)

- **`Offline component cache fallback`** *(new patch)* — when the device is offline, the per-game pickers (GPU driver, DXVK, VKD3D, FEXCore, Box64, container) used to render only the embedded built-in versions, even though every cached catalog entry the user had ever seen online was already sitting in the on-disk `sp_winemu_unified_resources.xml`. Root cause: `eci.a(RepoCategory, Continuation)` short-circuits to Kotlin's `EmptyList` sentinel when the network-backed `Uaa` flow returns an empty list. The patch swaps that sentinel for an `invoke-static` into `PickerCacheFallback.fromXxo(...)`, which reflects through the `eci.a:Lxxo;` field to read the `xxo.c:ConcurrentHashMap` (already hydrated by `u6o.<init>` from disk at app start) and returns a category-filtered `ArrayList<WinEmuRepo>`. Online behavior is unchanged — the hook only fires on the original empty-list path; any non-empty `Uaa` list goes through the original filter loop as before. Fields are looked up by single-letter name (`a`, `c`) plus a runtime type sanity check, so it survives R8 letter shuffles between minor base APK bumps without source change. See [`OfflineComponentCachePatch.kt`](patches/src/main/kotlin/app/revanced/patches/gamehub/misc/offlinecache/OfflineComponentCachePatch.kt) for the structural-anchor recipes used to re-derive `eci`, `RepoCategory`, and `KOTLIN_EMPTY_LIST_CLASS` (currently `Lz85;`) on a fresh decompile.

## What's new in v1.0.0-602

- **Base APK refresh: GameHub 6.0.1 → 6.0.2** (versionCode 111 → 112). The 6.0.1 → 6.0.2 minor bump triggered another sweeping R8 letter reshuffle (every single class letter the bytecode patches keyed on was reassigned), so every anchor was re-derived against the new base via the structural-anchor recipes recorded in the patch sources. Same situation as 6.0.0 → 6.0.1; CI green on the first try this time, no silent-no-op surprises.
- **`Bypass login` re-anchored** — `AUTH_IMPL` `Lrs0;` → `Lit0;`, `AUTH_INTERFACE` `Lls0;` → `Lct0;`, `AUTH_TOKEN` `Lfdm;` → `Lkpm;`, `GAME_LIB_REPO` `Lhp7;` → `Luu7;` (its userId-getter renamed `f()` → `e()`), `NAVIGATOR` `Lade;` → `Lxle;`, `NAV_INTERCEPTOR` `Lar0;` → `Lrr0;`. The Login intent class is now `Lsa0;` (was `Lca0;` in 6.0.1) but the patch anchors on the iget instruction so it doesn't need an explicit ref.
- **`MutableStateFlow(value)` factory surgery** — the 6.0.0 / 6.0.1 patch could call `Lumn;->h(Object)Lt8k;` inline because its return type was directly assignable to the auth interface's StateFlow getters. In 6.0.2 the only one-arg factory (`Ltwo;->l(Object)Ltjk;`) returns `Ltjk;`, which is **not** assignable to the abstract StateFlow interface (`Lrjk;`) declared on `h()` / `e()` — the host wraps it in `Lhzh;` (which does implement `Lrjk;`) before exposing it. Doing the same wrap inline from smali would require growing the patched method's `.locals` from 0 to 2; instead introduced a new `FakeStateFlow.java` extension that performs the wrap via reflection and caches the result. The smali edit stays a one-line `invoke-static`.
- **`Redirect catalog API` re-anchored** — `ENV_ENUM_CLASS` `Lzhj;` → `Lxrj;`. Both Online enum hosts (`landscape-api-cn.vgabc.com`, `landscape-api-oversea.vgabc.com`) still redirect to `bannerhub-api.the412banner.workers.dev`.
- **`Prefix API path with /v6` re-anchored** — `Lohb;->b(Lj1a;String)V` → `Lvob;->b(Lm7a;String)V`. Same anchor shape (iget on the builder's URL field + `Lpll;->s1` string-trim).
- **`Debug logging` re-anchored** — `Lodb;` → `Li86;` (y2d-impl), `Lxm7;->u` → `Luu7;->v` (game-import save), `Ly4i;` → `Lyji;` (RetroGameDao.upsert wrapper), `Ly2d;` → `Lpgd;` (y2d-interface), `Lel7;` → `Lvs7;` (inner Room transaction). Probe semantics unchanged — same logcat tag, same trace markers.
- **No-touch patches** — `Disable Firebase Crashlytics`, `Mute UI sounds`, `File manager access`, `Rewrite custom permissions per variant`, `Change package name`, `Change app name` are all anchored on full Android / Firebase / asset-path / manifest names that R8 doesn't touch, so they apply byte-for-byte without any source change.

### 🆕 Inherited from upstream GameHub 6.0.2

XiaoJi marks 6.0.2 as the official worldwide launch of GameHub. Three headline upgrades bundled with the 6.0.2 base APK that you get automatically by using this build:

1. **Landscape / portrait auto-rotation across the UI** — first-ever rotation support in GameHub; one-tap seamless layout switching optimized for both PC widescreen and mobile / portable form factors.
2. **Epic Games Store integration + Retro module** — full Epic Games support (sync your Epic library, launch from inside GameHub) and a dedicated retro section for arcade and classic-console titles.
3. **AI Super Frame Interpolation in the PC emulator** — runtime frame-interpolation pipeline that lifts effective framerate above native with low added latency for smoother PC-game playback on Android.

(Source: XiaoJi's official 6.0.2 announcement.)

## What's new in v1.0.1-601 (historical hotfix)

- **`Bypass login`, `Redirect catalog API`, and `Prefix API path with /v6`** — all three were silently no-op on v1.0.0-601 because R8 in the 6.0.1 base APK reshuffled class letters between 6.0.0 and 6.0.1. The patcher's class-letter-keyed fingerprints matched unrelated classes (the patcher reported success but the methods were never actually rewritten). All three were re-anchored against the 6.0.1 letters; structural-anchor comments were added at the top of each patch source so future minor-version bumps are easy to track down — and indeed those structural anchors are exactly what made the 6.0.2 re-derivation routine.

## What's new in v1.0.0-601 (historical)

- **Base APK refresh: GameHub 6.0.0 → 6.0.1** (versionCode 110 → 111). Re-targeted the same patch bundle at the new XiaoJi base APK. *Three of those patches turned out to silently no-op on v1.0.0-601 due to R8 reshuffling — see v1.0.1-601 above for the fix.*

## What's new in v1.0.1-600 (historical)

- **`File manager access`** — the MTDataFiles `<provider android:authorities>` and wake-up activity `android:taskAffinity` are now derived per-variant from `packageNameOption.value`, instead of being baked at `com.xiaoji.egggame.*` for every variant.
- **`Rewrite custom permissions per variant`** *(new patch)* — rewrites the `com.xiaoji.egggame.permission.C2D_MESSAGE` declaration (and any other upstream-baked custom permission with the same prefix) so each variant's permission name is namespaced to that variant's package. Without this, Android 7+ rejects the second-installed variant with `INSTALL_FAILED_DUPLICATE_PERMISSION`.
- **`Change package name` invocation** — the `release.yml` workflow now passes `-O 'updatePermissions=true'` and `-O 'updateProviders=true'` to the upstream patch, so the 10 inherited provider authorities (Mob, file, Fly, utilcode, Firebase, AndroidContext, androidx-startup, filekit, fileprovider, wbsdk) and the `signature`-protected `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` are all rewritten to the variant package.

---

## What this is

GameHub 6.0 (the KMP rewrite under the package `com.xiaoji.egggame`) gates the entire game-library flow behind a login screen, ships with bundled UI feedback sounds, and hits XiaoJi's `landscape-api-{cn,oversea}.vgabc.com` catalog endpoints for the component (driver / DXVK / FEX / Wine prefix / firmware) registry that drives every game launch. This patch bundle changes all three:

- **No login** — six bytecode rewrites short-circuit the auth gate so a fresh install lands on the home screen, the **Import → Save** dialog persists rows to the on-device Room database (`db_game_library.db`), and the imported games appear in the library list — all without ever logging in or hitting the upstream auth endpoint.
- **Catalog redirect to the BannerHub Cloudflare Worker** — both `landscape-api-*.vgabc.com` hosts on the `xrj` `Online` enum value are swapped for `bannerhub-api.the412banner.workers.dev`, and a single chokepoint helper (`vob.b`) is hooked to prefix every relative API call with `v6/`. The Worker uses the prefix to serve 6.0-specific response shapes (firmware 1.3.5, `EnvListData` wrapper required by 6.0's kotlinx-strict deserializer, etc.) while a parallel 5.x branch keeps the upstream shape for older clients.
- **Muted UI sounds** — bundled menu/click `.wav` assets are replaced with silent PCM at packaging time, no runtime audio routing is touched.

It also fixes a launch-time `VerifyError` that the original 5.x `Disable Crashlytics` patch caused on 6.0, ships a diagnostic `Debug logging` probe (kept for ongoing triage convenience even though the import flow is confirmed stable end-to-end), and includes an unrelated convenience patch (`File manager access`) that exposes a content provider for browsing GameHub's data dir from external file managers.

## ⚠ Known limitations — please read

- **Steam game launches via the standard Steam client are likely broken.** Redirecting the catalog API to the BannerHub Worker changes which Steam client component the host resolves at launch. If your Steam games stop launching after upgrading, switch to the **Lightweight Steam client** in the picker — it's the variant that pairs cleanly with the BannerHub catalog. The standard Steam client may still work for some titles, but Lightweight should be your default on this build.
- ~~**Imported games have no cover art by default.**~~ **Fixed 2026-05-11 in the BannerHub Worker** (deploy `5fd6c6a7…`). The PC-EXE import recognition call (`/simulator/getLocalGameDetail`) was falling through to the worker's anonymous-passthrough path, so upstream returned an empty `LocalGameInfoSvrEntity` and imported games landed with no artwork. The endpoint is now on the same authenticated-proxy branch as the vjoy/Scheme endpoints — client headers are forwarded and the shared `bannerhub_token` is injected, so upstream returns populated `logo`/`cover_image`/`back_image`/`hero_capsule`/`square_image` fields. **No APK rebuild required** — the fix is server-side and applies retroactively to every existing patched build.

## Source

- **Base APK:** `GameHub_6.0.2.apk` — the official 6.0.2 global build (versionCode 112), attached unmodified to the [`base-apk-602`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-602) release for reproducibility. Earlier base APKs remain attached to [`base-apk-601`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-601) (6.0.1) and [`base-apk-600`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-600) (6.0.0) for older releases.
- **Patcher:** [ReVanced CLI 6.0.0](https://github.com/ReVanced/revanced-cli/releases/tag/v6.0.0) + the bundle built from this repo's `gamehub-602-build` branch (`gamehub-601-build` and `gamehub-600-build` remain in place for older 6.0.x work).
- **Catalog backend:** [`The412Banner/bannerhub-api`](https://github.com/The412Banner/bannerhub-api) — Cloudflare Worker source, deployed at `bannerhub-api.the412banner.workers.dev`. Serves the curated component catalog from GitHub Pages and forwards unallowlisted paths back to upstream `landscape-api.vgabc.com` with the original signed-request behavior preserved.
- **Build environment:** GitHub Actions, Ubuntu 24.04 runner, Temurin JDK 17. The full pipeline is [`.github/workflows/release.yml`](.github/workflows/release.yml): a `build` job produces the `.rvp` patch bundle, a 9-way matrix patches the base APK in parallel (one variant per matrix entry), and a final `release` job globs all artefacts into a single GitHub Release when triggered with `stable=true`.

## Variants

The same patch bundle is applied to the same base APK 9 times, each time with a different package name + launcher label so the variants install **side-by-side** on the same device. The `Original` variant keeps the upstream package name `com.xiaoji.egggame` and so **replaces** an installed GameHub on install; everything else coexists.

| Variant | APK file | Package | Launcher label |
| --- | --- | --- | --- |
| Normal | `GameHub-6.0.2-Patched-Normal.apk` | `banner.hub` | GameHub |
| Normal (GHL) | `GameHub-6.0.2-Patched-Normal.GHL.apk` *(GitHub strips parentheses from `Normal(GHL)`)* | `gamehub.lite` | GameHub |
| PuBG | `GameHub-6.0.2-Patched-PuBG.apk` | `com.tencent.ig` | GameHub PuBG |
| AnTuTu | `GameHub-6.0.2-Patched-AnTuTu.apk` | `com.antutu.ABenchMark` | GameHub AnTuTu |
| alt-AnTuTu | `GameHub-6.0.2-Patched-alt-AnTuTu.apk` | `com.antutu.benchmark.full` | GameHub AnTuTu |
| PuBG-CrossFire | `GameHub-6.0.2-Patched-PuBG-CrossFire.apk` | `com.tencent.tmgp.cf` | GameHub PuBG CrossFire |
| Ludashi | `GameHub-6.0.2-Patched-Ludashi.apk` | `com.ludashi.aibench` | GameHub Ludashi |
| Genshin | `GameHub-6.0.2-Patched-Genshin.apk` | `com.miHoYo.GenshinImpact` | GameHub Genshin |
| Original | `GameHub-6.0.2-Patched-Original.apk` | `com.xiaoji.egggame` | GameHub |

## Patches applied

This bundle ships only patches that successfully apply against GameHub 6.0. Every patch below appears as an individually-named, individually-toggleable entry in the published `.rvp` bundle (`revanced-cli list-patches patches.rvp` to enumerate; `--include` / `--exclude` to pick).

### `Bypass login`

Skips the login screen entirely and makes the library system function under a synthetic identity. Six bytecode rewrites cooperate (R8 letter map shown at 6.0.2 — the same patch logic was already used on 6.0.0 / 6.0.1 with different letter names, see the per-patch source comments for the full version history):

1. **`xle.i(gi0)` and `xle.r(gi0)`** — the navigator methods that gate Login routing. Original logic does `iget Lxle;->b:Lct0;` → `invoke-interface Lct0;->a()Z` → `if-nez :skipLogin` → otherwise build a `Lsa0;` Login navigation intent. Patch removes the `invoke-interface`/`move-result` pair and substitutes `const/4 vN, 0x1` so the branch is always taken.
2. **`rr0.a(...)`** — a separate `NavigationInterceptor` (`getOrder()==10`) added in 6.0.1 that gates on `Lct0;->a()Z` independently of the navigator. Same iget+invoke-interface+if-nez pattern; bypassed identically with `const/4 vN, 0x1`.
3. **`it0.h()`** — the real DB-backed `Lct0;` implementation's `isLoggedIn` `StateFlow<Boolean?>`. Body replaced to return `FakeStateFlow.boolTrue()` (a host-compatible `Lhzh;` wrapping `Ltjk;(Boolean.TRUE)`, built via reflection in the Java extension and cached) so every collector — `NavHost.collectAsState`, the listener, the analytics pipeline — sees a logged-in state.
4. **`it0.e()`** — the user-account `StateFlow<fpm?>`. Without an `auth_token` row in the DB this emits `null` and the library-list reader's `flatMapLatest` collapses to an empty `Flow`. Patch replaces the body with `FakeStateFlow.userFlow()` where the underlying value is `FakeUserAccount.get()`, a Java extension that reflectively constructs `Lfpm;` via `Class.forName("fpm").getDeclaredConstructor(...)`, with `a="99999"` and every other field zeroed/empty. Result: the library reader's pipeline `it0.e().flatMapLatest { fpm -> dao.subjectAllByUserId(fpm.a) }` always queries with `user_id="99999"` and returns the imported rows.
5. **`uu7.e()`** — the `GameLibraryRepository`'s user-id getter (was `hp7.f()` in 6.0.1; the method was renamed between minor versions). Returns `"99999"` directly so it matches the synthetic user account.
6. **`ct0.f()`** — the interface default method that returns the current `kpm` auth token. Body replaced to call `FakeAuthToken.get()`, a Java extension that reflectively constructs `Lkpm;` with `a="99999"` and `b=""`. Several lambdas use this directly to read the user-id for network-prep and lambda capture; under the bypass they all see the same synthetic identity.

End-to-end consequence: a fresh install lands on the home screen, the **Import** dialog opens, picking an APK + metadata + tapping **Save** persists a row into `/data/data/<pkg>/databases/db_game_library.db` (`t_game_library_base`, `t_game_launch_method`), and the row appears in the library list immediately because the read pipeline is now keyed off the matching synthetic user-id.

### `Disable Firebase Crashlytics`

Removes the Firebase Crashlytics initialisation block. Without this, GameHub 6.0.2 crashes on launch with `VerifyError`. Root cause: the upstream 5.x patch used `goto` to skip the Crashlytics call site, which in 6.0 leaves a join-point where the same register holds either `String` (goto path) or `Boolean` (fall-through path) and the ART verifier rejects it. The 6.0-compatible patch removes the three Crashlytics instructions in **reverse index order** (`setCrashlyticsCollectionEnabled`, `move-result-object`, `invoke-static getInstance`) so the intermediate `const/4 v2, 0x0` redefines the register with a consistent `Boolean` type at the join point. Anchored on `Lcom/xiaoji/egggame/BaseAndroidApp;->onCreate` plus full Firebase class names, so it ports across versions without source change.

### `Mute UI sounds`

Replaces the bundled UI feedback sounds (`assets/composeResources/com.xiaoji.egggame.core/files/sound/*.wav`) with silent PCM. Menu navigation and button taps stop clicking. The patch substitutes the resource at packaging time — no runtime audio routing is changed, so game audio is unaffected. The patch's resource lookup is anchored on a Kotlin `object {}` to give the classloader a stable handle (the alternative — anchoring on the patch class itself — fails when ReVanced's class loader can't see the patches module's resources from inside the runner JVM).

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

### `Offline component cache fallback` *(unreleased — on `gamehub-602-build` after v1.0.0-602)*

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

## Build it yourself

```sh
git clone https://github.com/The412Banner/bannerhub-revanced.git
cd bannerhub-revanced
git checkout gamehub-602-build

# 1. Build the patch bundle
./gradlew build

# 2. Get the base APK
gh release download base-apk-602 \
  --repo The412Banner/bannerhub-revanced \
  --pattern "GameHub_6.0.2.apk" \
  --output GameHub_6.0.2.apk

# 3. Get ReVanced CLI
curl -L https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar \
  -o revanced-cli.jar

# 4. Patch it (single-variant example: Normal)
java -jar revanced-cli.jar patch GameHub_6.0.2.apk \
  --patches "$(find patches/build/libs -name '*.rvp' ! -name '*-sources*' ! -name '*-javadoc*' | head -1)" \
  --bypass-verification \
  -e "Change package name" -O 'packageName="banner.hub"' \
  -e "Change app name"     -O 'appName="GameHub"' \
  --out GameHub-6.0.2-Patched-Normal.apk
```

> **Note on `-O` quoting:** the JSON-string quotes around the value (`"…"` inside the single-quoted shell argument) are required. Picocli's `Map<String,Object>` parser auto-coerces values and trips on package names ending in `f`/`d`/`l` (Java numeric-literal suffixes — `com.tencent.tmgp.cf` is the canonical example).

## Releases

The release pipeline has two modes:

- **Prerelease (default)** — every tag push and every `workflow_dispatch` run produces the 9 variant APKs as Actions artifacts only. Useful for testing without cluttering the Releases page.
- **Stable** — `workflow_dispatch` from `Actions → Run workflow` with the **`stable`** checkbox ticked and a tag (e.g. `v1.0.0-602`) populated. The matrix runs as normal, then a final `release` job creates a GitHub Release with the 9 APKs, `.rvp` bundle, `.rve` extension files, and the release notes (sourced verbatim from `release.yml`).

## Repo layout

- `patches/src/main/kotlin/app/revanced/patches/` — patch sources. The active GameHub-6.0 patches live under `gamehub/`:
  - `misc/login/BypassLoginPatch.kt` — the bypass-login bytecode rewrites.
  - `misc/analytics/DisableCrashlyticsPatch.kt` — reverse-order Crashlytics removal.
  - `misc/sound/MuteUiSoundsPatch.kt` — silent-PCM resource swap.
  - `misc/apiredirect/RedirectCatalogApiPatch.kt` and `misc/apiredirect/PrefixApiPathPatch.kt` — Worker redirect + `/v6/` prefix.
  - `misc/offlinecache/OfflineComponentCachePatch.kt` — per-game-picker offline cache fallback (unreleased on `gamehub-602-build`).
  - `misc/debuglog/DebugLogPatch.kt` — debug-log probes.
  - `misc/permissions/RewriteCustomPermissionsPatch.kt` — per-variant rewrite of `com.xiaoji.egggame.permission.*`.
  - `filemanager/FileManagerAccessPatch.kt` — MTDataFiles provider patch.
  - `misc/extension/` — internal shared dependency that wires the `.rve` extension dex into the patched APK.
  - `all/misc/` — upstream ReVanced disabled-by-default patches (`appname`, `customcertificates`, `debugging`, `network`, `packagename`); the per-variant `Change app name` and `Change package name` are explicitly enabled by the workflow.
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/` — Java extension classes compiled into the `.rve` and injected into the patched APK at build time:
  - `login/FakeAuthToken.java`, `login/FakeUserAccount.java`, `login/FakeStateFlow.java` — reflective constructors used by `Bypass login`.
  - `api/V6PathPrefix.java` — the `Prefix API path with /v6` runtime helper.
  - `winemu/PickerCacheFallback.java` — the offline cache reader called by `Offline component cache fallback` (unreleased).
  - `debug/DebugTrace.java` — the `Log.i` helper used by `Debug logging` and the offline-cache patch.
  - `util/GHLog.java` — categorized `Log` tag helper (`GHL/Token`, `GHL/Net`, etc.) for selective debug logging.
  - `filemanager/MTDataFilesProvider.java`, `filemanager/MTDataFilesWakeUpActivity.java` — the file-manager content provider plus the immediate-finish activity that pre-creates the data dir.
- `extensions/gamehub/stub/` — compile-only host stubs (`com.winemu.openapi.WinUIBridge`, `com.xj.pcvirtualbtn.inputcontrols.InputControlsView`, `com.blankj.utilcode.util.Utils`, `com.winemu.core.server.XServer`) so the extension module type-checks against host symbols without packaging them into the `.rve`.
- `.github/workflows/release.yml` — the 3-job CI pipeline (`build` → 9-way `patch` matrix → `release`). `.github/workflows/build_pull_request.yml` mirrors the build job for PRs.
- `PROGRESS_LOG.md` — chronological notes from the 6.0 port: every CI run, every patched smali method, every device-test result, every dead-end. The full investigation that produced this build.

## License

GPLv3 — same as upstream ReVanced.
