package app.revanced.patches.gamehub.misc.login

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

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
private const val AUTH_IMPL              = "Lux0;"
private const val AUTH_INTERFACE         = "Lrx0;"
private const val AUTH_TOKEN             = "Lqbm;"
private const val GAME_LIB_REPO          = "Lqv7;"
private const val GAME_LIB_REPO_USERID_METHOD = "h"
private const val NAVIGATOR              = "Ljrd;"
// NAV_INTERCEPTOR in 6.0.4 is Liod;, but its a(...) body no longer holds the
// auth check inline — it dispatches to coroutine continuation Lhod;->invokeSuspend
// where the iget+invoke+if-nez pattern actually lives. The apply block below
// is commented out for 6.0.4; if device testing reveals a login-redirect leak
// post-build, switch to option C (hook hod.invokeSuspend) — see GH604_LETTER_MAP.md.
@Suppress("unused")
private const val NAV_INTERCEPTOR        = "Liod;"

private const val FAKE_STATE_FLOW = "Lapp/revanced/extension/gamehub/login/FakeStateFlow;"
// =========================================================================

@Suppress("unused")
val bypassLoginPatch = bytecodePatch(
    name = "Bypass login",
    description = "Bypasses the login requirement by replacing the auth-session StateFlow getters with synthetic always-true / always-non-null values, plus short-circuiting the navigator gates and the navigation interceptor.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
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
            definingClass == AUTH_IMPL && name == "h"
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->c:Lhzh;
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
        firstMethod {
            definingClass == AUTH_IMPL && name == "e"
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->a:Lhzh;
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
        firstMethod {
            definingClass == GAME_LIB_REPO &&
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
        firstMethod {
            definingClass == AUTH_INTERFACE && name == "f"
        }.apply {
            repeat(6) { removeInstruction(0) }
            // FakeAuthToken.get() does the DebugTrace.write internally so
            // each fire shows "FakeAuthToken.get() called" in logcat.
            addInstructions(
                0,
                """
                    invoke-static {}, Lapp/revanced/extension/gamehub/login/FakeAuthToken;->get()Ljava/lang/Object;
                    move-result-object p0
                    check-cast p0, $AUTH_TOKEN
                    return-object p0
                """,
            )
        }

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
        for (methodName in listOf("i", "s")) {
            firstMethod {
                definingClass == NAVIGATOR && name == methodName
            }.apply {
                val igetIdx = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.IGET_OBJECT &&
                        getReference<FieldReference>()?.let {
                            it.name == "b" && it.definingClass == NAVIGATOR
                        } == true
                }
                val reg = (getInstruction(igetIdx) as TwoRegisterInstruction).registerA
                removeInstruction(igetIdx + 2) // move-result vN
                removeInstruction(igetIdx + 1) // invoke-interface AUTH_INTERFACE->a()Z
                addInstructions(
                    igetIdx + 1,
                    """
                        const/4 v$reg, 0x1
                    """,
                )
            }
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
