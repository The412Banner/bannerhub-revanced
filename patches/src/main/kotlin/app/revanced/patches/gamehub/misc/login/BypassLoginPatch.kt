package app.revanced.patches.gamehub.misc.login

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstLiteralInstructionOrThrow
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
//   l() → field e = GUEST StateFlow<Boolean>        (backs the k()Z default)
//   h() → field b = UserProfile StateFlow<Lhfr;>    (backs the d() default)
//   f() → field c = UserToken   StateFlow<Lpfr;>    (backs the i() default)
//
// 🔧 pre20 POLARITY FIX. Earlier bases (and pre8–pre19) labelled this flow
// "isLoggedIn" and forced it TRUE. On 6.1.0 it is the GUEST flag: yf1's ctor
// builds field e as stateIn(map(userAccountFlow, tag 8), FALSE) and the app's own
// "logged in with a real account" signal lives in m()/c() instead (field d =
// stateIn(combine(userProfile, userToken), FALSE)). Forcing the guest flow TRUE
// therefore made the app a GUEST, and the navigation-layer full-account gate
// (fch.j(): reads k()=l().getValue(); "Navigate intercepted guest for
// full-account key=SteamLogin") dropped the Steam-QR navigation. So this flow is
// now faked FALSE (not a guest) while m()/c() is faked TRUE (has a full account) —
// one coherent "full, non-guest account" identity, not per-gate clamps.
private const val IMPL_IS_GUEST_FLOW = "l"
// h() = the UserProfile StateFlow (field b), backing the interface default
// d()Lhfr;. Faked pre8–pre18, REMOVED in pre19 (judged "off the Bind-Steam
// path" — true for that gate), and RESTORED in pre21 because it is the LIBRARY
// grid's READ-KEY, which is a different consumer entirely:
//   hzi (library VM) collects p5a.M() =
//     transformLatest(rf1.h()) { profile ->
//       SELECT * FROM t_game_library_base WHERE user_id = profile.a }
//   (a5a.invokeSuspend case 0: `String str = hfr.a` is bound as the `?`).
// Left unfaked, h() emitted the REAL DB user_account row, whose user_id is EMPTY
// on our bypassed session, so the grid queried user_id="" and matched nothing —
// while the WRITE path (p5a.h() = returnEarly("99999") below) stored every row
// under "99999". Device-proven: an imported local game sat in
// t_game_library_base under user_id='99999' but never rendered. Faking h() with
// FakeStateFlow.userFlow() (an hfr whose .a = "99999", the SAME constant
// FakeAuthToken and p5a.h() already use) makes read-key == write-key == "99999",
// so the existing row surfaces with no re-import.
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

// The GUEST check the navigator gates call. k()Z = l().getValue() (interface
// default on Lrf1;). fch.i()/fch.j() gate on this: j() intercepts navigation to a
// full-account destination when k() is TRUE (guest). Forcing it FALSE (not a
// guest) is what lets the SteamLogin modal through. (Was mislabeled the
// "isLoggedIn" check pre20 and forced TRUE — the exact inversion that self-blocked
// Bind-Steam.)
private const val AUTH_IS_GUEST_METHOD = "k"
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

// -------------------------------------------------------------------------
// ProfileTab (Home > Profile) state — the ACTUAL Bind-Steam gate on 6.1.0.
//
//   PROFILE_MODEL     Lgek;  "ProfileTabModel". Its toString proves the field
//                     roles: .a = isLoggedIn, .b = isGuest.
//   PROFILE_COLLECTOR Lhfk;  the FlowCollector.emit() that reduces the auth
//                     StateFlows into PROFILE_MODEL (one packed-switch case per
//                     source flow). NOT pinned by letter below — located by the
//                     copy-mask literal instead.
//
// The gate (ofk, "ClickBindSteam") reads gek.b directly:
//   iget-boolean vN, Lgek;->b:Z  +  if-eqz -> real bind r()  /  else guest no-op.
// Device log: "HomeProfile: ProfileTab ClickBindSteam loggedIn=true guest=true".
//
// gek.b's SOLE writer is the emit() branch that copies one unboxed Boolean (from
// the auth guest StateFlow, rf1.l()) into Gek.a(state, ...) as arg2. That copy
// call's flags int is 0xFFFFFFD: every field is kept from the old state EXCEPT
// bit 1 (0x2 = arg2 = field b = isGuest), which is applied from the argument.
// The mask is unique to this branch, so it is our structural anchor.
private const val PROFILE_MODEL   = "Lgek;"
@Suppress("unused")
private const val PROFILE_COLLECTOR = "Lhfk;"
private const val GUEST_COPY_MASK = 0xFFFFFFDL
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
        // 🚨 DB SEEDING IS DISABLED — IT CORRUPTED THE DATABASE ON DEVICE.
        //
        // pre14-pre16 shipped a seeder that opened egggame.db with the FRAMEWORK
        // SQLite (android.database.sqlite.SQLiteDatabase). Room 3 does not use the
        // framework SQLite: it ships its own BUNDLED engine
        // (androidx.sqlite.driver.bundled). Two different SQLite implementations
        // writing the same WAL-mode file produced, on device:
        //
        //   FATAL EXCEPTION: android.database.SQLException: Error code: 11,
        //   message: database disk image is malformed
        //       at androidx.sqlite.driver.bundled.BundledSQLiteStatementKt.nativeReset
        //
        // The app crashed on first launch after install and worked on relaunch only
        // because Room recovered the file. That is data corruption, not a cosmetic
        // crash, and it is not an acceptable cost for a login bypass.
        //
        // The seeder's own safety rules covered the wrong risk: they guarded against
        // CREATING the database, and said nothing about which SQLite implementation
        // writes it. BhAuthSeed is left in the tree (unreferenced) with that lesson
        // recorded, in case a bundled-driver rewrite is ever wanted.
        //
        // Not re-enabling without: using androidx.sqlite's BUNDLED driver so exactly
        // one engine touches the file, plus an on-device integrity_check afterwards.

        // -----------------------------------------------------------------
        // AUTH_IMPL.l() — GUEST StateFlow getter (field e). ⚠️ NOT isLoggedIn.
        //
        // Original body: `iget-object p0, p0, AUTH_IMPL->e:StateFlow` + return.
        // yf1's ctor builds field e as
        //   stateIn(map(createFlow(user_account, auth_token), tag 8), FALSE)
        // i.e. a Boolean projection of the user row = isGuest, default FALSE.
        //
        // pre20: fake it FALSE (boolFalse), not TRUE. This is the SOURCE of the
        // fix — it makes k()=l().getValue()=false, which:
        //   • passes the nav-layer full-account gate (fch.j(): intercepts when
        //     k() is TRUE) so SteamLogin is not dropped;
        //   • feeds the ProfileTab reducer (hfk reads rf1.k()) so gek.b (isGuest)
        //     reduces to false and Bind-Steam takes the real-bind path.
        // The login-skip does NOT ride this flow — it rides m()/c() (faked TRUE
        // below) — so making guest false does not re-introduce the login wall.
        // The helper handles StateFlow construction so we don't grow `.locals`.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == AUTH_IMPL && name == IMPL_IS_GUEST_FLOW
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->e:StateFlow
            removeInstruction(0) // return-object p0
            // .locals is 0 in the original; we only use p0 so no register grow.
            addInstructions(
                0,
                """
                    invoke-static {}, $FAKE_STATE_FLOW->boolFalse()Ljava/lang/Object;
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        // -----------------------------------------------------------------
        // ProfileTab isGuest belt-and-suspenders (pre19; KEPT in pre20).
        //
        // The Bind-Steam gate (ofk, "ClickBindSteam") reads gek.b == isGuest
        // directly and branches: isGuest -> a no-op guest prompt; else -> r(),
        // the real store bind. Device log: "ClickBindSteam loggedIn=true
        // guest=true", tap does nothing.
        //
        // gek.b is written in exactly one place: the ProfileTab reducer's
        // FlowCollector.emit() branch that copies the auth guest signal (rf1.k(),
        // = l().getValue()) into Gek.a(oldState, ..., flags=0xFFFFFFD). In that
        // copy the flags keep every field EXCEPT bit 1 (arg2 = isGuest), which is
        // taken from the passed register. Forcing that register to 0 makes every
        // emitted ProfileTabModel isGuest=false.
        //
        // ⚠️ pre20 makes this REDUNDANT but keeps it deliberately. Now that the
        // SOURCE guest flow (AUTH_IMPL.l() = boolFalse) and the k() seam are FALSE,
        // the reducer already emits gek.b=false — this clamp no longer contradicts
        // the source (as it did in pre19, which forced l()/k() TRUE and then
        // clamped here), it merely reinforces it. Kept as belt-and-suspenders: if a
        // future base changes how the reducer reads the guest signal, this still
        // pins isGuest=false at the single write site. Costs one const.
        //
        // Anchored structurally on the unique copy-mask literal, not on the
        // R8 letters of the collector or its emit index.
        // -----------------------------------------------------------------
        firstMethod {
            name == "emit" &&
                parameterTypes.size == 2 &&
                implementation?.instructions?.any {
                    (it as? WideLiteralInstruction)?.wideLiteral == GUEST_COPY_MASK
                } == true
        }.apply {
            val maskIdx = indexOfFirstLiteralInstructionOrThrow(GUEST_COPY_MASK)
            // The Gek.a(...) copy is the first range-invoke after the mask load.
            val copyIdx = indexOfFirstInstructionOrThrow(maskIdx) {
                opcode == Opcode.INVOKE_STATIC_RANGE &&
                    getReference<MethodReference>()?.let {
                        it.definingClass == PROFILE_MODEL && it.name == "a"
                    } == true
            }
            // Gek.a(Gek state, Z isLoggedIn, Z isGuest, ...): the range starts at
            // p0=state, so p2 (isGuest, field b) is startRegister + 2.
            val guestReg =
                getInstruction<RegisterRangeInstruction>(copyIdx).startRegister + 2
            addInstructions(copyIdx, "const v$guestReg, 0x0")
        }

        // -----------------------------------------------------------------
        // AUTH_IMPL.e() — current-user StateFlow getter.
        //
        // ❌ OVERRIDE REMOVED in pre19. h() backs the interface default
        // d()Lhfr; (UserProfile). The prior fix (d3ea3720) faked this flow so
        // d()'s UserProfile.isGuest (Lhfr;->z) would read false, on the theory
        // that the Bind-Steam gate consulted it. It does NOT: the gate reads
        // ProfileTabModel.isGuest (gek.b), fed by rf1.l() through the collector
        // patched above — the d()/UserProfile path is never on it. And the
        // decorator copy of it (Llm;) is off-path too: Koin binds Lrf1; -> Lyf1;
        // (ia4.smali:84), so the ProfileTab never holds the Llm; instance.
        // The override was therefore dead code and is gone; the token,
        // isLoggedIn and real-session flow patches below remain.
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
        // AUTH_IMPL.h() — UserProfile StateFlow getter (field b). RESTORED pre21.
        //
        // This is the LIBRARY GRID's read-key (see IMPL_USER_FLOW note above):
        // hzi collects p5a.M() = transformLatest(rf1.h()){ profile -> query
        // t_game_library_base WHERE user_id = profile.a }. Unfaked, profile.a was
        // "" and the grid matched none of the rows the save path had written under
        // p5a.h()="99999". userFlow() emits an hfr with .a="99999" so read-key ==
        // write-key. Same 2-instruction .locals-0 shape as l()/m()/f().
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == AUTH_IMPL && name == IMPL_USER_FLOW
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->b:StateFlow
            removeInstruction(0) // return-object p0
            addInstructions(
                0,
                """
                    invoke-static {}, $FAKE_STATE_FLOW->userFlow()Ljava/lang/Object;
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
            // GUEST flow -> FALSE (not a guest); real-session/full-account -> TRUE.
            IMPL_IS_GUEST_FLOW to "boolFalse",
            IMPL_REAL_SESSION_FLOW to "boolTrue",
            // IMPL_USER_FLOW ("h") RESTORED pre21 — it is the library grid's
            // read-key (p5a.M() -> rf1.h() -> UserProfile.a). userFlow() carries
            // hfr.a="99999" to match the write-key p5a.h()="99999". Patched on the
            // decorator too: whichever rf1 instance a consumer holds must agree, and
            // p5a's injected `b` may be either the impl or this wrapper.
            IMPL_USER_FLOW to "userFlow",
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
        // AUTH_INTERFACE.k()Z — the GUEST check the navigator gates dispatch to.
        //
        // k() is a DEFAULT method on Lrf1; whose body is `l().getValue()` — so it
        // reports GUEST, not isLoggedIn. The navigator reads it directly:
        //   fch.i(): `if (keyReq && this.b.k()) j(key)`   (routes guests aside)
        //   fch.j(): `if (!k()) return false; … return a(...) || k()`
        //            → returns TRUE (⇒ "Navigate intercepted guest for
        //              full-account", navigation dropped) exactly when k() is TRUE.
        //
        // pre20 forces it to 0 (NOT a guest). Forcing it to 1 — as every prior base
        // did under the "isLoggedIn" mislabel — is precisely what made j() intercept
        // and swallow the SteamLogin modal. This is belt-and-braces with the
        // AUTH_IMPL.l() = boolFalse source patch above (k()'s own body reads
        // l().getValue(), already false once l() is faked); both layers are kept so
        // that if either anchor breaks on a future base the guest flag still reads
        // false. It does NOT affect login-skip, which rides c()/m() (faked TRUE),
        // a separate seam.
        //
        // Safe because AUTH_IMPL does NOT override k() (it implements only the
        // abstract members), so every invoke-interface k()Z lands on this body; and
        // the decorator's k() delegates to the wrapped instance's k(), inheriting it.
        // -----------------------------------------------------------------
        // NAVIGATOR is still resolved+asserted below so a future reshuffle that
        // removes the navigator seam fails loudly rather than shipping unguarded.
        firstMethod {
            definingClass == AUTH_INTERFACE &&
                name == AUTH_IS_GUEST_METHOD &&
                parameterTypes.isEmpty() &&
                returnType == "Z"
        }.apply {
            // Original body: invoke-interface l() -> getValue() -> check-cast
            // Boolean -> booleanValue -> return. Replace wholesale with `false`.
            // `.locals 0` in the original, and p0 is the receiver, so use p0 as the
            // return register to avoid growing the register count.
            repeat(implementation?.instructions?.count() ?: 0) { removeInstruction(0) }
            addInstructions(
                0,
                """
                    const/4 p0, 0x0
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
