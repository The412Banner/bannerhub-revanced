# BannerHub for ReVanced — GameHub 6.0

A ReVanced patch bundle and pre-built APKs for [XiaoJi GameHub](https://www.gamehubglobal.com/) 6.0.1 (`com.xiaoji.egggame`) that **remove the login requirement, redirect the catalog API to the BannerHub Cloudflare Worker, mute UI sound feedback, and ship a debug-logging probe**, plus build-side variants that install side-by-side on the same device.

**Latest stable release:** [`v1.0.1-601` — Gamehub 6.0.1 - BannerHub API - Patched](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.1-601) — 9 ready-to-install APK variants + the `.rvp` patch bundle and `.rve` extension files for use with `revanced-cli`.

> ⚠ **v1.0.0-601 was broken** — it shipped with `Bypass login` + `Redirect catalog API` + `Prefix API path` all silently no-op'd because R8 in the 6.0.1 base APK reshuffled class letters and the patcher's fingerprints matched unrelated classes (the patcher reported success but the rewrites never landed). Use **v1.0.1-601** instead — same 9 patches, all correctly applied.

> ⚠ **A fresh install is required if a previous release is still installed.** Each release run generates a new debug keystore, so the signing certificate differs between releases and Android refuses the upgrade with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the previous version of the same variant first, then install the new one. (Within a single release, all 9 variants are signed with the same cert.)

## What's new in v1.0.1-601 (hotfix)

- **`Bypass login`, `Redirect catalog API`, and `Prefix API path with /v6`** — all three were silently no-op on v1.0.0-601 because R8 in the 6.0.1 base APK reshuffled class letters between 6.0.0 and 6.0.1. The patcher's class-letter-keyed fingerprints matched unrelated classes (the patcher reported success but the methods were never actually rewritten). All three patches now re-anchored against the new 6.0.1 letters; structural-anchor comments added at the top of each patch source so future minor-version bumps are easy to track down.

## What's new in v1.0.0-601

- **Base APK refresh: GameHub 6.0.0 → 6.0.1** (versionCode 110 → 111). Re-targets the same patch bundle at the new XiaoJi base APK.
- Functional behaviour intended to be unchanged from v1.0.1-600 — same 9 patches, same multi-install fix, on top of a newer base. *Three of those patches turned out to silently no-op due to R8 reshuffling — see v1.0.1-601 above for the fix.*

### 🆕 Inherited from upstream GameHub 6.0.1

XiaoJi-side improvements that came with the 6.0.1 base APK; you get them automatically by using this build:

1. Improved Steam login and game download stability
2. Faster Steam game launch speeds
3. Virtual button layouts can now be switched dynamically via keypress
4. Multiple new touchscreen input methods
5. Gyroscope support for camera control
6. AI frame generation now supported across all games
7. Numerous bug fixes

## What's new in v1.0.1-600 (historical)

- **`File manager access`** — the MTDataFiles `<provider android:authorities>` and wake-up activity `android:taskAffinity` are now derived per-variant from `packageNameOption.value`, instead of being baked at `com.xiaoji.egggame.*` for every variant.
- **`Rewrite custom permissions per variant`** *(new patch)* — rewrites the `com.xiaoji.egggame.permission.C2D_MESSAGE` declaration (and any other upstream-baked custom permission with the same prefix) so each variant's permission name is namespaced to that variant's package. Without this, Android 7+ rejects the second-installed variant with `INSTALL_FAILED_DUPLICATE_PERMISSION`.
- **`Change package name` invocation** — the `release.yml` workflow now passes `-O 'updatePermissions=true'` and `-O 'updateProviders=true'` to the upstream patch, so the 10 inherited provider authorities (Mob, file, Fly, utilcode, Firebase, AndroidContext, androidx-startup, filekit, fileprovider, wbsdk) and the `signature`-protected `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` are all rewritten to the variant package.

---

## What this is

GameHub 6.0 (the KMP rewrite under the package `com.xiaoji.egggame`) gates the entire game-library flow behind a login screen, ships with bundled UI feedback sounds, and hits XiaoJi's `landscape-api-{cn,oversea}.vgabc.com` catalog endpoints for the component (driver / DXVK / FEX / Wine prefix / firmware) registry that drives every game launch. This patch bundle changes all three:

- **No login** — five bytecode rewrites short-circuit the auth gate so a fresh install lands on the home screen, the **Import → Save** dialog persists rows to the on-device Room database (`db_game_library.db`), and the imported games appear in the library list — all without ever logging in or hitting the upstream auth endpoint.
- **Catalog redirect to the BannerHub Cloudflare Worker** — both `landscape-api-*.vgabc.com` hosts on the `zhj` `Online` enum value are swapped for `bannerhub-api.the412banner.workers.dev`, and a single chokepoint helper (`ohb.b`) is hooked to prefix every relative API call with `v6/`. The Worker uses the prefix to serve 6.0-specific response shapes (firmware 1.3.5, `EnvListData` wrapper required by 6.0's kotlinx-strict deserializer, etc.) while a parallel 5.x branch keeps the upstream shape for older clients.
- **Muted UI sounds** — bundled menu/click `.wav` assets are replaced with silent PCM at packaging time, no runtime audio routing is touched.

It also fixes a launch-time `VerifyError` that the original 5.x `Disable Crashlytics` patch caused on 6.0, ships a diagnostic `Debug logging` probe (will be removed in a follow-up build now that the import flow is confirmed stable end-to-end), and includes an unrelated convenience patch (`File manager access`) that exposes a content provider for browsing GameHub's data dir from external file managers.

## ⚠ Known limitations — please read

- **Steam game launches via the standard Steam client are likely broken.** Redirecting the catalog API to the BannerHub Worker changes which Steam client component the host resolves at launch. If your Steam games stop launching after upgrading, switch to the **Lightweight Steam client** in the picker — it's the variant that pairs cleanly with the BannerHub catalog. The standard Steam client may still work for some titles, but Lightweight should be your default on this build.
- **Imported games have no cover art by default.** When you add a game via Import, no banner / cover / hero artwork is fetched automatically. Open the imported game's edit screen and set the artwork manually (cover, banner, hero, logo as applicable). The game itself is fully importable and launchable without artwork — this is purely cosmetic.

## Source

- **Base APK:** `GameHub_6.0.1.apk` — the official 6.0.1 global build (versionCode 111), attached unmodified to the [`base-apk-601`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-601) release for reproducibility. The previous 6.0.0 base APK remains attached to [`base-apk-600`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-600) for older releases.
- **Patcher:** [ReVanced CLI 6.0.0](https://github.com/ReVanced/revanced-cli/releases/tag/v6.0.0) + the bundle built from this repo's `gamehub-601-build` branch (`gamehub-600-build` remains the rolling default for older 6.0.0 work).
- **Catalog backend:** [`The412Banner/bannerhub-api`](https://github.com/The412Banner/bannerhub-api) — Cloudflare Worker source, deployed at `bannerhub-api.the412banner.workers.dev`. Serves the curated component catalog from GitHub Pages and forwards unallowlisted paths back to upstream `landscape-api.vgabc.com` with the original signed-request behavior preserved.
- **Build environment:** GitHub Actions, Ubuntu 24.04 runner, Temurin JDK 17. The full pipeline is [`.github/workflows/release.yml`](.github/workflows/release.yml): a `build` job produces the `.rvp` patch bundle, a 9-way matrix patches the base APK in parallel (one variant per matrix entry), and a final `release` job globs all artefacts into a single GitHub Release when triggered with `stable=true`.

## Variants

The same patch bundle is applied to the same base APK 9 times, each time with a different package name + launcher label so the variants install **side-by-side** on the same device. The `Original` variant keeps the upstream package name `com.xiaoji.egggame` and so **replaces** an installed GameHub on install; everything else coexists.

| Variant | APK file | Package | Launcher label |
| --- | --- | --- | --- |
| Normal | `GameHub-6.0.1-Patched-Normal.apk` | `banner.hub` | GameHub |
| Normal (GHL) | `GameHub-6.0.1-Patched-Normal.GHL.apk` *(GitHub strips parentheses from `Normal(GHL)`)* | `gamehub.lite` | GameHub |
| PuBG | `GameHub-6.0.1-Patched-PuBG.apk` | `com.tencent.ig` | GameHub PuBG |
| AnTuTu | `GameHub-6.0.1-Patched-AnTuTu.apk` | `com.antutu.ABenchMark` | GameHub AnTuTu |
| alt-AnTuTu | `GameHub-6.0.1-Patched-alt-AnTuTu.apk` | `com.antutu.benchmark.full` | GameHub AnTuTu |
| PuBG-CrossFire | `GameHub-6.0.1-Patched-PuBG-CrossFire.apk` | `com.tencent.tmgp.cf` | GameHub PuBG CrossFire |
| Ludashi | `GameHub-6.0.1-Patched-Ludashi.apk` | `com.ludashi.aibench` | GameHub Ludashi |
| Genshin | `GameHub-6.0.1-Patched-Genshin.apk` | `com.miHoYo.GenshinImpact` | GameHub Genshin |
| Original | `GameHub-6.0.1-Patched-Original.apk` | `com.xiaoji.egggame` | GameHub |

## Patches applied

This bundle ships only patches that successfully apply against GameHub 6.0. Every patch below appears as an individually-named, individually-toggleable entry in the published `.rvp` bundle (`revanced-cli list-patches patches.rvp` to enumerate; `--include` / `--exclude` to pick).

### `Bypass login`

Skips the login screen entirely and makes the library system function under a synthetic identity. Five bytecode rewrites cooperate:

1. **`ade.i(ph0)` and `ade.r(ph0)`** — the navigator methods that gate Login routing. Original logic does `iget Lade;->b:Lls0;` → `invoke-interface Lls0;->a()Z` → `if-nez :skipLogin` → otherwise build a Login navigation intent. Patch removes the `invoke-interface`/`move-result` pair and substitutes `const/4 vN, 0x1` so the branch is always taken.
2. **`ar0.a(...)`** — a separate NavigationInterceptor (`getOrder()==10`) added in 6.0.1 that gates on `Lls0;->a()Z` independently of the navigator. Same iget+invoke-interface+if-nez pattern; bypassed identically with `const/4 vN, 0x1`.
3. **`rs0.h()`** — the real DB-backed `Lls0;` implementation's `isLoggedIn` `StateFlow<Boolean?>`. Body replaced to return `umn.h(Boolean.TRUE)` (a fresh `MutableStateFlow` over `TRUE`) so every collector — `NavHost.collectAsState`, the listener, the analytics pipeline — sees a logged-in state.
4. **`rs0.e()`** — the user-account `StateFlow<adm?>`. Without an `auth_token` row in the DB this emits `null` and the library-list reader's `flatMapLatest` collapses to an empty `Flow`. Patch replaces the body with `umn.h(FakeUserAccount.get())` where `FakeUserAccount` is a Java extension that reflectively constructs `Ladm;` via `Class.forName("adm").getDeclaredConstructor(...)`, with `a="99999"` and every other field zeroed/empty. Result: the library reader's pipeline `rs0.e().flatMapLatest { adm -> dao.subjectAllByUserId(adm.a) }` always queries with `user_id="99999"` and returns the imported rows.
5. **`hp7.f()`** — the `GameLibraryRepository`'s user-id getter, called from both write (Save flow early-bail) and read paths. Returns `"99999"` directly so it matches the synthetic user account.
6. **`ls0.f()`** — the interface default method that returns the current `fdm` auth token. Body replaced to call `FakeAuthToken.get()`, a Java extension that reflectively constructs `Lfdm;` with `a="99999"` and `b=""`. Several lambdas use this directly to read the user-id for network-prep and lambda capture; under the bypass they all see the same synthetic identity.

End-to-end consequence: a fresh install lands on the home screen, the **Import** dialog opens, picking an APK + metadata + tapping **Save** persists a row into `/data/data/<pkg>/databases/db_game_library.db` (`t_game_library_base`, `t_game_launch_method`), and the row appears in the library list immediately because the read pipeline is now keyed off the matching synthetic user-id.

### `Disable Firebase Crashlytics`

Removes the Firebase Crashlytics initialisation block. Without this, GameHub 6.0.1 crashes on launch with `VerifyError`. Root cause: the upstream 5.x patch used `goto` to skip the Crashlytics call site, which in 6.0 leaves a join-point where the same register holds either `String` (goto path) or `Boolean` (fall-through path) and the ART verifier rejects it. The 6.0-compatible patch removes the three Crashlytics instructions in **reverse index order** (`setCrashlyticsCollectionEnabled`, `move-result-object`, `invoke-static getInstance`) so the intermediate `const/4 v2, 0x0` redefines the register with a consistent `Boolean` type at the join point.

### `Mute UI sounds`

Replaces the bundled UI feedback sounds (`assets/.../sound/*.wav`) with silent PCM. Menu navigation and button taps stop clicking. The patch substitutes the resource at packaging time — no runtime audio routing is changed, so game audio is unaffected. The patch's resource lookup is anchored on a Kotlin `object {}` to give the classloader a stable handle (the alternative — anchoring on the patch class itself — fails when ReVanced's class loader can't see the patches module's resources from inside the runner JVM).

### `Redirect catalog API`

Patches the `zhj` environment enum's `Online` value so the catalog API's `cnHost` and `overseaHost` both point at the BannerHub Cloudflare Worker (`bannerhub-api.the412banner.workers.dev`) instead of `landscape-api-{cn,oversea}.vgabc.com`. The Worker:

- Serves a curated component catalog from `the412banner.github.io/bannerhub-api/` for `simulator/v2/*` and other allowlisted paths (drivers, DXVK, VKD3D, FEX, Box64, Wine prefix, firmware metadata).
- Reshapes responses for 6.0's kotlinx-strict deserializer (wraps `getAllComponentList` data in `EnvListData` `{list, page, page_size, total}` instead of a bare array — without this the `l13.smali:861` cast silently fails and the in-memory COMPONENT registry stays empty, breaking game launch at "Download Game Config").
- Token-injects + signature-regens forwards for any unallowlisted path back to `landscape-api.vgabc.com` so anything not curated still works against the original upstream.
- Branches 6.0-only response variants behind a `/v6/` path prefix (see next patch); 5.x clients hitting the same Worker without the prefix get the upstream-shaped pass-through.

The Beta + Test enum values, the analytics hosts (`landscape-api-*-*.vgabc.com/events`), `clientgsw.vgabc.com`, and the bigeyes CDN are all intentionally untouched — only the curated-catalog hosts are swapped.

**Side benefit (PC game settings orientation):** the per-game **PC game settings** screen now renders correctly in both landscape *and* portrait orientation. Upstream's catalog response carried a constraint that the host honored by locking that screen to landscape only — the BannerHub Worker's payload doesn't include that constraint, so the picker is usable from a portrait-held phone for the first time. This is a behavioral byproduct of the catalog redirect, not a separate patch.

### `Prefix API path with /v6`

Hooks `ohb.b(j1a builder, String path)` — the single static Ktor URL-builder helper through which every relative GameHub API request flows — and prepends `v6/` via the small `V6PathPrefix.prefix()` Java extension. The Worker strips the prefix and uses it as a feature gate so the same backend can serve 6.0 and 5.x clients side-by-side without divergent state:

- `/v6/simulator/v2/getAllComponentList` → `EnvListData`-wrapped response, reshaped for 6.0 (`is_ui` / `gpu_range` stripped, `fileType` / `framework` / `framework_type` / `is_steam` / `status` / `blurb` / `upgrade_msg` / `sub_data` / `base` injected, `base.fileType=0`).
- `/simulator/v2/getAllComponentList` (no prefix, from a 5.x client) → native upstream catalog passed through with `is_ui` / `gpu_range` preserved.
- `/v6/simulator/v2/getImagefsDetail` → firmware 1.3.5. Without prefix → firmware 1.3.3.

Full URLs (paths already starting with `http://` or `https://`) are short-circuited by the helper and pass through untouched, so direct downloads from the catalog's `download_url` fields still resolve to the Worker-authored GitHub-release URLs without the prefix being injected into them.

### `Debug logging`

A diagnostic patch that:

- Sets `android:debuggable="true"` in the `<application>` manifest so `Log.d` / `Log.v` lines from the patched APK reach `logcat`.
- Inserts `Log.i("GH600-DEBUG", ...)` markers along the import code path: Save ENTRY/CATCH, transaction body ENTRY, both Room DAO insert PRE markers (`GameLaunchMethodDao.insert`, `GameLibraryBaseDao.insert`), and per-call markers in `FakeAuthToken.get()` and `FakeUserAccount.get()`.
- Hooks the global `odb.e()` `Throwable` swallower to surface every exception that the app's Kotlin coroutine state machines would otherwise eat silently.

Kept in this release for ongoing device-side triage; will be dropped from a future release now that the import flow is confirmed stable end-to-end.

### `File manager access`

Adds an exposed `MTDataFiles` content provider (Java extension class shipped in the patches `.rve`) so external file managers like MT Manager can browse GameHub's per-app data directory without needing root. The provider's `android:authorities` and the wake-up activity's `android:taskAffinity` are derived from `packageNameOption.value` so each variant gets its own values — required to avoid `INSTALL_FAILED_CONFLICTING_PROVIDER` when two variants are installed side-by-side.

### `Rewrite custom permissions per variant`

Iterates the manifest's `<permission>` and `<uses-permission>` elements and rewrites any name starting `com.xiaoji.egggame.permission.` so the prefix matches the variant's package. The notable case is the Mob Push SDK's `C2D_MESSAGE` permission, which upstream declares directly. Without this rewrite, two installed variants both declaring `com.xiaoji.egggame.permission.C2D_MESSAGE` violate Android 7+'s rule against multiple packages declaring the same custom permission, and the second-installed variant gets rejected with `INSTALL_FAILED_DUPLICATE_PERMISSION` (UI surfaces this as the unhelpful "package conflicts with a current package" dialog). Reads `packageNameOption.value` directly rather than relying on patcher ordering against `Change package name`.

### `Change package name` *(per variant)*

Rewrites the APK's `<manifest package=…>` and `<application>` references to the variant's value listed in the table above, plus rewrites compatibility receiver permissions and exported provider authorities so they don't collide with the upstream package. Driven by the `packageName` option, set per matrix entry in `release.yml`. The workflow now also passes `updatePermissions=true` and `updateProviders=true` so the upstream-baked `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and the 10 inherited provider authorities are renamed to the variant package as well.

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

# 1. Build the patch bundle
./gradlew build

# 2. Get the base APK
gh release download base-apk-601 \
  --repo The412Banner/bannerhub-revanced \
  --pattern "GameHub_6.0.1.apk" \
  --output GameHub_6.0.1.apk

# 3. Get ReVanced CLI
curl -L https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar \
  -o revanced-cli.jar

# 4. Patch it (single-variant example: Normal)
java -jar revanced-cli.jar patch GameHub_6.0.1.apk \
  --patches "$(find patches/build/libs -name '*.rvp' ! -name '*-sources*' ! -name '*-javadoc*' | head -1)" \
  --bypass-verification \
  -e "Change package name" -O 'packageName="banner.hub"' \
  -e "Change app name"     -O 'appName="GameHub"' \
  --out GameHub-6.0.1-Patched-Normal.apk
```

> **Note on `-O` quoting:** the JSON-string quotes around the value (`"…"` inside the single-quoted shell argument) are required. Picocli's `Map<String,Object>` parser auto-coerces values and trips on package names ending in `f`/`d`/`l` (Java numeric-literal suffixes — `com.tencent.tmgp.cf` is the canonical example).

## Releases

The release pipeline has two modes:

- **Prerelease (default)** — every tag push and every `workflow_dispatch` run produces the 9 variant APKs as Actions artifacts only. Useful for testing without cluttering the Releases page.
- **Stable** — `workflow_dispatch` from `Actions → Run workflow` with the **`stable`** checkbox ticked and a tag (e.g. `v1.0.0-601`) populated. The matrix runs as normal, then a final `release` job creates a GitHub Release with the 9 APKs, `.rvp` bundle, `.rve` extension files, and the release notes (sourced verbatim from `release.yml`).

## Repo layout

- `patches/src/main/kotlin/app/revanced/patches/` — patch sources. The active GameHub-6.0 patches live under `gamehub/`:
  - `misc/login/` — `BypassLoginPatch` and the four cooperating method-replacement patches.
  - `misc/analytics/DisableCrashlyticsPatch` — the reverse-order Crashlytics removal.
  - `misc/sound/MuteUiSoundsPatch` — silent-PCM resource swap.
  - `misc/apiredirect/RedirectCatalogApiPatch` and `misc/apiredirect/PrefixApiPathPatch` — Worker redirect + `/v6/` prefix.
  - `misc/debuglog/` — debug-log probes.
  - `filemanager/` — MTDataFiles provider patch.
  - `misc/extension/` — internal shared dependency that wires the `.rve` extension dex into the patched APK.
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/` — Java extension classes injected into the patched APK at build time:
  - `login/FakeAuthToken.java`, `login/FakeUserAccount.java` — reflective constructors used by `Bypass login`.
  - `api/V6PathPrefix.java` — the `Prefix API path with /v6` runtime helper.
  - `debug/DebugTrace.java` — the `Log.i` helper used by `Debug logging`.
  - `filemanager/MTDataFilesProvider.java` — the file-manager content provider.
- `.github/workflows/release.yml` — the 3-job CI pipeline (`build` → 9-way `patch` matrix → `release`).
- `PROGRESS_LOG.md` — chronological notes from the 6.0 port: every CI run, every patched smali method, every device-test result, every dead-end. The full investigation that produced this build.

## License

GPLv3 — same as upstream ReVanced.
