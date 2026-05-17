package app.revanced.patches.gamehub.gpuspoof

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
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
//   3. Library list popup         — Lpzc;->j0(...)List (Lz4e rows)
//   + resolver Lxd3;->l1 short-circuit so the Lell label key resolves.
//
// ANR HISTORY: a first cut of Injection 3 + the l1 hook ANR'd MainActivity
// cold start (2026-05-17) — l1 is called for every Compose string resolve
// and the original maybeResolveCustomLabel did Class.forName +
// getDeclaredField PER CALL, stacked as a second uncached hook on top of
// the vibration patch's. Re-added here for full parity with PC Vibration's
// placement, but BhGpuSpoofMenuRowClick.maybeResolveCustomLabel is now
// O(1): the tdi.a Field is resolved ONCE into a static and every call is a
// single cached Field.get + String compare. Near-zero per-resolve cost, so
// stacking it on vibration's (which ships fine alone) stays within budget.
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
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(gpuSpoofMenuLabelPatch)

    apply {
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

        // ── Injection 3: library list popup (Lpzc;->j0) ────────────────────
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
        val returnIdx = (finalizeIdx until pzcInstructions.size).firstOrNull { i ->
            pzcInstructions[i].opcode == Opcode.RETURN_OBJECT
        }
        require(returnIdx != null && returnIdx > finalizeIdx) {
            "GpuSpoofMenuRowPatch: no return-object after Lx9d;->i() in pzc.j0()"
        }
        val returnReg = (pzcInstructions[returnIdx] as OneRegisterInstruction).registerA
        val pzcCallSmali = if (returnReg <= 15) {
            "invoke-static {v$returnReg}, $CLICK_HANDLER->appendLibraryPopupRow(Ljava/lang/Object;)Ljava/util/List;"
        } else {
            "invoke-static/range {v$returnReg .. v$returnReg}, $CLICK_HANDLER->appendLibraryPopupRow(Ljava/lang/Object;)Ljava/util/List;"
        }
        pzcMethod.addInstructions(
            returnIdx,
            """
                $pzcCallSmali
                move-result-object v$returnReg
            """.trimIndent(),
        )

        // ── Resolver Lxd3;->l1 short-circuit for our Lell label key ────────
        // Label-at-end-of-snippet workaround (works at index 0; see
        // [[revanced-trailing-label-footgun]] and VibrationMenuRowPatch).
        // Distinct label name so this coexists with the vibration patch's
        // own index-0 head block. maybeResolveCustomLabel is O(1) (cached
        // Field) — see the ANR HISTORY note in this file's header.
        val resolverMethod = firstMethod {
            definingClass == "Lxd3;" && name == "l1" &&
                parameterTypes == listOf("Lell;", "Lv83;", "I") &&
                returnType == "Ljava/lang/String;"
        }
        resolverMethod.addInstructions(
            0,
            """
                invoke-static {p0}, $CLICK_HANDLER->maybeResolveCustomLabel(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :bh_gpuspoof_resolve_fallthrough
                return-object v0
                :bh_gpuspoof_resolve_fallthrough
            """.trimIndent(),
        )
    }
}
