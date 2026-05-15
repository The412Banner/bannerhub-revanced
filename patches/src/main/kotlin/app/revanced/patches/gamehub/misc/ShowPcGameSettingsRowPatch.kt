package app.revanced.patches.gamehub.misc

import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =============================================================================
// Forces "PC Game Settings" to always appear in the Explorer view's
// game-detail More Menu (Lx57;->a). XiaoJi-native UX safety filtering hides
// this row for game types where direct Wine/DXVK/Box64/VKD3D settings don't
// apply (Steam-launched games, retro games, etc.) — see
// [[bannerhub-revanced-menu-gating]] for the full trace.
//
// User direction: scope the unblanking to PC Game Settings ONLY. Other rows
// (PC Uninstall, Online Update, Instant Settings, Version Switch) keep their
// native gating. Clicking PC Game Settings on a "wrong" game type may no-op
// or show an empty dialog — accepted risk.
//
// Mechanism: the row's gate is a single `if-eqz vN, :cond_X` instruction
// preceding the row construction in Lx57;->a. Removing it lets control fall
// through unconditionally into the row construction code → row always added.
//
// Anchor strategy (no hardcoded smali line):
//   1. Locate the menu Composable via the same structural anchor used by
//      [[VibrationMenuRowPatch]]: method on a public final class with the
//      signature `a(Lf37;Lpo7;Lv83;I)V`, body constructs Liae rows AND
//      references Lwhl;->S:Lxrl; (Remove from Library label).
//   2. Within that method, find the SOLE sget-object that loads
//      Lmil;->U:Lxrl; — this is the PC Game Settings label. Confirmed by
//      tracing Lmil;->U -> Lggl(15) -> packed-switch :pswitch_d ->
//      const-string "string:features_game_pc_settings" in GameHub 6.0.4.
//   3. Walk backward up to MAX_BACKWARD_SCAN instructions from that sget.
//      The nearest if-eqz/if-nez is the row's gate. Remove it.
//
// MAX_BACKWARD_SCAN is conservative (the actual distance on 6.0.4 is 17
// instructions — line 2421 gate to line 2438 sget). 40 is a safety margin
// for minor base bumps that might inject extra instructions.
//
// How to re-derive Lmil;->U on a future base bump (the letters reshuffle
// every minor; the lookup chain doesn't):
//   - Grep CVR resources for the base64 of "PC Game Settings" or the key
//     "features_game_pc_settings" to confirm the value.
//   - Find the packed-switch case that const-strings that key. Note its
//     :pswitch_X label and its integer position in the packed-switch_data_0
//     block (the data block lists labels in REVERSE — value N maps to entry
//     [last_index - N]).
//   - The integer N is the constructor argument to the Lggl-class lambda;
//     find the `sput-object` that initializes the `Lmil;-><letter>:Lxrl;`
//     field wrapping `new Lggl(N)`. The <letter> is the field name (e.g. U).
// =============================================================================

private const val LMIL = "Lmil;"
private const val PC_SETTINGS_LABEL_FIELD = "U"
private const val LXRL = "Lxrl;"
private const val ROW_DATA = "Liae;"
private const val REMOVE_LIBRARY_LABEL = "Lwhl;->S:Lxrl;"

private const val MAX_BACKWARD_SCAN = 40

@Suppress("unused")
val showPcGameSettingsRowPatch = bytecodePatch(
    name = "Show PC Game Settings row",
    description = "Forces the 'PC Game Settings' row to appear in the Explorer " +
        "game-detail More Menu for every game type, including Steam-linked games " +
        "where XiaoJi-native logic would normally hide it. Removes the single " +
        "if-eqz gate immediately preceding the row's construction in Lx57;->a. " +
        "Other rows keep their native gating.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // Anchor: the More Menu Composable. Reuses the structural predicate
        // from VibrationMenuRowPatch — public method (Lf37;Lpo7;Lv83;I)V whose
        // body constructs Liae rows and references the Remove-from-Library
        // label. This identifies Lx57;->a uniquely on 6.0.4 even after R8
        // reshuffles the class letter.
        val menuMethod = firstMethod {
            parameterTypes == listOf("Lf37;", "Lpo7;", "Lv83;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.reference
                            ?.let { it is MethodReference &&
                                    it.definingClass == ROW_DATA &&
                                    it.name == "<init>" &&
                                    it.parameterTypes.toList() == listOf(
                                        "Lo05;", "Ljava/lang/String;", "Lpw6;"
                                    )
                            } == true
                } ?: false) &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.SGET_OBJECT &&
                        (ins as? ReferenceInstruction)?.reference?.toString()
                            ?.contains(REMOVE_LIBRARY_LABEL) == true
                } ?: false)
        }

        val instructions = menuMethod.implementation!!.instructions.toList()

        // Find the sget-object Lmil;->U:Lxrl; — the PC Game Settings label load.
        val labelSgetIdx = instructions.indexOfFirst { ins ->
            ins.opcode == Opcode.SGET_OBJECT &&
                (ins as? ReferenceInstruction)?.reference
                    ?.let { it is FieldReference &&
                            it.definingClass == LMIL &&
                            it.name == PC_SETTINGS_LABEL_FIELD &&
                            it.type == LXRL
                    } == true
        }
        require(labelSgetIdx >= 0) {
            "ShowPcGameSettingsRowPatch: Lmil;->U:Lxrl; not found in menu method " +
                "— letter mapping may have reshuffled. See [[bannerhub-revanced-menu-gating]] " +
                "for the re-derivation recipe."
        }

        // Scan backward for the nearest if-eqz/if-nez — that's the row's gate.
        val scanLowerBound = (labelSgetIdx - MAX_BACKWARD_SCAN).coerceAtLeast(0)
        val gateIdx = (labelSgetIdx - 1 downTo scanLowerBound).firstOrNull { i ->
            val opcode = instructions[i].opcode
            opcode == Opcode.IF_EQZ || opcode == Opcode.IF_NEZ
        }
        require(gateIdx != null) {
            "ShowPcGameSettingsRowPatch: no if-eqz/if-nez found within " +
                "$MAX_BACKWARD_SCAN instructions before the PC Game Settings " +
                "label load. The menu may have been restructured upstream — " +
                "re-inspect Lx57;->a around the Lmil;->U:Lxrl; sget."
        }

        // Drop the gate. Control now falls through unconditionally into the
        // row construction code, so the row is always added.
        menuMethod.removeInstruction(gateIdx)
    }
}
