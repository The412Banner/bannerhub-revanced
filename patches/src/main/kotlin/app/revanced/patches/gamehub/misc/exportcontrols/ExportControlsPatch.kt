package app.revanced.patches.gamehub.misc.exportcontrols

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.patches.gamehub.vibration.vibrationMenuRowPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// VJoy Export-to-File / Import-from-File patch.
//
// Hijacks the two host VJoy "cloud-share-by-code" entry points in the share
// repository and re-routes them to a local SAF file picker:
//
//   - shareMap(layout)                  → write VJoyLayout JSON to picked URI
//   - getByShareCode(code)              → read VJoyLayout JSON from picked URI
//
// The UX-side relabel ("Share" → "Export to file", "Apply share code" →
// "Import from file") is handled by [[ExportControlsResourcesPatch]] +
// the shared Lxd3;->l1 resolver (injected by VibrationMenuRowPatch).
//
// =============================================================================

// URL fragments that uniquely identify the two repo methods, regardless of
// how R8 mangled their names. These come from the master-map endpoint list
// (§26.4) and are stable server-side — XiaoJi has not changed the API paths
// across 6.0.x.
private const val SHARE_URL_FRAGMENT = "vcontroller/shareMap"
private const val APPLY_URL_FRAGMENT = "vcontroller/getMapByShareCode"
private const val UPLOAD_URL_FRAGMENT = "vcontroller/uploadGtheme"

// Java extension class — owns the actual SAF + file-IO logic.
private const val HOOK_HANDLER =
    "Lcom/xj/winemu/exportcontrols/BhVjoyShareHook;"

@Suppress("unused")
val exportControlsPatch = bytecodePatch(
    name = "VJoy export/import controls",
    description = "Hijacks the on-screen-controls cloud-share repository so " +
        "the 'Share' button writes the VJoyLayout JSON to a user-picked file " +
        "via SAF (ACTION_CREATE_DOCUMENT) and the 'Apply share code' button " +
        "reads a VJoyLayout JSON from a user-picked file (ACTION_OPEN_DOCUMENT) " +
        "and feeds it through the stock import pipeline. No HTTP traffic — " +
        "users keep their layouts as portable .icp files, no cloud account " +
        "required.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(
        sharedGamehubExtensionPatch,
        exportControlsManifestPatch,
        exportControlsResourcesPatch,
        // Installs the Lxd3;->l1 resolver hook that both the "Export to file"/
        // "Import from file" relabels AND the composition-time import trigger
        // (BhVjoyShareHook.kickImportFromDialogOpen, via
        // BhMenuRowClick.maybeResolveCustomLabel) ride on. Without this
        // dependency the resolver is absent under selective patching, so the
        // relabels and the import trigger silently no-op.
        vibrationMenuRowPatch,
    )

    apply {
        // -----------------------------------------------------------------
        // Hook 1: shareMap entry point.
        //
        // Anchor by URL literal: find the method whose body contains a
        // const-string with "vcontroller/shareMap". That method is the
        // shareMap suspend impl; its signature (post-Kotlin compile) is
        //   shareMap(LVJoyLayout;Lkotlin/coroutines/Continuation;)
        //     Ljava/lang/Object;
        //
        // We prepend an invoke-static to BhVjoyShareHook.interceptShare; if
        // it returns a non-null result we early-return that result, else
        // fall through to the stock HTTP path.
        //
        // Note: p0 = `this` (repo), p1 = layout, p2 = continuation.
        //
        // Param-register choice for the hook: we want to pass the layout
        // object. In a suspend method compiled to the JVM signature above,
        // p1 IS the layout (as Object reference); we pass it directly.
        // -----------------------------------------------------------------
        // NOTE on the second-param check: in 6.0.4 R8 renames
        //   kotlin.coroutines.Continuation -> Lci3;
        // so the un-mangled FQN literal does NOT appear as a parameter type
        // anywhere in the dex. (Confirmed via baksmali of Lrqn;->i where the
        // share method's signature reads (Lsrn;Lci3;)Ljava/lang/Object;.)
        // The URL fragment is unique enough on its own; we keep param count
        // as a sanity check but drop the type-name match.
        val shareMethod = firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes.size == 2 &&
                bodyReferencesString(this, SHARE_URL_FRAGMENT)
        }

        // Use invoke-static/range NOT bare invoke-static. Method `i` has
        // `.registers 20` (per baksmali of pre1/pre2 artefacts), which means
        // p0/p1/p2 map to v17/v18/v19. Bare invoke-static encodes registers
        // in 4 bits — anything > v15 silently fails parsing and the patcher
        // drops the instruction (verified empirically pre2 → "move-result-
        // object v0" left dangling at method head with no preceding invoke,
        // and the hook never fired).
        //
        // /range encodes registers in 8 bits and works at any register index.
        // The {p1 .. p1} syntax names a single-register range; smali resolves
        // to the actual high register at assembly time.
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
        // Hook 2: getByShareCode entry point — DEFENSIVE FALLBACK.
        //
        // As of pre22 this hook is functionally redundant in normal
        // operation: the composition-time resolver hook in
        // BhMenuRowClick.maybeResolveCustomLabel fires SAF BEFORE the
        // Import dialog can accept user input, so the host's
        // getByShareCode coroutine never gets called from the import
        // flow in practice. We keep this hook in place because:
        //
        //   1. If a future GameHub version renames the resource key
        //      `features_vjoy_dialog_import_share_code_title`, the
        //      composition-time skip silently stops working. This hook
        //      catches the user's Confirm tap as a fallback path.
        //   2. It's anchored by a SERVER-STABLE URL fragment
        //      (`vcontroller/getMapByShareCode`), so it will keep
        //      matching even across R8 reshuffles. Cost: ~5 smali
        //      instructions per build.
        //
        // Anchor by URL literal — same strategy as Hook 1. Real
        // signature on 6.0.4: `Lrqn;->d(Lwpn;Lci3;)Ljava/lang/Object;`.
        // p1 is a DTO (not String) and p2 is the renamed Continuation,
        // so we discriminate purely by const-string body reference.
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
        // Hook 3: uploadGtheme entry point (Lrqn;->j).
        //
        // This fires BEFORE the layout file is uploaded to Tencent COS.
        // The DTO at this point (Lasn) wraps an Lvnm that contains the
        // local filesystem path of the freshly-written, kotlinx-serialized
        // .gtheme on disk. Those bytes are PRISTINE — no UTF-8 mangling.
        //
        // We pass p1 (the Lasn DTO) to BhVjoyShareHook.interceptUpload,
        // which reflects into the DTO graph to find the path, reads the
        // file, and launches SAF with the clean bytes. We return null
        // (fallthrough) so the host's upload-to-CDN proceeds normally —
        // the resulting cloud-orphan is harmless and the user gets a
        // clean local file regardless of layout Unicode content.
        //
        // Anchored by URL literal "vcontroller/uploadGtheme" — server-
        // stable, identical strategy to Hooks 1 & 2.
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
        // Hook 4: capture the user-typed share name from `Lnrn;->h`.
        //
        // The Share/Export flow: tap Share → "Name Profile" dialog →
        // user types a name → tap Confirm → host calls
        //   Lnrn;->h(gameId, typedName, callback, continuation)
        // which builds the local .gtheme file (containing the layout's
        // INTERNAL name, NOT the typed share name) and then uploads via
        // Lrqn;->j. By the time our interceptUpload fires we only have
        // the local file — the typed name isn't in it.
        //
        // Hook h at its head, pass the String param to
        // BhVjoyShareHook.captureShareName so interceptUpload can use it
        // as the SAF suggested filename.
        //
        // ANCHOR — two structural constraints chained so the match is
        // unique even after R8 reshuffles:
        //
        //   1. Body invokes the upload method we located above
        //      (`Lrqn;->j` — itself anchored by the server-stable URL
        //      "vcontroller/uploadGtheme"). h is the ONLY caller of j in
        //      the dex (verified by grep on the smali dump). Even if R8
        //      renames everything in sight, the body-invoke relationship
        //      between h and j is preserved because that's a real method
        //      call.
        //   2. Signature: returns Object, 4 params, parameterTypes[1] is
        //      String. The typed share name lives in p3 (after the long
        //      gameId which takes two slots) — sanity check that the
        //      logical param 1 is a String so we don't smear a non-String
        //      into captureShareName.
        //
        // Previously also matched the literal "Layout not found:" but
        // that string is host-internal and not server-stable; dropped in
        // favour of the body-invokes anchor since the upload method ref
        // is structurally guaranteed to live in h's body.
        // -----------------------------------------------------------------
        val uploadMethodRef = "${uploadMethod.definingClass}->${uploadMethod.name}"
        val shareNameMethod = firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes.size == 4 &&
                parameterTypes[1] == "Ljava/lang/String;" &&
                bodyInvokes(this, uploadMethodRef)
        }

        shareNameMethod.addInstructions(
            0,
            """
                invoke-static/range {p3 .. p3}, $HOOK_HANDLER->captureShareName(Ljava/lang/String;)V
            """.trimIndent(),
        )

        // Label/relabel handling is done at runtime via the shared resource-
        // string resolver hook on `Lxd3;->l1` (installed by
        // VibrationMenuRowPatch) — see BhMenuRowClick.maybeResolveCustomLabel
        // for the host-key overrides. The composition-time call to
        // `BhVjoyShareHook.kickImportFromDialogOpen()` from that resolver
        // is what skips the Import dialog entirely (pre16).
    }
}

/**
 * True iff {@code method}'s body references a const-string whose value
 * contains {@code fragment}. Used to anchor methods by the URL literal
 * they emit, which is more stable than R8-mangled method names.
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
 * True iff {@code method}'s body contains an invoke instruction whose
 * resolved method reference (formatted as `LClass;->name`) matches
 * {@code methodReferencePrefix}. Used to chain anchors — once one method
 * is robustly located (e.g. via URL fragment), other methods that call
 * it can be located by the call-relationship, which is preserved across
 * R8 reshuffles even if every class letter changes.
 *
 * Matches partial method references (just class + name, no signature)
 * to tolerate signature changes in the called method.
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

