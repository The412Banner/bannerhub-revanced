# BannerHub for ReVanced — GameHub 6.0 No Login

A ReVanced patch bundle and pre-built APKs for [XiaoJi GameHub](https://www.gamehubglobal.com/) 6.0.0 (`com.xiaoji.egggame`) that **remove the login requirement** so games can be imported and used without an account, plus build-side variants that install side-by-side.

**Latest release:** [GameHub 6.0 No Login](https://github.com/The412Banner/bannerhub-revanced/releases/tag/GameHub-6.0) — 9 ready-to-install APK variants.

---

## What this is

GameHub 6.0 (the KMP rewrite under the package `com.xiaoji.egggame`) gates the entire game-library flow behind a login screen. Even after logging in, the game-import save path requires a non-null auth-token row in the local Room database, and the library-list reader in turn keys off a user-account `StateFlow` that is only populated when an `auth_token` row exists. So no login = no working library, even for sideloaded data.

This patch bundle short-circuits that gate at five points so a fresh install lands directly on the home screen, the **Import → Save** dialog persists rows to the on-device Room database (`db_game_library.db`), and the imported games appear in the library list — all without ever logging in or hitting the upstream auth endpoint.

It also fixes a launch-time `VerifyError` that the original 5.x `Disable Crashlytics` patch caused on 6.0, and ships an unrelated convenience patch (`File manager access`) that exposes a content provider for browsing GameHub's data dir from external file managers.

## Source

- **Base APK:** `GameHub_beta_6.0.0_global.apk` — the official 6.0.0 global build, attached unmodified to the [`base-apk-600`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-600) release for reproducibility.
- **Patcher:** [ReVanced CLI 6.0.0](https://github.com/ReVanced/revanced-cli/releases/tag/v6.0.0) + the bundle built from this repo's `gamehub-600-build` branch (now the default).
- **Build environment:** GitHub Actions, Ubuntu 24.04 runner, Temurin JDK 17. The full pipeline is [`.github/workflows/release.yml`](.github/workflows/release.yml): a `build` job produces the `.rvp` patch bundle, a 9-way matrix patches the base APK in parallel (one variant per matrix entry), and a final `release` job globs all artefacts into a single GitHub Release.

## Variants

The same patch bundle is applied to the same base APK 9 times, each time with a different package name + launcher label so the variants install **side-by-side** on the same device. The `Original` variant keeps the upstream package name `com.xiaoji.egggame` and so **replaces** an installed GameHub on install; everything else coexists.

| Variant | APK file | Package | Launcher label |
| --- | --- | --- | --- |
| Normal | `GameHub-6.0-Patched-Normal.apk` | `banner.hub` | GameHub |
| Normal (GHL) | `GameHub-6.0-Patched-Normal(GHL).apk` *(uploaded as `Normal.GHL.apk` — GitHub strips parentheses)* | `gamehub.lite` | GameHub |
| PuBG | `GameHub-6.0-Patched-PuBG.apk` | `com.tencent.ig` | GameHub PuBG |
| AnTuTu | `GameHub-6.0-Patched-AnTuTu.apk` | `com.antutu.ABenchMark` | GameHub AnTuTu |
| alt-AnTuTu | `GameHub-6.0-Patched-alt-AnTuTu.apk` | `com.antutu.benchmark.full` | GameHub AnTuTu |
| PuBG-CrossFire | `GameHub-6.0-Patched-PuBG-CrossFire.apk` | `com.tencent.tmgp.cf` | GameHub PuBG CrossFire |
| Ludashi | `GameHub-6.0-Patched-Ludashi.apk` | `com.ludashi.aibench` | GameHub Ludashi |
| Genshin | `GameHub-6.0-Patched-Genshin.apk` | `com.miHoYo.GenshinImpact` | GameHub Genshin |
| Original | `GameHub-6.0-Patched-Original.apk` | `com.xiaoji.egggame` | GameHub |

All APKs are signed with ReVanced's default debug keystore. To upgrade in place between releases install the same variant; switching variants (different package names) installs as a fresh app with no shared state.

## Patches applied

This bundle ships only patches that successfully apply against GameHub 6.0. Patches from the upstream 5.x patch tree that target classes renamed in the 6.0 KMP rewrite have been removed from the source — they will be re-ported individually as needed.

### `Bypass login`

Skips the login screen entirely and makes the library system function under a synthetic identity. Five bytecode rewrites cooperate:

1. **`g8e.i(rh0)` and `g8e.r(rh0)`** — the navigator methods that gate Login routing. Original logic does `iget Lg8e;->b:Lis0;` → `invoke-interface Lis0;->a()Z` → `if-nez :skipLogin` → otherwise build a Login navigation intent. Patch removes the `invoke-interface`/`move-result` pair and substitutes `const/4 vN, 0x1` so the branch is always taken.
2. **`os0.h()`** — the real DB-backed `is0` implementation's `isLoggedIn` `StateFlow<Boolean?>`. Body replaced to return `r8o.r(Boolean.TRUE)` (a fresh `MutableStateFlow` over `TRUE`) so every collector — `NavHost.collectAsState`, the `ah7` listener, the `xv0` analytics pipeline — sees a logged-in state.
3. **`os0.e()`** — the user-account `StateFlow<f4m?>`. Without an `auth_token` row in the DB this emits `null` and the library-list reader's `flatMapLatest` collapses to an empty `Flow`. Patch replaces the body with `r8o.r(FakeUserAccount.get())` where `FakeUserAccount` is a Java extension that reflectively constructs `Lf4m;` via `Class.forName("f4m").getDeclaredConstructor(...)`, with `a="99999"` and every other field zeroed/empty. Result: the library reader's pipeline `is0.e().flatMapLatest { f4m -> dao.subjectAllByUserId(f4m.a) }` always queries with `user_id="99999"` and returns the imported rows.
4. **`xm7.f()`** — the `GameLibraryRepository`'s user-id getter, called from both write (`xm7.u()` early-bail) and read paths. Returns `"99999"` directly so it matches the synthetic user account.
5. **`is0.f()`** — the interface default method that returns the current `l4m` auth token. Body replaced to call `FakeAuthToken.get()`, a Java extension that reflectively constructs `Ll4m;` with `a="99999"` and `b=""`. Several callers (`lvd`, `aae`, `fh2`, `dt0`, `sak`, `w79`, `kpl`, `dlk`, `npl`) use this directly to read the user-id for network-prep and lambda capture; under the bypass they all see the same synthetic identity.

End-to-end consequence: a fresh install lands on the home screen, the **Import** dialog opens, picking an APK + metadata + tapping **Save** persists a row into `/data/data/<pkg>/databases/db_game_library.db` (`t_game_library_base`, `t_game_launch_method`), and the row appears in the library list immediately because the read pipeline is now keyed off the matching synthetic user-id.

### `Disable Firebase Crashlytics`

Removes the Firebase Crashlytics initialisation block. Without this, GameHub 6.0 crashes on launch with `VerifyError`. Root cause: the upstream 5.x patch used `goto` to skip the Crashlytics call site, which in 6.0 leaves a join-point where the same register holds either `String` (goto path) or `Boolean` (fall-through path) and the ART verifier rejects it. The 6.0-compatible patch removes the three Crashlytics instructions in **reverse index order** (`setCrashlyticsCollectionEnabled`, `move-result-object`, `invoke-static getInstance`) so the intermediate `const/4 v2, 0x0` redefines the register with a consistent `Boolean` type at the join point.

### `Debug logging`

A diagnostic patch that:

- Sets `android:debuggable="true"` in the `<application>` manifest so Log.d / Log.v lines from the patched APK reach `logcat`.
- Inserts `Log.i("GH600-DEBUG", ...)` markers along the import code path: `xm7.u` ENTRY/CATCH, `el7.invokeSuspend` ENTRY, both Room DAO insert PRE markers (`GameLaunchMethodDao.insert`, `GameLibraryBaseDao.insert`), and per-call markers in `FakeAuthToken.get()` and `FakeUserAccount.get()`.
- Hooks the global `odb.e()` `Throwable` swallower to surface every exception that the app's Kotlin coroutine state machines would otherwise eat silently.

These probes are intentionally kept in this release to make it easy to triage any device-specific issues during the bake-in period; they will be removed in a follow-up build once the import flow is confirmed stable across devices.

### `File manager access`

Adds an exposed `MTDataFiles` content provider (Java extension class shipped in the patches `.rve`) so external file managers like MT Manager can browse GameHub's per-app data directory without needing root.

### `Change package name` *(per variant)*

Rewrites the APK's `<manifest package=…>` and `<application>` references to the variant's value listed in the table above, plus rewrites compatibility receiver permissions and exported provider authorities so they don't collide with the upstream package. Driven by the `packageName` option, set per matrix entry in `release.yml`.

### `Change app name` *(per variant)*

Rewrites `<application android:label=…>` to the variant's value listed in the table above. Driven by the `appName` option, set per matrix entry.

### Disabled-by-default options

Five generic patches from upstream `patches/all/misc/` are included but `use = false` (must be opted in via `revanced-cli -e <name>`):
- `Change app name`, `Custom network security`, `Enable Android debugging`, `Override certificate pinning`, `Change package name`.

`Change app name` and `Change package name` are the ones we explicitly enable per variant; the others are available for ad-hoc CLI use and have no effect on the released APKs.

## Build it yourself

```sh
git clone https://github.com/The412Banner/bannerhub-revanced.git
cd bannerhub-revanced

# 1. Build the patch bundle
./gradlew build

# 2. Get the base APK
gh release download base-apk-600 \
  --repo The412Banner/bannerhub-revanced \
  --pattern "GameHub_beta_6.0.0_global.apk" \
  --output GameHub_6.0.0.apk

# 3. Get ReVanced CLI
curl -L https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar \
  -o revanced-cli.jar

# 4. Patch it (single-variant example: Normal)
java -jar revanced-cli.jar patch GameHub_6.0.0.apk \
  --patches "$(find patches/build/libs -name '*.rvp' | head -1)" \
  --bypass-verification \
  -e "Change package name" -O 'packageName="banner.hub"' \
  -e "Change app name"     -O 'appName="GameHub"' \
  --out GameHub-6.0-Patched-Normal.apk
```

> **Note on `-O` quoting:** the JSON-string quotes around the value (`"…"` inside the single-quoted shell argument) are required. Picocli's `Map<String,Object>` parser auto-coerces values and trips on package names ending in `f`/`d`/`l` (Java numeric-literal suffixes — `com.tencent.tmgp.cf` is the canonical example).

## Repo layout

- `patches/src/main/kotlin/app/revanced/patches/` — patch sources. The active GameHub-6.0 patches live under `gamehub/` (`misc/login/`, `misc/analytics/DisableCrashlyticsPatch`, `misc/debuglog/`, `filemanager/`, plus the internal `misc/extension/` shared dependency).
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/` — Java extension classes injected into the patched APK at build time. Includes `login/FakeAuthToken.java`, `login/FakeUserAccount.java` (the reflective constructors used by `Bypass login`), `debug/DebugTrace.java` (the `Log.i` helper used by `Debug logging`), and `filemanager/MTDataFilesProvider.java`.
- `.github/workflows/release.yml` — the 3-job CI pipeline (`build` → 9-way `patch` matrix → `release`).
- `PROGRESS_LOG.md` — chronological notes from the 6.0 port: every CI run, every patched smali method, every device-test result, every dead-end. The full investigation that produced this build.

## License

GPLv3 — same as upstream ReVanced.
