package app.revanced.patches.gamehub.bannertools

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
// "Banner Tools" — single per-game menu row that consolidates the four
// previously-standalone BannerHub rows (PC Vibration, GPU Spoof, Renderer,
// Show Game ID) into one entry. Click opens an AlertDialog whose 4 items
// dispatch into the existing per-feature handlers — so all settings
// activities / dialogs / prefs are reused unchanged.
//
// Structural clone of GpuSpoofMenuRowPatch — same 3 injection sites:
//   1. Game-details More Menu     — Lx57;->a(Lf37;Lpo7;Lv83;I)V (Liae rows)
//   2. Library-tile popup (ted.f) — 7-arg, Lscd rows via Lqs2;->H
//   3. Library-list popup (Lpzc;->j0) — Lz4e(Lell,Lnw6,int) rows
//
// Injection 3 uses an Lell sentinel key "string:bh_banner_tools_label"
// that the SINGLE shared Lxd3;->l1 resolver hook (owned by
// vibrationMenuRowPatch's BhMenuRowClick.maybeResolveCustomLabel) maps to
// the literal "Banner Tools". dependsOn(vibrationMenuRowPatch) guarantees
// that hook is applied; a second l1 head-block would ANR cold start
// (regression observed 2026-05-17), so we never inject our own.
//
// On the feature/banner-tools-menu branch, the 4 standalone *MenuRow
// patches are wrapped in `if (false)` — their row injections do not run,
// only the resolver hook (in vibration) does. This patch then renders the
// sole BannerHub-added row at each of the 3 sites.
// =========================================================================

private const val ROW_DATA      = "Liae;"
private const val LIST_BUILDER  = "Lx9d;"
private const val CLICK_HANDLER =
    "Lcom/xj/winemu/bannertools/BhBannerToolsMenuRowClick;"

@Suppress("unused")
val bannerToolsMenuRowPatch = bytecodePatch(
    name = "Banner Tools menu row",
    description = "Adds a single 'Banner Tools' row to GameHub's per-game " +
        "menus. Tapping it opens a dialog with PC Vibration / GPU Spoof / " +
        "Renderer / Show Game ID entries that dispatch into the existing " +
        "per-feature handlers. Replaces the 4 standalone BannerHub rows " +
        "to keep the per-game menu short.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    // vibrationMenuRowPatch owns the SINGLE Lxd3;->l1 resolver head-block
    // (BhMenuRowClick.maybeResolveCustomLabel) that resolves our Injection-3
    // sentinel key. menuGameIdCapturePatch populates BhMenuGameId so the
    // per-feature handlers' invoke() can read the active gameId.
    // bannerToolsDrawablesPatch ships the 4 vector drawables that the
    // dialog tile row inflates via Resources.getIdentifier().
    dependsOn(menuGameIdCapturePatch, vibrationMenuRowPatch, bannerToolsDrawablesPatch)

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
            "BannerToolsMenuRowPatch: no Lx9d;->add(Object)Z in menu method body"
        }
        menuMethod.addInstructions(
            lastAddIdx + 1,
            "invoke-static {v4}, $CLICK_HANDLER->appendBannerToolsRowTo(Ljava/lang/Object;)V",
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
            "BannerToolsMenuRowPatch: Lqs2;->H call not found in ted.f()"
        }
        val moveResultIns = libInstructions[arraysAsListIdx + 1]
        require(moveResultIns.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "BannerToolsMenuRowPatch: expected move-result-object after Lqs2;->H"
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
            "BannerToolsMenuRowPatch: no Lx9d;->i() finalize call in pzc.j0()"
        }
        val pzcReturnIdx = (finalizeIdx until pzcInstructions.size).firstOrNull { i ->
            pzcInstructions[i].opcode == Opcode.RETURN_OBJECT
        }
        require(pzcReturnIdx != null && pzcReturnIdx > finalizeIdx) {
            "BannerToolsMenuRowPatch: no return-object after Lx9d;->i() in pzc.j0()"
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
    }
}
