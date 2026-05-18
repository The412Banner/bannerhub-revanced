package app.revanced.patches.gamehub.gpuspoof

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.common.menuGameIdCapturePatch
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
//
// Both use raw String labels, so NO Lxd3;->l1 resolver hook is needed. The
// vibration patch's l1 hook runs reflection on the main thread for every
// Compose string resolve; a second copy here ANR'd MainActivity cold start
// on slow devices (2026-05-17). The library-list popup (Lpzc;->j0/Lz4e)
// path — the only one that needs l1 — is therefore deliberately omitted.
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
    dependsOn(menuGameIdCapturePatch)

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

        // NOTE: a third injection (library-list popup Lpzc;->j0 with an
        // Lz4e/Lell-typed label) plus an Lxd3;->l1 resolver short-circuit
        // were intentionally REMOVED. The l1 hook ran reflection on the
        // main thread for every Compose string resolve at startup; stacked
        // on top of the vibration patch's identical l1 hook it tipped cold
        // start past the ANR threshold on slow devices (verified: ANR on
        // com.xiaoji.egggame.MainActivity, 2026-05-17). Injections 1 (More
        // Menu) and 2 (tile popup) use raw String labels — no l1, zero
        // startup cost — and already cover the per-game GPU-settings entry
        // point (where the Crysis 2 fix is reached). The library-list popup
        // row is dropped as acceptable scope.
    }
}
