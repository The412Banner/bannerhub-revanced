package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
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
// proven safe pattern). Raw String labels only — NO Lxd3;->l1 hook (ANR).
//
//   1. Game-details More Menu     — Lx57;->a(Lf37;Lpo7;Lv83;I)V (Liae rows)
//   2. Library-tile popup (ted.f) — 7-arg, Lscd rows via Lqs2;->H
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
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, rendererManifestPatch)

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

        // ── Per-game capture: at method entry of each builder, hand the
        // menu-data param (p0 — Lf37 GameDetailArgs / Lued, both static
        // methods) to the handler so the injected row's click is scoped to
        // the actual game even from a pre-launch menu (sniffGameIdFromStack
        // finds nothing there). Single no-label invoke, runs once per menu
        // open — not the per-resolve l1 path, so no ANR/footgun. range form
        // because p0 is a high param register in these .locals-heavy methods.
        val captureSmali =
            "invoke-static/range {p0 .. p0}, " +
                "$CLICK_HANDLER->captureGameId(Ljava/lang/Object;)V"
        menuMethod.addInstructions(0, captureSmali)
        libraryMenuMethod.addInstructions(0, captureSmali)
    }
}
