package app.revanced.patches.gamehub.misc.login

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.returnEarly

// =========================================================================
// 6.0.2 R8-mangled class letter map
//
// All names below are R8 outputs from the GameHub 6.0.2 base APK (r8-map-id
// 032c299c671f...). They WILL change on the next minor-version bump; treat
// this block as version config — update here, leave the patch body alone.
//
// To re-derive on a new base APK: decompile (`apktool d --no-res`) and find
// each by structural anchor:
//
//   AUTH_IMPL          : class with three instance fields of the same
//                        StateFlow-impl type AND a constructor accepting
//                        UserDao + AuthTokenDao.
//                        (Was `Los0;` in 6.0.0, `Lrs0;` in 6.0.1.)
//   AUTH_INTERFACE     : interface with abstract `h()`/`e()`/`d()` returning
//                        a StateFlow type. AUTH_IMPL implements it.
//                        (Was `Lis0;` in 6.0.0, `Lls0;` in 6.0.1.)
//   AUTH_TOKEN         : 10-field data class (S,S,S,S,Long,Long,J,Z,J,J)
//                        returned by AUTH_INTERFACE.f().
//                        (Was `Ll4m;` in 6.0.0, `Lfdm;` in 6.0.1.)
//   GAME_LIB_REPO      : class with `b:AUTH_INTERFACE` field AND constructor
//                        taking GameLibraryDatabase + AUTH_INTERFACE. Has
//                        a no-arg `String` getter that reads
//                        AUTH_INTERFACE.f().a (the user-id field). Method
//                        name renamed `f()` → `e()` between 6.0.1 and 6.0.2.
//                        (Was `Lxm7;` in 6.0.0, `Lhp7;` in 6.0.1.)
//   GAME_LIB_REPO_USERID_METHOD : the no-arg `()Ljava/lang/String;` method
//                        on GAME_LIB_REPO that returns the auth-token's
//                        user-id field. Verified by reading the body — it
//                        does `iget GAME_LIB_REPO->b:AUTH_INTERFACE` then
//                        `invoke-interface AUTH_INTERFACE->f()` then reads
//                        AUTH_TOKEN->a:String. Name changed across versions:
//                        6.0.0/6.0.1 → "f", 6.0.2 → "e".
//   NAVIGATOR          : class with `b:AUTH_INTERFACE` field AND two methods
//                        whose body somewhere matches `iget NAVIGATOR->b:AUTH_INTERFACE`
//                        + `invoke-interface AUTH_INTERFACE->a()Z` + `if-nez`
//                        + `new-instance L<Login intent>;`. The two methods
//                        are still called `i` and `r` in 6.0.2, but their
//                        single arg (the screen-route enum) is now `Lgi0;`
//                        (was `Lph0;` in 6.0.1). The Login intent class is
//                        `Lsa0;` in 6.0.2 (was `Lca0;` in 6.0.1). The patch
//                        anchors on the iget instruction, not the params.
//                        (Was `Lg8e;` in 6.0.0, `Lade;` in 6.0.1.)
//   NAV_INTERCEPTOR    : class implementing the host's NavigationInterceptor
//                        with `<init>(AUTH_INTERFACE)V` constructor and an
//                        `a(...)Object` method that calls AUTH_INTERFACE.a()
//                        before delegating to the next interceptor in chain.
//                        (Was `Lar0;` in 6.0.1; not present in 6.0.0.)
//
// MUTABLE_FLOW_FACTORY (6.0.0 / 6.0.1): a static `(Object) → StateFlow-impl`
//   method that was DIRECTLY assignable to AUTH_INTERFACE.h()'s return type.
//   In 6.0.2 the only one-arg factory (`Ltwo;->l(Object)Ltjk;`) returns a
//   type that is NOT a subtype of the abstract StateFlow interface declared
//   on h()/e(); the host wraps it in an `Lhzh;` adapter before exposing it.
//   To avoid growing patched-method `.locals` from 0 to 2, we route both
//   patches through the FakeStateFlow Java extension, which performs the
//   wrap via reflection and caches the result. Update the letter constants
//   inside FakeStateFlow.java on each base APK bump.
// 6.0.4 (r8-map-id 6a5cde6143fc...57b) — every anchor reshuffled from 6.0.2;
// see gamehub_reports/GH604_LETTER_MAP.md for the full delta and structural
// verification per anchor.
// 6.0.7 (r8-map-id 4551753f...) — full reshuffle from 6.0.4; re-derived against
// ~/gh607-apktool-d using the structural anchors above. Method names on the auth
// interface (a/b/c/d/e/f/g/h) are preserved; only class letters + the userid
// method (e→g) and the second navigator gate (r→s) changed.
// 6.0.8 — re-derived against ~/gh608-apktool-d. AUTH_IMPL/AUTH_INTERFACE are
// UNCHANGED (Lfw0; implements Lcw0;, 3× Lq4g; StateFlow fields, getters d/e/h
// return Lsdi;, f() returns the token — bodies byte-identical to 607). Reshuffled:
//   AUTH_TOKEN   Ln2l;→Lt2l;  (= return type of Lcw0;->f(); 10-field token, .a=userId)
//   GAME_LIB_REPO Lam7;→Ldm7;  (am7 is now a coroutine lambda; dm7 has b:Lcw0;,
//                  save x(GameInfo,LaunchMethod,Continuation), userid getter h())
//   USERID method g→h          (dm7.h(): iget b:Lcw0; → f()Lt2l; → Lt2l;->a:String)
//   NAVIGATOR    Lg8d;→Lj8d;   (j8d has b:Lcw0;, gates i/s with the auth-check+login)
// 6.0.9 — full reshuffle from 6.0.8; re-derived against ~/gh609-apktool-d via the
// structural anchors above (the 6.0.8 letters were all reassigned to unrelated
// classes by R8). Auth interface method names (a/b/c/d/e/f/g/h) and the save/userid
// method names are PRESERVED; only class letters changed:
//   AUTH_IMPL      Lfw0;→Lux0;  (implements Lrx0;, ctor (UserDao,AuthTokenDao,Li90;),
//                   3 StateFlow fields a/b/c:Lcrg;, getters d/e/h()Ly4j;)
//   AUTH_INTERFACE Lcw0;→Lrx0;  (interface; a()Z, d/e/h()Ly4j;, f()Lqbm; default)
//   AUTH_TOKEN     Lt2l;→Lqbm;  (= rx0.f() return; 10-field token S,S,S,S,Long,Long,J,Z,J,J; .a=userId)
//   GAME_LIB_REPO  Ldm7;→Lqv7;  (ctor (GameLibraryDatabase,Lrx0;); userid getter h()
//                   reads b:Lrx0;→f()Lqbm;→qbm.a; save x(GameInfo,LaunchMethod,Lpv3;))
//   NAVIGATOR      Lj8d;→Ljrd;  (b:Lrx0;; gates i/s = iget b:Lrx0;→invoke a()Z→if-nez→login)
// FakeStateFlow.java letters re-derived too (impl udi→a5j, wrapper q4g→crg, holder s3d→smd).
// 6.1.0 — the auth layer was RESTRUCTURED, not merely re-lettered. Full re-derivation
// against ~/gh610-apktool-d; every anchor below was verified by reading the smali body.
//
//   AUTH_INTERFACE  Lrx0;→Lrf1;  (smali_classes3/rf1.smali; 6 StateFlow getters
//                    b/f/h/l/m/n + DEFAULT methods c()Z k()Z d()Lhfr; i()Lpfr;)
//   AUTH_IMPL       Lux0;→Lyf1;  (implements Lrf1;; ctor (Ludr;Lqg1;Lui0;Lxdn;)V takes
//                    the AuthTokenDao Lqg1;; 4 StateFlow + 4 MutableStateFlow fields)
//   AUTH_TOKEN      Lqbm;→Lpfr;  (= "UserToken"; 10 fields S,S,S,S,Long,Long,J,Z,J,J —
//                    the SAME shape as 6.0.9, and .a is still userId)
//   USER model            →Lhfr;  (= "UserProfile", .a = userId)
//   GAME_LIB_REPO   Lqv7;→Lp5a;  (ctor (GameLibraryDatabase;Lrf1;Liwi;)V)
//   NAVIGATOR       Ljrd;→Lfch;  (ctor (Landroidx/navigation3/runtime/NavBackStack;Lrf1;…))
//
// 🔑 KEY CHANGES vs 6.0.x, beyond the letters:
// 1. The interface now declares the REAL kotlinx StateFlow, not an obfuscated one.
//    FakeStateFlow.java therefore no longer needs its 3 mangled letter constants —
//    it calls the un-obfuscated kotlinx factory. See that file.
// 2. Method roles moved: isLoggedIn was `a()Z`, now `k()Z` (backed by the `l()`
//    StateFlow). The token accessor was `f()`, now `i()Lpfr;` (backed by `f()`).
//    The user accessor was `e()`, now `d()Lhfr;` (backed by `h()`).
//    ⚠️ There is a SECOND boolean default `c()Z` (backed by `m()`) that is NOT the
//    login flag — do not patch it by mistake.
// 3. AUTH_IMPL does NOT override the interface defaults (it implements only the
//    abstract members), so patching the impl's StateFlow getters propagates to
//    k()/d()/i() automatically. We patch the impl getters, not both layers.
// 4. ⚠️ A DECORATOR `Llm;` also implements Lrf1; and wraps another instance of it.
//    It is NOT the DI-bound implementation — Koin binds Lrf1; → Lyf1;
//    (ia4.smali:1040-1124, ha4.smali:618-622 construct `new Lyf1;` under
//    `const-class Lrf1;`), while Llm; is built only inside abstract Lbmr;:456-460.
//    Patching Lyf1; is therefore correct. Re-check this on the next bump: if the
//    binding ever moves to the decorator, every getter patch here is bypassed.
//
// GAME_LIB_REPO and NAVIGATOR are now located STRUCTURALLY, on un-obfuscated
// framework/app types in their constructors (GameLibraryDatabase and NavBackStack).
// R8 cannot rename those, so those two anchors should never need re-pinning again.
// 🚨🚨 6.1.0 DECORATOR — DEVICE-PROVEN REQUIRED, not optional.
// `Llm;` also implements AUTH_INTERFACE and WRAPS another instance of it
// (`a:Lrf1;` + its own StateFlow fields b/c/d/e/f/g). I initially dismissed it
// because Koin binds Lrf1; -> Lyf1; (ia4.smali:1040-1124, ha4.smali:618-622) —
// that was WRONG. The decorator is what the UI actually consumes, and it
// OVERRIDES EVERY getter we patch, returning its OWN fields rather than
// delegating:  l() -> lm.e ,  h() -> lm.b ,  f() -> lm.c
// so patching AUTH_IMPL alone is silently bypassed.
//
// Device symptom that exposed it (pre8): the app entered the UI fine and was NOT
// stuck at a login gate — because `lm.k()` DOES delegate to the interface default
// we patch — but the Library tab rendered its logged-out empty state ("Log in to
// view your game library") because the USER StateFlow was never faked. Exactly
// the "patch applies 9/9, CI green, does nothing" shape this base keeps producing.
// Confirmed by the absence of any DebugTrace output: our injected calls never ran.
//
// To re-derive: it is the class that IMPLEMENTS AUTH_INTERFACE **and** takes
// AUTH_INTERFACE in its constructor. (Don't anchor on the ctor alone — two classes
// have `<init>(Lrf1;)V` on 6.1.0; only this one implements the interface.)
private const val AUTH_DECORATOR         = "Llm;"
private const val AUTH_IMPL              = "Lyf1;"
private const val AUTH_INTERFACE         = "Lrf1;"
private const val AUTH_TOKEN             = "Lpfr;"
private const val GAME_LIB_REPO_USERID_METHOD = "h"

// Structural anchors — unobfuscated ctor parameter types, immune to R8 renaming.
private const val GAME_LIB_DATABASE = "Lcom/xiaoji/egggame/game/database/GameLibraryDatabase;"
private const val NAV_BACK_STACK    = "Landroidx/navigation3/runtime/NavBackStack;"

// 6.1.0 impl getters (all `.locals 0`, body = iget-object p0 + return-object p0):
//   l() → field e = isLoggedIn StateFlow<Boolean>   (backs the k()Z default)
//   h() → field b = UserProfile StateFlow<Lhfr;>    (backs the d() default)
//   f() → field c = UserToken   StateFlow<Lpfr;>    (backs the i() default)
private const val IMPL_IS_LOGGED_IN_FLOW = "l"
private const val IMPL_USER_FLOW         = "h"
private const val IMPL_TOKEN_FLOW        = "f"
// The COMBINED user+token flow, read by the interface default c()Z. Distinct from
// k()Z (isLoggedIn): the ctor builds this one as
//   stateIn(combine(userAccountFlow, authTokenFlow, ...))
// i.e. it asserts BOTH a user AND a token, which is the app's notion of a real
// (non-guest) session. Left unpatched until now on the assumption that it "wasn't
// the login flag" -- true, but it turns out to be the GUEST flag, which is why the
// Profile screen kept saying "Guest Mode" even with the DB correctly seeded and the
// profile override removed.
private const val IMPL_REAL_SESSION_FLOW = "m"

// The isLoggedIn check the navigator gates call.
private const val AUTH_IS_LOGGED_IN_METHOD = "k"
// NAV_INTERCEPTOR in 6.0.4 is Liod;, but its a(...) body no longer holds the
// auth check inline — it dispatches to coroutine continuation Lhod;->invokeSuspend
// where the iget+invoke+if-nez pattern actually lives. The apply block below
// is commented out for 6.0.4; if device testing reveals a login-redirect leak
// post-build, switch to option C (hook hod.invokeSuspend) — see GH604_LETTER_MAP.md.
@Suppress("unused")
private const val NAV_INTERCEPTOR        = "Liod;"

// Seeds the Room DB with a synthetic account -- the real gate on 6.1.0.
private const val AUTH_SEED = "Lcom/xj/winemu/login/BhAuthSeed;"
private const val FAKE_STATE_FLOW = "Lapp/revanced/extension/gamehub/login/FakeStateFlow;"
// =========================================================================

@Suppress("unused")
val bypassLoginPatch = bytecodePatch(
    name = "Bypass login",
    description = "Bypasses the login requirement by replacing the auth-session StateFlow getters with synthetic always-true / always-non-null values, plus short-circuiting the navigator gates and the navigation interceptor.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    // This patch injects calls into our extension (FakeStateFlow, FakeAuthToken,
    // BhAuthSeed) but never declared the dependency that actually bundles the
    // extension into the APK -- it worked only because some OTHER selected patch
    // happened to pull it in. That is the same latent coupling found in the Explore
    // patch, and on 6.1.0 (where many bytecode patches fail) it is exactly how you
    // get "patch succeeded, runtime no-op". Declared explicitly.
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // -----------------------------------------------------------------
        // SEED THE DATABASE — the actual gate on 6.1.0.
        //
        // Device-proven: faking the auth interface's getters is NOT enough. The
        // auth impl builds its StateFlows from the Room `user_account` /
        // `auth_token` tables (FlowUtil.createFlow), and the Library consumes
        // DB-derived state — so with an empty DB it renders "Log in to view your
        // game library" no matter how convincing the accessors are. Hand-seeding
        // one row into each table unlocked the Library instantly.
        //
        // Anchored on the application class, whose name is NOT obfuscated
        // (com.xiaoji.egggame.AndroidApp) — the same anchor DisableMobPushPatch
        // has used across every base. onCreate runs before anything queries the
        // library, and the seeder itself polls because Room creates the file
        // lazily. p0 is the Application, i.e. a Context.
        //
        // The accessor patches below are KEPT as belt-and-braces: they are
        // device-proven to execute, and if a future base changes how the DB is
        // read they may carry the bypass on their own.
        // -----------------------------------------------------------------
        // Injected into the AUTH IMPL CONSTRUCTOR rather than the application class.
        // The app-class anchor (Lcom/xiaoji/egggame/AndroidApp;->onCreate) is correct
        // in principle -- unobfuscated name, and DisableMobPushPatch already mutates
        // that very class -- but the patcher rejected the injection there with
        // "PatchException: classDef is null". Not worth fighting: the auth impl is a
        // class this patch already mutates successfully three times over, and its
        // constructor is an even better moment than onCreate, because it runs just
        // BEFORE the Room-backed auth flows are created, so the seeded rows are
        // present for the very first emission instead of arriving via invalidation.
        // BhAuthSeed.seed() resolves its own Context via ActivityThread, so no
        // Context parameter is needed here.
        firstMethod {
            definingClass == AUTH_IMPL && name == "<init>"
        }.addInstructions(0, "invoke-static {}, $AUTH_SEED->seed()V")

        // -----------------------------------------------------------------
        // AUTH_IMPL.h() — isLoggedIn StateFlow getter.
        //
        // Original body: `iget-object p0, p0, AUTH_IMPL->c:Lhzh;` + return.
        // The Boolean StateFlow it returns is built in the ctor by combining
        // UserDao + AuthTokenDao flows; default initial value is FALSE so
        // every collector sees logged-out at startup.
        //
        // Replace with `FakeStateFlow.boolTrue()` (a host-compatible
        // StateFlow holding TRUE). The helper handles the per-version
        // construction so we don't have to grow `.locals`.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == AUTH_IMPL && name == IMPL_IS_LOGGED_IN_FLOW
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->e:StateFlow
            removeInstruction(0) // return-object p0
            // .locals is 0 in the original; we only use p0 so no register grow.
            addInstructions(
                0,
                """
                    invoke-static {}, $FAKE_STATE_FLOW->boolTrue()Ljava/lang/Object;
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        // -----------------------------------------------------------------
        // AUTH_IMPL.e() — current-user StateFlow getter.
        //
        // Original body: `iget-object p0, p0, AUTH_IMPL->a:Lhzh;` + return.
        // Underlying StateFlow emits null when no UserEntity is in Room;
        // the library-list reader then `flatMapLatest`s null to an empty
        // Flow and the imported game never appears.
        //
        // Replace with `FakeStateFlow.userFlow()` so the reader's
        // flatMapLatest hits the userId-keyed query.
        // -----------------------------------------------------------------
        // ⚠️ DELIBERATELY NOT PATCHING THE USER-PROFILE FLOW ANY MORE.
        //
        // On 6.0.x this had to be faked because the DB had no user row, so the
        // profile flow emitted null and the library reader flat-mapped that to an
        // empty list. On 6.1.0 we seed a REAL row (see BhAuthSeed), so the
        // DB-derived profile is already correct AND complete — nickname, username,
        // is_guest=0, timestamps.
        //
        // Overriding it here made things WORSE, and this was visible on device:
        // SyntheticModel fills every String except the user id with "", so the
        // injected profile had no nickname, and the Profile screen fell back to its
        // `core_profile_guest_name` placeholder — literally "Guest Mode" — plus the
        // "Cloud saves are unavailable in Guest Mode" banner. Note isGuest (field z)
        // was ALREADY false in the injected object, so this was never a flag to
        // flip: it was an empty object shadowing a good one.
        //
        // Letting the real flow through is both simpler and more faithful. The
        // isLoggedIn and token overrides below stay, since nothing renders them.

        // -----------------------------------------------------------------
        // AUTH_IMPL.f() — UserToken StateFlow getter. NEW EDIT FOR 6.1.0.
        //
        // On 6.0.x the token was only reachable through the interface's
        // default accessor, so patching that accessor was sufficient. On
        // 6.1.0 the token is its own StateFlow on the impl, and the default
        // `i()Lpfr;` simply reads it. Patching the flow at source covers BOTH
        // `i()` callers and anything collecting the flow directly — which the
        // old shape could not reach.
        // -----------------------------------------------------------------
        // AUTH_IMPL.m() — the combined user+token "real session" flow behind c()Z.
        firstMethod {
            definingClass == AUTH_IMPL && name == IMPL_REAL_SESSION_FLOW
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->d:StateFlow
            removeInstruction(0) // return-object p0
            addInstructions(
                0,
                """
                    invoke-static {}, $FAKE_STATE_FLOW->boolTrue()Ljava/lang/Object;
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        firstMethod {
            definingClass == AUTH_IMPL && name == IMPL_TOKEN_FLOW
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->c:StateFlow
            removeInstruction(0) // return-object p0
            addInstructions(
                0,
                """
                    invoke-static {}, $FAKE_STATE_FLOW->tokenFlow()Ljava/lang/Object;
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        // -----------------------------------------------------------------
        // GAME_LIB_REPO userId getter (name == GAME_LIB_REPO_USERID_METHOD).
        //
        // Returns the user-id string used by Save (xm7.u in 6.0.0 / hp7
        // equivalent in 6.0.1 / uu7.v in 6.0.2) to filter library queries.
        // Pinning it to "99999" matches the synthetic identity used
        // elsewhere. Method name was `f()` in 6.0.0/6.0.1 and renamed to
        // `e()` in 6.0.2; the parameterTypes/returnType filter prevents an
        // accidental match against a same-named overload.
        // -----------------------------------------------------------------
        // -----------------------------------------------------------------
        // THE DECORATOR — same three StateFlow getters, same 2-instruction shape
        // (`.locals 0` / iget-object p0 / return-object p0), but reading its own
        // fields (l()->e, h()->b, f()->c). This is the instance the UI consumes,
        // so WITHOUT these three edits the whole patch is a no-op on the library
        // screen even though it applies cleanly. See the AUTH_DECORATOR note above.
        //
        // Both layers are patched deliberately: whichever instance a given consumer
        // holds, it now yields synthetic values.
        // -----------------------------------------------------------------
        listOf(
            IMPL_IS_LOGGED_IN_FLOW to "boolTrue",
            IMPL_REAL_SESSION_FLOW to "boolTrue",
            // IMPL_USER_FLOW intentionally absent — see the note above; the seeded
            // DB row is a better profile than anything we can synthesise.
            IMPL_TOKEN_FLOW to "tokenFlow",
        ).forEach { (getterName, helper) ->
            firstMethod {
                definingClass == AUTH_DECORATOR &&
                    name == getterName &&
                    parameterTypes.isEmpty() &&
                    returnType == "Lkotlinx/coroutines/flow/StateFlow;"
            }.apply {
                removeInstruction(0) // iget-object p0, p0, $AUTH_DECORATOR-><field>
                removeInstruction(0) // return-object p0
                addInstructions(
                    0,
                    """
                        invoke-static {}, $FAKE_STATE_FLOW->$helper()Ljava/lang/Object;
                        move-result-object p0
                        return-object p0
                    """,
                )
            }
        }

        // GAME_LIB_REPO is located structurally: the only class whose constructor
        // takes the unobfuscated GameLibraryDatabase. Its userId getter is still
        // named h() on 6.1.0, and its body was verified as
        //   iget b:AUTH_INTERFACE -> invoke-interface i()AUTH_TOKEN -> iget AUTH_TOKEN.a
        // ⚠️ GameLibraryDatabase alone is NOT unique — 8 classes take it as their
        // first ctor param on 6.1.0 (aqb, bif, g1a, ic9, p5a, qhf, xf9, yhf), and
        // `firstMethod` returns the FIRST match, which is not the repo. That mistake
        // failed as "Required value was null" from the userId lookup below, several
        // steps removed from the actual cause. The (database, AUTH_INTERFACE) PAIR is
        // what identifies the repo uniquely — only p5a takes the auth interface second.
        // `.toString()` because dexlib2 exposes parameterTypes as CharSequence, which
        // does not compare equal to a String literal.
        val gameLibRepoClass = firstMethod {
            name == "<init>" &&
                parameterTypes.size >= 2 &&
                parameterTypes[0].toString() == GAME_LIB_DATABASE &&
                parameterTypes[1].toString() == AUTH_INTERFACE
        }.definingClass

        firstMethod {
            definingClass == gameLibRepoClass &&
                name == GAME_LIB_REPO_USERID_METHOD &&
                parameterTypes.isEmpty() &&
                returnType == "Ljava/lang/String;"
        }.returnEarly("99999")

        // -----------------------------------------------------------------
        // AUTH_INTERFACE.f() — default method returning the auth-token
        // wrapper (10-field data class).
        //
        // Original body (6 instructions): invoke-interface d() →
        // move-result-object → invoke-interface getValue() →
        // move-result-object → check-cast AUTH_TOKEN → return-object.
        //
        // Replace with `FakeAuthToken.get() as AUTH_TOKEN` so direct
        // callers (the various lambdas that read the auth-token's a/b
        // fields directly) see a consistent synthetic identity.
        // -----------------------------------------------------------------
        // ⚠️ 6.1.0: the old edit here patched AUTH_INTERFACE."f" as the token
        // accessor. On 6.1.0 `f()` is the ABSTRACT StateFlow getter and the token
        // accessor is the `i()Lpfr;` default — so that edit would now target the
        // wrong member entirely (and an abstract method has no body to rewrite).
        // It is intentionally gone: the token is instead faked at source by the
        // AUTH_IMPL.f() StateFlow patch above, which `i()` reads. That covers both
        // `i()` callers and direct flow collectors, which the old shape could not.

        // -----------------------------------------------------------------
        // NAVIGATOR.i(...) and NAVIGATOR.r(...) — Login navigation gates.
        //
        // Both methods have the pattern (somewhere in their body):
        //   iget-object vN, p0, NAVIGATOR->b:AUTH_INTERFACE
        //   invoke-interface {vN}, AUTH_INTERFACE->a()Z   ← isLoggedIn check
        //   move-result vN
        //   if-nez vN, :skipLogin                          ← skips on logged in
        //   new-instance L<Login intent>;                   ← Login intent build
        //
        // Replace `invoke-interface a()Z` + `move-result` with `const/4 1`
        // so the branch always skips. Belt-and-braces with the StateFlow
        // patches above: even if AUTH_IMPL.h() weren't reached for some
        // reason, this gate still passes.
        // -----------------------------------------------------------------
        // NAVIGATOR is located structurally: the only class whose constructor takes
        // the unobfuscated androidx NavBackStack. On 6.1.0 its login gates live in
        // i(Lw01;)V (ONE gate) and j(Lw01;)Z (TWO gates) — three sites in total.
        //
        // ⚠️ The 6.0.x implementation looped over a hardcoded method-name list and
        // took the FIRST `iget b` per method. That would silently leave 6.1.0's
        // second gate in j() live — a login leak that still reports "succeeded" at
        // patch time. So instead: find EVERY `invoke-interface AUTH_INTERFACE->k()Z`
        // in the class and force each one to 1.
        //
        // Replacing the invoke+move-result pair (rather than editing the branch) is
        // also polarity-agnostic — the three sites branch if-eqz / if-nez / if-eqz
        // respectively, and forcing "logged in = true" is correct for all of them.
        // Rather than editing each gate's call site, neutralize the check they all
        // dispatch to: AUTH_INTERFACE.k()Z. This is strictly better than the 6.0.x
        // approach for three reasons:
        //   1. It covers ALL gates including 6.1.0's second gate inside j(), which a
        //      first-match-per-method loop would silently have left live — a login
        //      leak that still reports "succeeded" at patch time.
        //   2. It is polarity-agnostic (the three sites branch if-eqz / if-nez /
        //      if-eqz) and immune to further call sites appearing in a later base.
        //   3. It needs no class-by-type iteration, only the `firstMethod` lookup
        //      already used throughout this repo.
        // Safe because k() is a DEFAULT method on the interface and AUTH_IMPL does
        // NOT override it (it implements only the abstract members), so every
        // invoke-interface k()Z lands on this body.
        //
        // This is belt-and-braces with the AUTH_IMPL.l() patch above: k()'s original
        // body reads l().getValue(), which already returns TRUE once l() is faked.
        // Both layers are kept deliberately — if either anchor breaks on a future
        // base, the other still holds the gate open.
        firstMethod {
            definingClass == AUTH_INTERFACE &&
                name == AUTH_IS_LOGGED_IN_METHOD &&
                parameterTypes.isEmpty() &&
                returnType == "Z"
        }.apply {
            // Original body: invoke-interface l() -> getValue() -> check-cast
            // Boolean -> booleanValue -> return. Replace wholesale with `true`.
            // `.locals 0` in the original, and p0 is the receiver, so use p0 as the
            // return register to avoid growing the register count.
            repeat(implementation?.instructions?.count() ?: 0) { removeInstruction(0) }
            addInstructions(
                0,
                """
                    const/4 p0, 0x1
                    return p0
                """,
            )
        }

        // NAVIGATOR is still resolved (and asserted to exist) so that a future base
        // reshuffle that removes the navigator seam fails loudly here rather than
        // silently shipping a build with an unguarded login path.
        // NavBackStack as first ctor param IS unique on 6.1.0 (only the navigator),
        // unlike GameLibraryDatabase above.
        firstMethod {
            name == "<init>" &&
                parameterTypes.isNotEmpty() &&
                parameterTypes[0].toString() == NAV_BACK_STACK
        }

        // -----------------------------------------------------------------
        // NAV_INTERCEPTOR.a(...) — SKIPPED FOR 6.0.4.
        //
        // In 6.0.0–6.0.2 this class held the auth check inline (iget +
        // invoke-interface a()Z + if-nez + new-instance redirect). In 6.0.4
        // Liod;->a(Lrdb;Lzzn;Laem;)V builds a coroutine continuation Lhod;
        // and dispatches to it; the pattern this block looks for now lives
        // in Lhod;->invokeSuspend instead, with a continuation state-machine
        // register window. Hooking that requires a different edit shape
        // (option C in GH604_LETTER_MAP.md). For now skip and rely on:
        //   - AUTH_IMPL h/e/d returning fake StateFlows
        //   - NAVIGATOR i/r gates short-circuiting
        //   - GAME_LIB_REPO.e returning "99999"
        //   - is0.f / AUTH_INTERFACE.f returning the fake token
        // If device testing surfaces a login-redirect leak that the above
        // doesn't cover, implement option C against Lhod;->invokeSuspend.
        // -----------------------------------------------------------------
        // 6.0.4 TODO: re-enable via option C if needed.
        // firstMethod {
        //     definingClass == NAV_INTERCEPTOR && name == "a"
        // }.apply {
        //     val igetIdx = indexOfFirstInstructionOrThrow {
        //         opcode == Opcode.IGET_OBJECT &&
        //             getReference<FieldReference>()?.let {
        //                 it.name == "a" && it.definingClass == NAV_INTERCEPTOR
        //             } == true
        //     }
        //     val reg = (getInstruction(igetIdx) as TwoRegisterInstruction).registerA
        //     removeInstruction(igetIdx + 2) // move-result vN
        //     removeInstruction(igetIdx + 1) // invoke-interface AUTH_INTERFACE->a()Z
        //     addInstructions(
        //         igetIdx + 1,
        //         """
        //             const/4 v$reg, 0x1
        //         """,
        //     )
        // }
    }
}
