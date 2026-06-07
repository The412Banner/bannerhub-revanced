package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.common.menuGameIdCapturePatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.patches.gamehub.vibration.vibrationMenuRowPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// Injects a "Renderer" row into GameHub 6.0.4's per-game menus. Structural
// clone of GpuSpoofMenuRowPatch (which itself cloned VibrationMenuRowPatch).
// Additive to the GPU-Spoof row: both append after the last Lx9d;->add /
// rebuild the ted.f Lscd list, distinct labels — both rows appear (same
// proven safe pattern).
//
//   1. Game-details More Menu     — Lx57;->a(Lf37;Lpo7;Lv83;I)V (Liae rows)
//   2. Library-tile popup (ted.f) — 7-arg, Lscd rows via Lqs2;->H
//   3. Library-list popup (Lpzc;->j0) — Lz4e(Lell,Lnw6,int) rows
//
// Injections 1 & 2 use raw String labels. Injection 3's Lell label is
// resolved by the SINGLE shared Lxd3;->l1 hook the vibration patch injects
// (its BhMenuRowClick.maybeResolveCustomLabel now maps
// "string:bh_renderer_label" → "Renderer"). We add NO l1 head-block here —
// a 2nd one ANR'd cold start (2026-05-17); hence dependsOn(vibrationMenuRowPatch).
// =========================================================================

private const val ROW_DATA      = "Liae;"
private const val LIST_BUILDER  = "Lx9d;"
private const val CLICK_HANDLER = "Lcom/xj/winemu/renderer/BhRendererMenuRowClick;"

@Suppress("unused")
val rendererMenuRowPatch = bytecodePatch(
    name = "Renderer menu row",
    description = "Adds a 'Renderer' row to GameHub's per-game menus. Tapping " +
        "it launches BhRendererSettingsActivity scoped to the active game " +
        "(New Vulkan / Legacy GLES2). Injects after the existing rows so " +
        "stock behaviour and the GPU-Spoof row are preserved.",
) {
    // GATED OUT of 6.0.7: pinned to 6.0.4 so the patcher SKIPS it (version-
    // incompatible, not a SEVERE failure). The Legacy GLES2 path swaps in the
    // 6.0.2 libxserver, whose JNI_OnLoad RegisterNatives needs XServer methods
    // 6.0.7 deleted (setSurfaceFormat/setFlipEnabled) -> SIGABRT at <clinit>
    // (device-confirmed on DOOMBLADE, 2026-06-06). 6.0.7 grew XServer 11->40
    // natives (ReShade FX engine), so the old .so cannot satisfy the contract;
    // not patchable without a source-built GLES2 libxserver. New mode = stock,
    // unaffected. Revive only with a 6.0.7-contract GLES2 libxserver.
    compatibleWith(GAMEHUB_PACKAGE("6.0.4"))
    // vibrationMenuRowPatch owns the SINGLE Lxd3;->l1 resolver head-block
    // that resolves Injection 3's sentinel key — depend on it (no 2nd l1).
    dependsOn(sharedGamehubExtensionPatch, rendererManifestPatch, menuGameIdCapturePatch, vibrationMenuRowPatch)

    apply {
        // [feature/banner-tools-menu] Standalone row injections disabled —
        // BannerToolsMenuRowPatch owns the 3 sites on this branch and
        // dispatches into the per-feature handlers (incl. this one's
        // BhRendererSettingsActivity) from a single consolidated dialog.
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
            "RendererMenuRowPatch: no Lx9d;->add(Object)Z in menu method body"
        }
        menuMethod.addInstructions(
            lastAddIdx + 1,
            "invoke-static {v4}, $CLICK_HANDLER->appendRendererRowTo(Ljava/lang/Object;)V",
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
            "RendererMenuRowPatch: Lqs2;->H call not found in ted.f()"
        }
        val moveResultIns = libInstructions[arraysAsListIdx + 1]
        require(moveResultIns.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "RendererMenuRowPatch: expected move-result-object after Lqs2;->H"
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
        // Mirrors VibrationMenuRowPatch Injection 3. Append our Lz4e row
        // before the post-build return-object (after Lx9d;->i() finalize).
        // The Lell label key is resolved by the vibration patch's single
        // shared l1 hook — NO l1 head-block added here.
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
            "RendererMenuRowPatch: no Lx9d;->i() finalize call in pzc.j0()"
        }
        val pzcReturnIdx = (finalizeIdx until pzcInstructions.size).firstOrNull { i ->
            pzcInstructions[i].opcode == Opcode.RETURN_OBJECT
        }
        require(pzcReturnIdx != null && pzcReturnIdx > finalizeIdx) {
            "RendererMenuRowPatch: no return-object after Lx9d;->i() in pzc.j0()"
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

        // Per-game id is captured once by the shared menuGameIdCapturePatch
        // (dependency) into BhMenuGameId; the click handler reads it.
        } // [END disabled standalone-row injections]
    }
}
