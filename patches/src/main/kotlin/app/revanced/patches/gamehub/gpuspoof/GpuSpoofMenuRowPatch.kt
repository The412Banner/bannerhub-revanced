package app.revanced.patches.gamehub.gpuspoof

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.common.menuGameIdCapturePatch
import app.revanced.patches.gamehub.vibration.vibrationMenuRowPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// Injects a "GPU Spoof" row into GameHub 6.0.4's per-game menus. Structural
// clone of VibrationMenuRowPatch (pre7→pre17 trail in [[bannerhub-revanced-
// menu-injection-playbook]]). Each injection hands row construction to a
// Java helper via a single invoke-static — no register clobbering.
//
//   1. Game-details More Menu     — Lx57;->a(Lf37;Lpo7;Lv83;I)V (Liae rows)
//   2. Library-tile popup (ted.f) — 7-arg, Lscd rows via Lqs2;->H
//   3. Library-list popup (Lpzc;->j0) — Lz4e(Lell,Lnw6,int) rows
//
// Injections 1 & 2 use raw String labels (no resolver). Injection 3's row
// uses an Lell label that the Compose runtime resolves via Lxd3;->l1. We do
// NOT add our own l1 head-block: a 2nd one stacked on the vibration patch's
// ANR'd MainActivity cold start (2026-05-17). Instead Injection 3 reuses the
// SINGLE shared l1 hook the vibration patch already injects — its resolver
// (BhMenuRowClick.maybeResolveCustomLabel) now maps "string:bh_gpuspoof_label"
// → "GPU Spoof". Hence dependsOn(vibrationMenuRowPatch) so that one hook is
// present. Zero new l1 head-blocks → no ANR regression.
// =========================================================================

private const val ROW_DATA      = "Liae;"
private const val LIST_BUILDER  = "Lx9d;"
private const val CLICK_HANDLER = "Lcom/xj/winemu/gpuspoof/BhGpuSpoofMenuRowClick;"

@Suppress("unused")
val gpuSpoofMenuRowPatch = bytecodePatch(
    name = "GPU Spoof menu row",
    description = "Adds a 'GPU Spoof' row to GameHub's per-game menus. Tapping " +
        "it launches BhGpuSpoofSettingsActivity scoped to the active game. " +
        "Injects after the existing rows so stock behaviour is preserved.",
) {
    // Pinned to 6.0.4 (skipped on 6.0.7): the 6.0.7 base app ships a native
    // GPU-spoof feature, so BannerHub's redundant. Version-incompatible = skipped, not failed.
    compatibleWith(GAMEHUB_PACKAGE("6.0.4"))
    // vibrationMenuRowPatch owns the SINGLE Lxd3;->l1 resolver head-block
    // (BhMenuRowClick.maybeResolveCustomLabel) that resolves our Injection-3
    // sentinel key — depending on it guarantees that one hook is applied
    // (and avoids a 2nd, ANR-causing l1 head-block).
    dependsOn(menuGameIdCapturePatch, vibrationMenuRowPatch)

    apply {
        // [feature/banner-tools-menu] Standalone row injections disabled —
        // BannerToolsMenuRowPatch owns the 3 sites on this branch and
        // dispatches into the per-feature handlers (incl. this one's
        // BhGpuSpoofSettingsActivity) from a single consolidated dialog.
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
            "GpuSpoofMenuRowPatch: no Lx9d;->add(Object)Z in menu method body"
        }
        menuMethod.addInstructions(
            lastAddIdx + 1,
            "invoke-static {v4}, $CLICK_HANDLER->appendGpuSpoofRowTo(Ljava/lang/Object;)V",
        )

        // ── Injection 2: library-tile popup (ted.f) ────────────────────────
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
            "GpuSpoofMenuRowPatch: Lqs2;->H call not found in ted.f()"
        }
        val moveResultIns = libInstructions[arraysAsListIdx + 1]
        require(moveResultIns.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "GpuSpoofMenuRowPatch: expected move-result-object after Lqs2;->H"
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
        // Mirrors VibrationMenuRowPatch Injection 3 exactly. Append our
        // Lz4e row right before the post-build return-object (the one that
        // follows the Lx9d;->i() finalize). The row's Lell label key is
        // resolved by the vibration patch's single shared Lxd3;->l1 hook —
        // we add NO l1 head-block here (that was the ANR cause).
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
            "GpuSpoofMenuRowPatch: no Lx9d;->i() finalize call in pzc.j0()"
        }
        val pzcReturnIdx = (finalizeIdx until pzcInstructions.size).firstOrNull { i ->
            pzcInstructions[i].opcode == Opcode.RETURN_OBJECT
        }
        require(pzcReturnIdx != null && pzcReturnIdx > finalizeIdx) {
            "GpuSpoofMenuRowPatch: no return-object after Lx9d;->i() in pzc.j0()"
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
