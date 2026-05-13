package app.revanced.patches.gamehub.vibration

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// Injects a 5th row ("PC Vibration Settings") into the per-game library
// popup menu (PC Game Settings / Add to Desktop / Remove from Library /
// Edit Cover / **PC Vibration Settings**).
//
// The menu Composable lives in the obfuscated class Lx57; (smali_classes4)
// method a(Lf37;Lpo7;Lv83;I)V, .locals 81. Lines ~3120-3300 build the row
// list. Each row pattern:
//   sget icon (Lzz4;->X:Lxrl;)        -> Lo05
//   sget label (Lwhl;->X:Lxrl;)       -> resolved via Lxd3.l1 -> String
//   new-instance Lb47; invoke-direct (..)V  -> Lpw6 onClick closure
//   new-instance Liae; invoke-direct (Lo05;String;Lpw6;)V
//   invoke-virtual {v4, v_iae}, Lx9d;->add(Object)Z
//
// We append a 5th row right after the LAST existing add() call.
// Registers v2, v3, v9, v13 are dead between rows (rewritten each
// iteration), so we reuse them — keeps the injection in 4-bit register
// range and avoids invoke-*-range.
//
// The click handler is a freshly-constructed BhMenuRowClick (Java class
// in this patch's extension module that implements Function1 = Lpw6 and
// fires startActivity(BhVibrationSettingsActivity) via ActivityThread
// reflection — no Context capture needed at construction time).
// =========================================================================

private const val ROW_DATA      = "Liae;"
private const val ICON_HOLDER   = "Lzz4;"
private const val ICON_FIELD    = "m"
private const val LIST_BUILDER  = "Lx9d;"
private const val XRL_WRAPPER   = "Lxrl;"
private const val CLICK_HANDLER = "Lcom/xj/winemu/vibration/BhMenuRowClick;"

@Suppress("unused")
val vibrationMenuRowPatch = bytecodePatch(
    name = "PC Vibration Settings menu row",
    description = "Adds a 'PC Vibration Settings' row to the per-game " +
        "library popup menu. Tapping it launches BhVibrationSettingsActivity " +
        "with the active game's id when a WineActivity is on the stack. " +
        "Injects after the existing rows so stock behavior is preserved.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    // The library-tile popup injection (injection 3) needs the
    // bh_pc_vibration_label Compose-resource entry so Lell.<init> can
    // produce a key Lxd3.l1 can resolve to "PC Vibration Settings".
    dependsOn(vibrationMenuLabelPatch)

    apply {
        // Find the menu Composable structurally via firstMethod (returns
        // a MutableMethod ready for addInstructions). Predicate:
        //   - 4 params (Lf37;Lpo7;Lv83;I), returns void
        //   - body constructs an Liae with the canonical 3-arg ctor
        //   - body references the Remove-from-Library label (Lwhl;->S:Lxrl;)
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

        // Find the index right after the LAST invoke-virtual to Lx9d.add(Object)Z.
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
            "VibrationMenuRowPatch: no Lx9d;->add(Object)Z in menu method body"
        }

        // Inject AFTER the last existing add() — index = lastAddIdx + 1.
        //
        // pre7 (first attempt) used 9 smali instructions reusing dead regs
        // v2/v3/v9/v13 in-line. ART verifier rejected because reassigning v9
        // to BhMenuRowClick conflicted with downstream type-flow expectations
        // at the merge point in :goto_35 — the verifier couldn't unify
        // `BhMenuRowClick` with the `Lpw6` type other paths assume.
        //
        // Fix: hand the entire row construction off to a Java helper. The
        // smali injection collapses to a single invoke-static taking the
        // list builder (v4) as its only argument — zero register clobbering,
        // zero new types introduced into x57's verifier flow analysis.
        menuMethod.addInstructions(
            lastAddIdx + 1,
            """
                invoke-static {v4}, $CLICK_HANDLER->appendVibrationRowTo(Ljava/lang/Object;)V
            """.trimIndent(),
        )

        // ─────────────────────────────────────────────────────────────────────
        // Injection 2: library-tile popup (ted.smali method f())
        //
        // Structural anchor:
        //   - 7 params (Lued;Lpw6;Lnw6;ZLt9e;Lv83;I), returns void
        //   - body has 4 invoke-direct {..}, Lscd;-><init>(Ljava/lang/String;Lo05;Ljava/lang/String;Lnw6;)V
        //   - body has invoke-static {..}, Lqs2;->H([Ljava/lang/Object;)Ljava/util/List;
        //
        // The 4 rows get collected into a `List<Lscd>` via Arrays.asList
        // equivalent (Lqs2;->H). We inject right AFTER that call:
        //     move-result-object v?_list
        //     <our injection>
        //         invoke-static {v?_list}, BhMenuRowClick;->appendScdRowToTedList(Object)List;
        //         move-result-object v?_list
        // Result: the immutable 4-row list is replaced with a fresh 5-row
        // ArrayList containing the original rows + ours. Downstream code
        // iterates the list (same shape) — focus tree picks up our row's
        // action ID, render emits our row.
        // ─────────────────────────────────────────────────────────────────────
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

        // Find the index of Lqs2;->H([Ljava/lang/Object;)Ljava/util/List; call;
        // inject right after the move-result-object that captures its return.
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
            "VibrationMenuRowPatch: Lqs2;->H call not found in ted.f()"
        }
        // The next instruction is `move-result-object vN` capturing the List.
        // Inject after that move-result. We need to know which register N.
        val moveResultIns = libInstructions[arraysAsListIdx + 1]
        require(moveResultIns.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "VibrationMenuRowPatch: expected move-result-object after Lqs2;->H"
        }
        // Extract the register number (it's a one-register instruction)
        val listReg =
            (moveResultIns as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
                .registerA
        // For invoke-static with a single 4-bit register arg, listReg must be <= 15;
        // otherwise we must use invoke-static/range with that register.
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

        // ─────────────────────────────────────────────────────────────────────
        // Injection 3: library-tile popup (pzc.smali method j0())
        //
        // The library tile's 3-dot popup is rendered from
        //   Lpzc;->j0(Laub;ZLlvc;Llvc;Lmob;Lmob;Lz9;Ljn9;Lmvc;Lmvc;Ljvc;)Ljava/util/List;
        // which builds rows as Lz4e(Lell label, Lnw6 onClick, int) and
        // collects them into an Lx9d list builder, then finalizes via
        //   invoke-virtual {v0}, Lx9d;->i()Lx9d;
        // and `return-object pN`.
        //
        // We inject right before the return: append our row to the list
        // via a Java helper (appendLibraryPopupRow). Helper constructs the
        // Lz4e via reflection using a Compose-resource label key added by
        // VibrationMenuLabelPatch ("bh_pc_vibration_label").
        // ─────────────────────────────────────────────────────────────────────
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

        // Inject right before the LAST `return-object` in this method.
        // Multiple return paths exist (early-bail branches return p0 with
        // the input game info — irrelevant); we want the post-build path
        // that returns the list built via Lx9d. That return follows the
        // Lx9d;->i() finalization.
        val pzcInstructions = pzcMethod.implementation!!.instructions.toList()
        val finalizeIdx = pzcInstructions.indexOfLast { ins ->
            ins.opcode == Opcode.INVOKE_VIRTUAL &&
                (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                    ?.let { it.definingClass == "Lx9d;" && it.name == "i" } == true
        }
        require(finalizeIdx >= 0) {
            "VibrationMenuRowPatch: no Lx9d;->i() finalize call in pzc.j0()"
        }
        // After Lx9d;->i() there's a move-result-object capturing the list
        // into the same register that gets returned. Find the next return-
        // object after the finalize, inject just before that return.
        val returnIdx = (finalizeIdx until pzcInstructions.size).firstOrNull { i ->
            pzcInstructions[i].opcode == Opcode.RETURN_OBJECT
        }
        require(returnIdx != null && returnIdx > finalizeIdx) {
            "VibrationMenuRowPatch: no return-object after Lx9d;->i() in pzc.j0()"
        }
        val returnIns = pzcInstructions[returnIdx]
        val returnReg =
            (returnIns as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
                .registerA
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

        // ─────────────────────────────────────────────────────────────────────
        // Patch the resolver Lxd3.l1 to short-circuit our sentinel key.
        //
        // pre14 device-tested with the Unsafe-allocated Lell + appended CVR
        // resource entry, but crashed:
        //   IllegalStateException: Resource with ID='string:bh_pc_vibration_label' not found
        //     at fef.k -> aei.a -> xd3.V0 -> ... -> xd3.l1
        //
        // The Compose Multiplatform resource runtime requires a manifest /
        // index registration alongside the .cvr file — just appending to
        // the .cvr isn't enough. Rather than fight the resource system,
        // patch the resolver Lxd3.l1 at its head to detect our key and
        // return our hardcoded string before the normal lookup runs.
        //
        // Smali injection is 3 instructions at index 0:
        //   invoke-static {p0}, BhMenuRowClick.maybeResolveCustomLabel(Lell;)String
        //   move-result-object v0
        //   if-nez v0, :short_circuit_return  (return v0)
        //   ... original code runs unchanged ...
        // ─────────────────────────────────────────────────────────────────────
        val resolverMethod = firstMethod {
            definingClass == "Lxd3;" && name == "l1" &&
                parameterTypes == listOf("Lell;", "Lv83;", "I") &&
                returnType == "Ljava/lang/String;"
        }
        // Avoid addInstructionsWithLabels + ExternalLabel — pre15 hit
        //   PatchException: classDef is null  at InstructionKt.toInstructions
        //   at MethodKt.addInstructionsWithLabels
        // Reason still unclear (patcher bug or class-bind timing). Use the
        // label-at-end-of-snippet workaround at index 0 instead. Per the
        // existing feedback note, this works at index 0 because the snippet-
        // relative offset of the trailing label EQUALS the absolute offset
        // in the destination method when the shift is zero. The label then
        // resolves to the first original instruction. The footgun only
        // applies at non-zero injection indices.
        resolverMethod.addInstructions(
            0,
            """
                invoke-static {p0}, $CLICK_HANDLER->maybeResolveCustomLabel(Ljava/lang/Object;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :bh_resolve_fallthrough
                return-object v0
                :bh_resolve_fallthrough
            """.trimIndent(),
        )

        // ─────────────────────────────────────────────────────────────────────
        // Probe 4: joc.invoke() — diagnostic
        //
        // joc is a `Function0`-implementing synthetic with `invoke()Object;`.
        // It reads Lpjl;->{a, b, c, d, e} (library popup labels) and
        // constructs Lb5d(actionId, label). 4 distinct constructors and ~30
        // return-paths suggest it's used as a multi-variant row builder.
        //
        // We probe by logging at the start of invoke(). On the next device
        // test, opening the library tile popup should print probeJocInvoke
        // calls — telling us (a) whether joc IS the popup's row builder,
        // (b) what actionId values come in (so we know which branch to
        // extend if we add a "PC Vibration" row variant).
        //
        // Probe is one smali line and touches no working registers — pure
        // diagnostic, removable in the next iteration.
        // ─────────────────────────────────────────────────────────────────────
        val jocInvoke = firstMethod {
            definingClass == "Ljoc;" && name == "invoke" &&
                parameterTypes.isEmpty() &&
                returnType == "Ljava/lang/Object;"
        }
        jocInvoke.addInstructions(
            0,
            """
                invoke-static {p0}, $CLICK_HANDLER->probeJocInvoke(Ljava/lang/Object;)V
            """.trimIndent(),
        )
    }
}
