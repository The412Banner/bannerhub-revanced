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

## 2026-05-07 (cont. 4) — v1.0.1-601 SHIPPED + cleanup

### Live release
[`v1.0.1-601` — Gamehub 6.0.1 - BannerHub API - Multi-Install](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.1-601). 9 APKs + .rvp bundle + .rve extensions. CI run 25526848503, branch head `9ea01d4`.

### Cleanup
- Deleted v1.0.0-601 release entirely (was already de-listed; tag also removed from origin + local).
- v1.0.1-601 is now Latest.
- Memory file `project_bypass_login_601_refingerprint.md` deleted (obsolete now that the fix shipped).
- Memory `project_bannerhub_revanced.md` updated: gamehub-601-build entry rewritten, "Active next step" block rewritten to reflect shipped state, MEMORY.md index entry updated.

### What we know works on 6.0.1 (per device test of prerelease run 25526407710)
1. ✅ Bypass login (rs0.h/e + ls0.f + hp7.f + ade.i/r + ar0.a)
2. ✅ Redirect catalog API (zhj Online enum hosts → Worker)
3. ✅ Prefix API path with /v6 (ohb.b inserts V6PathPrefix call)
4. ✅ Mute UI sounds
5. (untested but applied per CI) Disable Crashlytics, Debug logging, File manager, Rewrite custom permissions, Change package name, Change app name

### Known follow-up
None blocking. Possible future work: migrate the const-block letter mapping to true MethodFingerprint-based auto-discovery so future minor-version bumps don't require any source edits at all. Deferred — current setup is good enough for the 6.0.x series.

## 2026-05-07 — vjoy cloud-share Worker proxy (cross-repo) + docs

User reported the new 6.0.1 cloud-share vjoy/Scheme screen still showed "log in first" even with bypass-login active. Diagnosed via temporary KV-debug intercept on the Worker: the 401 is server-side (upstream rejects unauthenticated requests on `vcontroller/recommendMapList`); the client sends GETs with `clientparams`/`sign`/`time` headers but no token at all. Existing Worker fall-through stripped all original headers and never injected a token, so upstream got an anonymous request → 401 → "Please login first".

Fixed in `bannerhub-api` repo (commit `0792400` on master + main): new dedicated handler covering `vcontroller/*`, `simulator/{configList,getConfigById,shareConfig,deleteShareConfig,reportConfigApply}`, `readLayoutType/*`, `writeLayoutType/*`. Forwards original request headers verbatim, drops only hop-by-hop and CF-injected ones, injects `token: <bannerhub_token>` from KV, and recomputes `sign` for POST bodies that contain a token field. Verified live → device-confirmed: full upstream catalog visible (GTA5专用按键, Gamehub 2, etc.). Side-effect: every BannerHub user authenticates as the same shared `bannerhub_token` user — acceptable for now.

### Documentation updates
- `gamehub_reports/GAMEHUB_600_MASTER_MAP.md` — added § 26 (6.0.0 → 6.0.1 deltas) covering APK identity bump, full R8 letter-remap table with structural anchors, new vjoy/Scheme cloud-share subsystem (NavKeys, data model, repository, ViewModel, on-device storage), new API endpoint family, new `Lar0;` NavigationInterceptor, firmware 1.3.4 → 1.3.5, upstream feature highlights. Map grew 2556 → 2702 lines.
- `gamehub_reports/BANNERHUB_API_6.0_INTEGRATION.md` — added § 14 (2026-05-07 6.0.1 changes) covering v1.0.1-601 hotfix, R8 letter remap, firmware bump, new endpoint family, captured request-shape table, Worker proxy implementation, verification, files-touched manifest.
- Memory: `project_gamehub_600_master_map.md`, `project_bannerhub_api_60_integration_report.md`, `project_bannerhub_api_worker.md`, `MEMORY.md` index — all updated to reflect the new sections + line counts + the "on the next 6.0.x bump" instructions for re-deriving R8 letters via structural anchors.

## 2026-05-10 — gamehub-602-build (GameHub 6.0.2 base bump)

### Goal
Bump base from GameHub 6.0.1 (versionCode 111) to 6.0.2 (versionCode 112). Re-anchor every bytecode patch against the new R8 letter map, mirror what we did for the 6.0.0 → 6.0.1 jump.

### Branch + base APK
- Branch: `gamehub-602-build`, forked from `gamehub-601-build` head (`9ea01d4`).
- Base APK: `GameHub_6.0.2.apk` (135 MB, package `com.xiaoji.egggame`, versionCode 112).
- Hosted as release tag [`base-apk-602`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-602) (target = `gamehub-602-build`). Workflow downloads from there during patch-build.
- Decompile: `apktool d --no-res` against the 6.0.2 APK lands in `/tmp/gh602_smali/` across `smali/`, `smali_classes2/` … `smali_classes6/` (6 dex shards, up from 5 on 6.0.1). r8-map-id `032c299c671f291b037da144c04f4b9bdf25a0ddc75c43b14ff2382d5f50d1fa` is the unique build identifier — every smali file's `.source` line carries it, useful as a single-grep verification that the decompile is actually 6.0.2 and not a 6.0.1 leftover.

### Fingerprint verification — every single bytecode anchor moved

Same situation as 6.0.0 → 6.0.1: a sweeping R8 letter reshuffle. None of the 6.0.1 letter classes still hold the same shape in 6.0.2 (some keep their letter but the body is now an unrelated coroutine continuation). New mappings derived via the structural-anchor recipes recorded in each patch source:

| Patch | Anchor | 6.0.1 | 6.0.2 | How re-derived |
|---|---|---|---|---|
| BypassLogin | AUTH_IMPL | `Lrs0;` | `Lit0;` | Class with ctor `(UserDao, AuthTokenDao, Lu70;)V` + 3 same-type StateFlow fields |
| BypassLogin | AUTH_INTERFACE | `Lls0;` | `Lct0;` | `.implements` line on AUTH_IMPL — interface with abstract `d/e/h()Lrjk;` |
| BypassLogin | AUTH_TOKEN | `Lfdm;` | `Lkpm;` | 10-field data class returned by `ct0.f()`; ctor sig `(S,S,S,S,Long,Long,J,Z,J,J)V` matches exactly |
| BypassLogin | GAME_LIB_REPO | `Lhp7;` | `Luu7;` | Class with `b:Lct0;` field + ctor `(GameLibraryDatabase, Lct0;)V`. ⚠ userId getter renamed `f()` → `e()` between 6.0.1 and 6.0.2 — patch updated to filter by `parameterTypes.isEmpty() && returnType == "Ljava/lang/String;"` to avoid matching unrelated overloads |
| BypassLogin | NAVIGATOR | `Lade;` | `Lxle;` | Class with `b:Lct0;` field + `i(Lgi0;)V` and `r(Lgi0;)V` methods that contain the `iget b:ct0` + `invoke-interface ct0->a()Z` + `new-instance Lsa0;` (Login intent — was Lca0; in 6.0.1) gate pattern at xle.i:270 / xle.r:79 |
| BypassLogin | NAV_INTERCEPTOR | `Lar0;` | `Lrr0;` | Class with `<init>(Lct0;)V` ctor + `a(Lp4c;Ls2c;Lzh3;)Object` that calls `ct0.a()Z` then builds `Lo5c;` redirect |
| RedirectCatalogApi | ENV_ENUM_CLASS | `Lzhj;` | `Lxrj;` | Unique class containing both `landscape-api-cn.vgabc.com` and `landscape-api-oversea.vgabc.com` string literals (in xrj's `<clinit>` at lines 41/45) |
| PrefixApiPath | URL_HELPER_CLASS | `Lohb;` | `Lvob;` | Static method `b(L<short>;Ljava/lang/String;)V` whose body starts with `iget-object` from the builder's URL field then `Lpll;->s1(CharSequence)CharSequence` trim |
| PrefixApiPath | URL_BUILDER_TYPE | `Lj1a;` | `Lm7a;` | First param of vob.b — the Ktor URLBuilder analog |
| DebugLog | y2d-impl | `Lodb;` | `Li86;` | Concrete class implementing `pgd.e(Throwable, Lmw6;)V` whose body delegates to `pgd.e` then writes to a sink |
| DebugLog | save method | `Lxm7;->u` | `Luu7;->v` | 3-arg `(GameInfo, LaunchMethod, Continuation)` method on uu7 that calls `pgd.e` in catch (line 322) |
| DebugLog | retro upsert wrapper | `Ly4i;->b` | `Lyji;->b` | Single class referencing `RetroGameDao;->upsert` (yji.smali:97) |
| DebugLog | y2d-interface | `Ly2d;` | `Lpgd;` | Abstract method `e(Ljava/lang/Throwable;Lmw6;)V` (`Lmw6;` is the Function0 type) |
| DebugLog | inner Room txn | `Lel7;` | `Lvs7;` | Continuation class with `invokeSuspend.locals 70` (closest to original `el7` `.locals 69`) and both `GameLaunchMethodDao.insert` + `GameLibraryBaseDao.insert` |

Patches keyed on full Android / Firebase / asset-path / manifest names — Disable Crashlytics (anchored on `Lcom/xiaoji/egggame/BaseAndroidApp;->onCreate` + `FirebaseCrashlytics->getInstance` / `setCrashlyticsCollectionEnabled`), Mute UI sounds (asset path `assets/composeResources/com.xiaoji.egggame.core/files/sound`), File manager access (manifest), Rewrite custom permissions (manifest), Change package name / Change app name (revanced built-ins) — apply byte-for-byte without any source change.

### MutableStateFlow factory surgery

The 6.0.0 / 6.0.1 patch could call `Lumn;->h(Object)Lt8k;` inline — a one-arg StateFlow factory whose return type was directly assignable to `AUTH_INTERFACE.h()` / `.e()`'s declared return type. In 6.0.2 the only one-arg factory is `Ltwo;->l(Object)Ltjk;`, and `Ltjk;` does NOT implement the abstract StateFlow interface (`Lrjk;`) that `h()`/`e()` declare; the host wraps it in `Lhzh;` (which DOES implement Lrjk;) before exposing it via `Leuo;->e0` (stateIn).

Doing the same wrap inline from smali would require growing the patched method's `.locals` from 0 to 2 (need scratch registers for the inner Ltjk; instance and the outer Lhzh; instance). To avoid that, added a `FakeStateFlow` Java extension that does the wrap via reflection (`Class.forName("tjk")` → `<init>(Object)` → `Class.forName("hzh")` → `<init>(vfe)` where `Class.forName("vfe")` is the Lhzh; ctor's interface arg). Both factories cached after first build. Smali edit stays a single `invoke-static`:

```smali
invoke-static {}, FakeStateFlow->boolTrue()Ljava/lang/Object;
move-result-object p0
return-object p0
```

Updated FakeAuthToken (`adm` → `kpm`) and FakeUserAccount (`fdm` → `fpm`) extension class refs. `FakeUserAccount`'s 27-arg ctor signature stayed byte-identical between 6.0.1 and 6.0.2.

### Workflow + release notes
`.github/workflows/release.yml` rewritten for 6.0.2: base APK download tag, all 9 variant filenames (`GameHub-6.0.2-Patched-*.apk`), release name + body. Release narrative documents the 6.0.1 → 6.0.2 letter remap so future readers don't have to guess.

### CI verification
First test build kicked off as run [`25619647877`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25619647877) on `gamehub-602-build` via `gh workflow run release.yml --ref gamehub-602-build -f tag=v0.1.0-602-test` (artifact-only prerelease — not a stable cut). Watching for green; if patches build cleanly the next step is device-test the 9 APKs to confirm bypass-login + catalog-redirect + /v6 prefix all fire end-to-end.

### CI verification result
Run [`25619647877`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25619647877) **green in 2m49s** (started 04:20:19 UTC, finished 04:23:08 UTC):
- ✅ Build patches bundle
- ✅ Patch Normal / Normal-GHL / PuBG / AnTuTu / alt-AnTuTu / PuBG-CrossFire / Ludashi / Genshin / Original (all 9)
- ⏭ Create GitHub Release (intentionally skipped — `stable=false` on this test run)

Every re-anchored fingerprint matched at patcher time. All 9 APK artifacts (~111 MB each) on the run, 14-day retention. **Next step: device test** — install the `Original` artifact (or any of the variants) and confirm bypass-login + catalog redirect + /v6 prefix all fire on 6.0.2 the same as they did on 6.0.1, then cut a stable release.

### Stable release — v1.0.0-602 (2026-05-10 14:59 UTC)

[`v1.0.0-602` — Gamehub 6.0.2 - BannerHub API - Patched](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.0-602) is live. Stable cut as run [`25631854018`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25631854018) — all 9 variant patch jobs succeeded plus the `release` job (`stable=true`), 12 assets attached: the 9 patched APKs + the `.rvp` patch bundle + the 2 `.rve` extension files. Device-tested by user before stable cut. Release notes ship the 6.0.1 → 6.0.2 R8 letter remap table and the `FakeStateFlow` rationale verbatim.

This is the first stable on the `gamehub-602-build` branch and the first one in the project's history that didn't require a `.0` → `.1` hotfix because the structural-anchor recipes recorded after the 6.0.0 → 6.0.1 fiasco let every fingerprint be re-derived correctly on the first try. README updated to reflect 6.0.2 as the latest stable; v1.0.1-601 / v1.0.0-601 / v1.0.1-600 sections moved into "historical" sections at the top.

## 6.0.4 base port — 2026-05-12

### Setup

User requested 6.0.4 base bump from 6.0.2 stable. `GameHub_6.0.4.apk` (versionCode 114, versionName 6.0.4) staged from Downloads → home → released as `base-apk-604`. Decompile to `/tmp/gh604_smali` with the standard recipe. New branch `gamehub-604-build` cut off `gamehub-602-build` head `abf1eac` per the branch-per-patch rule.

### R8 letter delta (6.0.2 → 6.0.4)

R8 map id `6a5cde6143fc8cf76f6f3a447d0fececd4794d83066e6ead7a9537e6527b057b` (vs 6.0.2's `032c299c…`). Every anchor reshuffled again. Full per-anchor verification + structural-recipe trace lives in `gamehub_reports/GH604_LETTER_MAP.md`.

| Patch | Anchor | 6.0.2 | 6.0.4 | Structural verification |
|---|---|---|---|---|
| BypassLogin | AUTH_IMPL | `Lit0;` | `Ljt0;` | 3× `Lozh;` fields, ctor `(UserDao, AuthTokenDao, Lv70;)V`, implements `Ldt0;` |
| BypassLogin | AUTH_INTERFACE | `Lct0;` | `Ldt0;` | Abstract `d/e/h()Lyjk;` + `f()Lwpm;` + `b()Lrpm;` |
| BypassLogin | AUTH_TOKEN | `Lkpm;` | `Lwpm;` | 10-field data class returned by `dt0.f()`, ctor sig `(S,S,S,S,Long,Long,J,Z,J,J)V` matches exactly |
| BypassLogin | GAME_LIB_REPO | `Luu7;` | `Lvu7;` | Class with `b:Ldt0;` field + ctor `(GameLibraryDatabase, Ldt0;)V`. User-id getter still `e()` |
| BypassLogin | NAVIGATOR | `Lxle;` | `Lgme;` | `b:Ldt0;` field + `i(Lhi0;)V` and `r(Lhi0;)V` methods carrying iget+invoke+if-nez+new-instance `Lta0;` (Login intent — was `Lsa0;` in 6.0.2) |
| BypassLogin | NAV_INTERCEPTOR | `Lrr0;` | **`Liod;` (skipped)** | Inline auth check moved into coroutine continuation `Lhod;->invokeSuspend` (lines 255/259/267). Apply block commented out; option C TODO recorded for later |
| RedirectCatalogApi | ENV_ENUM_CLASS | `Lxrj;` | `Lesj;` | Unique class containing both `landscape-api-cn.vgabc.com` + `landscape-api-oversea.vgabc.com` |
| PrefixApiPath | URL_HELPER_CLASS | `Lvob;` | `Lcpb;` | Static `b(Ln7a;Ljava/lang/String;)V` body: iget URL field → invoke trim → toString → length |
| PrefixApiPath | URL_BUILDER_TYPE | `Lm7a;` | `Ln7a;` | First param of cpb.b; field `a:Lokm;` (Ktor builder shape preserved). Trim helper moved from `Lpll;->s1` to `Lbml;->s1` (patch doesn't reference it directly) |
| DebugLog | y2d-impl | `Li86;` | `Lj86;` | Concrete class with `e(Throwable, Lnw6;)V` delegating to `Lxgd;->e` |
| DebugLog | y2d-interface | `Lpgd;` | `Lxgd;` | Abstract `e(Throwable, Lnw6;)V` + 9 other methods. `Lnw6;` is the Function0 type (was `Lmw6;`) |
| DebugLog | save method | `Luu7;->v` | `Lvu7;->v` | Same method name; new owning class follows GAME_LIB_REPO |
| DebugLog | retro upsert wrapper | `Lyji;->b` | `Lfki;->b` | Single field `a:RetroGameDao`, method `b(RetroGameEntity, Ci3)Object` is the only meaningful upsert wrapper |
| DebugLog | inner Room txn | `Lvs7;` | `Lws7;` | `.locals 70`, calls both `GameLaunchMethodDao;->insert` and `GameLibraryBaseDao;->insert` |
| OfflineComponentCache | ECI_CLASS | `Leci;` | `Lmci;` | `a(RepoCategory, Lci3;)Ljava/io/Serializable;` — unique match in dex tree |
| OfflineComponentCache | CONTINUATION_TYPE | `Lai3;` | `Lci3;` | Inferred from mci.a's second parameter |
| OfflineComponentCache | KOTLIN_EMPTY_LIST_CLASS | `Lz85;` | `Lw85;` | `implements List, Serializable, RandomAccess` (kotlin.collections.EmptyList) — sget'd at `:goto_2` in mci.a |
| FakeAuthToken ext | AUTH_TOKEN_CLASS | `kpm` | `wpm` | Same as BypassLogin AUTH_TOKEN |
| FakeUserAccount ext | USER_ACCOUNT_CLASS | `fpm` | `rpm` | 27-field class matching reflective 27-arg ctor lookup; `dt0.b()` returns `Lrpm;` |
| FakeStateFlow ext | STATE_FLOW_IMPL_CLASS | `tjk` | `akk` | `<init>(Object)V`, implements `Ldge;` (the holder iface) |
| FakeStateFlow ext | STATE_FLOW_WRAPPER_CLASS | `hzh` | `ozh` | `<init>(Ldge;)V`, implements `Lyjk;` (the abstract StateFlow interface) |
| FakeStateFlow ext | STATE_FLOW_HOLDER_INTERFACE | `vfe` | `dge` | Inferred from ozh ctor + akk `.implements` |

Patches unchanged (no R8-renamed anchors): Disable Crashlytics, Mute UI sounds, File manager access, Rewrite custom permissions, Change package/app name. Constants.GAMEHUB_VERSION bumped `6.0.2` → `6.0.4`.

### NAV_INTERCEPTOR architectural change (the "fingerprint problem")

In 6.0.0–6.0.2 the NAV_INTERCEPTOR's `a(...)` method body held the auth check inline — patch hooked on `iget AUTH_INTERFACE` + `invoke-interface a()Z` + `if-nez` + `new-instance Lredirect;`. In 6.0.4 `Liod;->a(Lrdb;Lzzn;Laem;)V` no longer iget's its `a:Ldt0;` field directly — it constructs a coroutine continuation `Lhod;` and dispatches to it. The pattern the patch looked for now lives at `Lhod;->invokeSuspend` (lines 255/259/267), reading the AUTH_INTERFACE from `p1` (the outer iod reference) not `p0` (this is now the continuation).

Three options documented:
- **A (chosen for v1)**: Skip NAV_INTERCEPTOR entirely. Other 6 BypassLogin hooks (AUTH_IMPL fake StateFlows + NAVIGATOR i/r gates + GAME_LIB_REPO user-id getter + is0.f → FakeAuthToken) may cover the user-facing surface. Cheapest.
- **B**: Patch `Liod;->a` body wholesale (return-void or passthrough). Risk: unclear what downstream navigation expects.
- **C**: Hook `Lhod;->invokeSuspend` directly — most surgical but needs continuation-state-machine-aware edits.

If device testing reveals a login-redirect leak, implement option C. The `NAV_INTERCEPTOR` constant value is kept (`@Suppress("unused")` `"Liod;"`) and the apply block left commented in `BypassLoginPatch.kt` for archaeology.

### CI verification result

Run [`25747297755`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25747297755) on `gamehub-604-build` head `147d63c` triggered via `gh workflow run release.yml --ref gamehub-604-build -f tag=v0.0.1-604-test` (artifact-only prerelease). All 11 jobs green:

- ✅ Build patches bundle
- ✅ Patch Normal / Normal-GHL / PuBG / AnTuTu / alt-AnTuTu / PuBG-CrossFire / Ludashi / Genshin / Original (all 9 variants)
- ⏭ Create GitHub Release (intentionally skipped — `stable=false`)

Every re-anchored fingerprint matched at patcher time. 9 APK artifacts on the run (14-day retention). **Next step: device test** — install any variant, confirm launch lands on home screen (bypass login working), confirm game-import save persists (library DB writes working), confirm catalog redirect + /v6 prefix all fire. If no login-redirect leak from the skipped NAV_INTERCEPTOR, cut stable. If a leak surfaces, implement option C.

### Known caveat — release notes

`.github/workflows/release.yml` body text still narrates the 6.0.1 → 6.0.2 migration. Workflow + variant filenames + `base-apk-604` download + release title were swapped, but the long-form release-page markdown remains 6.0.2-flavored. Only relevant if `stable=true` is flipped on the next dispatch; rewrite before cutting a 6.0.4 stable release.

### Device-test pass — 2026-05-12

User installed and verified one of the run 25747297755 artifacts on real hardware. End-to-end working: bypass-login lands on home screen, catalog redirect to BannerHub Worker + /v6 prefix both firing, game-import path persists rows correctly. The skipped NAV_INTERCEPTOR (Option A) had no observable effect — the remaining six BypassLogin hooks (AUTH_IMPL h/e/d + NAVIGATOR i/r gates + GAME_LIB_REPO.e + is0.f → FakeAuthToken) cover the user-facing surface fully on 6.0.4. Option C (hook `Lhod;->invokeSuspend`) is **not needed**.

**Status:** 6.0.4 patch port is feature-complete and verified. Ready for stable cut (`v1.0.0-604`) once release.yml's body text is rewritten from 6.0.1→6.0.2 narrative to 6.0.2→6.0.4 narrative.

### Stable release — v1.0.0-604 (2026-05-12 17:56 UTC)

[`v1.0.0-604` — Gamehub 6.0.4 - BannerHub API - Patched](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.0-604) is live. Stable cut as run [`25752321469`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25752321469) — all 9 variant patch jobs succeeded + the `release` job (`stable=true`), 12 assets attached (9 APKs ~114 MB each + the `.rvp` bundle/sources). Branch head at release: `508cede` on `gamehub-604-build`.

Release notes ship the 6.0.2 → 6.0.4 R8 letter remap table, the `NAV_INTERCEPTOR` skip rationale (Liod;->a's inline auth check moved into the `Lhod;` coroutine continuation in 6.0.4 — left commented in the patch source as a starting point for option C if a future regression requires it), the `FakeStateFlow` letter-trio update (`tjk/hzh/vfe` → `akk/ozh/dge`), the `Offline component cache fallback` patch's first stable shipping (was on `gamehub-602-build` post-602 stable but never made it into a release), and — at the user's call-out — the **server-side Steam-launch fix** in the BannerHub API Worker. The previous "use Lightweight Steam client only" warning is dropped from both `release.yml` and `README.md`; both standard and Lightweight Steam clients now launch games end-to-end (server-side, retroactive to existing patched builds).

Second consecutive base bump (after v1.0.0-602) where the structural-anchor recipes caught every fingerprint on the first patcher run — no .0 → .1 hotfix needed.

README updates: header bumped to 6.0.4 latest stable, v1.0.0-602 moved to historical, offline-cache "unreleased" markers dropped, Source + Build sections point at `base-apk-604` and `gamehub-604-build`, Variants table file names refresh, Known limitations drops the standard-Steam-client warning.

### Post-release tweak — xtask first-launch hint (2026-05-12)

User-flagged caveat for v1.0.0-604: if a user's **first** Steam game launch errors with `xtask install components failed`, the workaround is to open that title's PC game settings, set components manually (Wine prefix, DXVK, VKD3D, container, etc.), confirm the correct Steam client is selected, then retry — it's a one-time setup hiccup, not a fatal regression. Added to both `release.yml` body (commit `b9397c8`) and README's "what's new" Steam bullet; live `v1.0.0-604` release body refreshed via `gh release edit --notes-file` so the in-flight published page already carries the hint.

### Post-release release-page trim (2026-05-12)

User feedback: the v1.0.0-604 release page is too long; the per-patch R8 letter-by-letter delta table and the per-patch smali-edit walkthrough in the "Patches applied" table aren't useful for end users. Trimmed both:

- The base-APK-refresh section dropped its six bullet-pointed letter-by-letter remap and now just says the patches were re-anchored, with a pointer line to `gamehub_reports/GH604_LETTER_MAP.md` + the patch sources for anyone who wants the gritty detail. (commit `2771684`)
- The "Patches applied" table rows are now one-line user-facing descriptions of what each patch does, instead of the patcher-side mechanics. A single sentence below the table links to the README's Patches applied section + the patch source directory for the deeper breakdown. (commit `3b89575`)

Both updates pushed to `release.yml` for future re-cuts and mirrored onto the live `v1.0.0-604` release body via `gh release edit --notes-file`. README is intentionally untouched — its long-form section is the canonical deep-dive and the release page now links to it.

### Offline component cache fallback — flagged broken on 6.0.4 (2026-05-12)

User reported the offline component cache fallback patch isn't delivering the intended behavior at runtime on 6.0.4 despite patcher-side success (CI green, the patch applies, structural anchors all resolved correctly). Flagged as **currently broken** in `release.yml` body, the README's "what's new" bullet AND its detailed `### Offline component cache fallback` section, and at the top of `OfflineComponentCachePatch.kt` itself. Live `v1.0.0-604` release body refreshed via `gh release edit --notes-file` so users see the warning. Patch stays enabled per user direction.

Investigation angles to check on the next pass (recorded at top of the patch source):
- Has `mci.a(RepoCategory, Continuation)` itself changed shape beyond the `:goto_2` sentinel? Walk the full method body and compare against the 6.0.2 `eci.a` body the patch was designed around.
- Did `xxo`'s field layout / `xxo.c` `ConcurrentHashMap` type change? `PickerCacheFallback.fromXxo` uses single-letter field lookups (`a`, `c`) plus a runtime type sanity check; if `c`'s declared type is no longer assignable to `Map`, the sanity check returns the empty ArrayList silently — visible only via `DebugTrace`.
- Is `u6o.<init>` still the disk-hydrator? If 6.0.4 renamed/restructured the hydrator the map could simply be empty at the time the picker consults it, in which case the patch IS firing but has nothing to return.

## Vibration port to 6.0.4 — feature/vibration branch (2026-05-12)

User requested porting BannerHub PR #80 (TideGear's PC-accurate XInput rumble support, shipped in BannerHub v3.7.0 stable on 5.3.5) as a ReVanced patch for our 6.0.4 build. TideGear had already done the legwork to port it to GameHub 6.0.2 at https://github.com/TideGear/GameHub-Vibration-Fix — only 4 smali hooks on 6.0.2 (vs 5 on 5.3.5; 6.0 fixed the lazy-attach issue natively so the GamepadManager.B0 wake-up hook is unnecessary).

### Feasibility verification

Verified each of TideGear's 4 smali anchors against the 6.0.4 decompile. Trap caught: the 6.0.2 letters `Lza8;` (Physical) and `Ldg5;` (EnvBuilder) both still exist as class names in 6.0.4, but R8 reassigned them to completely unrelated classes (an empty marker interface and a coroutine continuation respectively). Naive name matching would have patched the wrong code; structural matching by method shapes + field layouts found the true 6.0.4 equivalents.

### 6.0.2 → 6.0.4 vibration-anchor delta

| Symbol | 6.0.2 (TideGear) | 6.0.4 (re-derived) | Recipe |
|---|---|---|---|
| `GamepadServerManager.onRumble(III)V` | same | same | Annotated `@Keep`, `:cond_4` label preserved |
| Physical class | `Lza8;` | `Lab8;` | `public final` extends `Lcb8;`, `g(II)V`/`f()V` shapes preserved |
| Physical.k field type | `Llrl;` | `Lxrl;` | Motor manager |
| EnvBuilder class | `Ldg5;` | `Lbg5;` | `a(...)V` `.locals 35`, anchor block lines 458-465 byte-identical |
| Join helper class | `Lns2;` | `Lps2;` | CollectionsKt joinToString$default |
| Join method name | `I0` | `I0` | **survived R8** |
| Function1 lambda type | `Low6;` | `Lpw6;` | |

### Branch state

`feature/vibration` cut off `gamehub-604-build` head `65e6902` 2026-05-12. Head: `4b25858`.

### Stage 1 — bytecode hooks + manifest registration (commit `0ae2228` → `248f7bd`)

- `extensions/gamehub/.../com/xj/winemu/vibration/BhVibrationController.java` (1106 lines, TideGear's package preserved verbatim — only Android SDK imports, no host references)
- `extensions/gamehub/.../com/xj/winemu/vibration/BhVibrationSettingsActivity.java` (266 lines)
- `patches/.../gamehub/vibration/VibrationPatch.kt` — 4 bytecode hooks with the 6.0.4 letters above
- `patches/.../gamehub/vibration/VibrationManifestPatch.kt` — registers BhVibrationSettingsActivity (exported=false, translucent theme)
- `extensions/gamehub/build.gradle.kts` — added lint suppression for `MissingPermission` / `NewApi` / `WrongConstant` (false positives — host APK declares VIBRATE permission and host targets Android 14, but extension lint runs in isolation against compile-only stubs).

CI run [`25761322965`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25761322965) green on commit `248f7bd`. Bytecode patches all applied across the 9 variants — but the LD_PRELOAD inject was inert (no .so to find at runtime).

### Stage 2 — NDK build + native-shim injection (commit `d9b9c96` → `4b25858`)

- `native/evshim/evshim.c` + `CMakeLists.txt` (TideGear's source copied verbatim — 698 lines of C, patches `winebus.so`'s `pSDL_JoystickRumble` + `pSDL_JoystickClose` .bss pointers via `LD_PRELOAD`)
- `patches/.../gamehub/vibration/VibrationLibPatch.kt` — resource patch that reads `libevshim.so` from the .rvp's classloader resources and writes it into the staged APK's `lib/arm64-v8a/`. Sentinel class (`private object VibrationLibResources`) used as classloader anchor to dodge Kotlin's self-referential type inference (can't reference `vibrationLibPatch::class` inside its own initializer body).
- `.github/workflows/release.yml` — new "Build libevshim.so" step inserted before the gradle build: locates the runner's NDK, builds via cmake/ninja for arm64-v8a android-29, drops the output under `patches/src/main/resources/lib/arm64-v8a/` so gradle bakes it into the .rvp.

CI run [`25761713424`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25761713424) green on commit `4b25858`. Now end-to-end: NDK builds .so → gradle bakes it into .rvp → revanced-cli applies → resource patch copies .so into APK's lib dir → bytecode hook injects LD_PRELOAD at runtime → libevshim re-issues SDL rumble every 500ms to defeat the 1s auto-stop.

### Pending

**Device test.** Pull any variant from run 25761713424 artifacts (14-day retention) or trigger a fresh run with a named tag. Install on a phone with at least one Bluetooth rumble-capable controller (DualSense / DS4 / 8BitDo Pro 2 in XInput mode). Launch any Wine PC game that uses XInput rumble (Brawlhalla, Diablo, etc.). Expected: heavy/light motors driven independently, sustained holds last as long as the in-game rumble effect, instant release on let-go.

If the device test passes, merge `feature/vibration` → `gamehub-604-build` and cut a follow-up release (e.g. `v1.1.0-604` for the feature bump).

### Stage 3 — Hook 4 VerifyError fix (2026-05-12)

**Device crash on first game launch.** User installed a variant from run 25761713424, opened a game, and `banner.hub:wine` immediately died at `WineActivity.onCreate` with:

```
java.lang.VerifyError: Verifier rejected class bg5:
  void bg5.a(eco, java.lang.String, boolean):
  [0x1F2] target dex pc 0x28 is not at instruction start.
```

Logcat: `/data/data/com.termux/files/home/log_2026_05_12_19_54_05.log`.

**Root cause.** Hook 4 (the EnvBuilder LD_PRELOAD inject) used `addInstructions` with a label `:bh_skip_evshim_preload` placed at the END of the inserted block:

```smali
if-eqz v15, :bh_skip_evshim_preload
const/4 v15, 0x0
invoke-virtual {v12, v15, v13}, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
:bh_skip_evshim_preload
```

When `addInstructions` parses the snippet, smali assigns the trailing label an offset of *block-length-in-bytes* relative to the snippet start. The inserted block is exactly 18 instructions = 40 bytes = **0x28** — matching the verifier error target verbatim. The patcher embeds that absolute 0x28 in the resulting method, instead of resolving it to the original `invoke-static/range` that follows the injection. The `if-eqz` then branches to absolute offset 0x28 of `bg5.a`, which lands mid-instruction in the original prologue → VerifyError.

**Why Hooks 1 + 2 didn't crash with the same shape.** They insert at index 0. Snippet-relative offset *equals* absolute offset in the destination method when the shift is zero, so the bug doesn't surface. Hook 3 has no labels at all.

**Fix.** Switched Hook 4 to `addInstructionsWithLabels` + `ExternalLabel`, capturing the original `invoke-static/range` instruction at `joinIdx` *before* insertion. The patcher resolves the label by Instruction identity and tracks it correctly after insertion shifts the target index down by 18. Trailing `:bh_skip_evshim_preload` line removed from the snippet.

**Imports added.** `ExternalLabel`, `addInstructionsWithLabels` (both from `app.revanced.patcher.extensions`).

**Lesson for future bytecode patches.** When inserting at index > 0 with a forward branch that needs to skip past the inserted block, always use ExternalLabel pointing to the original instruction at the insertion index. Trailing-label-in-snippet is a footgun that only surfaces when insertion shifts > 0.

### Stage 3b — Hook 4 v14-type-mismatch fix (2026-05-12, after pre2)

**Second device crash.** v1.1.0-604-pre2 installed and the VerifyError shape changed:

```
java.lang.VerifyError: Verifier rejected class bg5:
  void bg5.a(eco, java.lang.String, boolean):
  [0x1F8] register v14 has type Reference: java.io.File
  but expected Reference: java.lang.String
```

Crash log at `/data/data/com.termux/files/home/log_2026_05_12_20_07_32_crash.log` (PID 19846, `banner.hub:wine`).

**Root cause.** Hook 4 inserts at `joinIdx` = the `invoke-static/range` of `JOIN_HELPER->I0`. The 5 instructions immediately preceding the invoke are Kotlin's joinToString$default arg setup:

```
const/16 v16, 0x0
const/16 v17, 0x3e
const-string v13, ":"
const/4 v14, 0x0       ← v14 set to ConstZero (null CharSequence)
const/4 v15, 0x0
invoke-static/range {v12..v17}, JOIN_HELPER->I0(...)
```

So inserting AT joinIdx places our File-path code *after* the setup. Our `new-instance v14, Ljava/io/File;` then overwrites v14 with `File`, and the verifier rejects the subsequent invoke with `expected Reference: java.lang.String`.

**Fix.** Move the insertion point 5 instructions earlier, to the start of the setup block (`setupStartIdx = joinIdx - 5`). Now both the fall-through and branch-taken paths from our `if-eqz` flow into the setup, which cleanly re-initializes v13..v17 to the types `invoke-static/range` expects. ExternalLabel target updated to the original `const/16 v16` instruction at `setupStartIdx`. Added a `require()` for the setup-block lookback in case a future R8 reshuffle inlines or reorders the setup.

Insertion ordering matters: when inserting `addInstructionsWithLabels` at an index, our snippet is placed *before* the existing instruction at that index. So `setupStartIdx` (= joinIdx - 5) puts our injection just before the setup; the setup then runs after our injection, before the invoke.

### Stage 3b device test — DOOMBLADE clean launch, no rumble triggered (2026-05-12 ~21:46)

v1.1.0-604-pre3 (`9681b60`) installed as `banner.hub` (Normal variant). User launched **DOOMBLADE** via DirectLaunch (Wine Proton 10 arm64x-2, FEX Game Presets, Turnip v25.0.0 R1). Wine session ran clean 21:46:12 → 21:48:36, no VerifyError, no crash. Stage 3b fix verified at the verifier level.

Initial verdict from logcat: zero `BhVibration` log lines, zero gamepad-source InputDevice events. Hooks didn't fire during this session. Hypotheses recorded at the time: either no real controller was paired, or DOOMBLADE didn't issue rumble during the playthrough (2D metroidvania, rumble fires only on specific hits).

Hook insertion verified correct in the installed APK by apktool-decompiling `/data/app/~~8kz5yy-HOJCA8DhNk4duGQ==/banner.hub-JC7NoskjYKMoBofYk4cZ7g==/base.apk`:
- Hook 1: `smali_classes7/com/winemu/core/gamepad/GamepadServerManager.smali:298` — `invoke-static {p1, p2, p3}, Lcom/xj/winemu/vibration/BhVibrationController;->onRumble(III)Z` ✅
- Hook 2: `smali_classes2/ab8.smali:511` — `dispatchToController(III)Z` ✅
- Hook 3: `smali_classes2/ab8.smali:438` — `onStop(I)V` ✅
- Hook 4: `smali_classes8/bg5.smali:985,993` — `nativeLibraryDir` + `/libevshim.so` injection ✅
- `lib/arm64-v8a/libevshim.so` shipped, 41,384 bytes ✅

### Stage 3b — GTA 5 Enhanced device test: VIBRATION CONFIRMED (2026-05-12)

User retested v1.1.0-604-pre3 (`9681b60`) with **GTA 5 Enhanced** and a real controller. **Rumble works.** Device-confirmed end-to-end:

- `GamepadServerManager.onRumble` → `BhVibrationController.onRumble` invoke path active
- Per-controller dispatch via Hook 2 (`ab8.g(II)V`) firing
- libevshim.so LD_PRELOAD keepalive holding rumble past SDL2's 1s auto-stop
- Stop hook (Hook 3, `ab8.f()V`) releasing cleanly

This unblocks the merge: `feature/vibration` ready to land on `gamehub-604-build`.

### Merged into gamehub-604-build 2026-05-12

Pre-merge commit `7d149f1` (docs: GTA 5 confirmation + stable-release-pipeline spec section) pushed to `origin/feature/vibration`, then:

```
git checkout gamehub-604-build
git merge --no-ff feature/vibration
git push origin gamehub-604-build
```

Merge commit: **`222730a`** (`Merge feature/vibration into gamehub-604-build`). `--no-ff` preserves the 8-commit feature history under the merge commit so the staged Verifier-error debugging trail (Stage 1 → 3b) stays readable in `git log --graph`.

`gamehub-604-build` head 65e6902 → 222730a on origin. `feature/vibration` left at `7d149f1` on origin (not deleted — kept as a reference for the verifier-fix post-mortem).

## 2026-05-13 — Stable release pipeline implemented on feature/stable-release-pipeline

User chose option (a): make `v1.1.0-604` itself the new-cert anchor instead of shipping it on the old ephemeral key first. **One uninstall, ever.**

### Keystore

Generated `keystore/bannerhub.keystore` via:

```bash
keytool -genkeypair -v \
  -keystore keystore/bannerhub.keystore \
  -alias bannerhub \
  -keyalg RSA -keysize 2048 \
  -validity 36500 \
  -storepass bannerhub -keypass bannerhub \
  -dname "CN=BannerHub, OU=ReVanced, O=The412Banner, C=US"
```

Cert fingerprints (LOCKED IN — CI must print this SHA-256 on every release):

- **SHA-256:** `10:89:5A:31:1F:E0:4F:95:F8:2E:4D:A5:C9:A6:C0:41:BA:92:82:BF:21:1F:1B:57:8F:E1:CB:EB:89:4C:E0:BA`
- **SHA-1:** `1F:51:B2:5E:5C:9F:58:08:E0:CF:45:17:4F:CC:B3:8D:67:CA:6D:E5`
- **Serial:** `5ee03b1e340fd1ac`
- **Validity:** 2026-05-13 → 2126-04-19 (100 years)
- **Signature algorithm:** SHA384withRSA
- **Schemes used at sign time:** v1 + v2 + v3 (v4 disabled — no `.idsig` sidecar)

Passwords (`bannerhub`/`bannerhub`) and full security model documented in `keystore/README.md`.

### release.yml changes

- **Hybrid trigger**: kept `push: tags: ["v*", "GameHub-*"]`; replaced the `tag` workflow_dispatch input with a `version` input (e.g. `1.1.0-604-pre1`, strip leading `v`). The workflow derives `version` (and `tag` = `v${version}`) from whichever source fired, in a new build-job step `Derive version` that exposes job-level outputs.
- **Filename**: drop the hardcoded `variant.file:` matrix column; compute filename as `BannerHub-V6-${{ needs.build.outputs.version }}-Patched-${{ matrix.variant.name }}.apk` in both the patch step and the artifact upload path.
- **Labels**: matrix `variant.label:` rewritten to "BannerHub v6 …" — three variants share the bare "BannerHub v6" label (Normal, Normal-GHL, Original); AnTuTu and alt-AnTuTu share "BannerHub v6 AnTuTu"; rest are unique.
- **Patch job checkout**: added `actions/checkout@v5` to the patch job so apksigner can read the keystore from the repo.
- **Re-sign step**: new step right after `Apply patches`. Uses `${ANDROID_HOME}/build-tools/<latest>/apksigner` with `--ks keystore/bannerhub.keystore --ks-pass pass:bannerhub --ks-key-alias bannerhub --key-pass pass:bannerhub --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false`. Followed by `apksigner verify --print-certs` so the cert SHA-256 surfaces in CI logs each run.
- **Release job**: now needs both `build` and `patch`; dropped the standalone "Get tag name" step (was reading `inputs.tag`); uses `${{ needs.build.outputs.tag }}` for the release tag and `${{ needs.build.outputs.version }}` for body interpolation; release body rewritten — title is now `BannerHub v6 ${{ version }}`, replaced the 6.0.2→6.0.4 base-bump section with a "Stable signing — in-place updates from this release onward" section, updated variant table with new filenames + labels, updated migration note, file glob changed `GameHub-6.0.4-Patched-*.apk` → `BannerHub-V6-*.apk`.

### README + keystore/README.md

- README banner rewritten from "fresh install required" to "In-place updates — from v1.1.0-604 onward". Variant table updated with new filenames + labels. New `## Signing` section after `## Variants` with cert SHA-256 + SHA-1 fingerprints.
- `keystore/README.md` written: full security model (public test key, anyone can re-sign), keystore fields table, fingerprints, generation command, CI usage, one-time migration note.

### Validated 2026-05-13

- Branch pushed at `67b65ed` (commit `feat(release): stable test-keystore signing + BannerHub-V6 naming`)
- Validation run [`25775495418`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25775495418) — all 9 patch jobs green, release job correctly skipped (stable=false)
- Verified all 9 artifacts:
  - Filename pattern `BannerHub-V6-1.1.0-604-pre1-Patched-{variant}.apk` rendered correctly for every variant (Normal-GHL uses the hyphen form; no parentheses needed)
  - apksigner cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` for every variant — matches `keystore/README.md` byte-for-byte
  - apksigner found at `/usr/local/lib/android/sdk/build-tools/37.0.0/apksigner` (auto-discovered via the `ls -d "${ANDROID_HOME}/build-tools"/* | sort -V | tail -1` lookup)
- Artifacts available for 14 days under run 25775495418's artifacts tab

### Merged 2026-05-13

`feature/stable-release-pipeline` (head `7344420`, 2 commits) merged into `gamehub-604-build` at **merge commit `41a2b27`** with `--no-ff` so the feature history is preserved under the merge commit. Pushed to origin.

`gamehub-604-build` head e26529b → 41a2b27. `feature/stable-release-pipeline` left at `7344420` on origin (not deleted — kept as a reference branch).

### Pending

- ☐ Pre2 rebuild on `gamehub-604-build` to verify in-place updates: `gh workflow run release.yml --ref gamehub-604-build -f version=1.1.0-604-pre2 -f stable=false`. Expected: same cert SHA-256 `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba`. User will install pre1 (from run 25775495418 artifacts), then install pre2 on top to confirm Android accepts the upgrade with no uninstall.
- ☐ When ready, cut `v1.1.0-604` stable: `gh workflow run release.yml --ref gamehub-604-build -f version=1.1.0-604 -f stable=true`. Release notes should call out the one-time uninstall for users on v1.0.0-604 or older.

## 2026-05-13 — feature/app-icon: launcher icon + wine_logo rebrand

New branch `feature/app-icon` off `gamehub-604-build`. Single resource patch (`ChangeAppIconPatch`) that swaps two drawables in the staged APK without touching bytecode.

### Source

`/storage/emulated/0/Download/BannerHub v6_icon.png` — user-provided, 918×903 RGBA with alpha. Centered logo content, transparent surround.

### Generated patch resources

Both checked in to `patches/src/main/resources/bannerhub-icon/`:

| File | Dimensions | Purpose |
| --- | --- | --- |
| `ic_launcher_foreground.png` | 432×432 RGBA | Adaptive-icon foreground at xxxhdpi (108 dp). BannerHub logo content fit to the inner 288×288 safe zone, outer 18 dp margin reserved for launcher masking + parallax. |
| `wine_logo.png` | 240×72 RGBA | Drop-in replacement for the original `drawable-xxhdpi/wine_logo.png`. Square BannerHub icon resized to 72×72 and centered with transparent left/right padding so the 80×24 dp intrinsic measure stays identical and no ImageView layouts regress. |

Generated via ImageMagick:
```
magick "$SRC" -resize "288x288" -gravity center -background transparent -extent "432x432" ic_launcher_foreground.png
magick "$SRC" -resize "72x72" -gravity center -background transparent -extent "240x72" wine_logo.png
```

### Patch source

`patches/src/main/kotlin/app/revanced/patches/gamehub/icon/ChangeAppIconPatch.kt`. Resource patch only (no bytecode, no manifest). Apply block:

1. Stream `bannerhub-icon/ic_launcher_foreground.png` → `res/drawable-xxxhdpi/ic_launcher_foreground.png` (creates the file)
2. **Delete** `res/drawable/ic_launcher_foreground.xml` — the stock GameHub vector. Without this delete, aapt2 keeps both definitions and lower-density devices fall back to the vector (= still GameHub). Deleting forces every density bucket to use the xxxhdpi raster.
3. Stream `bannerhub-icon/wine_logo.png` → `res/drawable-xxhdpi/wine_logo.png` (overwrites stock)

Uses the same sentinel-object classloader pattern as `VibrationLibPatch` (Kotlin's self-referential type-inference snag).

### Background drawable

Intentionally left alone. Adaptive-icon backgrounds are mostly masked away by launcher shapes (circle/squircle/rounded-rect); only a sliver shows at the edge of the foreground. The default GameHub background works fine behind the new foreground content.

### wine_logo usage

R.drawable.wine_logo (resource ID `0x7f080180`, declared in `res/values/public.xml:1273`) is referenced from one place in code: `smali_classes2/ego.smali:1218` via `sget v0, Lyqh;->wine_logo:I` — looks like a Wine-container header/splash logo. Replacing the bitmap content keeps the resource ID stable, so no smali edit is needed.

### Validated 2026-05-13

- Branch + commit: `feature/app-icon` @ `022f10f`, pushed to origin
- Validation run [`25776533760`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25776533760) on `feature/app-icon` with `version=1.1.0-604-pre3 stable=false` — all 9 patch jobs green, release job correctly skipped
- Confirmed per-job:
  - `"Change app icon" succeeded` log line on every variant
  - Output filename `BannerHub-V6-1.1.0-604-pre3-Patched-{variant}.apk` (icon patch did not break the stable-release-pipeline naming)
  - apksigner cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` on every variant — byte-for-byte identical to pre1 (run 25775495418) and pre2 (run 25775755966), so an in-place upgrade install of pre3 over pre2 should be accepted by Android without uninstall
- Artifacts live 14 days under run 25776533760

### Pre4 — added 2 Compose Multiplatform auth-screen logos to same patch (2026-05-13)

User asked to additionally rebrand:
- `assets/composeResources/com.xiaoji.egggame.features.auth/drawable/features_auth_ic_logo_landscape.png` (stock 96×96 square — "landscape" refers to auth-screen orientation, not image aspect) — replaced with BannerHub icon scaled to 96×96 with transparent padding
- `assets/composeResources/com.xiaoji.egggame.features.auth/drawable/features_auth_ic_logo_overseas.png` (stock 366×72, 5.08:1 wide) — replaced with user-supplied 2277×448 RGB source `/storage/emulated/0/Download/ADM/features_auth_ic_logo_overseas.png` direct-downscaled (aspect ratio matched exactly, no padding). RGB→RGB transition acceptable since auth screen has opaque background.

Extended `ChangeAppIconPatch` (still ONE patch, one entry in `revanced-cli list-patches`) with two more `copy()` calls. Refactored apply block to factor out the classloader-load + parent-mkdirs + stream-copy pattern into a local helper, eliminating four near-identical blocks.

CN-locale auth logo (`features_auth_ic_logo_cn.png`, 270×72) intentionally left alone — not shown on overseas builds.

Branch head: `718d241`. Validation [run 25777014627](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25777014627) all 9 patch jobs green, `"Change app icon" succeeded` on every variant, apksigner cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` (unchanged across pre1 → pre2 → pre3 → pre4 → upgrades between any pair should be in-place).

### Pre5 — added splash_logo to same patch (2026-05-13)

User asked to additionally rebrand `assets/composeResources/com.xiaoji.egggame.features.splash/drawable/splash_logo.png` (stock 996×200, 4.98:1 aspect, RGBA) using the same overseas-banner artwork source. Same 5.08:1 aspect on the source; resolved by resizing to 996×196 to preserve proportions exactly, then `-extent 996x200` to pad 2 px of transparency top + bottom. Output is RGBA so a future splash background change (e.g. dark mode) can bleed through cleanly.

ImageMagick produces RGBA automatically when an RGB input is `-extent`'d with a transparent background — useful pattern.

ChangeAppIconPatch (still ONE patch) now ships **five** drawables in its apply block: launcher foreground (+ vector delete), wine_logo, auth landscape, auth overseas, splash. CN-locale `drawable-zh-rCN/splash_logo.png` left alone — same policy as `features_auth_ic_logo_cn.png` (not displayed on overseas builds).

Branch head: `0d55adf`. Validation [run 25777391685](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25777391685) all 9 patch jobs green, `"Change app icon" succeeded` on every variant, cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` (unchanged pre1 → pre5).

### Merged 2026-05-13

`feature/app-icon` (head `46a1a6e`, 6 commits — 3 feat + 3 docs) merged into `gamehub-604-build` at **merge commit `bf2882e`** with `--no-ff` so the per-pre stages stay readable in `git log --graph`. Pushed to origin. **No CI triggered** by the branch push — `release.yml` fires on tag push or workflow_dispatch only.

`gamehub-604-build` head e3c708a → bf2882e. `feature/app-icon` left at `46a1a6e` on origin as a reference branch.

### Post-merge sanity build 2026-05-13 (pre6 on gamehub-604-build)

User triggered an artifacts-only build of `gamehub-604-build` @ `841a0ba` after the icon-patch merge to verify the merged tree builds clean: `gh workflow run release.yml --ref gamehub-604-build -f version=1.1.0-604-pre6 -f stable=false`.

[Run 25777687347](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25777687347) — all 9 patch jobs green, `Create GitHub Release` correctly skipped (stable=false). Per-job:
- `"Change app icon" succeeded` on every variant
- apksigner cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` (unchanged pre1 → pre6)

Confirms vibration patch + stable-release-pipeline + 5-drawable icon patch all coexist cleanly. Artifacts live 14 days at the run URL.

### Pending

- ☐ User device-tests pre6 (or pre5/pre4) Normal installed on top of any earlier new-cert build:
  1. Android accepts the upgrade with no uninstall
  2. Launcher tile shows BannerHub icon
  3. wine_logo rebrand visible somewhere in-app
  4. Auth-screen logos rebranded on login flow
  5. Splash screen on app launch shows BannerHub banner
- ☐ When user gives the go-ahead, cut `v1.1.0-604` stable: `gh workflow run release.yml --ref gamehub-604-build -f version=1.1.0-604 -f stable=true`. First new-cert release; release notes should call out the one-time uninstall for users on v1.0.0-604 or older.

### Per-game hamburger-menu Vibration Settings option — NOT in this build

User asked whether the per-game hamburger-menu "Vibration Settings" item from BannerHub 3.7.2 stable also ships in the ReVanced build. **No.** The ReVanced patch set only registers `com.xj.winemu.vibration.BhVibrationSettingsActivity` in the manifest with `exported="false"` and no `<intent-filter>` (`VibrationManifestPatch.kt:32-37`). There is no patch under `patches/.../gamehub/` that injects a menu item into the XJ Java/XML UI to launch that activity — that would be a separate bytecode patch (find the per-game menu adapter R8 class, inject a row that fires an explicit Intent to `BhVibrationSettingsActivity --es gameId <gid>`).

Functionally rumble still works without the UI: `BhVibrationController.java:98-99` defaults to `MODE_CONTROLLER` at intensity 100. The settings activity only adjusts per-game mode/intensity overrides.

Follow-up task (after rumble is confirmed working in GTA 5): port the menu-item injection as a new bytecode patch.

## 2026-05-12 — Stable release pipeline spec approved (NOT executed yet)

User-approved spec for the next-but-one release cycle. Execution is **gated on**: (1) GTA 5 + real-controller retest of `feature/vibration` head `9681b60` confirming rumble, (2) merging `feature/vibration` → `gamehub-604-build`, (3) cutting `v1.1.0-604` stable on the **old ephemeral key** (final release before the cert switch). Only after that do we branch `feature/stable-release-pipeline` off the updated `gamehub-604-build` and apply the changes below.

### Goal

Replace revanced-cli's per-run ephemeral keystore with a checked-in test keystore so the signing cert is stable across releases. After a one-time uninstall break for users on the v1.0.0-604 ephemeral-key build, every future stable updates in-place (no uninstall, no `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

### Spec

- **Tag format:** `vX.Y.Z-{branch-base#}` — e.g. `v1.1.0-604`. `604` derived from `patches/.../gamehub/Constants.kt:GAMEHUB_VERSION = "6.0.4"` and the `base-apk-604` GitHub release. Pre-releases: `vX.Y.Z-{branch-base#}-preN`.

- **APK filename:** `BannerHub-V6-{version}-Patched-{variant}.apk` where `version = ${GITHUB_REF_NAME#v}`. Drops the hardcoded `variant.file:` column from the matrix; computed at workflow level.

- **App labels (9-variant table):** three variants share the bare "BannerHub v6" label (Normal, Normal-GHL, Original) and install side-by-side via different package names — same pattern as the two AnTuTu variants which share "BannerHub v6 AnTuTu".

  | variant.name | variant.pkg | variant.label |
  |---|---|---|
  | Normal | banner.hub | BannerHub v6 |
  | Normal-GHL | gamehub.lite | BannerHub v6 |
  | PuBG | com.tencent.ig | BannerHub v6 PuBG |
  | AnTuTu | com.antutu.ABenchMark | BannerHub v6 AnTuTu |
  | alt-AnTuTu | com.antutu.benchmark.full | BannerHub v6 AnTuTu |
  | PuBG-CrossFire | com.tencent.tmgp.cf | BannerHub v6 PuBG CrossFire |
  | Ludashi | com.ludashi.aibench | BannerHub v6 Ludashi |
  | Genshin | com.miHoYo.GenshinImpact | BannerHub v6 Genshin |
  | Original | com.xiaoji.egggame | BannerHub v6 |

- **Test keystore at `keystore/bannerhub.keystore`** (RSA 2048, 100-year validity, alias `bannerhub`, store and key password both `bannerhub`, DN `CN=BannerHub, OU=ReVanced, O=The412Banner, C=US`). Public test key — committed to repo, documented in `keystore/README.md` alongside cert SHA-256.

- **apksigner post-sign step** with `--v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false`, runs after revanced-cli `--out`. Followed by `apksigner verify --print-certs` so CI logs surface the cert SHA-256 every run for eyeball verification.

- **`build_pull_request.yml`** untouched — PR test artifacts continue with revanced-cli's ephemeral keystore.

### Sequencing checklist

1. ☐ GTA 5 + real controller retest of `feature/vibration` head `9681b60`
2. ☐ Merge `feature/vibration` → `gamehub-604-build` if green
3. ☐ Cut `v1.1.0-604` stable on the **old ephemeral key** (final release before cert switch)
4. ☐ Branch `feature/stable-release-pipeline` off updated `gamehub-604-build`
5. ☐ Generate + commit keystore at `keystore/bannerhub.keystore`, write `keystore/README.md`
6. ☐ Update `release.yml` (matrix, filename template, label table, apksigner step)
7. ☐ Update README "Signing" section + release-notes copy
8. ☐ Validate via non-stable `workflow_dispatch`; inspect filenames + cert SHA-256 in logs
9. ☐ Merge `feature/stable-release-pipeline` → `gamehub-604-build`
10. ☐ Tag `v1.1.1-604` (or `v1.2.0-604`) — **new-cert anchor**; release notes call out the one-time uninstall
11. ☐ Lock cert SHA-256 into memory + README for permanent verification reference

Full spec in `[[project_bannerhub_revanced_stable_release_pipeline]]` memory file.

## 2026-05-13 — Per-game menu integration recon

User wants a 5th menu row "PC Vibration Settings" in the per-game library popup (PC Game Settings / Add to Desktop / Remove from Library / Edit Cover). Pure Compose Multiplatform, heavy R8 obfuscation. Full architecture mapped this session before the patch implementation begins.

### Menu Composable host

**`smali_classes4/x57.smali`** (18,783 lines), method `a(Lf37;Lpo7;Lv83;I)V` (line 214 → ~7807). Per-game menu rows built lines ~3120-3300.

### Row data class

`Liae;` (file `smali/iae.smali`):
| Field | Type | Meaning |
|---|---|---|
| `a` | `Lo05;` | Icon (Painter / vector) |
| `b` | `Ljava/lang/String;` | Resolved label string |
| `c` | `Lpw6;` | onClick (Function1<Object, Object>) |
| `d` | `Z` | Enabled boolean |

Constructor: `<init>(Lo05;Ljava/lang/String;Lpw6;)V` (3-arg overload defaults d=true).

### Click handler interface

`Lpw6;` = Compose's `Function1` — single abstract method `invoke(Ljava/lang/Object;)Ljava/lang/Object;`.

### Label resolver lookup

`Lwhl;` is the only ComposableSingletons holder containing all four menu labels:
- `Lwhl;->S:Lxrl;` — `Lwgl(23)` = `common_game_remove_from_library` ✓ verified
- `Lwhl;->e0:Lxrl;` — `Ldhl(13)` = `game_cover_edit_title` ✓ verified
- Two more (PC Game Settings + Add to Desktop) in the same singleton

### Compose label int values (verified by walking pswitch tables)

- `Lghl(25)` → `features_winemu_entrance_setting` ("PC Game Settings")
- `Ltfl(20)` → `features_game_add_to_desktop` ("Add to Desktop")
- `Lwgl(23)` → `common_game_remove_from_library` ("Remove from Library")
- `Ldhl(13)` → `game_cover_edit_title` ("Edit Cover")

### Canonical row construction pattern (x57 lines ~3130-3210)

```smali
:goto_30
if-eqz v36, :cond_66                                 ; row-visible state guard
const v2, -0x3f27e2da                                ; Compose state-group key
invoke-virtual {v7, v2}, Ln49;->g0(I)V               ; Composer.startReplaceableGroup
sget-object v2, Lzz4;->m:Lxrl;                       ; ICON ref
invoke-virtual {v2}, Lxrl;->getValue()Ljava/lang/Object;
move-result-object v2
check-cast v2, Lo05;                                 ; v2 = Lo05 icon
sget-object v3, Lwhl;->S:Lxrl;                       ; LABEL ref
invoke-virtual {v3}, Lxrl;->getValue()Ljava/lang/Object;
move-result-object v3
check-cast v3, Lell;
const/4 v9, 0x0
invoke-static {v3, v7, v9}, Lxd3;->l1(Lell;Lv83;I)Ljava/lang/String;
move-result-object v3                                ; v3 = resolved string
invoke-virtual {v7, v10}, Ln49;->i(Ljava/lang/Object;)Z   ; Composer.changed
move-result v13
invoke-virtual {v7}, Ln49;->S()Ljava/lang/Object;    ; Composer.rememberedValue
move-result-object v9
if-nez v13, :cond_64
if-ne v9, v15, :cond_65                              ; reuse remembered if not Empty
:cond_64
new-instance v9, Lb47;
const/4 v13, 0x0
invoke-direct {v9, v10, v0, v6, v13}, Lb47;-><init>(Lpo7;Lcge;Lcge;I)V
invoke-virtual {v7, v9}, Ln49;->p0(Ljava/lang/Object;)V   ; Composer.updateRememberedValue
:cond_65
check-cast v9, Lpw6;                                 ; v9 = onClick Function1
new-instance v13, Liae;
invoke-direct {v13, v2, v3, v9}, Liae;-><init>(Lo05;Ljava/lang/String;Lpw6;)V
invoke-virtual {v4, v13}, Lx9d;->add(Ljava/lang/Object;)Z   ; list builder v4 ← row
invoke-virtual {v7}, Ln49;->u()V                     ; Composer.endReplaceableGroup
```

### Implementation plan

Two artifacts:

1. **Java helper** `extensions/gamehub/src/main/java/com/xj/winemu/vibration/BhMenuRowClick.java` implementing `kotlin.jvm.functions.Function1<Object, Object>`:
   - Walks `ActivityThread` (reflection — same pattern `BhVibrationController.maybeResolveContainerFromActivityStack` already uses) to find current top Activity
   - Reads gameId Intent extra from the active WineActivity if present
   - Fires `currentActivity.startActivity(Intent(currentActivity, BhVibrationSettingsActivity.class).putExtra("gameId", gameId))`

2. **Bytecode patch** `patches/.../gamehub/vibration/VibrationMenuRowPatch.kt`:
   - Structural anchor: find a method with `sget-object .*Lwhl;->S:Lxrl;` AND `Lx9d;->add(Object)Z` AND `Liae;-><init>(Lo05;Ljava/lang/String;Lpw6;)V`
   - Just before the method's final return (or at the end of the row-construction block), inject the smali to construct a new `Liae("PC Vibration Settings", icon, BhMenuRowClick(), true)` and append to `v4` via `Lx9d;->add`
   - Use ExternalLabel pattern via `addInstructionsWithLabels`; reserve fresh free registers; preserve Composer state-group balance with paired `Ln49.g0` / `Ln49.u`

### Risk factors / iteration expectations

- Compose `Composer.startReplaceableGroup` / `endReplaceableGroup` pairs must match — wrong boundary = ART verifier crash on app start
- Compose's `Ln49.i` / `Ln49.S` / `Ln49.p0` remember-state slot tracking has strict invariants
- Register reuse: `v7` (Composer), `v4` (list builder), `v10` / `v15` (constants) must not clobber
- Expect 2-4 CI cycles to pass verifier + 1-2 device-test cycles to confirm row renders cleanly without crashing the popup

### Recon files referenced

| File | Role |
|---|---|
| `x57.smali` | Menu Composable host (18,783 lines, method `a()` builds rows) |
| `iae.smali` | Row data class `Liae(icon, label, onClick, enabled)` |
| `b47.smali` | Compose-emitted onClick closure (`Lpw6` impl, 4-field captured state) |
| `cge.smali` | State delegate interface (NOT click handler — `MutableState`-like) |
| `pw6.smali` | Function1 interface — actual click-handler type |
| `whl.smali` | ComposableSingletons holder for menu labels |
| `ghl.smali` / `tfl.smali` / `wgl.smali` / `dhl.smali` | Label string resolvers (packed-switch on int → string key) |
| `vhl.smali` / `shl.smali` / `zhl.smali` | Other singletons (NOT the per-game menu) |
| `jfd.smali` | ViewModel (game detail), 16-way Lhed sealed dispatch |
| `ddd.smali` | FlowCollector handling 13 sealed event types |
| `ycd.smali` | Edit Cover confirm closure |
| `j47.smali` | Composable lambda factory (5 different ctors, 14-callback variant) |
| `igg.smali:21668` | Builds j47 with `Ljava/util/List;` of menu rows |

### AppNavKey concrete names found

| AppNavKey class | Obfuscated |
|---|---|
| `AppNavKey$PcGameSettingEntrance` | `Lff0;` |
| `AppNavKey$GamepadVibrationSetting` | `Ltd0;` (built-in 6.0.4 — different from our BhVibrationSettingsActivity) |

## 2026-05-13 — Patch iterations (pre7 → pre11)

Branch `feature/menu-vibration-row`. Iteration trail captured in memory file `project_bannerhub_revanced_vibration.md`:

| Pre | Commit | Outcome |
|---|---|---|
| pre7 | `2d0c85c` | Kotlin compile fail (`Pair<...>.addInstructions` doesn't exist — use `firstMethod`) |
| pre8 | `8b1cb3f` | ART verifier reject — in-line smali clobbered v9 with `BhMenuRowClick` type, breaking downstream type-flow merge at `:goto_35` |
| pre9 | `28c5bd3` | Helper called repeatedly but failed `!pw6Cls.isInstance(click)` — R8 renamed `kotlin.jvm.functions.Function1` → `Lpw6;` so our extension's Function1 impl is a different JVM class |
| pre10 | `7eb024e` | ✅ Row appears in game-details More Menu — `java.lang.reflect.Proxy` implementing Lpw6 satisfies the Iae ctor type check |
| pre11 | `19080af` | Added 2nd injection in `ted.smali f()` for library tile popup — but logcat confirms helper never called; ted.f() is NOT the library tile popup |

### Confirmed working surface

`x57.smali` method `a(Lf37;Lpo7;Lv83;I)V` injection at lastAddIdx+1:
```
invoke-static {v4}, Lcom/xj/winemu/vibration/BhMenuRowClick;->appendVibrationRowTo(Ljava/lang/Object;)V
```
Helper reflectively constructs `Liae(icon, "PC Vibration Settings", Proxy<Lpw6>)` and appends to list builder `Lx9d` at `v4`. Renders as 5th item in the game-details screen "More Menu" popup. Device-confirmed via screenshot 2026-05-13 08:04.

## 2026-05-13 — BOTH menus working at pre17 🎉

After 10 iterations (pre7 → pre17), the library tile popup row now ALSO works.

### What got it across the line

Pre15-16 silently had a `PatchException: classDef is null` in the resolver short-circuit's `addInstructionsWithLabels`-via-`ExternalLabel` path. revanced-cli reported the OVERALL job as success because the per-patch SEVERE error doesn't fail the CI run. The 3 other injections landed but the resolver patch silently no-op'd, leaving rows in pzc.j0's output pointing at unresolvable Lell keys that crashed at render time.

Pre17 switched to plain `addInstructions` (no labels) at index 0 — works because the trailing-label-in-snippet footgun only applies mid-method. Index 0 lets the snippet-relative offset equal the absolute offset.

### Final injection pattern (3 sites + 1 resolver patch)

1. **Game-details "More Menu"** (`Lx57.a()`): `addInstructions(lastAddIdx+1, "invoke-static {v4}, ...->appendVibrationRowTo(Object)V")` → Java helper builds `Liae(icon, label-as-String, Proxy<Lpw6>)` and `list.add()`s.
2. **Library tile popup** (`Lpzc.j0()`): hook the return — `addInstructions(returnIdx, "invoke-static {vN}, ...->appendLibraryPopupRow(Object)List; \n move-result-object vN")` → Java helper builds `Lz4e(Lell-via-Unsafe, Proxy<Lnw6>, 0)` and returns augmented ArrayList.
3. **Resolver short-circuit** (`Lxd3.l1()`): `addInstructions(0, "invoke-static {p0}, ...->maybeResolveCustomLabel(Object)String; \n move-result-object v0 \n if-eqz v0, :tail \n return-object v0 \n :tail")` → Java helper returns "PC Vibration Settings" for our sentinel key, null for everything else.
4. **Compose resource entry** (`VibrationMenuLabelPatch`): appends to `assets/composeResources/com.xiaoji.egggame.features.home/values*/strings.commonMain.cvr` for documentation/future-use (actual mechanism is #3 since Compose Multiplatform's runtime needs a manifest registration the bare .cvr doesn't provide).

### Three architectural challenges solved

- **R8 renamed kotlin.jvm.functions.Function0/1** to `Lnw6;`/`Lpw6;`. Extension's `implements Function1` doesn't satisfy `pw6Cls.isInstance()`. Fix: `java.lang.reflect.Proxy.newProxyInstance` implementing the renamed interface.
- **Lell is an empty Kotlin subclass** of abstract `Ltdi(String, Set)`. Lell.smali has NO `.method`, NO `.field`. JVM-level the host does `new-instance + invoke-direct Ltdi.<init>`. `Lell.class.getDeclaredConstructor` returns nothing. Fix: `sun.misc.Unsafe.allocateInstance` + reflect-set the inherited fields `Ltdi.a` (key) and `Ltdi.b` (Set).
- **Compose resource keys** for library popup labels go through `Lxd3.l1` resolver which throws on unknown keys. Just appending to the .cvr isn't enough — runtime needs a manifest. Fix: bytecode short-circuit the resolver at its head.

### Full engineering reference

The reusable playbook (with smali patterns, register conventions, common pitfalls, full code snippets) lives in `project_bannerhub_revanced_menu_injection_playbook.md`. Future menu-row additions should start there.

### Critical CI anti-pattern caught

`revanced-cli` reports per-patch failures as `SEVERE:` log lines but the OVERALL CI job exits 0. Always grep CI logs for SEVERE after every iteration — wasted pre15 and pre16 by assuming "all 9 variants green" meant all patches landed.

```bash
gh run view --log --job <id> | grep -E "SEVERE|INFO.*<patch name>"
```

### Device confirmation 2026-05-13 12:51

Library tile 3-dot popup screenshot shows 5 rows in vertical text-only list:
- PC Game Settings
- Add to Desktop
- Remove from Library
- Edit Cover
- **PC Vibration Settings** ← OUR row

Game-details "More Menu" still has its own PC Vibration row from pre10. Both surfaces working independently.

### Pending

- ☐ Tap-test "PC Vibration Settings" in the library popup → confirm it opens BhVibrationSettingsActivity dialog (haven't device-tested the click yet, just the row rendering)
- ☐ When ready, cut `v1.1.0-604` stable — first release with full menu integration

### Merged 2026-05-13

`feature/menu-vibration-row` (14 commits, head `f472868`) merged into `gamehub-604-build` at **merge commit `91947fe`** with `--no-ff` so the pre7→pre17 iteration trail is preserved under the merge commit. Pushed to origin. `gamehub-604-build` head `4d609f0` → `91947fe`.

Will trigger an artifacts-only build to verify the merged tree builds clean.
