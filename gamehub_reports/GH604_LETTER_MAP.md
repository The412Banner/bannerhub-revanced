# GameHub 6.0.4 — patch-anchor delta report

Generated 2026-05-12 against `GameHub_6.0.4.apk` (versionCode 114, versionName 6.0.4).
R8 map id: `6a5cde6143fc8cf76f6f3a447d0fececd4794d83066e6ead7a9537e6527b057b`
6.0.2 R8 map id: `032c299c671f291b037da144c04f4b9bdf25a0ddc75c43b14ff2382d5f50d1fa` (every anchor reshuffled).

Base APK published as release tag [`base-apk-604`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-604).
Smali decompile at `/tmp/gh604_smali/` (ephemeral). Branch `gamehub-604-build` cut off `gamehub-602-build` head `abf1eac`.

## TL;DR

| Patch | Status | Notes |
|---|---|---|
| BypassLogin | ⚠️ **Re-architecture required** for NAV_INTERCEPTOR | NavigationInterceptor `a()` body moved into a coroutine continuation — patch site no longer holds the iget+invoke+if-nez pattern inline. 6 of 7 anchors clean-substitute. |
| RedirectCatalogApi | ✅ Clean substitute (1 anchor moved) | Enum class restructure unchanged. |
| PrefixApiPath | ✅ Clean substitute (2 anchors moved) | URL helper + builder both moved. |
| DebugLog | ✅ Clean substitute (5 anchors moved) | All probe targets present and structurally identical. |
| FakeAuthToken ext | ✅ Clean substitute | 10-field shape unchanged. |
| FakeUserAccount ext | ✅ Clean substitute | 27-field shape unchanged. |
| FakeStateFlow ext | ✅ Clean substitute | Wrap-via-reflection still applies. |

The **only risky anchor is `NAV_INTERCEPTOR`**. Everything else is a constant swap.

## Full letter delta — 6.0.2 → 6.0.4

### BypassLoginPatch.kt

| Constant | 6.0.2 | 6.0.4 | Source of truth |
|---|---|---|---|
| AUTH_IMPL | `Lit0;` | `Ljt0;` | `smali_classes4/jt0.smali` — 3× `Lozh;` fields, ctor `(UserDao, AuthTokenDao, Lv70;)V`, implements `Ldt0;` |
| AUTH_INTERFACE | `Lct0;` | `Ldt0;` | `smali_classes4/dt0.smali` — abstract `d()/e()/h()` return `Lyjk;`, `f()` returns `Lwpm;`, `a()Z`, `b()Lrpm;` |
| AUTH_TOKEN | `Lkpm;` | `Lwpm;` | `smali_classes4/wpm.smali` — 10 fields exactly matching `(S,S,S,S,Long,Long,J,Z,J,J)`, ctor sig identical |
| GAME_LIB_REPO | `Luu7;` | `Lvu7;` | `smali_classes4/vu7.smali` — `b:Ldt0;` field + ctor `(GameLibraryDatabase, Ldt0;)V` |
| GAME_LIB_REPO_USERID_METHOD | `"e"` | `"e"` | Unchanged — `vu7.e()` body: iget b:Ldt0 → invoke f()Lwpm → iget Lwpm;->a:String |
| NAVIGATOR | `Lxle;` | `Lgme;` | `smali_classes4/gme.smali` — has `i(Lhi0;)V` and `r(Lhi0;)V`, both contain iget `b:Ldt0;` + invoke a()Z + if-nez + new-instance `Lta0;` |
| NAV_INTERCEPTOR | `Lrr0;` | **⚠️ `Liod;` (synchronous body GONE — moved to `Lhod;->invokeSuspend`)** | See "Risk" below |

**Sub-letter changes** (only matter if patch body needs updating, not for predicate constants):
- Screen-route enum arg: `Lgi0;` (6.0.2) → `Lhi0;` (6.0.4) — `gme.i`/`r` parameter type
- Login intent class: `Lsa0;` (6.0.2) → `Lta0;` (6.0.4) — `new-instance` in nav gate body
- Abstract StateFlow interface (return type of h/e/d): `Lrjk;` (6.0.2) → `Lyjk;` (6.0.4)

### FakeStateFlow.java letter constants

| Constant | 6.0.2 | 6.0.4 | Source |
|---|---|---|---|
| STATE_FLOW_IMPL_CLASS | `tjk` | `akk` | `smali_classes5/akk.smali` — `<init>(Object)V`, implements `Ldge;` |
| STATE_FLOW_WRAPPER_CLASS | `hzh` | `ozh` | `smali_classes5/ozh.smali` — `<init>(Ldge;)V`, implements `Lyjk;` |
| STATE_FLOW_HOLDER_INTERFACE | `vfe` | `dge` | Inferred from `ozh` ctor + `akk` `.implements` line |

### FakeAuthToken.java letter constant

| Constant | 6.0.2 | 6.0.4 |
|---|---|---|
| AUTH_TOKEN_CLASS | `kpm` | `wpm` |

### FakeUserAccount.java letter constant

| Constant | 6.0.2 | 6.0.4 | Source |
|---|---|---|---|
| USER_ACCOUNT_CLASS | `fpm` | `rpm` | `smali_classes4/rpm.smali` — 27 fields, exact 27-arg ctor sig matches reflective lookup |

### RedirectCatalogApiPatch.kt

| Constant | 6.0.2 | 6.0.4 | Source |
|---|---|---|---|
| ENV_ENUM_CLASS | `Lxrj;` | `Lesj;` | `smali_classes4/esj.smali` — enum extending `Ljava/lang/Enum;` with fields cnHost/overseaHost/displayName/value, `<clinit>` builds Online value with `landscape-api-cn.vgabc.com` at v5, `landscape-api-oversea.vgabc.com` at v6 |

### PrefixApiPathPatch.kt

| Constant | 6.0.2 | 6.0.4 | Source |
|---|---|---|---|
| URL_HELPER_CLASS | `Lvob;` | `Lcpb;` | `smali_classes4/cpb.smali` — `b(Ln7a;Ljava/lang/String;)V`, body iget→invoke trim→toString→length |
| URL_BUILDER_TYPE | `Lm7a;` | `Ln7a;` | First param of cpb.b; field `a:Lokm;` (Ktor builder shape preserved) |

Sub-letter: the string-trim helper moved from `Lpll;->s1` (6.0.2) to `Lbml;->s1` (6.0.4); patch doesn't reference it directly.

### DebugLogPatch.kt

| Constant | 6.0.2 | 6.0.4 | Source |
|---|---|---|---|
| Y2D_IMPL (`Li86;`) | `Li86;` | `Lj86;` | `smali_classes4/j86.smali` — `e(Ljava/lang/Throwable;Lnw6;)V`, ctor takes `Lxgd;` first arg (delegates) |
| Y2D_INTERFACE (in catch lookup) | `Lpgd;` | `Lxgd;` | `smali_classes4/xgd.smali` — interface, abstract `e(Throwable, Lnw6;)V` + 9 other methods |
| SAVE_REPO | `Luu7;` | `Lvu7;` | Same as BypassLogin GAME_LIB_REPO |
| SAVE_METHOD | `"v"` | `"v"` | Unchanged — `vu7.v(GameInfo, LaunchMethod, Ci3)Object` |
| RETRO_REPO_WRAPPER | `Lyji;` | `Lfki;` | `smali_classes5/fki.smali` — `<init>()V`, single field `a:RetroGameDao`, method `b(RetroGameEntity, Ci3)Object` |
| IMPORT_TXN | `Lvs7;` | `Lws7;` | `smali_classes4/ws7.smali` — `invokeSuspend(Object)Object` with `.locals 70` (closest to 6.0.0's `69`); calls both `GameLaunchMethodDao;->insert` and `GameLibraryBaseDao;->insert` |
| Function0 type (in `e()` signature) | `Lmw6;` | `Lnw6;` | Visible on `xgd.e(Throwable, Lnw6;)V` |

Other IMPORT_TXN candidates rejected: `Ljqc;` (.locals 75) and `Lzs7;` (.locals 77) — both farther from the 6.0.0 baseline.

## Risk: NAV_INTERCEPTOR (`Lrr0;` → `Liod;`)

In 6.0.2 the navigation-interceptor's `a(...)` method body held the auth check inline:
```
iget-object pN, p0, Lrr0;->a:Lct0;
invoke-interface {pN}, Lct0;->a()Z
move-result pN
if-nez pN, :cond_0
new-instance pN, L<redirect-to-login result>;
```
The patch hooks this pattern via `firstMethod { definingClass == NAV_INTERCEPTOR && name == "a" }`.

In 6.0.4 the interceptor `Liod;` (smali_classes4/iod.smali) implements `Llaa;` and has `<init>(Ldt0;Lzzn;Ls01;Lmm3;)V` + `a(Lrdb;Lzzn;Laem;)V`. Its `a()` method body **no longer iget's `b:Ldt0;` directly** — it builds a coroutine continuation `Lhod;` and dispatches to it. The pattern the patch looks for now lives at `smali_classes4/hod.smali`:
```
255: iget-object p1, p1, Liod;->a:Ldt0;
259: invoke-interface {p1}, Ldt0;->a()Z
267: if-nez p1, :cond_3
```

This means:
1. Pointing `NAV_INTERCEPTOR = "Liod;"` is **not enough** — the iget-on-`a:Ldt0;` predicate will not find that opcode in `iod.a` because it's no longer there.
2. Hooking `Lhod;->invokeSuspend` instead requires accepting the coroutine state-machine context: a different register window, the iget being read from `p1` (Liod*) not `p0` (Lhod*), and surrounding switch dispatch on the state label.

### Three options for the patch

**Option A — Skip NAV_INTERCEPTOR entirely.** Empirically the other anchors (AUTH_IMPL.h/e/d returning fake StateFlows + NAVIGATOR gates short-circuiting + GAME_LIB_REPO.e returning "99999") already cover the user-facing surface. If device testing shows no login-redirect leaks, leave NAV_INTERCEPTOR un-patched on 6.0.4. Cheapest, lowest risk of new breakage.

**Option B — Patch `Liod;->a` to short-circuit before the continuation dispatch.** Replace the entire `a(Lrdb;Lzzn;Laem;)V` body with `return-void` (or a passthrough invocation of the next interceptor). Requires understanding what `iod.a` is supposed to do when bypassed — without reading the full body I can't say whether returning void produces the right downstream behavior or breaks navigation entirely.

**Option C — Hook `Lhod;->invokeSuspend`, rewrite the auth check.** Find the `invoke-interface Ldt0;->a()Z` at idx 259 inside `hod.invokeSuspend`, replace `move-result p1` + `if-nez p1` with `const/4 p1, 0x1` + `goto :cond_3`. Same logical edit as before, just inside a continuation. Most surgical; preserves all surrounding navigation flow.

Option **A** is my recommended starting point unless device testing reveals a login-redirect regression. Option **C** is the fallback.

## Suggested execution order

1. Drop in the 6 clean BypassLogin letter swaps (AUTH_IMPL / AUTH_INTERFACE / AUTH_TOKEN / GAME_LIB_REPO / NAVIGATOR + screen-enum/login-intent sub-letters).
2. Comment out the NAV_INTERCEPTOR apply-block (option A) — leave a `// 6.0.4 TODO` marker.
3. Update FakeAuthToken/FakeUserAccount/FakeStateFlow Java letter constants.
4. Update RedirectCatalogApi + PrefixApiPath + DebugLog constants.
5. Bump `base-apk-602` → `base-apk-604` in any build-script reference; update `Constants.kt`'s GAMEHUB_VERSION if it tracks versionCode (112 → 114).
6. CI build, fix any patcher misses by inspecting decompile + iterating on a single anchor at a time.
7. Device-test for login-redirect regressions; only if found, implement option C for NAV_INTERCEPTOR.

## VJoy export/import (`ExportControlsPatch.kt`)

New patch (`patches/.../gamehub/misc/exportcontrols/`) that hijacks the on-screen-controls cloud-share repository methods. Anchors below.

### 6.0.4 ground truth (baksmali'd from the patched-Normal APK, 2026-05-21)

| Anchor | 6.0.4 actual |
|---|---|
| Share repo class | `Lrqn;` (classes3.dex, sole class containing both vcontroller URL literals) |
| Implements interface | `Lgqn;` |
| Share method | `Lrqn;->i(Lsrn;Lci3;)Ljava/lang/Object;` — `const-string/jumbo "vcontroller/shareMap"` at line 10932 of rqn.smali |
| Apply method | `Lrqn;->d(Lwpn;Lci3;)Ljava/lang/Object;` — `const-string/jumbo "vcontroller/getMapByShareCode"` at line 3717 |
| Layout DTO | `Lsrn;` (R8-renamed `VJoyLayout`) — first param of share method |
| Apply code DTO | `Lwpn;` — first param of apply method (NOT a bare String; it's a wrapper carrying the code) |
| Continuation | `Lci3;` (R8-renamed `kotlin.coroutines.Continuation`) — second param of every suspend method |

### Pre1 patch (`332ee89`) post-mortem

The first cut shipped a predicate of `returnType == Ljava/lang/Object; && parameterTypes.size == 2 && parameterTypes[1] == Lkotlin/coroutines/Continuation; && bodyReferencesString(URL)`. Two of the four predicates were violated on 6.0.4:

- `Lkotlin/coroutines/Continuation;` does not appear as a parameter type anywhere in the dex; R8 mangled it to `Lci3;`. The patch's `firstMethod {}` matched zero methods.
- `Ljava/lang/String;` as `parameterTypes[0]` (for the apply method) was also wrong — actual first param is `Lwpn;`, a wrapper DTO around the share code.

ReVanced Patcher's per-patch exception isolation swallowed the `NoSuchElementException` from `firstMethod {}`, the build artefact still produced (with the extension classes packed in via `gamehub.rve`), and the device test produced the stock cloud-share UX because nothing got injected. Lesson: **never anchor by Kotlin-stdlib type names** in this app — R8 keep-rules don't cover kotlin.coroutines / kotlin.jvm.functions; the URL-fragment body match is the only stable discriminator.

### Pre2 fix

Patch now matches solely on `(returnType == Object) && parameterTypes.size == 2 && bodyReferencesString(URL)`. Each URL fragment appears in exactly ONE method body across the whole dex tree, so the predicate is unique without needing the Continuation anchor.

### Anchor 4 — Share/apply button label sgets (still NOT WIRED)

The `ExportControlsResourcesPatch` adds two sentinel CVR entries (`bh_vjoy_export_label`, `bh_vjoy_import_label`) and the shared `Lxd3;->l1` resolver (injected by `VibrationMenuRowPatch`) is extended to handle them. The bytecode side of the relabel is **deferred** — see the "Hook 3" comment in `ExportControlsPatch.kt`. To complete: find the Composables that render the Share / Apply buttons (callers of `Lrqn;->i` and `Lrqn;->d`), then rewrite their label sget. Same Lell-via-Unsafe-allocate pattern as `BhMenuRowClick.appendLibraryPopupRow`.

### `VJoyLayout` FQN does NOT survive R8

The 600 master map asserted the @Serializable class would be kept by R8 keep-rules. **Empirically false on 6.0.4** — VJoyLayout is renamed to `Lsrn;`. `BhVjoyJson.decodeLayout` therefore can't use `Class.forName("com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayout")` on 6.0.4. Pre2 caches the layout class on first encode call and reuses it for decode; consequence is that **import only works in app processes where at least one export has happened first**. Acceptable for the scaffold; revisit if a user-reported "fresh-process import fails" comes up.

### Legacy recipes (kept for future re-derivation)

### Anchor 1 — `VJOY_REPO_CLASS` (placeholder: `Lnyf;` from 6.0.1)

Implementation of the `Lkyf;` share-API interface (6.0.1 letter). Per 600 master map §26.3:
- Ctor: `<init>(Lj40;Lzi5;Lhp7;)V` — `(CoroutineScope, HttpClient, GameLibraryRepository)`.
- Two suspend methods, one taking a `VJoyLayout`, the other taking a `String` share code.

Re-derivation recipe:
1. `grep -r "vcontroller/shareMap" smali_classes*/` — the unique class with this URL literal in `<clinit>` or in the method body is the repo (or its Ktor URL-builder helper invoked from the repo).
2. The class itself implements the share interface. Confirm by checking `.implements Lkyf;` (or its 6.0.4 successor — verify the interface letter by finding the abstract interface with two suspend methods, layout-typed + String-typed).
3. The patch does NOT actually need the class letter (it anchors by URL literal in `bodyReferencesString`), but documenting it here makes future audits easier.

### Anchor 2 — Share-method JVM signature

After Kotlin compile, the suspend `shareMap(layout)` becomes:
```
(LVJoyLayout-or-some-internal-DTO;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
```
The patch matches on:
- 2 parameters
- Second parameter is exactly `Lkotlin/coroutines/Continuation;`
- Return type is `Ljava/lang/Object;`
- Body contains const-string `"vcontroller/shareMap"`

Verify on the 6.0.4 smali that the matcher resolves to exactly one method. If multiple candidates fire (e.g. there's a wrapper that calls the impl), tighten by also requiring the body to invoke Ktor's `HttpClient.post` or by adding an `definingClass ==` predicate against the rederived `VJOY_REPO_CLASS`.

### Anchor 3 — Apply-method JVM signature

Same shape, signature:
```
(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
```
Anchor by const-string `"vcontroller/getMapByShareCode"`.

### Anchor 4 — Share/apply button label sgets (NOT YET WIRED)

The `ExportControlsResourcesPatch` adds two sentinel CVR entries (`bh_vjoy_export_label`, `bh_vjoy_import_label`) and the shared `Lxd3;->l1` resolver (injected by `VibrationMenuRowPatch`) is extended to handle them. The bytecode side of the relabel is **deferred** — see the "Hook 3" comment in `ExportControlsPatch.kt`. To complete:

1. Find the Composables that render the "Share" button and the "Apply share code" button on the VJoy main / edit screens. They will both invoke the repo's share / apply methods (which we already anchored). Walk callers of those methods.
2. For each rendering Composable, find the `sget-object L<X>;-><field>:Lxrl;` that loads the button's label (the `Lxrl;` is the Compose-resource wrapper). That's the rewrite site.
3. Add an instruction-replace in `ExportControlsPatch.kt` that swaps each sget's reference to a new sget that loads an `Lell;` (sentinel-keyed resource descriptor) pointing at `bh_vjoy_export_label` / `bh_vjoy_import_label`. Use the same `Lell;`-via-Unsafe-allocate pattern `BhMenuRowClick.appendLibraryPopupRow` uses.

Until anchor 4 is wired, the **buttons keep their stock labels** but the click behavior is hijacked (correct outcome, wrong label). Functional but not polished.

### Anchor 5 — `VJoyLayout` FQN (low-risk)

`com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayout`. Kotlinx-`@Serializable` classes are kept by R8 keep-rules; the FQN should be stable across 6.0.x. If it has moved, fix in both:
- `extensions/gamehub/.../exportcontrols/BhVjoyJson.java` — `VJOY_LAYOUT_FQN` const.
- `patches/.../exportcontrols/ExportControlsPatch.kt` — `VJOY_LAYOUT_CLASS` const (not currently used by predicates; kept for documentation).

### Verification checklist after first build

1. `apktool d` the patched APK, grep `classes*.dex` for `BhVjoyShareHook` — confirm the static calls land at index 0 of the two repo methods.
2. Device test: open VJoy edit screen → tap Share → confirm SAF file picker appears → save → confirm toast shows file path → re-open file with any text editor → confirm valid VJoyLayout JSON.
3. Tap "Apply share code" → confirm SAF open picker appears → pick the exported file → confirm layout shows up in the local layout list.
4. `logcat -s BhVjoyShareHook BhSafProxy BhVjoyJson` during steps 2–3 — any WARN/ERROR is a regression.

## VJoy save-coroutine + DB registry anchors (pre11)

The save side has been fully reverse-engineered as of pre11 (2026-05-24 device-tested):

### Save coroutine — `Lm0n;` (file write)

```
<init>(Ljava/lang/String;Lcom/xiaoji/egggame/common/ui/vjoy/model/VJoyLayout;Lbi3;)V
```

Single-shot kotlinx-coroutines suspend block. Writes layout.json + assets/ + (optionally) preview.png to `vjoy_layouts/<layoutId>/`. Returns a `VJoyLayoutSaveReceipt` (R8-keep-listed FQN). Invoked via the public-suspend wrapper `Lo0n;->i(String, VJoyLayout, Lci3;) Object` which internally does `BuildersKt.withContext(Lf80;->a, new Lm0n;(id, layout, null), continuation)`.

To call from a non-coroutine Java context (BhVjoyImporter.saveLayoutLocal), we **bypass `Lo0n;->i`** because its third param type is the abstract `Lci3;` which java.lang.reflect.Proxy can't satisfy. Instead call `Lw0o;->s0(Ldm3;Ldx6;Lbi3;) Object` (`BuildersKt.withContext`) directly — Bi3 is the interface, Proxy-able.

### Continuation proxy shape

`Lbi3;` interface (`kotlin.coroutines.Continuation`):
- `getContext() Ldm3;` — return any non-null CoroutineContext. We use the IO dispatcher (which implements `Ldm3;` via Element).
- `resumeWith(Object) V` — receives the kotlin.Result-wrapped value. The raw Object IS the Result (Result is inline-class erased).

Our Proxy captures the result in a CompletableFuture from resumeWith(). saveLayoutLocal checks if the suspend returned the `COROUTINE_SUSPENDED` sentinel (detected by toString containing "COROUTINE_SUSPENDED") and blocks on the future if so.

### `kotlinx.coroutines` static helpers (R8-renamed)

| Purpose | 6.0.4 letter | Stock FQN |
|---|---|---|
| `BuildersKt.withContext` | `Lw0o;->s0(Ldm3;Ldx6;Lbi3;) Object` | `kotlinx.coroutines.BuildersKt.withContext(CoroutineContext, Function2, Continuation)` |
| Dispatchers holder | `Lf80;` (static field `a:Ll14;` is IO) | `kotlinx.coroutines.Dispatchers.IO` |
| Continuation interface | `Lbi3;` | `kotlin.coroutines.Continuation` |
| ContinuationImpl (abstract) | `Lci3;` extends `Lk11;` | `kotlin.coroutines.jvm.internal.ContinuationImpl` |
| CoroutineContext | `Ldm3;` | `kotlin.coroutines.CoroutineContext` |
| Function2 | `Ldx6;` | `kotlin.jvm.functions.Function2` |

### `kotlinx.serialization.json.Json` instance

`kotlinx.serialization.json.Json.Default` does NOT have the polymorphic `InputMapping` SerializersModule needed for VJoyLayout — using it triggers "Class discriminator was missing". The host registers polymorphic InputMapping subtypes on a **custom** Json instance held by:

```
com.xiaoji.egggame.common.ui.vjoy.model.VJoyLayoutJson  (R8-kept FQN)
  private static final Default:Lzeb;
  private static final Export:Lzeb;     // pretty-printed
  private static final Snapshot:Lzeb;
  public static final INSTANCE:VJoyLayoutJson
```

`Lzeb;` = abstract `Json` class, `Lyeb;` = `Json.Default` concrete subclass. The host's configured Default lives at `VJoyLayoutJson.Default`. Resolve via `Class.forName(VJoyLayoutJson FQN).getDeclaredField("Default")` (private — needs setAccessible).

`Json.decodeFromString` is renamed too — find by method shape: `(SerializationStrategy, String) -> Object` on the Json instance. See `BhVjoyJson.findDecodeMethod`.

### Layout registry — `egggame.db.virtual_key_layout` (Room)

After `Lm0n;` save completes, the host's Create flow ALSO inserts a row into the `virtual_key_layout` table in `egggame.db` (Room). My Layouts is backed by Room's Flow on this table — without the row, the layout is invisible regardless of disk state.

32-column schema (full dump in pre11 commit message). Required values for an imported layout matching the visible-in-list shape:

| Column | Value |
|---|---|
| user_id | `"99999"` |
| folder_key | `<layoutId>` (matches layout.json's `"id"` field) |
| folder_path | `"vjoy_layouts/<layoutId>/"` |
| title_i18n_json | `{"default":"<layoutName>"}` |
| title_search | `<layoutName>` |
| layout_type | `"common"` (host has no separate field in VJoyLayout — set by user choice at Create) |
| source / catalog / acquire | `"local" / "local" / "created"` |
| source_key | `"local:<layoutId>"` |
| apply_count | `0` |
| publish_status | `"none"` |
| last_upload_result / last_download_result | `"none" / "none"` |
| index_mtime / created_at / updated_at | `System.currentTimeMillis()` |
| index_hash | from `VJoyLayoutSaveReceipt.getConfigHash()` |
| broken | `0` |

### CRITICAL: DB must be opened in WAL mode

Room runs egggame.db in WAL journal mode. Opening a second connection in default (journal) mode and writing **corrupts the DB** — verified pre10f: Room's next read crashed with `SQLITE_CORRUPT (code 11)`. Fix:

```java
SQLiteDatabase.OpenParams params = new SQLiteDatabase.OpenParams.Builder()
    .setOpenFlags(SQLiteDatabase.OPEN_READWRITE)
    .setJournalMode("WAL")
    .setSynchronousMode("NORMAL")
    .build();
SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile, params);
```

### Suspend-method double-fire gate

Smali hooks injected at the head of a suspend method fire TWICE per user-tap:
1. Initial entry — args are the real values
2. Coroutine resume — args may be null / state-machine reentry values

Without a gate, both fires kick off SAF launches in `interceptApply`; the second hits "Import already in flight" and toasts a spurious "Import failed" right as the file picker appears. Fix: `AtomicBoolean` gate in BhVjoyShareHook (`IMPORT_IN_FLIGHT`).

### CDN serves CORRUPTED ZIP bytes — JSON survives intact

Xiaoji's CDN (tencent-cos) serves layout `.gtheme` archives with binary ZIP headers UTF-8-mangled (every byte ≥ 0x80 replaced with `0xEF 0xBF 0xBD`, the UTF-8 encoding of U+FFFD). `ZipInputStream` chokes with "invalid stored block lengths" on every payload; even native `unzip` rejects them.

But the JSON content inside the broken container is itself valid UTF-8 and survives intact. `BhVjoyImporter.extractJsonPayload` brace-matches `{...}` directly in the byte stream, skipping ZIP entirely.

For export: we save the body byte-for-byte (`.gtheme` is the host's native shareable format, corruption preserved — the host's own apply-by-share-code path consumes corrupted bytes the same way).

### SAF ContentResolver corrupts binary on this device

`ContentResolver.openInputStream(uri)` / `openOutputStream(uri, "w")` on Samsung One UI 7 (kernel 5.x) goes through a SAF document provider that DECODES bytes as UTF-8 and RE-ENCODES them — every byte ≥ 0x80 → `0xEF 0xBF 0xBD`. Verified by adding a "first 32 bytes" log after HttpURLConnection's getInputStream (bytes already corrupted on this path).

Fix: open the SAF URI via `ContentResolver.openFileDescriptor(uri, "r"|"w")` and wrap with `FileInputStream(pfd.getFileDescriptor())` / `FileOutputStream(...)`. Raw fd, no provider transformation.

### Pending — Compose-level Import button hijack (deferred)

To skip the "Import Layout from File" textbox dialog entirely (user currently has to type any char + tap Confirm to launch SAF), need to find the Composable that:
1. Renders the Import button (uses string key `features_vjoy_main_action_import`)
2. Has an `onClick: () -> Unit` lambda
3. That lambda dispatches a "show import dialog" command — probably `Lytm;->n(Lotm;)` or similar via the layouts-store ViewModel

The label-holder lambdas (`Ljgl;`, `Lagl;`, `Lggl;`) are synthetic Function0 string-resource providers — not the click handlers. The click handlers are in DIFFERENT Compose-generated classes whose call sites we haven't enumerated yet.

Approach for resuming:
1. Probe instrumentation: hook `Lytm;->n(Lotm;)`, `Lytm;->o(Ltcn;)`, `Lytm;->p(Lhtm;)` with a stack-dump
2. Tap Import button → see which dispatch method fires + what command type
3. Match the command type to a "show dialog" intent → hook one level up to swap for our SAF launch
