package app.revanced.patches.gamehub.gog

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// Injects a "GOG" row into GameHub 6.0.4's game-details "More Menu"
// (Lx57;->a(Lf37;Lpo7;Lv83;I)V). Tapping it opens GogMainActivity (the GOG
// login / owned-library hub).
//
// WHY a menu row (not the seeded card): the seeded library card only renders
// in the HANDHELD library surface; explore (portrait) mode is a separate
// library surface that never queries our local sentinel row (device +
// logcat confirmed — GOG_LIBRARY_TAB_DESIGN §32–§32b). Making it appear
// there = the high-risk dual-enum/Compose-grid surgery the design doc flags.
// The per-game "More Menu" exists in BOTH modes, so a row there is a
// mode-independent entry point that sidesteps the library-surface problem.
// The seeded card is kept (works in handheld, harmless) as a second entry.
//
// Structural clone of GpuSpoofMenuRowPatch Injection 1 — the device-confirmed
// menu-injection playbook ([[bannerhub-revanced-menu-injection-playbook]]).
// Only Menu A is targeted: it uses a raw String label (no Compose resolver,
// no Unsafe-built Lell, no shared l1 head-block) so there is no dependency
// on the vibration resolver and zero ANR/verifier surface — one
// invoke-static hands row construction to the Java helper.
// =========================================================================

private const val ROW_DATA      = "Liae;"
private const val LIST_BUILDER  = "Lx9d;"
private const val CLICK_HANDLER = "Lcom/xj/winemu/gog/BhGogMenuRowClick;"

@Suppress("unused")
val gogMenuRowPatch = bytecodePatch(
    name = "GOG menu row",
    description = "Adds a 'GOG' row to GameHub's game-details More Menu. " +
        "Tapping it opens the GOG login / library hub (GogMainActivity). " +
        "Mode-independent entry point (works in handheld + explore); the " +
        "seeded library card only covers handheld. Injects after the " +
        "existing rows so stock behaviour is preserved.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // ── game-details More Menu (Lx57;->a) ──────────────────────────────
        // Same structural fingerprint as GpuSpoof/Vibration Injection 1:
        // (Lf37;Lpo7;Lv83;I)V whose body builds an Liae(Lo05,String,Lpw6)
        // row and reads the Lwhl;->S:Lxrl; label singleton.
        val menuMethod = firstMethod {
            parameterTypes == listOf("Lf37;", "Lpo7;", "Lv83;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.reference
                            ?.let {
                                it is MethodReference &&
                                    it.definingClass == ROW_DATA &&
                                    it.name == "<init>" &&
                                    it.parameterTypes.toList() == listOf(
                                        "Lo05;", "Ljava/lang/String;", "Lpw6;",
                                    )
                            } == true
                } ?: false) &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.SGET_OBJECT &&
                        (ins as? ReferenceInstruction)?.reference?.toString()
                            ?.contains("Lwhl;->S:Lxrl;") == true
                } ?: false)
        }

        val instructions = menuMethod.implementation!!.instructions.toList()
        val lastAddIdx = instructions.indexOfLast { ins ->
            ins.opcode == Opcode.INVOKE_VIRTUAL &&
                (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                    ?.let {
                        it.definingClass == LIST_BUILDER &&
                            it.name == "add" &&
                            it.parameterTypes.toList() == listOf("Ljava/lang/Object;") &&
                            it.returnType == "Z"
                    } == true
        }
        require(lastAddIdx >= 0) {
            "GogMenuRowPatch: no Lx9d;->add(Object)Z in menu method body"
        }
        menuMethod.addInstructions(
            lastAddIdx + 1,
            "invoke-static {v4}, $CLICK_HANDLER->appendGogRowTo(Ljava/lang/Object;)V",
        )
    }
}
