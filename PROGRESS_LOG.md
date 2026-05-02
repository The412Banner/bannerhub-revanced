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

---

## 2026-05-02 — Component Manager port: redo Jobs 7-8 against BannerHub 3.5.0 spec

The first cut at Jobs 7-8 (a23723a / 67677a8 / 58d3c53) shipped a stub
file-picker / URL-paste UI. That was wrong — the Component Manager being
ported is BannerHub 3.5.0's existing 3-mode ListView + multi-repo browser,
which extracts WCP/XZ/ZIP archives via a real `WcpExtractor` + injects via
`ComponentInjectorHelper`. The earlier two activity files were deleted from
the working tree at the start of this session and have now been replaced
with proper ports of the 3.5.0 smali.

### Source

- `bannerhub/component-manager-patch/patches/smali_classes16/com/xj/landscape/launcher/ui/menu/`
  - `WcpExtractor.smali` (327 lines)
  - `ComponentInjectorHelper.smali` (35 KB, includes `appendLocalComponents` for 5.x — not ported, replaced by sidecar reader)
  - `ComponentManagerActivity.smali` + 2 inner classes (655 + 4246 + 2494 bytes)
  - `ComponentDownloadActivity.smali` + 9 inner classes (583 lines + ~50 KB inners)

### What landed (this session)

| File | Purpose | Notes |
| --- | --- | --- |
| `extensions/gamehub/.../components/WcpExtractor.java` | Format-detected (.zip / zstd / xz) → flat or path-preserving extraction. FEXCore detection via profile.json. | Uses stub-jar API, no reflection — the gamehub stub now exposes Apache-Commons-Compress, Zstd-JNI, and XZ minimal facades. |
| `extensions/gamehub/.../components/ComponentInjectorHelper.java` | Format detect + extract + sidecar register. Replaces 3.5.0's `EmuComponents.D()` registration call with `SidecarRegistry.put()`. | Type ints converted to 6.0's `EnvLayerEntity.type` mapping (2/3/4/5/6). Box64+FEXCore both fall back to type 6 (no native 6.0 type). |
| `extensions/gamehub/.../components/ComponentManagerActivity.java` | 3-mode `ListView` (component list / options / type-selection). Extends `android.app.Activity` (not AppCompat — extension module has no androidx.appcompat dep). | `removeComponent` calls `SidecarRegistry.remove()` instead of `EmuComponents.HashMap.remove`. Backup writes to `Downloads/BannerHub/<name>/` unchanged. |
| `extensions/gamehub/.../components/ComponentDownloadActivity.java` | Multi-repo browser (3-mode: repos / categories / assets). All 6 repos: Arihany WCPHub, Kimchi/StevenMXZ/MTR/Whitebelyash GPU drivers, The412Banner Nightlies. | Internal category ints kept as 5.x tags (10/12/13/94/95) for `detectType` consistency; converted to 6.0 sidecar type ints right before `injectComponent()`. |
| `extensions/gamehub/stub/.../ZstdInputStreamNoFinalizer.java` | compileOnly facade | Real impl shipped in GameHub APK via `com.github.luben.zstd-jni`. |
| `extensions/gamehub/stub/.../XZInputStream.java` | compileOnly facade | Real impl shipped in GameHub APK via `org.tukaani.xz`. |
| `extensions/gamehub/stub/.../TarArchiveInputStream.java` + `TarArchiveEntry.java` | compileOnly facades | Real impls in `org.apache.commons.compress.archivers.tar`. `getNextEntry()` (not `getNextTarEntry()`) is the kept bridge method; `isDirectory()` is obfuscated → detect via `name.endsWith("/")`. |

### Patch-side fixes

- `ComponentManagerPatch.kt`: theme switched from `@style/Theme.AppCompat.NoActionBar` to `@android:style/Theme.DeviceDefault.NoActionBar`. 6.0 is a Compose/KMP app — AppCompat resources are not guaranteed to be present in the base APK.
- `ComponentInjectionPatch.kt`: added `dependsOn(sharedGamehubExtensionPatch)`. Without it the `ComponentInjector` extension dex never lands in the APK and the patch's `invoke-static` would NoClassDefFound at runtime.

### Carried forward

- `SidecarRegistry.java` and `ComponentInjector.java` from earlier in the session — unchanged. The new `ComponentInjectorHelper.registerComponent()` writes JSON in the exact shape `ComponentInjector.append()` expects to read.

### CI result — v0.3.0-component-manager-test (run 25249141081, commit cebbb88)

✅ All 9 patch variants succeeded. Apply-patches log for Original confirms
all 9 patches landed: Bypass login, Component injection, Component manager,
Debug logging, Disable Firebase Crashlytics, File manager access, Mute UI
sounds, Change package name, Change app name. APK size 108.9 MB. Per
prerelease policy this is artifact-only — release step skipped.

Awaiting device install + functional test (Job 11):
- Verify launcher icon "BH Components" appears.
- Open Component Manager → component list populates from `files/usr/home/components/`.
- Tap "+ Add New Component" → type-selection menu → pick a type → file picker.
- Pick a `.wcp`/`.tzst`/`.zip` → injection succeeds, sidecar entry written
  to `sp_bh_components.xml`, toast confirms.
- Inside any imported game's PC settings, open the matching dropdown
  (GPU/DXVK/VKD3D) → injected component appears alongside official entries.
- "Download from Online Repos" → all 6 repos fetch and list assets.
