package app.revanced.patches.gamehub.misc.stability

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val appNullSafetyPatch = bytecodePatch {
    apply {
        // F: App — Koin module list construction iterates modules and calls Collection.add(module).
        // If any module resolves to null (e.g. UmengApp.a() gutted), the add() call crashes.
        // Inject an if-eqz before add() that jumps over it to the existing goto, leaving the
        // original add() instruction untouched to avoid type-flow VerifyErrors.
        firstMethod {
            definingClass == "Lcom/xj/app/App;" &&
                implementation?.instructions?.count { instr ->
                    instr.getReference<MethodReference>()?.name == "add"
                } == 1
        }.apply {
            val addIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    getReference<MethodReference>()?.name == "add"
            }
            val elemReg = getInstruction<FiveRegisterInstruction>(addIndex).registerD

            // Capture the instruction after add() (the loop's goto) before we shift indices.
            val afterAdd = instructions[addIndex + 1]

            addInstructionsWithLabels(
                addIndex,
                "if-eqz v$elemReg, :skip_null_module",
                ExternalLabel("skip_null_module", afterAdd),
            )
        }
    }
}
