# BannerHub ReVanced — GameHub 6.0 Port Progress Log

## 2026-05-01 — GameHub 6.0 port session

### Goal
Port the existing 5.3.5 ReVanced patches to GameHub 6.0.0 (`com.xiaoji.egggame` KMP rewrite).

### Branch
`gamehub-600-build` — forked from `playday-build`, retargeted to `GameHub_beta_6.0.0_global.apk`

### Completed fixes

#### 1. CI compile error (commit `e1a6a12`)
- `getInstruction<Instruction>(...)` — patcher v22 does NOT accept type parameters
- Fix: remove `import com.android.tools.smali.dexlib2.iface.Instruction`, use bare `getInstruction(idx)`

#### 2. Firebase Crashlytics crash-on-launch — VerifyError (commit `2437dca`)
- **Root cause:** Original `DisableCrashlyticsPatch` used a `goto` to skip the Crashlytics block.
  At the join point, v2 had type `String` (goto path) vs `Boolean` (fall-through path) → ART VerifyError.
- **Fix:** Remove all 3 Crashlytics instructions in **reverse order**:
  - `setCrashlyticsCollectionEnabled` (endIdx)
  - `move-result-object` (getInstanceIdx + 1)
  - `invoke-static getInstance` (getInstanceIdx)
  This leaves the `const/4 v2, 0x0` between them in place, which redefines v2 String→Boolean and satisfies the ART verifier at the join point.

#### 3. TokenProvider.<clinit> dead-code removal (commit `aab98d7`)
- **Root cause:** `TokenProvider.loginBypassed` is `static boolean = false` — Java does NOT generate
  `<clinit>` for default-value static fields. `firstMethod { name == "<clinit>" }` threw "Required value was null".
- **Fix:** Remove the entire `TokenProvider.<clinit>` patching block plus the `addInstructions`,
  `TOKEN_PROVIDER_CLASS`, and `sharedGamehubExtensionPatch` imports/dependencies.

#### 4. Bypass login — complete rewrite for 6.0 (commit `f53a74d`)
- **Root cause:** `UserManager` class (`Lcom/xj/common/user/UserManager;`) is gone in 6.0.
  All 5 `firstMethod { definingClass == USER_MANAGER_CLASS }` calls threw "Required value was null".
  `HomeLeftMenuDialog` also renamed/restructured.
- **6.0 architecture analysis (via apktool decompile of `GameHub_beta_6.0.0_global.apk`):**
  - Login state is now managed by `Lis0;` interface with two implementations:
    - `Los0;` — real DB-backed impl using `UserDao` + `AuthTokenDao` (Room database), StateFlow initialized to `Boolean.FALSE`
    - `Lah;` — `os0` decorator that hardcodes `a()=true` and wraps StateFlow with `Boolean.TRUE`
  - `Lg8e;` is the navigator class (holds `is0` as field `b`)
  - Two methods in `g8e` gate Login navigation by calling `is0.a()`:
    - `g8e.i(Lrh0;)V` — guards via `iget Lg8e;->b → invoke-interface Lis0;->a() → if-nez → new Lga0;`
    - `g8e.r(Lrh0;)V` — same pattern with different register (v1 vs v3)
  - `Lga0;` is the Login navigation intent
- **Fix:** For both methods `i` and `r`:
  - Find `iget-object vN, p0, Lg8e;->b:Lis0;` → get register N
  - Remove `invoke-interface {vN}, Lis0;->a()Z` (igetIdx+1)
  - Remove `move-result vN` (igetIdx+2, removed first to keep indices stable)
  - Insert `const/4 vN, 0x1` at igetIdx+1
  - Result: `if-nez vN, :skipLogin` always branches → Login navigation never reached

### CI results

| Test tag | Run ID | "Bypass login" | "Disable Firebase Crashlytics" | Notes |
|---|---|---|---|---|
| v0.0.1-600-test | 25215699388 | SEVERE | SEVERE | Compile error (getInstruction type param) |
| v0.0.2-600-test | ~25218... | SEVERE | SEVERE | goto approach → VerifyError crash |
| v0.0.3-600-test | 25220... | SEVERE | ✅ INFO | Crashlytics fixed; login still failing (TokenProvider.<clinit>) |
| v0.0.4-600-test | 25222889321 | SEVERE | ✅ INFO | TokenProvider block removed; login still failing (UserManager gone) |
| v0.0.5-600-test | 25224133234 | ✅ INFO | ✅ INFO | Login bypass rewritten for 6.0 g8e navigator |

### Pending (other patches still failing)

All remaining patches target 5.3.5 class names not present in 6.0. Each needs:
1. `apktool d` decompile + grep for the target functionality
2. Find the 6.0 equivalent class/method
3. Rewrite the patch fingerprint and bytecode manipulation

Known failing patches and root causes:
- **appNullSafetyPatch** → targets `Lcom/xj/app/App;` (renamed in 6.0)
- **bypassTokenExpiryPatch** → targets `RouterUtils$checkGuideStep$1` (renamed)
- **settingsMenuPatch** → UI classes renamed
- **errorHandlingPatch** → `NetErrorHandler$DefaultImpls` renamed
- **tokenResolutionPatch** → `UserManager.getToken()` gone
- Everything that depends on these (cascade failures via "patch failed previously")

### Local resources
- 6.0 APK decompile: `/tmp/gh600_smali/` (rebuilt from `GameHub_beta_6.0.0_global.apk` via apktool each session — `/tmp/` is ephemeral)
- 6.0 APK local copy: `/data/data/com.termux/files/home/GameHub_beta_6.0.0_global.apk`
- To rebuild smali: `java -jar ~/apktool.jar d ~/GameHub_beta_6.0.0_global.apk -o /tmp/gh600_smali -f --no-res`

### v0.0.6 / v0.0.7 / v0.0.8 incremental fixes (post-v0.0.5)

| Tag | Commit | Patch added |
|---|---|---|
| v0.0.6 | `65f2349` | `os0.h()` → `MutableStateFlow(Boolean.TRUE)` — NavHost `collectAsState()` had been picking Login as start destination because StateFlow init was FALSE |
| v0.0.7 | `21b151f` | `xm7.f()` → `"99999"` — game-import save was hitting null UID null-check in `xm7.u()` and short-circuiting to FALSE |
| v0.0.8 | `02195ff` | New `DebugLogPatch.kt` — sets `android:debuggable="true"` and prepends `Log.e("GH600-DEBUG", "y2d.e caught", t)` to `odb.e()` so swallowed exceptions surface in logcat |

### v0.0.8 device test result (2026-05-01, log_2026_05_01_17_04_18.log)

- Login is bypassed cleanly ✅ — landed on home screen, no Login route
- Game import dialog opens, can select APK + metadata
- Tap Save → dialog dismisses with no toast → game does NOT appear in library ❌

Decompiled v0.0.8 APK to verify all three patches were live:
- ✅ `android:debuggable="true"` set in `<application>`
- ✅ `"GH600-DEBUG"` in `odb.smali:129`
- ✅ `xm7.f()` returns `"99999"`
- ✅ `os0.h()` returns `r8o.r(Boolean.TRUE)`

Logcat shows NO `GH600-DEBUG y2d.e caught` line — meaning `xm7.u()` did NOT throw an exception. So the save use case `q1d.a()` got `Boolean.TRUE` back from `xm7.u()` (dialog dismissed cleanly because save reported success). But the row still doesn't appear in the library list.

#### Root-cause analysis (smali trace)

`xm7.u()` flow at `smali_classes5/xm7.smali:13667`:
1. `invoke-virtual xm7.f()` → "99999"
2. `if-nez :cond_3` (non-null, branch taken)
3. Build `fl7` lambda with userId="99999"
4. `withTransaction { fl7.invoke() }` → `el7.invokeSuspend` → `GameLibraryBaseDao.insert` (line 922 of `el7.smali`)
5. Return `Boolean.TRUE`

Local readers (`xm7.p`, `xm7.s`) ALSO call `xm7.f()` for their `WHERE user_id = ?` filters. So the writer and these specific readers are consistent on "99999".

But `is0.f()` is called directly by **other** consumers — `lvd` (network request prep, reads `l4m.b` username), `aae` (synthetic property-getter lambda), `fh2`, `dt0`, `sak`, `w79`, `kpl`, `dlk`, `npl`. The auth-token StateFlow that backs `is0.f()` is built off `AuthTokenDao.observeCurrent()` and emits null when the table is empty. With our login bypass there's no `auth_token` row in the DB, so `is0.f()` returns null, and any refresh/library-list signal that keys off this Flow stays in an "empty" state regardless of what's in `t_game_library_base`.

#### Fix planned for v0.0.9

Patch `is0.f()` (the interface default method in `is0.smali`) to return a non-null synthetic `l4m` constructed via reflection in a Java extension helper.

**New file:** `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/login/FakeAuthToken.java`
- `get()` reflectively constructs `Class.forName("l4m")` with `(a="99999", b="", c..f=null, g=h=i=j=0)`, caches in volatile static
- Logs to `GH600-DEBUG` tag on success/failure

**`BypassLoginPatch.kt`:**
- Adds patch to `Lis0;->f()Ll4m;` — removes its 6 original instructions (`invoke-interface d()` → `getValue()` → `check-cast Ll4m;` → `return-object`) and replaces with `invoke-static FakeAuthToken.get()` → `check-cast Ll4m;` → `return-object`
- Keeps existing `xm7.f()`="99999" patch as redundant safety net (and so xm7's local cache logic stays consistent)
- Keeps the `g8e.i/r` navigator bypass and `os0.h()`=TRUE

---

## 2026-05-01 evening — Save-button silent-failure investigation

### Symptom
Bypass-login works (no Login screen), but **clicking Import → fill game form → Save** does not add the game to the library. Repeated tests show no rows ever appear in the library UI.

### Test 1 — v0.0.9-600-test (commit `59ab364`)
Existing patches: xm7.u ENTRY/CATCH probes, odb.e Throwable hook. DebugTrace writes to file at `/storage/emulated/0/Android/data/com.xiaoji.egggame/files/gh600-debug.log` AND Log.e.

Reproduction with `getlog -n 15000 com.xiaoji.egggame` after Save:
- 10251 lines captured, **0 GH600-DEBUG entries, 0 E-level lines from the app** (66 D, 10146 I, 38 W).

Hypothesis: this device (or kernel build) filters app-tagged Log.e for non-system uids. File output unreachable from PRoot due to scoped storage.

### Test 2 — v0.1.0-600-test (commit `ac86a5f`, CI 25237506742 ✅)
Changes:
- DebugTrace switches from `Log.e` to `Log.i` (Log.i lines ARE reaching logcat per the test-1 capture).
- DebugTrace adds zero-arg markers `markY4iUpsert()`, `markFakeAuth()` for probes inserted into methods with `.locals 0`.
- New probe at `y4i.b` ENTRY (RetroGameDao upsert wrapper).
- `FakeAuthToken.get()` now logs on every call, not only on first construction.

Reproduction:
- `xm7.u ENTRY` fires **once** at 19:33:23.935 ✓
- `xm7.u CATCH` fires **0** times — transaction did not throw
- `FakeAuthToken.get() called` fires **45×** — bypass-login pathway is alive
- `y4i.b ENTRY` fires **0** — RetroGameDao not touched
- `y2d.e caught` fires **0**

### Conclusion of Test 2
xm7.u runs successfully end-to-end without exception, yet nothing lands in the library. `y4i.b` was a red herring — `RetroGameDao` is for retro emulators only. Re-tracing xm7.u smali (`smali_classes4/xm7.smali` line 13663) shows the actual write path:

```
xm7.u
  ├─ early bail: if xm7.f() returns null → return Boolean.FALSE     [line 13822]
  └─ withTransaction(GameLibraryDatabase, fl7) → fl7.invokeSuspend
       └─ withTransaction body: el7.invokeSuspend (.locals 69)
            ├─ build GameLaunchMethodTable, setLinkedGameId
            ├─ GameLaunchMethodDao.insert(table, cont)               ← line 609 in el7.smali
            ├─ build GameLibraryBaseTable via oh7.c(GameInfo)
            └─ GameLibraryBaseDao.insert(table, cont)                ← line 922 in el7.smali
```

The actual main-library writes are inside `el7.invokeSuspend` against `GameLibraryDatabase` — separate database from `RetroGameDatabase`.

### Test 3 — v0.1.1-600-test (commit `0892555`, CI 25237940015)
Added probes:
- `el7.invokeSuspend` ENTRY → confirms transaction body started
- `GameLaunchMethodDao.insert` PRE → marker right before INVOKE_INTERFACE
- `GameLibraryBaseDao.insert` PRE → marker right before INVOKE_INTERFACE

Implementation: `addInstructions` walked from highest target index to lowest so earlier insertions don't shift later targets. All three markers route through `DebugTrace.markEl7Entry()` / `markLaunchInsert()` / `markLibraryInsert()` (no-arg statics) since el7.invokeSuspend doesn't have free local registers everywhere.

**Branching logic for next reproduction:**
- el7 ENTRY missing → xm7.u took the early `Boolean.FALSE` branch (xm7.f() patch silently shadowed somehow)
- el7 ENTRY hit, no insert markers → withTransaction body bailed before reaching inserts
- both insert markers hit, library still empty → bug is **library-read-side**: UI either filters by a userId mismatch or fetches from a remote endpoint that 401s with our empty-bearer fake token

### Parallel infrastructure: logcat-bridge v1.1.0
The bridge can't read scoped external storage from PRoot, but the daemon runs as root. v1.1.0 (zip ready at `/data/data/com.termux/files/home/logcat-bridge-magisk.zip`, awaiting flash) adds `cat <path>`, `ls <path>`, and `sql <dbpath> <query>` verbs to the handler with allowlisted prefixes (`/data/data/`, `/data/local/tmp/`, `/data/tombstones/`, `/data/adb/modules/`, `/storage/emulated/0/Android/`, `/sdcard/Android/`) and `..` traversal blocked. `sqlite3` invoked with `-readonly -header`. Client side: `getlog --cat <path>`, `getlog --ls <path>`, `getlog --sql <dbpath> "SELECT ..."`. Once flashed + rebooted, this lets us inspect `GameLibraryDatabase` rows directly to confirm whether writes actually persist — covering the case where probes show inserts firing but UI still shows empty.

### Status awaiting user
- Flash logcat-bridge v1.1.0 zip + reboot.
- Install v0.1.1-600-test APK, reproduce Save, capture logs.
- Then I pull `getlog -n 20000 com.xiaoji.egggame` for probe markers AND `getlog --sql /data/data/com.xiaoji.egggame/databases/<gameLibraryDbName> "SELECT count(*) FROM game_library_base"` for the conclusive write-vs-read answer.

### Test 3 device-test result (2026-05-01, v0.1.1-600-test, run 25237940015)

All four probes fired in order, transaction body completed without CATCH:

```
19:59:41.063  GH600-DEBUG: xm7.u ENTRY
19:59:41.065  GH600-DEBUG: el7.invokeSuspend ENTRY
19:59:41.065  GH600-DEBUG: GameLaunchMethodDao.insert PRE
19:59:41.069  GH600-DEBUG: GameLibraryBaseDao.insert PRE
19:59:41.090  W App_Lifecycle: DISPOSE overlay=ye0      ← dialog dismisses
```

DB inspection (post-test) via `getlog --cat` + Python `sqlite3`:

- `egggame.db` — auth/UI DB (NOT GameLibraryDatabase). All tables empty as expected — login bypass means no auth_token / user_account row.
- **`db_game_library.db`** — actual GameLibraryDatabase, found via `et2.smali:584 const-string "db_game_library.db"`. Earlier listing missed it because the file is created lazily on first write.
  - `t_game_library_base` count = **1** (the imported row landed)
  - `t_game_launch_method` count = **1**
  - `t_game_install_state` count = 0
  - Imported row: `user_id='99999'`, `game_name='God of War'`, `id='local_DaebwST-TEyzp1KJX2xRzQ'`, `extension_data={"filePath":"/storage/emulated/0/Winlator/Games/GodOfWar/GoW.exe","steamAppid":"1593500"}`, `launch_method_id=1`. Write side **fully working**.

### Root cause of empty library UI (read-side, not write-side)

Library-list reader pipeline (smali trace, `wl7.smali` → `erc.smali:340`):

```
is0.e()                              ← StateFlow<f4m?> for current user account
  ↓
flatMapLatest { f4m ->
    if (f4m == null) emptyFlow()    ← TAKEN under our bypass
    else dao.subjectAllByUserId(f4m.a)
}
```

`is0` interface (smali_classes4/is0.smali):
- `d()Ld3k;` → Flow<l4m?> (auth token)
- `e()Ld3k;` → Flow<f4m?> (user account)        **← library reader uses this**
- `h()Ld3k;` → Flow<Boolean?> (is logged in)    **← we patched in v0.0.6**
- `f()Ll4m;` → `d().getValue()`                 **← we patched in v0.0.9**
- `b()Lf4m;` → `e().getValue()`

We patched `is0.f()` (l4m getter) and `os0.h()` (Boolean flow), but NOT `os0.e()` (f4m flow). With `t_user_account` empty (login bypassed), `os0.a` field's underlying StateFlow emits null, flatMapLatest drops to the empty branch, library list shows zero entries despite the row being in `t_game_library_base`.

### Fix planned for v0.1.2-600-test

**New extension** `extensions/gamehub/.../FakeUserAccount.java`:
- Reflectively constructs `Lf4m;` via `Class.forName("f4m").getDeclaredConstructor(...)` with sig
  `(String,String,String,String,String,String,I,I,Z,String,I,I,I,I,I,J,String,String,I,I,String,J,I,String,String,J,J)V`.
- Sets `a="99999"`, all other String fields `""`, all numerics zero.
- Caches in volatile static, logs to `GH600-DEBUG`. f4m's ctor null-checks `a` (p1) and `q` (p18); both pass.

**`BypassLoginPatch.kt` addition** (mirrors v0.0.6 `os0.h()` block):
```kotlin
firstMethod { definingClass == "Los0;" && name == "e" }.apply {
    removeInstruction(0) // iget-object p0, Los0;->a:Likh;
    removeInstruction(0) // return-object p0
    addInstructions(0, """
        invoke-static {}, Lapp/revanced/extension/gamehub/login/FakeUserAccount;->get()Ljava/lang/Object;
        move-result-object p0
        invoke-static {p0}, Lr8o;->r(Ljava/lang/Object;)Lf3k;
        move-result-object p0
        return-object p0
    """)
}
```

Debug probes intentionally **kept in place** (xm7.u ENTRY/CATCH, el7 ENTRY, both insert PRE markers, FakeAuthToken.get, DebugLogPatch) so the next device test can confirm `FakeUserAccount.get() called` fires before the UI populates and that the import flow is otherwise unchanged. Probes will be removed in a cleanup pass after the import flow is confirmed end-to-end.

## 2026-05-02 — v1.0.0-600 stable release session

### Goal
Ship the first stable BannerHub-API-enabled GameHub 6.0 release that pairs with the Worker's `/v6/` gate.

### Branch operations
- `bannerhub-api-patch` (commits `7644ad0` Redirect catalog API + `561b246` Prefix /v6) was fast-forward merged into `gamehub-600-build`. Branch kept (not deleted) per user instruction.
- `gamehub-537-build` deleted from local + `origin` (9 unique commits, all 5.3.7 work — the v6 CLI revert, settings menu disable for 5.3.7 R8 renames, target-APK swap, etc.). Abandoned 5.3.7 port; nothing reachable elsewhere.

### Stable release v1.0.0-600 (run 25264095270, commit 572ff30)
Triggered via `gh workflow run release.yml --ref gamehub-600-build -f tag=v1.0.0-600 -f stable=true`. Workflow opt-in `stable=true` checkbox produces the GitHub Release; default behavior is artifacts-only prerelease.

Title: **"Gamehub 6.0 - Bannerhub API - No Login - Muted UI"** (renamed in-place via `gh release edit` after the initial publish; workflow `name:` field also updated for future runs).

9 variant APKs + .rvp bundle + .rve extension files attached:
- Normal (`banner.hub`), Normal-GHL (`gamehub.lite`), PuBG (`com.tencent.ig`), AnTuTu (`com.antutu.ABenchMark`), alt-AnTuTu (`com.antutu.benchmark.full`), PuBG-CrossFire (`com.tencent.tmgp.cf`), Ludashi (`com.ludashi.aibench`), Genshin (`com.miHoYo.GenshinImpact`), Original (`com.xiaoji.egggame`).

### Release notes iterations (workflow body + live release body kept in sync via `gh release edit --notes-file`)
1. **Initial body was stale** — described only Bypass login + Disable Crashlytics + Debug logging + File manager + per-variant naming. Missing 3 patches that had landed since: Mute UI sounds (5ce470d, 2df0e54), Redirect catalog API (7644ad0), Prefix API path /v6 (561b246). Cancelled the in-flight run, updated `release.yml` body, retriggered. Run 25264031838 (cancelled) → 25264095270 (succeeded).
2. **Cross-release install warning added** — every CI run mints a new debug keystore (no `--keystore` passed to revanced-cli, ephemeral runner has empty workdir), so Android refuses cross-release upgrades with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Replaced the wrong "To upgrade in place install the same variant" line with explicit uninstall-first instruction.
3. **Known limitations section added** with two callouts:
   - Steam launches via standard client are likely broken — use Lightweight Steam (`steam_client_0403`).
   - Imported games need cover art set manually.
4. **PC game settings orientation note added** — side benefit of the API redirect: per-game PC settings now renders in both landscape AND portrait (vanilla locked it to landscape only). Caused by the BannerHub Worker not carrying upstream's orientation constraint in the catalog response.

### README rewritten for v1.0.0-600 (commit 816dd2d, then b4cee47 for the orientation note)
Full doc rewrite covering all four user-facing patches (was stale, only described the No Login flow). Adds: cross-release fresh-install warning, Known Limitations section, per-patch sections for Mute UI sounds + Redirect catalog API + Prefix /v6, link to `bannerhub-api` repo as catalog backend, Releases section explaining stable-vs-prerelease workflow.

### Open follow-ups
- **Persistent keystore** — pin one in Actions secrets and pass `--keystore`/`--keystore-entry-alias`/`--keystore-password`/`--keystore-entry-password` on the revanced-cli patch command. Eliminates the cross-release uninstall requirement.
- **Bump versionCode per release** — patches don't currently touch versionCode; APKs ship with the base APK's value. Cosmetic only (Android allows install-replace with equal versionCode), but proper update-detection in package installers wants it bumped.
- **Component Manager port still pinned on `component-manager-injection`** at `5b89073`. Picker still doesn't show injected `Fex_2604`. Resume plan in memory unchanged: ship debug build with `Log.i("GH600-DEBUG", ...)` at top of every `HostCache.*` method, then in-foreground inject test.

## 2026-05-05 — Fix: per-variant DocumentsProvider authority

### Problem
v1.0.0-600 ships 9 variants that all declare the **same** DocumentsProvider authority, baked at the unrenamed `com.xiaoji.egggame.app.revanced.extension.gamehub.filemanager.MTDataFilesProvider`. Cause: `FileManagerAccessPatch` runs in the default `apply { ... }` block, which executes BEFORE `ChangePackageNamePatch`'s `afterDependents { ... }`, so it reads the original `manifest@package` value before the variant rename happens. The wake-up activity's `android:taskAffinity` (`com.xiaoji.egggame.MTDataFilesWakeUp`) is frozen the same way.

Practical impact: Android allows one app per provider authority globally. Installing a second variant alongside a first fails with `INSTALL_FAILED_CONFLICTING_PROVIDER`.

### Branch
`fix/file-manager-per-variant-authority` off `gamehub-600-build` (per branch-per-patch policy).

### Change
`patches/src/main/kotlin/app/revanced/patches/gamehub/filemanager/FileManagerAccessPatch.kt`: `apply { ... }` → `afterDependents { ... }` (and `return@apply` → `return@afterDependents`). Patch body unchanged. Now reads `manifest@package` after `ChangePackageNamePatch` has rewritten it, so each variant gets:

- `<provider android:authorities="<variant-pkg>.app.revanced.extension.gamehub.filemanager.MTDataFilesProvider">`
- wake-up activity `android:taskAffinity="<variant-pkg>.MTDataFilesWakeUp"`

### Why this over `-O updateProviders=true`
- Single-file change vs workflow edit (and CLI option that has to be remembered on every future build).
- `updateProviders=true` only rewrites `<provider android:authorities>` — it does NOT touch `taskAffinity` on the wake-up activity, which would still collide across variants.
- `updateProviders=true` also rewrites authorities on **all** existing providers in the base APK (not just ours), which the option's own description warns can break features.

### Status
- CI compile green run 25379952428 (`build_pull_request.yml`).
- **Round 1** (commit 6d329bd, `apply{} → afterDependents{}`): release run 25382219259 success, but verifying APKs with `aapt dump xmltree | grep MTDataFiles` showed authority + taskAffinity STILL frozen at `com.xiaoji.egggame.*` for every variant. afterDependents alone wasn't sufficient.
- **Round 2** (commit 8f4a8fc, added `dependsOn(changePackageNamePatch)`): release run 25382742203 success, same negative result — patcher schedule still didn't put our manifest write after the rename.
- **Round 3 — verified working** (commit fadeaab, read `packageNameOption.value` directly): release run 25383226328 success. APKs now show per-variant authorities (`banner.hub.app.…MTDataFilesProvider`, `gamehub.lite.app.…MTDataFilesProvider`, …) and per-variant taskAffinities (`banner.hub.MTDataFilesWakeUp`, etc.). `Original` correctly stays at `com.xiaoji.egggame.*`. Branch ready to merge after device-side install test.

### Lesson
For revanced-patcher resource patches that need to react to "Change package name", do not rely on `afterDependents` + `dependsOn(changePackageNamePatch)` to read the post-rename `manifest@package` value — the scheduling guarantees aren't there. Instead, read `packageNameOption.value` directly (with a fallback to `packageNameOption.default`). The CLI option is set before any patch applies, so it's the only reliable source of the variant package within other patches.

### Round 4 — upstream literals also per-variant (commit f306a48)
After round 3 fixed MTDataFiles, manifest inspection of alt-AnTuTu showed pre-existing upstream literals still stuck at `com.xiaoji.egggame.*`:
- `<permission>`/`<uses-permission>` `com.xiaoji.egggame.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — `signature` protectionLevel, would have blocked side-by-side install of variants with different signing certs
- 10 `<provider android:authorities="com.xiaoji.egggame.*">` declarations from MobProvider / fileprovider / FlyProvider / utilcode / firebaseinit / AndroidContext / androidx-startup / filekit / fileprovider / wbsdk — same authority, different package names → install conflict
- `<permission>` `com.xiaoji.egggame.permission.C2D_MESSAGE` — `normal` protectionLevel, NOT a blocker (Android allows multi-declaration of normal perms across apps)

Fix: added `-O 'updatePermissions=true'` and `-O 'updateProviders=true'` to the Change package name CLI invocation in `.github/workflows/release.yml`. Release run 25385421598 verified all 10 upstream provider authorities and the `signature` permission are now per-variant. C2D_MESSAGE intentionally untouched — `updatePermissions` only rewrites the hardcoded `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` per upstream patch source, and C2D_MESSAGE is harmless cosmetically.

### Final state for branch fix/file-manager-per-variant-authority
Verified APKs at `/storage/emulated/0/bannerhub-revanced-test-25385421598/`. All 9 variants now have fully decoupled manifests:
- `manifest@package` per-variant (existing)
- `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` per-variant (round 4)
- All 10 upstream provider authorities per-variant (round 4)
- MTDataFiles provider authority + wake-up taskAffinity per-variant (round 3)

Ready to merge into `gamehub-600-build` once device install test confirms two variants install alongside each other without `INSTALL_FAILED_CONFLICTING_PROVIDER`.

### Round 5 — C2D_MESSAGE permission per-variant (commit 91436af)
User reported the install dialog still said "package conflicts with a current package" when trying to install one variant alongside another, even on package names they didn't have installed. Re-audited all `com.xiaoji.egggame.*` literals in the alt-AnTuTu manifest from run 25385421598 — only one globally-unique identifier was still shared across variants:

```xml
<permission android:name="com.xiaoji.egggame.permission.C2D_MESSAGE" />
<uses-permission android:name="com.xiaoji.egggame.permission.C2D_MESSAGE" />
```

This is the install blocker. Android 7+ rejects any install that declares a `<permission>` whose name another installed package already declares — regardless of `protectionLevel`, regardless of signing cert match. The package manager reports it as `INSTALL_FAILED_DUPLICATE_PERMISSION`, surfaced in the UI as the unhelpfully-vague "package conflicts with a current package" dialog. (Earlier I'd called this permission "harmless" on the grounds that `normal`-protection allows multi-declaration. That was wrong — the multi-declaration restriction was tightened to apply to all custom permissions in API 24, regardless of protection level.)

Other `com.xiaoji.egggame.*` literals in the manifest are NOT install blockers:
- `<activity android:name="...">` and `<service android:name="...">` — these are fully-qualified class names scoped to the app, not globally unique
- `taskAffinity="com.xiaoji.egggame"` — affinity is a task-grouping hint, not a unique system identifier; multiple apps can share it
- `<data android:scheme/host>` in intent-filters — multiple apps can register the same scheme

Fix: new `RewriteCustomPermissionsPatch` (resource patch, GameHub-specific). Iterates the manifest's `<permission>` and `<uses-permission>` elements; any element whose `android:name` starts with `com.xiaoji.egggame.permission.` gets the prefix rewritten to the variant package. Reads `packageNameOption.value` directly (same pattern as the MTDataFiles fix from round 3) — does not rely on patcher ordering against ChangePackageNamePatch. Confirmed via grep that no smali in the 6.0 decompile references `C2D_MESSAGE` literally, so renaming the manifest declaration doesn't break runtime broadcasts (the SDK either computes the name from `BuildConfig.APPLICATION_ID` at runtime, or doesn't use the permission at all).

Release run 25387394484 verified: each of the 9 variants now declares its own per-variant `<permission android:name="<variantPkg>.permission.C2D_MESSAGE">`. APKs at `/storage/emulated/0/bannerhub-revanced-test-25387394484/`.

### How to actually test
1. Uninstall ALL previously-installed BannerHub-ReVanced variants from the device (including any v1.0.0-600 builds and any earlier test builds from this branch). They still declare `com.xiaoji.egggame.permission.C2D_MESSAGE` and will block fresh installs from run 25387394484.
2. Install Variant A (e.g. `Normal` = `banner.hub`).
3. Install Variant B (e.g. `PuBG` = `com.tencent.ig`) without uninstalling A.
4. Both should now coexist on the launcher. If step 3 still fails with the same "package conflicts with a current package" message, run `adb logcat -d | grep -iE 'install_failed|already declared'` immediately after the failed install and paste the output — there's another globally-unique declaration to track down.

## 2026-05-05 — v1.0.1-600 stable cut

### Branch operation
`fix/file-manager-per-variant-authority` (8 commits — 5 patch fixes + 3 progress-log updates) fast-forward merged into `gamehub-600-build` (commit range `7108634..2113683`). Branch deleted from local + origin. `gamehub-600-build` advanced from `7108634` → `2113683` → `220a204` (after the docs/release-notes prep commit).

### Stable release
Run **25389334422** (`gh workflow run release.yml --ref gamehub-600-build -f tag=v1.0.1-600 -f stable=true`) — all 10 jobs green (build + 9-variant patch matrix + Create GitHub Release). Published as https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.1-600.

Title: **"Gamehub 6.0 - BannerHub API - Multi-Install"** (rebrand from v1.0.0-600's "Gamehub 6.0 - Bannerhub API - No Login - Muted UI" to highlight the headline fix).

### Release notes structure
1. Lead with the side-by-side install fix being the headline.
2. New `What's new vs v1.0.0-600` section explaining the three independent install-blockers stacked behind the single Android dialog (MTDataFiles authority + DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION + C2D_MESSAGE), each with its own root cause.
3. Migration warning: must uninstall **all** prior BannerHub-ReVanced variants — the legacy C2D_MESSAGE declaration on any single one of them blocks fresh installs from this release.
4. Full patch table updated to include the new `Rewrite custom permissions per variant` row and the per-variant `File manager access` callout.

### README
Bumped latest-stable line to v1.0.1-600 with the new title and link, added the "What's new" callouts and migration instruction, updated `File manager access` and `Change package name` patch sections, added new `Rewrite custom permissions per variant` section.

### Pre-release policy now in effect
Per `feedback_bannerhub_revanced_prerelease.md`, every workflow run from now on returns to artifact-only prerelease mode (no `stable=true`) until the user explicitly says "stable" again.

### Open follow-ups (unchanged from v1.0.0-600)
- Persistent keystore in Actions secrets so cross-release upgrades stop hitting `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Bump `versionCode` per release (cosmetic, but proper).
- Component Manager port resume — branch `component-manager-injection` still pinned at `5b89073`.

## 2026-05-07 — base APK bump to GameHub 6.0.1

### Goal
Verify existing patch bundle still applies cleanly against the new XiaoJi GameHub 6.0.1 base APK.

### What changed
- New base APK on hand: `GameHub_6.0.1.apk` — `com.xiaoji.egggame` versionCode `111` (was `110`), versionName `6.0.1`. Same signing cert (`gamesir`), same `targetSdkVersion=36`.
- Branch: `gamehub-601-build` cut from `gamehub-600-build` per branch-per-patch workflow.
- Commit `ab70d25`: `Constants.kt` `GAMEHUB_VERSION` `6.0.0` → `6.0.1`; `release.yml` source release `base-apk-600` → `base-apk-601` and asset/staged/CLI filenames `GameHub_beta_6.0.0_global.apk` / `GameHub_6.0.0.apk` → `GameHub_6.0.1.apk`. Variant output filenames left at `GameHub-6.0-Patched-*.apk` (version-agnostic enough for now).
- New release `base-apk-601` created with `GameHub_6.0.1.apk` (133 MB) attached.

### Result — CI run [25517417367](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25517417367)
**All 9 variants green** in ~3 min. No fingerprint or smali repair needed; every patch (BypassLogin, DisableCrashlytics, DebugLog, FileManagerAccess, RewriteCustomPermissions, MuteUiSounds, RedirectCatalogApi, PrefixApiPath, ChangePackageName, ChangeAppName) still applies untouched against versionCode 111.

### Implication
6.0.1 is a minor base bump only — no fingerprint targets moved. `gamehub-601-build` is shippable as-is once release notes are written; release-body text + variant output filenames in `release.yml` should be updated before the first stable v1.0.0-601 cut, but those are cosmetic, not functional.

### Next
- Device-test one variant (likely Original) installed alongside or replacing v1.0.1-600.
- If install + login-bypass + import flow OK, draft v1.0.0-601 release notes (or v1.0.2-600 if we treat as a refresh).
- Decide whether to merge `gamehub-601-build` → `gamehub-600-build` after stable, or keep them parallel.

## 2026-05-07 — v1.0.0-601 stable shipped

### Tag
[`v1.0.0-601`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.0-601) — "Gamehub 6.0.1 - BannerHub API - Multi-Install" — 9 APKs + `.rvp` bundle + `.rve` extensions

### Build
- Branch: `gamehub-601-build` (kept separate from `gamehub-600-build` per user direction; not merged back)
- Final commit on branch: `990e30e` (release prep — release.yml/README rewrites)
- Stable CI run: [25518201750](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25518201750), all 9 variants green, release job published successfully

### What shipped
- Base APK: `GameHub_6.0.1.apk` (versionCode 111) from `base-apk-601` release
- Same 9 patches as v1.0.1-600 — bypass login, disable Crashlytics, debug logging, file manager (per-variant), rewrite custom permissions (per-variant), mute UI sounds, redirect catalog API, prefix /v6/, change package name (per-variant), change app name (per-variant)
- Functional delta from v1.0.1-600: zero. This is a base APK refresh only.
- Variant filenames bumped from `GameHub-6.0-Patched-*.apk` to `GameHub-6.0.1-Patched-*.apk` so users can tell which base version they're running.

### Awaiting
- Device test of any v1.0.0-601 variant. v1.0.0-601 should be drop-in compatible with v1.0.1-600 device test results; only base APK changed.

## 2026-05-07 — v1.0.0-601 BROKEN: BypassLoginPatch is a no-op on 6.0.1

### Symptom
Device test of v1.0.0-601 (Original variant, `com.xiaoji.egggame`): app launches, opening game library shows the login wall. Logcat captured 7× `SHOW_SOFT_INPUT_BY_INSETS_API` consistent with login-form text fields gaining focus.

### Diagnosis (proven)
**All four targets in `BypassLoginPatch.kt` silently no-op'd against 6.0.1.** R8 in 6.0.1 renumbered class letters; the patch's hardcoded `Los0;`/`Lxm7;`/`Lis0;`/`Lg8e;` literals still resolve to *some* class in 6.0.1, but those classes have completely different roles:

| Class | 6.0.0 role | 6.0.1 reality |
|---|---|---|
| `Los0;` | DB-backed auth session impl with `h()`/`e()` StateFlow getters | Tiny `Flow.emit` operator wrapper — only `<init>` + `emit()` |
| `Lxm7;` | `GameLibraryRepository.f()` returning userId | A `Lbf3;` SuspendLambda — only `<init>` + `invokeSuspend` |
| `Lis0;` | Auth interface with default `f()` returning `Ll4m;` | The **actual** auth/user repository now (has `UserDao` + `AuthTokenDao` fields); `f()` is `(String, Continuation)→Object` — wrong signature |
| `Lg8e;` | Navigator class with `i(rh0)`/`r(rh0)` Login gates | Kotlin lambda factory `<init>` + `invoke()` only |

**Hard proof:**
- `os0.smali` MD5 identical between base 6.0.1 and patched 6.0.1: `50bb1fc9ea86ab180234aed6fe1e1cd4`
- `FakeAuthToken`/`FakeUserAccount` extension classes ARE bundled in the patched APK (`smali_classes7/app/revanced/extension/gamehub/login/`) but **no smali outside DebugTrace string literal calls them** — the patch never injected the calls.
- CI run 25517417367 + stable run 25518201750 both passed because patcher v22's `firstMethod { ... }` is lenient — when no method matches, it silently no-ops. The "all 9 variants green" was a false positive on 6.0.1.

### Artifacts available on resume
- 6.0.1 base APK decompile: `/tmp/gh601_smali/` (apktool, --no-res)
- 6.0.1 patched APK decompile: `/tmp/gh601_patched_smali/`
- Logcat from device repro: `/home/claude-user/logcat-com.xiaoji.egggame-20260507-170151.txt`
- 6.0.0 (working) decompile may need to be regenerated: `~/GameHub_beta_6.0.0_global.apk` is on disk
- Patch source: `bannerhub-revanced/patches/src/main/kotlin/app/revanced/patches/gamehub/misc/login/BypassLoginPatch.kt`

### Chosen fix path: Option A — string-literal refingerprinting
User picked re-fingerprinting with stable anchors (vs. quick-fix hardcoding new 6.0.1 letters) so the patch survives future minor versions (6.0.2, 6.0.3, …).

### What "string-literal refingerprinting" means here
Instead of `definingClass == "Los0;" && name == "h"`, anchor on things R8 *can't* mangle:
- **String literals** the class references (e.g., SharedPreferences keys like `"auth_token"`, screen route names like `"login"`/`"home"`, error messages)
- **DAO/Entity types** at instance-field level (`com.xiaoji.egggame.core.database.dao.UserDao`, `AuthTokenDao` — these names are stable, kept by R8 keep-rules)
- **Return-type shape** (e.g., method returning `Lf3k;` or kotlinx StateFlow)
- **Method-call sequences** (e.g., a method that calls both `UserDao.observeCurrent()` and `AuthTokenDao.observeCurrent()` is the auth-state combiner)

Concrete fingerprint targets to find in 6.0.1:
1. **Auth-session class** — has `UserDao` + `AuthTokenDao` instance fields; in 6.0.1 this is `Lis0;` (verified). Find via field-type fingerprint, not class name.
2. **isLoggedIn StateFlow getter** — method on the auth class returning `Lf3k;`/StateFlow over `Ljava/lang/Boolean;`. Find via return-type + a string-literal anchor.
3. **User-account StateFlow getter** — method returning StateFlow over the user entity (likely `Lcom/xiaoji/egggame/core/database/entity/UserEntity;` based on 6.0.1 smali).
4. **Auth token getter** — method returning the token wrapper class (was `Ll4m;` in 6.0.0 — verify shape in 6.0.1; probably renamed).
5. **GameLibraryRepository userId getter** — method returning `Ljava/lang/String;` keyed off the auth token.
6. **Navigator gate** — method that contains both `iget Lis0;->...` (or its 6.0.1 equiv) AND a string literal "login"/"home" in proximity. The instruction-pattern match (iget+invoke-interface+if-nez+new-instance) is what we re-anchor on.

### Resume checklist
1. Re-decompile current 6.0.0 base APK (`~/GameHub_beta_6.0.0_global.apk`) to confirm what role each renamed class held → use as cross-reference for what semantics we're trying to match in 6.0.1.
2. Find the auth-session class in 6.0.1 by field-type fingerprint (UserDao + AuthTokenDao). Confirmed candidate: `Lis0;`.
3. For each of the 6 targets above, write a fingerprint that resolves to the right method in 6.0.1.
4. Update `BypassLoginPatch.kt` to use ReVanced patcher's `MethodFingerprint` API instead of literal `definingClass ==` checks.
5. Add a runtime sanity log inside the patched method (one Log.i per gate fires) so future device tests immediately tell us if the patch ran.
6. Build, deploy, device-test on Original variant. Pull logcat and confirm the new sentinel logs fire AND the library opens without login.
7. Cut a v1.0.0-601 hotfix (probably v1.0.1-601) once verified.

### Decision parking lot
- v1.0.0-601 release is BROKEN (all installs hit login wall). Did not roll it back per user direction (we'll fix forward).
- 5.x stable v1.0.1-600 (gamehub-600-build) is unaffected — those patches still work against the 6.0.0 base.

## 2026-05-07 (cont.) — BypassLoginPatch rewritten for 6.0.1

### Mapping derived from base APK decompile
| 6.0.0 letter | 6.0.1 letter | Role |
|---|---|---|
| `Los0;` | `Lrs0;` | Auth-session impl (3 StateFlow fields, UserDao+AuthTokenDao ctor) |
| `Lis0;` | `Lls0;` | Auth-session interface (`a/b/c/d/e/f/g/h` methods) |
| `Lxm7;` | `Lhp7;` | GameLibraryRepository (`b:AUTH_INTERFACE` field, `f()String`) |
| `Lg8e;` | `Lade;` | Navigator (`b:AUTH_INTERFACE` field, `i(Lph0;)V` + `r(Lph0;)V` gates) |
| `Lga0;` | `Lca0;` | Login navigation intent (referenced from gates; not patched directly) |
| `Lrh0;` | `Lph0;` | Navigator i/r param type |
| `Ll4m;` | `Lfdm;` | Auth token wrapper (10-field data class, identical shape) |
| `Lf4m;` | `Ladm;` | User account (27-field data class, identical shape) |
| `Lf3k;` | `Lr8k;` | StateFlow read interface |
| `Lr8o;->r(Object)Lf3k;` | `Lumn;->h(Object)Lt8k;` | MutableStateFlow factory |

### What changed in code (commit pending)
- **`BypassLoginPatch.kt`**: full rewrite with all class letters extracted to a single named const block at top, accompanied by structural anchors (decompile recipes) for each. Patch body unchanged in semantics — same six targets — just sourcing names from the const block. Verified `Lt8k;` IS-A `Lr8k;` via `Lx6e;` so the synthetic flow returned from `rs0.h/e` type-checks against the declared return type.
- **NEW patch on `Lar0;->a(...)`**: 6.0.1 introduced a separate NavigationInterceptor (`getOrder()==10`, `Llxb;` interface) that gates on `Lls0;->a()Z` independently of the navigator. Same iget+invoke-interface+if-nez+new-instance pattern as `ade.i/r`; bypassed identically with `const/4 vN, 0x1`.
- **`FakeAuthToken.java`**: `Class.forName("l4m")` → `Class.forName("fdm")`, hoisted to `AUTH_TOKEN_CLASS` const for one-line bumps next time. Same 10-arg ctor (verified shape identical between `Ll4m;` and `Lfdm;`).
- **`FakeUserAccount.java`**: `Class.forName("f4m")` → `Class.forName("adm")`, hoisted to `USER_ACCOUNT_CLASS` const. Same 27-arg ctor (verified shape identical).
- **No inline `Log.i` sentinels** in the patched method bodies: `rs0.h/e` and `ls0.f` are `.locals 0`, can't accommodate two free registers without growing locals (which patcher v22's `addInstructions` doesn't auto-do). Sentinel signal is provided by the existing `DebugTrace.write` calls inside `FakeAuthToken.get()` and `FakeUserAccount.get()` — the next device test's logcat will show "FakeAuthToken.get() called" / "FakeUserAccount.get() called" lines tagged GH600-DEBUG, which only fire if the `ls0.f()` / `rs0.e()` patches landed.

### Why this still requires letter updates next minor version
Even with the const block, the letters `Lrs0;`/`Lls0;`/`Lhp7;`/`Lade;`/`Lar0;`/`Lumn;`/`Lt8k;`/`Lfdm;`/`Ladm;` will all rotate again on the next R8 build. The improvement vs. the original patch is:
- All letters are in ONE place (const block + 2 Java strings), so re-deriving a new mapping is a 9-string PR instead of a 4-file scavenger hunt.
- Each const has a structural anchor comment so the resolver recipe is recorded.
- True version-independence (no manual updates ever) would require resolving classes by structural fingerprint at patch time. Deferred — that's a bigger refactor and the const-block approach is sufficient for the 6.0.x series.

### Next: CI prerelease + device test
Trigger Release workflow (no `stable=true`) on `gamehub-601-build`. Pull artifact APK, install Original variant, repro library tap. Logcat must show:
- `GH600-DEBUG: FakeAuthToken.get() called`  → `ls0.f()` patch fired
- `GH600-DEBUG: FakeUserAccount.get() called` → `rs0.e()` patch fired
- Library opens without login wall → `rs0.h()` + navigator + interceptor patches all working

## 2026-05-07 (cont. 2) — API redirect patches also broken on 6.0.1

### Symptom
Device test of bypass-fix prerelease: login bypass + mute UI work, but BannerHub catalog API redirect doesn't take effect. App still hits upstream landscape-api.vgabc.com instead of the Worker.

### Diagnosis
Same R8 letter-shuffle pattern as BypassLoginPatch. Verified by inspecting prerelease patched APK: `zhj.smali` (the new Environment enum) still contains `"landscape-api-cn.vgabc.com"` and `"landscape-api-oversea.vgabc.com"` literals — `RedirectCatalogApiPatch.kt` silently no-op'd because it's hunting `Lmcj;` which is a different class in 6.0.1.

### Mapping
| 6.0.0 | 6.0.1 | Role |
|---|---|---|
| `Lmcj;` | `Lzhj;` | Catalog Environment enum (Online/Beta/Test); contains the host string literals |
| `Lzdb;` | `Lohb;` | Static URL-path helper (Ktor pipeline) |
| `Lqx9;` | `Lj1a;` | URL builder param type (Ktor HttpRequestBuilder.url) |
| `Lm1l;->t1` | `Lu9l;->s1` | String trim helper (referenced inside the body, not patched directly) |

### Fix
- **`RedirectCatalogApiPatch.kt`**: `MCJ_CLASS = "Lmcj;"` → `ENV_ENUM_CLASS = "Lzhj;"` with structural anchor comment ("the unique class containing both `landscape-api-cn.vgabc.com` and `landscape-api-oversea.vgabc.com`"). String literals are R8-stable so the anchor survives future minor versions; only the ONE letter `Lzhj;` needs updating per bump (or could be auto-derived).
- **`PrefixApiPathPatch.kt`**: `ZDB_CLASS = "Lzdb;"` → `URL_HELPER_CLASS = "Lohb;"`, plus new `URL_BUILDER_TYPE = "Lj1a;"` const replacing the hardcoded `"Lqx9;"` in the parameterTypes match. Body shape is byte-stable across versions; the anchor comment records the structural recipe (static method `(LBuilder;String)V` whose body starts iget-object from the builder's URL field then calls a string-trim helper).

### Re-verify after this fix
After CI: pull the new artifact, decompile, confirm `zhj.smali` now shows `"bannerhub-api.the412banner.workers.dev"` in BOTH host slots; confirm `ohb.b(Lj1a;String)V` opens with `invoke-static {p1}, V6PathPrefix->prefix(...)`.

## 2026-05-07 (cont. 3) — Device test ALL GREEN; cutting v1.0.1-601 hotfix

User confirmed prerelease build (run 25526407710, commit 3f81890) on Original variant: login bypassed, library opens, BannerHub catalog API redirect working, mute UI working. All 9 patches now actually applying as intended on 6.0.1.

### Cut as v1.0.1-601 stable
- Tag: v1.0.1-601
- Title: "Gamehub 6.0.1 - BannerHub API - Multi-Install" (same as v1.0.0-601)
- release.yml body rewritten with "Hotfix vs v1.0.0-601" section explaining the 3 patches that no-op'd and now don't
- README "Latest stable" pointer + What's-new block updated; broken-build warning added at the top
