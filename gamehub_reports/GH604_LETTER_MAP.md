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
