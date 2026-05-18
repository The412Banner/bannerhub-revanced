package app.revanced.patches.gamehub.misc.offlinecache

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// ============================================================================
// ⚠ THROWAWAY DIAGNOSTIC — DO NOT MERGE / DO NOT SHIP
//
// Locates where the OFFLINE component-picker path actually goes in 6.0.4
// mci.a(RepoCategory, Continuation). The prefs-read fix produced ZERO trace
// on device; we must know whether the offline path:
//   (a) never reaches this method (picker uses a different path), or
//   (b) reaches it but the network fetch THROWS and the coroutine rethrows
//       at Lkl5;->V (Kotlin throwOnFailure) on resume, BEFORE :goto_2, or
//   (c) reaches :goto_2 (returns-empty) and the failure is downstream in
//       fromXxo / the prefs read (i.e. DebugTrace was the blind spot).
//
// Three breadcrumbs (OfflineDiag, internal-files sink, NOT DebugTrace):
//   1. mciEnter()      injected at mci.a index 0           → answers (a)
//   2. mciPostResume() injected AFTER the first Lkl5;->V    → answers (b)
//   3. fromXxo ENTER   (in PickerCacheFallback itself)      → answers (c)
//
// All injected calls are zero-arg static void → no register pressure.
// ============================================================================

private const val MCI_CLASS         = "Lmci;"
private const val REPO_CATEGORY     = "Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;"
private const val CONTINUATION_TYPE = "Lci3;"
private const val KL5_CLASS         = "Lkl5;"   // Kotlin ResultKt (throwOnFailure = V)
private const val DIAG =
    "Lapp/revanced/extension/gamehub/winemu/OfflineDiag;"

@Suppress("unused")
val offlineDiagProbePatch = bytecodePatch(
    name = "Offline picker diagnostic probe (THROWAWAY)",
    description = "Injects root-readable breadcrumbs into mci.a to locate " +
        "the offline component-picker control path. Throwaway — never ship.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        firstMethod {
            definingClass == MCI_CLASS &&
                name == "a" &&
                parameterTypes == listOf(REPO_CATEGORY, CONTINUATION_TYPE) &&
                returnType == "Ljava/io/Serializable;"
        }.apply {
            // Probe 2: right AFTER the first Lkl5;->V (resume-path
            // throwOnFailure). Done before the index-0 insert so the index
            // we compute isn't shifted by probe 1.
            val vIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    getReference<MethodReference>()?.let {
                        it.definingClass == KL5_CLASS && it.name == "V"
                    } == true
            }
            addInstructions(
                vIdx + 1,
                "invoke-static {}, $DIAG->mciPostResume()V",
            )

            // Probe 1: very first instruction of mci.a.
            addInstructions(
                0,
                "invoke-static {}, $DIAG->mciEnter()V",
            )
        }
    }
}
