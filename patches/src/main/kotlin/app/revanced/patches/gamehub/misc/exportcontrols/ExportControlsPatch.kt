package app.revanced.patches.gamehub.misc.exportcontrols

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.firstMethodOrNull
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.patches.gamehub.vibration.vibrationMenuRowPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// VJoy Export-to-File / Import-from-File patch (GameHub 6.0.9).
//
// Hijacks the host VJoy "cloud-share-by-code" entry points in the share
// repository and re-routes them to a local SAF file picker:
//
//   - uploadGtheme(dto)                 → read pristine pre-CDN .gtheme bytes,
//                                         SAF-save them (ACTION_CREATE_DOCUMENT)
//   - shareMap(layout)                  → THROW to abort the cloud publish
//   - getByShareCode(code)              → defensive import fallback
//   - <share-name>(gameId, name, …)     → capture the typed profile name
//
// The UX-side relabel ("Share" → "Export to file", "Apply share code" →
// "Import from file") and the composition-time import trigger ride on the
// shared Ly99;->Z string-resolver hook installed by [[vibrationMenuRowPatch]]
// (see BhMenuRowClick.resolveCustomLabel).
//
// 6.0.9 NOTES (vs the 6.0.4 origin in feature/vjoy-export-import):
//   - All four hook sites are anchored by SERVER-STABLE URL fragments and by
//     the call-relationship between the share-name method and the upload
//     method — NOT by R8 letters — so they re-discover their methods on 6.0.9
//     even though the VJoy share repo class was renamed again (6.0.4 Lrqn; →
//     6.0.9 L?un;) and the dex set collapsed.
//   - The share-name method gained params on 6.0.9 (6.0.4 had 4; 6.0.9 has
//     `(J, String, Z, L?;, Continuation)` = 5). We no longer match on an exact
//     param COUNT — we match the device-verified invariant `param[0] = long
//     gameId, param[1] = String typedName` plus "invokes the upload method".
//     The wide `long` occupies p1+p2, so the String name is still at p3.
// =============================================================================

// URL fragments that uniquely identify the repo methods, regardless of how R8
// mangled their names. Stable server-side — XiaoJi has not changed the API
// paths across 6.0.x.
private const val SHARE_URL_FRAGMENT = "vcontroller/shareMap"
private const val APPLY_URL_FRAGMENT = "vcontroller/getMapByShareCode"
private const val UPLOAD_URL_FRAGMENT = "vcontroller/uploadGtheme"

// Java extension class — owns the actual SAF + file-IO logic.
private const val HOOK_HANDLER =
    "Lcom/xj/winemu/exportcontrols/BhVjoyShareHook;"

// Shared label/relabel resolver helper (in the vibration extension package).
private const val RESOLVER_HANDLER =
    "Lcom/xj/winemu/vibration/BhMenuRowClick;"

// 6.0.9 R8 letters for the string-resource resolver host + its argument types.
// Llok; is the Compose Multiplatform StringResource descriptor (6.0.4 Lell;),
// Lgm3; the Composer (6.0.4 Lv83;), Lov3; the Kotlin Continuation (6.0.4
// Lbi3;). Confirmed against the 6.0.9 base: vibrationMenuRowPatch hooks the
// single-key Compose variant Ly99;->Z(Llok;Lgm3;I)Ljava/lang/String;.
private const val RESOLVER_CLASS = "Ly99;"
private const val RESOURCE_DESC = "Llok;"
private const val COMPOSER = "Lgm3;"
private const val CONTINUATION = "Lov3;"

@Suppress("unused")
val exportControlsPatch = bytecodePatch(
    name = "VJoy export/import controls",
    description = "Hijacks the on-screen-controls cloud-share repository so " +
        "the 'Share' button writes the VJoyLayout to a user-picked file via " +
        "SAF (ACTION_CREATE_DOCUMENT) and the 'Apply share code' button reads " +
        "a VJoyLayout from a user-picked file (ACTION_OPEN_DOCUMENT) and feeds " +
        "it through the stock import pipeline. No HTTP traffic — users keep " +
        "their layouts as portable .gtheme files, no cloud account required.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(
        sharedGamehubExtensionPatch,
        exportControlsManifestPatch,
        exportControlsResourcesPatch,
        // Installs the Ly99;->Z resolver hook that both the "Export to file"/
        // "Import from file" relabels AND the composition-time import trigger
        // (BhVjoyShareHook.kickImportFromDialogOpen, via
        // BhMenuRowClick.resolveCustomLabel) ride on. Without this dependency
        // the resolver is absent under selective patching, so the relabels and
        // the import trigger silently no-op.
        vibrationMenuRowPatch,
    )

    apply {
        // -----------------------------------------------------------------
        // Hook 1: shareMap entry point → interceptShare (aborts cloud publish).
        //
        // Anchor by URL literal: the method whose body emits a const-string
        // containing "vcontroller/shareMap". It is a suspend fun, so the JVM
        // signature is (LVJoyLayout;LContinuation;)Ljava/lang/Object; — 2
        // declared params, returns Object. p0 = repo `this`, p1 = layout.
        //
        // interceptShare THROWS (CancellationException) to abort the publish;
        // the host catches it, deletes its temp .gtheme, and never runs the
        // cloud-tab navigation / "Cloud Backup Code" dialog. The local file is
        // already saved by interceptUpload (Hook 3), which fires earlier.
        //
        // /range (not bare invoke-static): the repo method has a high .locals,
        // so p1 maps to a register > v15 that the 4-bit bare form can't encode.
        // -----------------------------------------------------------------
        val shareMethod = firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes.size == 2 &&
                bodyReferencesString(this, SHARE_URL_FRAGMENT)
        }
        shareMethod.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, $HOOK_HANDLER->interceptShare(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :bh_share_fallthrough
                return-object v0
                :bh_share_fallthrough
            """.trimIndent(),
        )

        // -----------------------------------------------------------------
        // Hook 2: getByShareCode → interceptApply (DEFENSIVE import fallback).
        //
        // Normally redundant — the composition-time resolver hook fires SAF
        // before the Import dialog can accept input. Kept because it is
        // anchored by a SERVER-STABLE URL fragment, so it survives R8
        // reshuffles and catches the Confirm tap if a future host build
        // renames the import-dialog-title resource key.
        // -----------------------------------------------------------------
        val applyMethod = firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes.size == 2 &&
                bodyReferencesString(this, APPLY_URL_FRAGMENT)
        }
        applyMethod.addInstructions(
            0,
            """
                invoke-static {}, $HOOK_HANDLER->interceptApply()Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :bh_apply_fallthrough
                return-object v0
                :bh_apply_fallthrough
            """.trimIndent(),
        )

        // -----------------------------------------------------------------
        // Hook 3: uploadGtheme entry point → interceptUpload.
        //
        // Fires BEFORE the layout file is uploaded to Tencent COS. The DTO
        // wraps the local filesystem path of the freshly-written, kotlinx-
        // serialized .gtheme — those bytes are PRISTINE (no UTF-8/CDN
        // mangling). interceptUpload reflects the DTO graph for the path,
        // reads the file, and launches SAF with the clean bytes. We return
        // nothing (no move-result) so the host's CDN upload still runs (the
        // cloud orphan is harmless; interceptShare then aborts the publish).
        //
        // 3 declared params on the upload method (suspend → +Continuation).
        // -----------------------------------------------------------------
        val uploadMethod = firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes.size == 3 &&
                bodyReferencesString(this, UPLOAD_URL_FRAGMENT)
        }
        uploadMethod.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, $HOOK_HANDLER->interceptUpload(Ljava/lang/Object;)Ljava/lang/Object;
            """.trimIndent(),
        )

        // -----------------------------------------------------------------
        // Hook 4: capture the user-typed share name.
        //
        // The Share/Export flow: tap Share → "Name Profile" dialog → user
        // types a name → Confirm → host calls the share-name method, which
        // builds the local .gtheme (containing the layout's INTERNAL name,
        // not the typed name) then uploads via the upload method (Hook 3).
        // By the time interceptUpload fires we only have the file, so capture
        // the typed name here for use as the SAF suggested filename.
        //
        // ANCHOR (two structural constraints, R8-letter-free):
        //   1. Body invokes the upload method located above (the share-name
        //      method is its unique caller with this param shape).
        //   2. Signature: returns Object, param[0] = wide `long` gameId,
        //      param[1] = String typedName. The long occupies p1+p2, so the
        //      String name is at p3. On 6.0.9 the method has extra trailing
        //      params (Z, L?;, Continuation) — they trail the String, so p3
        //      still holds. The OTHER caller of the upload method (a 1-param
        //      SuspendLambda) is excluded by this param shape.
        // -----------------------------------------------------------------
        val uploadMethodRef = "${uploadMethod.definingClass}->${uploadMethod.name}"
        val shareNameMethod = firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes.size >= 2 &&
                parameterTypes[0] == "J" &&
                parameterTypes[1] == "Ljava/lang/String;" &&
                bodyInvokes(this, uploadMethodRef)
        }
        shareNameMethod.addInstructions(
            0,
            """
                invoke-static/range {p3 .. p3}, $HOOK_HANDLER->captureShareName(Ljava/lang/String;)V
            """.trimIndent(),
        )

        // -----------------------------------------------------------------
        // Hooks 5-7: extend the resolver short-circuit to the non-Compose and
        // format-args resource getters on Ly99.
        //
        // The main Ly99;->Z hook (vibrationMenuRowPatch) only catches the
        // single-key Compose stringResource path. But the host fetches its
        // "Share failed: %1$s" toast string (shown by the share coroutine's
        // catch after interceptShare throws) via a SUSPEND getString that
        // BYPASSES Z, so the BhMenuRowClick override of
        // features_vjoy_main_toast_share_failed never fires through Z alone.
        //
        // Three sibling getters on Ly99 take the same Llok; descriptor as Z.
        // We match them by DESCRIPTOR SHAPE (not by their R8 method letters,
        // which we can't verify offline) so the hook survives letter
        // reshuffles:
        //   (Llok;[Ljava/lang/Object;Lgm3;I)Ljava/lang/String;   Compose + args
        //   (Llok;Lov3;)Ljava/lang/Object;                        suspend
        //   (Llok;[Ljava/lang/Object;Lov3;)Ljava/lang/Object;     suspend + args
        //
        // Same head-block as Z, but calling maybeResolveCustomLabelNoKick:
        // these non-composition paths must NOT fire kickImportFromDialogOpen
        // (a stray lookup of the import-dialog-title key would launch SAF
        // behind the user's back). p0 is the Llok; descriptor in all three
        // (static methods); the returned String satisfies both String and
        // Object returns (String is an Object).
        //
        // NON-FATAL: each sibling is matched with firstMethodOrNull. If a
        // descriptor doesn't resolve on this base (e.g. the Continuation letter
        // differs from the verified Lov3;), we skip it (?.) rather than fail
        // the whole patch — worst case the "Share failed" toast isn't
        // suppressed, never a broken build.
        // -----------------------------------------------------------------

        // Compose stringResource + format args.
        firstMethodOrNull {
            definingClass == RESOLVER_CLASS &&
                returnType == "Ljava/lang/String;" &&
                parameterTypes == listOf(RESOURCE_DESC, "[Ljava/lang/Object;", COMPOSER, "I")
        }?.addInstructions(0, noKickResolverBlock("bh_resolve_args"))

        // Suspend getString (no args).
        firstMethodOrNull {
            definingClass == RESOLVER_CLASS &&
                returnType == "Ljava/lang/Object;" &&
                parameterTypes == listOf(RESOURCE_DESC, CONTINUATION)
        }?.addInstructions(0, noKickResolverBlock("bh_resolve_suspend"))

        // Suspend getString + format args (carries the layout-op error toast).
        firstMethodOrNull {
            definingClass == RESOLVER_CLASS &&
                returnType == "Ljava/lang/Object;" &&
                parameterTypes == listOf(RESOURCE_DESC, "[Ljava/lang/Object;", CONTINUATION)
        }?.addInstructions(0, noKickResolverBlock("bh_resolve_suspend_args"))

        // -----------------------------------------------------------------
        // Hook 8: hide the stock "Upload original" checkbox row in the
        // repurposed "Name Profile" dialog.
        //
        // The dialog's sub-sections are each a Compose ComposableLambda. The
        // "Upload original" row composable opens with Compose's skip check:
        //   invoke-virtual {..}, Ljy8;->Y(IZ)Z ; move-result vN ; if-eqz vN, :skip
        // where :skip runs skipToGroupEnd and returns (the codegen-standard
        // "this composable was skipped" path). Forcing the branch to ALWAYS
        // skip hides the row while keeping Compose's group/slot accounting
        // perfectly balanced (identical to a legitimate skip).
        //
        // Rather than rewrite the if-eqz into a goto (GameScrub's approach,
        // which pins R8 letters + registers), we anchor STRUCTURALLY and INSERT
        // `const vN, 0x0` just before the if-eqz, so the skip-check result
        // reads false and the branch is always taken. Anchor: the stable
        // testTag "vjoy_share_upload_check" + the unique IF_EQZ→XOR_INT_LIT8
        // adjacency (the only such pair in the row composable; verified on the
        // 6.0.9 base — the other testTag holder, the checkbox-state composable,
        // has no xor-int/lit8).
        //
        // NON-FATAL: if the anchor moves on a future base we skip the hide and
        // fall back to the "This checkbox does nothing." relabel
        // (BhMenuRowClick) — the build never breaks and the row stays honest.
        // -----------------------------------------------------------------
        val uploadRow = firstMethodOrNull {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
                bodyReferencesString(this, "vjoy_share_upload_check") &&
                hasIfEqzBeforeXor(this)
        }
        if (uploadRow != null) {
            val insns = uploadRow.implementation!!.instructions.toList()
            val ifEqzIdx = (0 until insns.size - 1).firstOrNull { i ->
                insns[i].opcode == Opcode.IF_EQZ &&
                    insns[i + 1].opcode == Opcode.XOR_INT_LIT8
            }
            if (ifEqzIdx != null) {
                val reg = (insns[ifEqzIdx] as OneRegisterInstruction).registerA
                val setZero =
                    if (reg <= 15) "const/4 v$reg, 0x0" else "const/16 v$reg, 0x0"
                uploadRow.addInstructions(ifEqzIdx, setZero)
            }
        }
    }
}

/**
 * True iff [method]'s body contains an IF_EQZ immediately followed by an
 * XOR_INT_LIT8 — the skip-check + boolean-toggle adjacency unique to the
 * "Upload original" row composable (used to disambiguate it from the sibling
 * checkbox-state composable that shares the testTag literal).
 */
private fun hasIfEqzBeforeXor(
    method: com.android.tools.smali.dexlib2.iface.Method,
): Boolean {
    val ins = method.implementation?.instructions?.toList() ?: return false
    for (i in 0 until ins.size - 1) {
        if (ins[i].opcode == Opcode.IF_EQZ && ins[i + 1].opcode == Opcode.XOR_INT_LIT8) {
            return true
        }
    }
    return false
}

/**
 * Head-block that short-circuits a non-Compose resource getter to
 * BhMenuRowClick.maybeResolveCustomLabelNoKick: if it returns a non-null
 * String, early-return it; else fall through to the stock lookup. [label] must
 * be unique per injection site. p0 is the Llok; descriptor (these are static
 * getters); a returned String satisfies both String and Object returns.
 */
private fun noKickResolverBlock(label: String) =
    """
        invoke-static {p0}, $RESOLVER_HANDLER->maybeResolveCustomLabelNoKick(Ljava/lang/Object;)Ljava/lang/String;
        move-result-object v0
        if-eqz v0, :$label
        return-object v0
        :$label
    """.trimIndent()

/**
 * True iff [method]'s body references a const-string whose value contains
 * [fragment]. Anchors methods by the URL literal they emit (stable across R8
 * reshuffles), not by their mangled names.
 */
private fun bodyReferencesString(
    method: com.android.tools.smali.dexlib2.iface.Method,
    fragment: String,
): Boolean {
    val instructions = method.implementation?.instructions ?: return false
    for (ins in instructions) {
        if (ins.opcode != Opcode.CONST_STRING && ins.opcode != Opcode.CONST_STRING_JUMBO) continue
        val ref = (ins as? ReferenceInstruction)?.reference
        val s = (ref as? StringReference)?.string ?: continue
        if (s.contains(fragment)) return true
    }
    return false
}

/**
 * True iff [method]'s body contains an invoke whose resolved method reference
 * (formatted `LClass;->name`) starts with [methodReferencePrefix]. Chains
 * anchors: once one method is robustly located (e.g. by URL fragment), other
 * methods that call it are found by the call-relationship, which is preserved
 * across R8 reshuffles even when every class letter changes. Matches a partial
 * ref (class + name, no signature) to tolerate signature changes in the callee.
 */
private fun bodyInvokes(
    method: com.android.tools.smali.dexlib2.iface.Method,
    methodReferencePrefix: String,
): Boolean {
    val instructions = method.implementation?.instructions ?: return false
    for (ins in instructions) {
        if (ins.opcode != Opcode.INVOKE_VIRTUAL &&
            ins.opcode != Opcode.INVOKE_STATIC &&
            ins.opcode != Opcode.INVOKE_DIRECT &&
            ins.opcode != Opcode.INVOKE_INTERFACE &&
            ins.opcode != Opcode.INVOKE_VIRTUAL_RANGE &&
            ins.opcode != Opcode.INVOKE_STATIC_RANGE &&
            ins.opcode != Opcode.INVOKE_DIRECT_RANGE &&
            ins.opcode != Opcode.INVOKE_INTERFACE_RANGE
        ) continue
        val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        val s = "${ref.definingClass}->${ref.name}"
        if (s.startsWith(methodReferencePrefix)) return true
    }
    return false
}
