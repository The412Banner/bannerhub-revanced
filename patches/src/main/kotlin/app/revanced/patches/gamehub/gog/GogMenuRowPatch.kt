package app.revanced.patches.gamehub.gog

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.vibration.vibrationMenuRowPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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
// Structural 1:1 clone of GpuSpoofMenuRowPatch (all 3 injections) — the
// device-confirmed menu-injection playbook
// ([[bannerhub-revanced-menu-injection-playbook]]). Same set of menus as the
// Renderer / GPU Spoof / Vibration rows:
//   1. game-details More Menu  (Lx57;->a)        — Liae, raw String label
//   2. library-tile popup      (ted.f, 7-arg)    — Lscd, raw String label
//   3. library-list popup      (Lpzc;->j0)       — Lz4e(Lell,Lnw6,int)
// Injections 1 & 2 use raw String labels (no resolver). Injection 3's Lell
// label is resolved by the SINGLE shared Lxd3;->l1 hook owned by
// vibrationMenuRowPatch (its BhMenuRowClick.maybeResolveCustomLabel now maps
// "string:bh_gog_label" → "GOG") — hence dependsOn(vibrationMenuRowPatch) and
// NO 2nd l1 head-block (a stacked one ANR'd cold start, playbook 2026-05-17).
// Each injection hands row construction to a Java helper via one
// invoke-static — zero register clobbering / verifier surface.
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
    // Injection 3's Lell label key is resolved by the SINGLE shared
    // Lxd3;->l1 head-block that vibrationMenuRowPatch owns
    // (BhMenuRowClick.maybeResolveCustomLabel — now also maps
    // "string:bh_gog_label" → "GOG"). Depending on it guarantees that one
    // resolver hook is present and avoids a 2nd, ANR-causing l1 head-block.
    dependsOn(vibrationMenuRowPatch)

    apply {
        // [feature/banner-tools-menu] Standalone row injections disabled —
        // BannerToolsMenuRowPatch owns the 3 sites on this branch and
        // dispatches into BhGogMenuRowClick (opens GogMainActivity) from the
        // single consolidated dialog. dependsOn(vibrationMenuRowPatch) above
        // is RETAINED so the shared Lxd3;->l1 resolver hook is still applied.
        // [START disabled standalone-row injections]
        if (false) {
            @Suppress("UNREACHABLE_CODE")
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

        // ── Injection 2: library-tile popup (ted.f) ────────────────────────
        // 7-arg method building ≥4 Lscd rows via Lqs2;->H([Object])List.
        // Rebuild the list with our Lscd row appended (raw String label).
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
            "GogMenuRowPatch: Lqs2;->H call not found in ted.f()"
        }
        val moveResultIns = libInstructions[arraysAsListIdx + 1]
        require(moveResultIns.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "GogMenuRowPatch: expected move-result-object after Lqs2;->H"
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
        // Append our Lz4e row right before the post-build return-object (the
        // one following the Lx9d;->i() finalize). The row's Lell label key
        // ("string:bh_gog_label") is resolved by the vibration patch's single
        // shared Lxd3;->l1 hook — we add NO l1 head-block here (ANR cause).
        val pzcMethod = firstMethod {
            parameterTypes == listOf(
                "Laub;", "Z", "Llvc;", "Llvc;", "Lmob;", "Lmob;",
                "Lz9;", "Ljn9;", "Lmvc;", "Lmvc;", "Ljvc;",
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
            "GogMenuRowPatch: no Lx9d;->i() finalize call in pzc.j0()"
        }
        val pzcReturnIdx = (finalizeIdx until pzcInstructions.size).firstOrNull { i ->
            pzcInstructions[i].opcode == Opcode.RETURN_OBJECT
        }
        require(pzcReturnIdx != null && pzcReturnIdx > finalizeIdx) {
            "GogMenuRowPatch: no return-object after Lx9d;->i() in pzc.j0()"
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
        // [END disabled standalone-row injections]
    }
}
