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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
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

// 6.0.7: More-Menu row data Liae→Ltyc; list builder (kotlin ListBuilder,
// implements java.util.List) Lx9d→Lj3c.
// 6.0.8 (shared keystone More-Menu map): ROW_DATA Ltyc;->Lwyc;, LIST_BUILDER
// Lj3c;->Lm3c; (a37.a calls Lm3c;->add; Lny2;->C returns Lm3c;). Llp0;->R and
// Lny2;->C class+method UNCHANGED on 608 (patch doesn't check their return types).
// 6.0.9 (shared keystone map): ROW_DATA Lwyc;->Luhd;, LIST_BUILDER Lm3c;->Lbmc;
// (lc7.a calls Lbmc;->add ×12; xdc.b0 finalizes via Lv33;->u(List)Lbmc;). M2
// collector Llp0;->R([Object)List → Lxq0;->a0([Object)ArrayList (name R→a0); M3
// finalize Lny2;->C → Lv33;->u. Patch checks neither's return type.
private const val ROW_DATA      = "Luhd;"
private const val LIST_BUILDER  = "Lbmc;"
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
        // ── Injection 1: game-details More Menu (6.0.7 Lc37;->a) ────────────
        // 6.0.4 Lx57;->a(Lf37;Lpo7;Lv83;I)V → Lc37;->a(Lf17;ILev6;Ljh7;Leh3;I)V
        // (Steam restructure; row ctor Liae(Lo05,String,Lpw6)→Ltyc(Ln55,
        // String,Lgv6); 6.0.4 Lwhl;->S label sget anchor dropped). The Ltyc
        // ctor anchor uniquely picks c37 (param sig is shared by su/v90 too).
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
            "BannerToolsMenuRowPatch: no $LIST_BUILDER;->add(Object)Z in menu method body"
        }
        // The list builder is the INSTANCE register of the add() call
        // (invoke-virtual {vBuilder, vRow}, Lj3c;->add) — 6.0.7 keeps it in
        // v3, not the hardcoded v4 the 6.0.4 patch used. Derive it.
        val builderReg = (instructions[lastAddIdx] as FiveRegisterInstruction).registerC
        val site1Call = if (builderReg <= 15) {
            "invoke-static {v$builderReg}, $CLICK_HANDLER->appendBannerToolsRowTo(Ljava/lang/Object;)V"
        } else {
            "invoke-static/range {v$builderReg .. v$builderReg}, $CLICK_HANDLER->appendBannerToolsRowTo(Ljava/lang/Object;)V"
        }
        menuMethod.addInstructions(lastAddIdx + 1, site1Call)

        // ── Injection 2: library-tile popup (6.0.7 Ly7c;->f) ───────────────
        // 6.0.4 Lted;->f(Lued;..Lv83;I)V → Ly7c;->f(Lz7c;Lgv6;Lev6;ZLfyc;Leh3;I)V
        // (same method name f, 7-arg shape). Rows: Lscd→Lg6c (×5). The 6.0.4
        // asList collector Lqs2;->H([Object)List is GONE — 6.0.7 builds the
        // row list via filled-new-array {…},[Lg6c; then Llp0;->R([Object)
        // ArrayList. We inject right after that R() call (same shape as the
        // old Lqs2;->H site: move-result-object holds the row list).
        val libraryMenuMethod = firstMethod {
            parameterTypes == listOf("Lrqc;", "Lt47;", "Lr47;", "Z", "Lfhd;", "Lgm3;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.count { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let { it.definingClass == "Lxoc;" && it.name == "<init>" } == true
                } ?: 0) >= 4 &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_STATIC &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let {
                                it.definingClass == "Lxq0;" && it.name == "a0" &&
                                    it.parameterTypes.toList() == listOf("[Ljava/lang/Object;")
                            } == true
                } ?: false)
        }

        val libInstructions = libraryMenuMethod.implementation!!.instructions.toList()
        val arraysAsListIdx = libInstructions.indexOfFirst { ins ->
            ins.opcode == Opcode.INVOKE_STATIC &&
                (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                    ?.let {
                        it.definingClass == "Lxq0;" && it.name == "a0" &&
                            it.parameterTypes.toList() == listOf("[Ljava/lang/Object;")
                    } == true
        }
        require(arraysAsListIdx >= 0) {
            "BannerToolsMenuRowPatch: Lxq0;->a0 row-list build not found in qqc.f()"
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

        // ── Injection 3: library-list popup (6.0.7 Levb;->b0) ──────────────
        // 6.0.4 Lpzc;->j0(Laub;..)List → Levb;->b0(Loza;..11 params)List. The
        // (L,Z,9xL)->List signature is globally unique (1 method in the apk),
        // so it pins b0 alone — NO inner instruction anchor (per the capture-
        // patch lesson, an inner MethodReference.returnType compare silently
        // fails). 6.0.4 finalized via virtual Lx9d;->i()Lx9d;; 6.0.7 uses the
        // STATIC Lny2;->C(Ljava/util/List;)Lj3c; (row builder Lx9d→Lj3c),
        // followed by move-result-object + return-object.
        val pzcMethod = firstMethod {
            parameterTypes == listOf(
                "Ljhb;", "Z", "Lobc;", "Lobc;", "Lgj8;", "Lgj8;",
                "Lplb;", "Ltz;", "Lnbc;", "Lobc;"
            ) &&
                returnType == "Ljava/util/List;"
        }

        val pzcInstructions = pzcMethod.implementation!!.instructions.toList()
        val finalizeIdx = pzcInstructions.indexOfLast { ins ->
            ins.opcode == Opcode.INVOKE_STATIC &&
                (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                    ?.let { it.definingClass == "Lv33;" && it.name == "u" } == true
        }
        require(finalizeIdx >= 0) {
            "BannerToolsMenuRowPatch: no Lv33;->u() finalize call in b0()"
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
