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

// Navigator class that holds the is0 (auth session) reference and gates Login routing.
private const val G8E_CLASS = "Lg8e;"

@Suppress("unused")
val bypassLoginPatch = bytecodePatch(
    name = "Bypass login",
    description = "Bypasses the login requirement by short-circuiting the auth-session gate in the navigator.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // os0 is the real DB-backed is0 (auth session) implementation. Its h()
        // returns the StateFlow<Boolean> for "is logged in", initially FALSE
        // and updated by combining UserDao + AuthTokenDao flows. Every collector
        // (Compose NavHost in z5o, ah7 listener, xv0 analytics) sees that FALSE
        // and the navigator picks Login as the initial route.
        //
        // Replace h() to return a fresh MutableStateFlow(Boolean.TRUE) — same
        // pattern ah.<init> uses for its bypass field. f3k implements d3k so the
        // return type matches.
        firstMethod {
            definingClass == "Los0;" && name == "h"
        }.apply {
            removeInstruction(0) // iget-object p0, p0, Los0;->c:Likh;
            removeInstruction(0) // return-object p0
            addInstructions(
                0,
                """
                    sget-object p0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                    invoke-static {p0}, Lr8o;->r(Ljava/lang/Object;)Lf3k;
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        // xm7 is GameLibraryRepository. xm7.f() reads is0.f() (the current
        // auth token's wrapped user-id string) and returns null when no auth
        // token is in the Room DB. The game-import use case q1d.a() calls
        // xm7.u(GameInfo, LaunchMethod, …), which fail-fasts to Boolean.FALSE
        // the moment xm7.f() is null — surfacing as the "Save failed" toast.
        // Hardcode a non-null fake UID so the save proceeds to the DB write.
        firstMethod {
            definingClass == "Lxm7;" && name == "f"
        }.returnEarly("99999")

        // is0.f() is the interface default method that returns the current
        // auth token wrapper (Ll4m;). It reads from the StateFlow built off
        // AuthTokenDao.observeCurrent() — which emits null when the table is
        // empty. xm7.f() is one consumer (covered above) but several others
        // call is0.f() directly (lvd reads l4m.b, aae uses it as a lambda
        // arg, fh2/dt0/sak/w79/kpl/dlk/npl/fh2 likewise). Some library list
        // refresh signals key off the same Flow — until is0.f() returns
        // something non-null AND consistent with xm7.f()="99999", the UI
        // can read stale/empty state even though the DB row was inserted.
        //
        // Replace is0.f() body with a call to FakeAuthToken.get() — which
        // reflectively constructs a single l4m{a="99999", b=""} and caches
        // it. Now every is0.f() consumer sees the same synthetic identity.
        firstMethod {
            definingClass == "Lis0;" && name == "f"
        }.apply {
            // Original body: 6 instructions (invoke-interface d(), move-result-object,
            // invoke-interface getValue(), move-result-object, check-cast, return-object).
            repeat(6) { removeInstruction(0) }
            addInstructions(
                0,
                """
                    invoke-static {}, Lapp/revanced/extension/gamehub/login/FakeAuthToken;->get()Ljava/lang/Object;
                    move-result-object p0
                    check-cast p0, Ll4m;
                    return-object p0
                """,
            )
        }

        // Defence in depth: g8e.i(rh0) and g8e.r(rh0) both guard Login
        // navigation with the pattern:
        //   iget-object vN, p0, Lg8e;->b:Lis0;
        //   invoke-interface {vN}, Lis0;->a()Z   ← isLoggedIn check
        //   move-result vN
        //   if-nez vN, :skipLogin                ← only skips if logged in
        //   new-instance Lga0;                   ← builds Login navigation intent
        //
        // Replace invoke-interface + move-result with const/4 vN, 0x1 so the
        // branch always skips. With h() patched above this is redundant for
        // os0, but covers any other is0 impl that bypasses h().
        for (methodName in listOf("i", "r")) {
            firstMethod {
                definingClass == G8E_CLASS && name == methodName
            }.apply {
                val igetIdx = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.IGET_OBJECT &&
                        getReference<FieldReference>()?.let {
                            it.name == "b" && it.definingClass == G8E_CLASS
                        } == true
                }
                val reg = (getInstruction(igetIdx) as TwoRegisterInstruction).registerA
                removeInstruction(igetIdx + 2) // move-result vN
                removeInstruction(igetIdx + 1) // invoke-interface Lis0;->a()Z
                addInstructions(igetIdx + 1, "const/4 v$reg, 0x1")
            }
        }
    }
}
