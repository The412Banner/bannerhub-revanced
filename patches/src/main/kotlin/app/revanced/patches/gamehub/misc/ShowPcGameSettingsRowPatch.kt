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
// game-detail More Menu. XiaoJi-native UX safety filtering hides this row for
// game types where direct Wine/DXVK/Box64/VKD3D settings don't apply
// (Steam-launched games, retro games, etc.) — see
// [[bannerhub-revanced-menu-gating]] for the full trace.
//
// User direction: scope the unblanking to PC Game Settings ONLY. Other rows
// (PC Uninstall, Online Update, Instant Settings, Version Switch) keep their
// native gating. Clicking PC Game Settings on a "wrong" game type may no-op
// or show an empty dialog — accepted risk.
//
// Mechanism (unchanged 6.0.4 → 6.0.7): the row's gate is a single
// `if-eqz vN, :cond_X` instruction preceding the row construction in the menu
// method. Removing it lets control fall through unconditionally into the row
// construction code → row always added. The menu was Compose-rewritten in
// 6.0.7 (the method is now a Composable taking a Composer + $changed), but the
// PC-settings block is still guarded by a single boolean if-eqz and is the
// always-present branch, so forcing it is the cleanest Compose case (the
// group is consistently present rather than appearing/disappearing).
//
// Obfuscated-name map (R8 letters reshuffle every minor; the structure holds):
//   Menu method     6.0.4 Lx57;->a(Lf37;Lpo7;Lv83;I)V
//                   6.0.7 Lc37;->a(Lf17;ILev6;Ljh7;Leh3;I)V  (signature is
//                         GLOBALLY UNIQUE — 1 match in the whole apk)
//   Row item ctor   6.0.4 Liae;-><init>(Lo05;String;Lpw6;)V
//                   6.0.7 Ltyc;-><init>(Ln55;String;Lgv6;)V
//   Label wrapper   6.0.4 Lxrl;   6.0.7 Lu3k;
//   PC-settings lbl 6.0.4 Lmil;->U:Lxrl;   6.0.7 Llsj;->c0:Lu3k;
//
// Anchor strategy (no hardcoded smali line):
//   1. Locate the menu Composable by its unique parameter signature, further
//      pinned by the presence of the More-Menu row-item constructor (ROW_DATA)
//      — the same row builder VibrationMenuRowPatch's family keys on.
//   2. Within that method, find the SOLE sget-object that loads the PC Game
//      Settings label (PC_SETTINGS_LABEL_CLASS->PC_SETTINGS_LABEL_FIELD).
//   3. Walk backward up to MAX_BACKWARD_SCAN instructions from that sget.
//      The nearest if-eqz/if-nez is the row's gate. Remove it.
//
// MAX_BACKWARD_SCAN is conservative (actual distance on 6.0.7 is 6
// instructions — the `if-eqz v63, :cond_55` gate to the label sget). 40 is a
// safety margin for minor base bumps that might inject extra instructions.
//
// How to re-derive the PC-settings label field on a future base bump (the
// letters reshuffle every minor; the lookup chain doesn't):
//   - The label key is the Compose string resource "features_game_pc_settings"
//     (assets/composeResources/.../strings.commonMain.cvr). In 6.0.7 it is
//     emitted by the `kqj` resource lambda's `:pswitch_8`, which the
//     packed-switch maps to integer index 0x14.
//   - Find the `<clinit>`/static init that does `const vX, 0x14` →
//     `new-instance Lkqj;` → `invoke-direct ...Lkqj;-><init>(I)V` →
//     `sput-object ...:Lu3k;`. That `L<class>;-><field>:Lu3k;` is the label
//     (6.0.7: Llsj;->c0). Confirm it is sget-object'd inside the menu method.
// =============================================================================

// 6.0.8 verified (~/gh608-apktool-d): label class Llsj;->Lssj; (field c0 stable),
// wrapper Lu3k;->Lb4k;, resource lambda kqj->rqj (rqj(0x14)=features_game_pc_settings,
// wrapped in Lb4k;, sput Lssj;->c0). Menu method Lc37;->La37; sig
// (Le17;ILdv6;Lhh7;Leh3;I)V, row ctor Ltyc;->Lwyc;(Lm55;,String,Lfv6;).
// a37.a sgets Lssj;->c0:Lb4k; exactly once (confirmed).
// 6.0.9 verified (~/gh609-apktool-d): the label model changed — labels are now
// lazy Lkwk; wrappers built from an Lr47; (Function0) provider Lwik; whose
// stored index `a:I` selects the resource. pc_settings = Lwik;(0x15) (the b()
// case; 0x14→0x15 = one string added upstream); b() builds the StringResource
// via Lo4h;-><init>("string:features_game_pc_settings", Set). That Lkwk; is
// cached at Lnkk;->q0:Lkwk; and sget exactly once inside the menu method lc7.a
// (the gate `if-eqz v51, :cond_55` sits ~6 instrs before it). Menu method
// La37;->Llc7; sig (Lpa7;ILr47;Lrq7;Lgm3;I)V, row ctor Lwyc;->Luhd;
// (Lqd5;,String,Lt47;). Wrapper Lb4k;->Lkwk;.
private const val PC_SETTINGS_LABEL_CLASS = "Lnkk;"   // 6.0.8: Lssj;  6.0.7: Llsj;  6.0.4: Lmil;
private const val PC_SETTINGS_LABEL_FIELD = "q0"      // 6.0.8: c0  (Lwik StringResource idx 0x15)
private const val LABEL_WRAPPER = "Lkwk;"             // 6.0.8: Lb4k;  6.0.7: Lu3k;  6.0.4: Lxrl;
private const val ROW_DATA = "Luhd;"                  // 6.0.8: Lwyc;  6.0.7: Ltyc;  6.0.4: Liae;  (More-Menu row item)

private const val MAX_BACKWARD_SCAN = 40

@Suppress("unused")
val showPcGameSettingsRowPatch = bytecodePatch(
    name = "Show PC Game Settings row",
    description = "Forces the 'PC Game Settings' row to appear in the Explorer " +
        "game-detail More Menu for every game type, including Steam-linked games " +
        "where XiaoJi-native logic would normally hide it. Removes the single " +
        "if-eqz gate immediately preceding the row's construction in the menu " +
        "Composable. Other rows keep their native gating.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // Anchor: the More Menu Composable, identified by its globally-unique
        // parameter signature (Lf17;ILev6;Ljh7;Leh3;I)V and further pinned by
        // the presence of the More-Menu row-item constructor (ROW_DATA), which
        // is unique to this method among any signature sig-sharers.
        val menuMethod = firstMethod {
            parameterTypes == listOf("Lpa7;", "I", "Lr47;", "Lrq7;", "Lgm3;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.reference
                            ?.let { it is MethodReference &&
                                    it.definingClass == ROW_DATA &&
                                    it.name == "<init>" &&
                                    it.parameterTypes.toList() == listOf(
                                        "Lqd5;", "Ljava/lang/String;", "Lt47;"
                                    )
                            } == true
                } ?: false)
        }

        val instructions = menuMethod.implementation!!.instructions.toList()

        // Find the sget-object Llsj;->c0:Lu3k; — the PC Game Settings label load.
        val labelSgetIdx = instructions.indexOfFirst { ins ->
            ins.opcode == Opcode.SGET_OBJECT &&
                (ins as? ReferenceInstruction)?.reference
                    ?.let { it is FieldReference &&
                            it.definingClass == PC_SETTINGS_LABEL_CLASS &&
                            it.name == PC_SETTINGS_LABEL_FIELD &&
                            it.type == LABEL_WRAPPER
                    } == true
        }
        require(labelSgetIdx >= 0) {
            "ShowPcGameSettingsRowPatch: $PC_SETTINGS_LABEL_CLASS->$PC_SETTINGS_LABEL_FIELD:$LABEL_WRAPPER " +
                "not found in menu method — letter mapping may have reshuffled. See " +
                "[[bannerhub-revanced-menu-gating]] for the re-derivation recipe."
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
                "re-inspect the menu method around the PC-settings label sget."
        }

        // Drop the gate. Control now falls through unconditionally into the
        // row construction code, so the row is always added.
        menuMethod.removeInstruction(gateIdx)
    }
}
