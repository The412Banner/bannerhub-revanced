package app.revanced.patches.gamehub.gameid

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.common.menuGameIdCapturePatch
import app.revanced.patches.gamehub.vibration.vibrationMenuRowPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// Injects a "Show Game ID" row into GameHub 6.0.4's per-game menus so users
// can read the gameId GameHub uses for external launcher entries (Beacon /
// ES-DE / Daijishou) without grepping a logcat.
//
// Structural clone of VibrationMenuRowPatch / GpuSpoofMenuRowPatch — three
// injection sites, each hands row construction to a Java helper via a single
// invoke-static. The row click pops a small dialog (no settings Activity).
//
//   1. Game-details More Menu     — Lx57;->a(Lf37;Lpo7;Lv83;I)V  (Liae rows)
//   2. Library-tile popup         — Lted;->f(Lued;Lpw6;Lnw6;ZLt9e;Lv83;I)V
//                                   (Lscd rows via Lqs2;->H)
//   3. Library-list popup         — Lpzc;->j0(Laub;Z…)Ljava/util/List;
//                                   (Lz4e(Lell,Lnw6,int) rows)
//
// Injections 1 & 2 use raw String labels (no resolver). Injection 3's row
// uses an Lell label that the Compose runtime resolves via Lxd3;->l1 — we do
// NOT add a 2nd l1 head-block (2026-05-17 ANR regression); we reuse the
// single shared one that vibrationMenuRowPatch already injects. Its resolver
// table (BhMenuRowClick.maybeResolveCustomLabel) now maps
// "string:bh_gameid_label" → "Show Game ID" (small edit in that file).
// Hence dependsOn(vibrationMenuRowPatch).
// =========================================================================

private const val ROW_DATA      = "Liae;"
private const val LIST_BUILDER  = "Lx9d;"
private const val CLICK_HANDLER = "Lcom/xj/winemu/gameid/BhGameIdDisplayMenuRowClick;"

@Suppress("unused")
val gameIdDisplayMenuRowPatch = bytecodePatch(
    name = "Show Game ID menu row",
    description = "Adds a 'Show Game ID' row to GameHub's per-game menus. " +
        "Tapping it pops a dialog with the gameId (with Copy button) so users " +
        "can configure external launchers (Beacon / ES-DE / Daijishou) " +
        "without grepping a logcat. Injects after the existing rows so stock " +
        "behaviour is preserved.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    // menuGameIdCapturePatch — share the index-0 captureGameId() so the row
    //   reads the active game's id even from a pre-launch More Menu.
    // vibrationMenuRowPatch — owns the SINGLE Lxd3;->l1 resolver head-block
    //   (BhMenuRowClick.maybeResolveCustomLabel) that resolves our
    //   Injection-3 sentinel key. Depending on it guarantees that one hook
    //   is applied and avoids a 2nd ANR-causing l1 head-block.
    // gameIdDisplayMenuLabelPatch — appends our CVR entry alongside the
    //   sentinel-key registration in the shared resolver.
    dependsOn(menuGameIdCapturePatch, vibrationMenuRowPatch, gameIdDisplayMenuLabelPatch)

    apply {
        // [feature/banner-tools-menu] Standalone row injections disabled —
        // BannerToolsMenuRowPatch owns the 3 sites on this branch and
        // dispatches into the per-feature handlers (incl. this one's
        // game-id dialog) from a single consolidated dialog.
        // dependsOn(vibrationMenuRowPatch) above is RETAINED so the shared
        // Lxd3;->l1 resolver hook is still applied.
        // [START disabled standalone-row injections]
        if (false) {
            @Suppress("UNREACHABLE_CODE")
        // ── Injection 1: game-details More Menu (Lx57;->a) ──────────────────
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
            "GameIdDisplayMenuRowPatch: no Lx9d;->add(Object)Z in menu method body"
        }
        menuMethod.addInstructions(
            lastAddIdx + 1,
            "invoke-static {v4}, $CLICK_HANDLER->appendGameIdRowTo(Ljava/lang/Object;)V",
        )

        // ── Injection 2: library-tile popup (Lted;->f) ─────────────────────
        val libraryMenuMethod = firstMethod {
            parameterTypes == listOf("Lued;", "Lpw6;", "Lnw6;", "Z", "Lt9e;", "Lv83;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.count { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let { it.definingClass == "Lscd;" && it.name == "<init>" } == true
                } ?: 0) >= 4 &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_STATIC &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let {
                                it.definingClass == "Lqs2;" && it.name == "H" &&
                                    it.parameterTypes.toList() == listOf("[Ljava/lang/Object;") &&
                                    it.returnType == "Ljava/util/List;"
                            } == true
                } ?: false)
        }

        val libInstructions = libraryMenuMethod.implementation!!.instructions.toList()
        val arraysAsListIdx = libInstructions.indexOfFirst { ins ->
            ins.opcode == Opcode.INVOKE_STATIC &&
                (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                    ?.let {
                        it.definingClass == "Lqs2;" && it.name == "H" &&
                            it.parameterTypes.toList() == listOf("[Ljava/lang/Object;") &&
                            it.returnType == "Ljava/util/List;"
                    } == true
        }
        require(arraysAsListIdx >= 0) {
            "GameIdDisplayMenuRowPatch: Lqs2;->H call not found in ted.f()"
        }
        val moveResultIns = libInstructions[arraysAsListIdx + 1]
        require(moveResultIns.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "GameIdDisplayMenuRowPatch: expected move-result-object after Lqs2;->H"
        }
        val listReg = (moveResultIns as OneRegisterInstruction).registerA
        val callSmali = if (listReg <= 15) {
            "invoke-static {v$listReg}, $CLICK_HANDLER->appendScdRowToTedList(Ljava/lang/Object;)Ljava/util/List;"
        } else {
            "invoke-static/range {v$listReg .. v$listReg}, $CLICK_HANDLER->appendScdRowToTedList(Ljava/lang/Object;)Ljava/util/List;"
        }
        libraryMenuMethod.addInstructions(
            arraysAsListIdx + 2,
            """
                $callSmali
                move-result-object v$listReg
            """.trimIndent(),
        )

        // ── Injection 3: library-list popup (Lpzc;->j0) ───────────────────
        val pzcMethod = firstMethod {
            parameterTypes == listOf(
                "Laub;", "Z", "Llvc;", "Llvc;", "Lmob;", "Lmob;",
                "Lz9;", "Ljn9;", "Lmvc;", "Lmvc;", "Ljvc;"
            ) &&
                returnType == "Ljava/util/List;" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_VIRTUAL &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let {
                                it.definingClass == "Lx9d;" && it.name == "i" &&
                                    it.returnType == "Lx9d;"
                            } == true
                } ?: false)
        }

        val pzcInstructions = pzcMethod.implementation!!.instructions.toList()
        val finalizeIdx = pzcInstructions.indexOfLast { ins ->
            ins.opcode == Opcode.INVOKE_VIRTUAL &&
                (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                    ?.let { it.definingClass == "Lx9d;" && it.name == "i" } == true
        }
        require(finalizeIdx >= 0) {
            "GameIdDisplayMenuRowPatch: no Lx9d;->i() finalize call in pzc.j0()"
        }
        val pzcReturnIdx = (finalizeIdx until pzcInstructions.size).firstOrNull { i ->
            pzcInstructions[i].opcode == Opcode.RETURN_OBJECT
        }
        require(pzcReturnIdx != null && pzcReturnIdx > finalizeIdx) {
            "GameIdDisplayMenuRowPatch: no return-object after Lx9d;->i() in pzc.j0()"
        }
        val pzcReturnReg =
            (pzcInstructions[pzcReturnIdx] as OneRegisterInstruction).registerA
        val pzcCallSmali = if (pzcReturnReg <= 15) {
            "invoke-static {v$pzcReturnReg}, $CLICK_HANDLER->appendLibraryPopupRow(Ljava/lang/Object;)Ljava/util/List;"
        } else {
            "invoke-static/range {v$pzcReturnReg .. v$pzcReturnReg}, $CLICK_HANDLER->appendLibraryPopupRow(Ljava/lang/Object;)Ljava/util/List;"
        }
        pzcMethod.addInstructions(
            pzcReturnIdx,
            """
                $pzcCallSmali
                move-result-object v$pzcReturnReg
            """.trimIndent(),
        )
        } // [END disabled standalone-row injections]
    }
}
