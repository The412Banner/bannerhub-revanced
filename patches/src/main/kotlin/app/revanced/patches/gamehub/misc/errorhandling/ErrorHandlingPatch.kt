package app.revanced.patches.gamehub.misc.errorhandling

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode

internal val errorHandlingPatch = bytecodePatch {
    apply {
        // NetErrorHandler$DefaultImpls — insert return-void before goto/16 so error callbacks
        // are silenced. The goto jumps to a logging/dialog path; skipping it avoids error popups.
        firstMethod {
            definingClass == $$"Lcom/drake/net/interfaces/NetErrorHandler$DefaultImpls;" &&
                implementation?.instructions?.any { it.opcode == Opcode.GOTO_16 } == true
        }.apply {
            val gotoIndex = indexOfFirstInstructionOrThrow { opcode == Opcode.GOTO_16 }
            addInstruction(gotoIndex, "return-void")
        }

        // TipUtils.c(String) — suppress tip/toast dialogs entirely.
        firstMethod { definingClass == "Lcom/drake/net/utils/TipUtils;" && name == "c" }.returnEarly()
    }
}
