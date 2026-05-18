package app.revanced.patches.gamehub.misc.offlinecache

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

// ============================================================================
// Offline component-picker fix (read-side) — supersedes the dead, KNOWN-BROKEN
// OfflineComponentCachePatch (mci.a hook; forensically proven inert on 6.0.4:
// the pickers never touch the myo/j7o registry path at all).
//
// REAL architecture (established 2026-05-18 via sink-verified probe +
// inotify/DB forensics):
//   - The pickers are fed by the `winemu_game_config` API response, parsed
//     into GameEnvConfigEntity (R8 `Lso7;`). Its `component` list (field `a`,
//     element type ComponentRepo) is what every picker sub-filters by type.
//   - Served offline from the `api_cache` Room table (egggame.db) — it holds
//     ONLY the server-recommended set.
//   - The user's locally-downloaded components are merged into `component`
//     ONLY by the online repository path (n5o.f -> kek.o()/j7o disk-hydrate).
//     That path is network-gated, so OFFLINE the cached config is served
//     WITHOUT the local merge and the saved catalog
//     (sp_winemu_unified_resources) is never even read.
//
// FIX: at BOTH `so7.<init>` ctors (the kotlinx-serialization synthetic ctor
// used by the offline deserializer, and the regular ctor), transform the
// incoming `component` list, just before it is stored into `Lso7;->a`, into
// `OfflineComponentMerge.augment(it)` — the union of the API set and the
// locally-downloaded components (obtained by reusing the host's own
// kek.p()/j7o-hydrate/myo.w(COMPONENT)/WinEmuRepo.toComponentRepo() chain),
// de-duplicated by ComponentRepo name+version. Idempotent: when the online
// path already merged, dedupe makes it a no-op. Any failure in the extension
// returns the original list (picker can never be broken).
//
// Injection is register-safe: a single `invoke-static {vR}` +
// `move-result-object vR` that reuses the value register already holding the
// component list — no `.locals` growth in the (param-dense) constructors. The
// heavy host-fetch/merge lives in the extension (reflection), exactly the
// rationale the old patch documented for using a helper over inline smali.
// ============================================================================

private const val SO7_CLASS = "Lso7;"
private const val LIST_TYPE = "Ljava/util/List;"
private const val MERGE =
    "Lapp/revanced/extension/gamehub/winemu/OfflineComponentMerge;"

@Suppress("unused")
val offlineComponentMergePatch = bytecodePatch(
    name = "Offline component picker — local merge",
    description = "Merges locally-downloaded components into the winemu " +
        "game-config component list at so7.<init>, so the GPU driver / DXVK " +
        "/ VKD3D / FEXCore / Box64 / container pickers list the user's " +
        "downloaded components offline (read-side; idempotent online).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // --- kotlinx-serialization synthetic ctor (OFFLINE deserializer) ---
        // First param = seen-fields bitmask (I); then component:List,
        // deps:List, ...
        firstMethod {
            definingClass == SO7_CLASS &&
                name == "<init>" &&
                parameterTypes.size >= 3 &&
                parameterTypes[0] == "I" &&
                parameterTypes[1] == LIST_TYPE &&
                parameterTypes[2] == LIST_TYPE
        }.apply {
            // The FIRST iput-object into Lso7;->a:Ljava/util/List; (component;
            // NOT b/deps). Replace the value register in place.
            val idx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IPUT_OBJECT &&
                    getReference<FieldReference>()?.let {
                        it.definingClass == SO7_CLASS &&
                            it.name == "a" &&
                            it.type == LIST_TYPE
                    } == true
            }
            val reg = getInstruction<TwoRegisterInstruction>(idx).registerA
            addInstructions(
                idx,
                """
                    invoke-static {v$reg}, $MERGE->augment(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$reg
                """.trimIndent(),
            )
        }

        // --- regular ctor (online path / direct construction) -------------
        // component:List, deps:List, ... Idempotent via dedupe.
        firstMethod {
            definingClass == SO7_CLASS &&
                name == "<init>" &&
                parameterTypes.size >= 2 &&
                parameterTypes[0] == LIST_TYPE &&
                parameterTypes[1] == LIST_TYPE
        }.apply {
            val idx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IPUT_OBJECT &&
                    getReference<FieldReference>()?.let {
                        it.definingClass == SO7_CLASS &&
                            it.name == "a" &&
                            it.type == LIST_TYPE
                    } == true
            }
            val reg = getInstruction<TwoRegisterInstruction>(idx).registerA
            addInstructions(
                idx,
                """
                    invoke-static {v$reg}, $MERGE->augment(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$reg
                """.trimIndent(),
            )
        }
    }
}
