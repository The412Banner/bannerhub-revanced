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
// 6.0.1 R8-mangled class letter map
//
// All names below are R8 outputs from the GameHub 6.0.1 base APK (r8-map-id
// 1c1886510d56...). They WILL change on the next minor-version bump; treat
// this block as version config — update here, leave the patch body alone.
//
// To re-derive on a new base APK: decompile (`apktool d --no-res`) and find
// each by structural anchor:
//
//   AUTH_IMPL          : class with three instance fields of the same
//                        StateFlow-impl type AND a constructor accepting
//                        UserDao + AuthTokenDao. (Was `Los0;` in 6.0.0.)
//   AUTH_INTERFACE     : interface with abstract `h()`/`e()`/`d()` returning
//                        a StateFlow type. AUTH_IMPL implements it.
//                        (Was `Lis0;` in 6.0.0.)
//   MUTABLE_FLOW_FACTORY : a static `(Object) → MutableStateFlowImpl` whose
//                        body is `new-instance` + `<init>(p0)V` + `return v0`.
//                        Returned class transitively implements StateFlow.
//                        (Was `Lr8o;->r(Ljava/lang/Object;)Lf3k;` in 6.0.0.)
//   AUTH_TOKEN         : 10-field data class (S,S,S,S,Long,Long,J,Z,J,J)
//                        returned by AUTH_INTERFACE.f(). (Was `Ll4m;`.)
//   GAME_LIB_REPO      : class with `b:AUTH_INTERFACE` field AND constructor
//                        taking GameLibraryDatabase + AUTH_INTERFACE.
//                        (Was `Lxm7;` in 6.0.0.)
//   NAVIGATOR          : class with `b:AUTH_INTERFACE` field AND two methods
//                        whose body matches `iget NAVIGATOR->b:AUTH_INTERFACE`
//                        + `invoke-interface AUTH_INTERFACE->a()Z` + `if-nez`
//                        + `new-instance Lca0;` (Login intent build).
//                        Methods are `i(Lph0;)V` + `r(Lph0;)V` in 6.0.1.
//                        (Was `Lg8e;` in 6.0.0.)
//   NAV_INTERCEPTOR    : class implementing the host's NavigationInterceptor
//                        with `<init>(AUTH_INTERFACE)V` constructor and an
//                        `a(...)Object` method that calls AUTH_INTERFACE.a()
//                        before delegating to the next interceptor in chain.
//                        New gate added in 6.0.1; not present in 6.0.0.
private const val AUTH_IMPL              = "Lrs0;"
private const val AUTH_INTERFACE         = "Lls0;"
private const val MUTABLE_FLOW_FACTORY   = "Lumn;->h(Ljava/lang/Object;)Lt8k;"
private const val AUTH_TOKEN             = "Lfdm;"
private const val GAME_LIB_REPO          = "Lhp7;"
private const val NAVIGATOR              = "Lade;"
private const val NAV_INTERCEPTOR        = "Lar0;"
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
        // Original body: `iget-object p0, p0, AUTH_IMPL->c:Luph;` + return.
        // The Boolean StateFlow it returns is built in the ctor by combining
        // UserDao + AuthTokenDao flows; default initial value is FALSE so
        // every collector sees logged-out at startup.
        //
        // Replace with `MutableStateFlow(Boolean.TRUE)` so every collector
        // (NavHost, the AUTH_INTERFACE.a() default impl, Compose flow
        // observers) sees logged-in immediately. Sentinel log fires on each
        // call so device tests can confirm the patch ran.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == AUTH_IMPL && name == "h"
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->c:Luph;
            removeInstruction(0) // return-object p0
            // .locals is 0 in the original; we only use p0 so no register grow.
            addInstructions(
                0,
                """
                    sget-object p0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                    invoke-static {p0}, $MUTABLE_FLOW_FACTORY
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        // -----------------------------------------------------------------
        // AUTH_IMPL.e() — current-user StateFlow getter.
        //
        // Original body: `iget-object p0, p0, AUTH_IMPL->a:Luph;` + return.
        // Underlying StateFlow emits null when no UserEntity is in Room;
        // the library-list reader then `flatMapLatest`s null to an empty
        // Flow and the imported game never appears.
        //
        // Replace with `MutableStateFlow(FakeUserAccount.get())` so the
        // reader's flatMapLatest hits the userId-keyed query.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == AUTH_IMPL && name == "e"
        }.apply {
            removeInstruction(0) // iget-object p0, p0, $AUTH_IMPL->a:Luph;
            removeInstruction(0) // return-object p0
            // FakeUserAccount.get() does the DebugTrace.write internally, so
            // logcat will show "FakeUserAccount.get() called" each time this
            // patched getter fires. No need to add a sentinel here (would
            // require growing .locals from 0).
            addInstructions(
                0,
                """
                    invoke-static {}, Lapp/revanced/extension/gamehub/login/FakeUserAccount;->get()Ljava/lang/Object;
                    move-result-object p0
                    invoke-static {p0}, $MUTABLE_FLOW_FACTORY
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        // -----------------------------------------------------------------
        // GAME_LIB_REPO.f() — repository userId getter.
        //
        // Returns the user-id string used by Save (xm7.u in 6.0.0 / hp7
        // equivalent in 6.0.1) to filter library queries. Pinning it to
        // "99999" matches the synthetic identity used elsewhere.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == GAME_LIB_REPO && name == "f"
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
        // callers (the various lambdas that read fdm.a or fdm.b directly)
        // see a consistent synthetic identity.
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
        // Both methods have the pattern:
        //   iget-object vN, p0, NAVIGATOR->b:AUTH_INTERFACE
        //   invoke-interface {vN}, AUTH_INTERFACE->a()Z   ← isLoggedIn check
        //   move-result vN
        //   if-nez vN, :skipLogin                          ← skips on logged in
        //   new-instance Lca0;                              ← Login intent build
        //
        // Replace `invoke-interface a()Z` + `move-result` with `const/4 1`
        // so the branch always skips. Belt-and-braces with the StateFlow
        // patches above: even if AUTH_IMPL.h() weren't reached for some
        // reason, this gate still passes.
        // -----------------------------------------------------------------
        for (methodName in listOf("i", "r")) {
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
        // NAV_INTERCEPTOR.a(...) — NavigationInterceptor.intercept.
        //
        // 6.0.1 added a separate NavigationInterceptor class (`getOrder()
        // == 10`). Body:
        //
        //   iget-object p0, p0, NAV_INTERCEPTOR->a:AUTH_INTERFACE
        //   invoke-interface {p0}, AUTH_INTERFACE->a()Z
        //   move-result p0
        //   if-nez p0, :cond_0
        //   new-instance p0, Lcyb;     ← redirect-to-login result
        //   ...
        //   :cond_0  ← passthrough delegation
        //
        // Same shape as NAVIGATOR gates — short-circuit to const 1 so the
        // interceptor always passes through.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == NAV_INTERCEPTOR && name == "a"
        }.apply {
            val igetIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IGET_OBJECT &&
                    getReference<FieldReference>()?.let {
                        it.name == "a" && it.definingClass == NAV_INTERCEPTOR
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
}
