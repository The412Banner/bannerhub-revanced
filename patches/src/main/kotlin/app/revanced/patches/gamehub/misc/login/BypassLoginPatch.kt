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
        // g8e.i(rh0) and g8e.r(rh0) both guard Login navigation with the pattern:
        //   iget-object vN, p0, Lg8e;->b:Lis0;
        //   invoke-interface {vN}, Lis0;->a()Z   ← isLoggedIn check
        //   move-result vN
        //   if-nez vN, :skipLogin                ← only skips if logged in
        //   new-instance Lga0;                   ← builds Login navigation intent
        //
        // Replace invoke-interface + move-result with const/4 vN, 0x1 so the branch
        // always skips to :skipLogin.
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
