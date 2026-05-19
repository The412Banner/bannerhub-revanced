package app.revanced.patches.gamehub.gog

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

// =============================================================================
// WS4 — permanent synthetic "GOG" card in the library grid (design doc §28).
//
// Two hooks:
//  (1) SEED — at com.xiaoji.egggame.MainActivity.onCreate (non-obfuscated,
//      P-B-confirmed, stable across base bumps): call
//      GogLibraryCard.ensureSeeded(this). Idempotent + self-healing → the
//      sentinel row is (re)created every app start.
//  (2) INTERCEPT — at the non-suspend LaunchRouter lambda `yv3.invoke()`
//      (anchored by the stable non-obf string "buildLibraryInfoWithContext ";
//      the GOG-card tap → game-detail dialog → "Launch Game" routes here, NOT
//      po7.F0/G0 — see §32). A single side-effect-only
//      `invoke-static {p0}, GogLibraryCard.openHubIfSentinel(...)V` at index 0
//      (no branch/return/register change): on the sentinel it opens
//      GogMainActivity; the original launch proceeds and harmlessly fails
//      behind the foregrounded hub. Non-suspend → index-0 verifier-safe (cf.
//      the §31 suspend `wel.b` VerifyError).
//
// Risk note (per §22 CI+device loop): hook (1) is high-confidence (exact
// non-obf anchor, pure-Context call). Hook (2) is the iterate-prone piece;
// the yv3 fingerprint is letter-free (stable string + invoke()Object shape)
// and the edit is the minimal verifier-safe form. It is wrapped in try/catch
// so a fingerprint miss degrades to "card still appears, tap iterates" (§22)
// instead of failing the whole patch (the pre5 silent-ship footgun).
// =============================================================================

private const val EXT = "Lapp/revanced/extension/gamehub/gog/GogLibraryCard;"
private const val MAIN_ACTIVITY = "Lcom/xiaoji/egggame/MainActivity;"

@Suppress("unused")
val gogLibraryCardPatch = bytecodePatch(
    name = "GOG library card (permanent)",
    description = "Seeds a permanent synthetic \"GOG\" card into GameHub's " +
        "library DB (idempotent, self-healing, server-sync-invisible) and " +
        "intercepts its launch to open the GOG hub (login / library) instead " +
        "of a Wine launch. Phase-1 entry point; reusable toward the Phase-2 " +
        "GameHub-library bridge.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // ── (1) seed hook ────────────────────────────────────────────────────
        // MainActivity.onCreate(Bundle) — exact non-obfuscated anchor. p0 =
        // the MainActivity instance (is-a Context). Index 0 is safe:
        // ensureSeeded only touches Context.getDatabasePath and is fully
        // guarded, so it is valid before super.onCreate().
        val onCreate = firstMethod {
            definingClass == MAIN_ACTIVITY &&
                name == "onCreate" &&
                parameterTypes == listOf("Landroid/os/Bundle;") &&
                returnType == "V"
        }
        onCreate.addInstructions(
            0,
            "invoke-static {p0}, $EXT->ensureSeeded(Landroid/content/Context;)V",
        )

        // ── (2) launch intercept — yv3.invoke (LaunchRouter lambda) ──────────
        // §32, device 2026-05-19 (pre6): card + seeder CONFIRMED, no crash
        // (pre6 applied cleanly), BUT the GOG-card tap → game-detail dialog →
        // "Launch Game" routes through GameHub's LaunchRouter interceptor
        // chain, NOT po7.F0/G0 — logcat showed `buildLibraryInfoWithContext
        // GOG , startType = 0` then `No strategy found: type=Unknown`, and
        // zero GogLibraryCard log lines (our intercept never ran; pre6's
        // try/catch silently swallowed the wrong-path F0/G0 non-match).
        //
        // Re-anchor on the first NON-suspend point that holds the game: the
        // multiplexed launch lambda `yv3.invoke()Ljava/lang/Object;` — a plain
        // Function0 invoke, NOT a coroutine, so index-0 is verifier-safe
        // (unlike the §31 suspend `wel.b`). Fingerprint = the proven
        // pre4-style stable non-obf CONST_STRING "buildLibraryInfoWithContext "
        // (uniquely that launch lambda) + the no-arg `invoke()Object` shape.
        // p0 = the lambda instance; it transitively holds the launch context
        // (t07 → GameInfo). Inject ONE side-effect-only call at index 0:
        //   invoke-static {p0}, $EXT->openHubIfSentinel(Ljava/lang/Object;)V
        // No move-result, no branch, no return change, zero register clobber —
        // the minimal possible verifier-safe edit. The extension reflectively
        // finds the sentinel GameInfo (bounded, fail-safe, de-duplicated) and
        // on match opens GogMainActivity; the original launch then proceeds
        // and harmlessly logs "No strategy found" behind the foregrounded hub.
        // try/catch keeps the §22 graceful-degrade (miss → card still works).
        try {
            val m = firstMethod {
                name == "invoke" &&
                    parameterTypes.isEmpty() &&
                    returnType == "Ljava/lang/Object;" &&
                    implementation?.instructions?.any { ins ->
                        ins.opcode == Opcode.CONST_STRING &&
                            (ins as? ReferenceInstruction)?.reference?.toString()
                                ?.contains("buildLibraryInfoWithContext ") == true
                    } == true
            }
            m.addInstructions(
                0,
                "invoke-static {p0}, $EXT->openHubIfSentinel(Ljava/lang/Object;)V",
            )
        } catch (_: Exception) {
            // Fingerprint miss: seed hook (1) already ran → card still
            // appears; only tap-behaviour iterates (§22).
        }
    }
}
