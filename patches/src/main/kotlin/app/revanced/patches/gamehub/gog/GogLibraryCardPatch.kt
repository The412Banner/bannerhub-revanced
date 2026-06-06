package app.revanced.patches.gamehub.gog

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
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
    description = "RETIRED (§34): the seeded library card was a handheld-only " +
        "entry — explore mode is a separate library surface that never " +
        "renders it. The per-game \"GOG\" menu row (GogMenuRowPatch) is now " +
        "the sole, mode-independent entry. This patch now only DELETES the " +
        "legacy sentinel row at MainActivity.onCreate so the card disappears " +
        "on existing installs too. (Name kept stable to preserve any " +
        "letter-map / dependency wiring.)",
) {
    // Pinned to 6.0.4 (skipped on 6.0.7): redundant with the working Explore
    // "Your stores" GOG card; the native library-grid card is dropped on 607.
    compatibleWith(GAMEHUB_PACKAGE("6.0.4"))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // ── cleanup hook ─────────────────────────────────────────────────────
        // MainActivity.onCreate(Bundle) — exact non-obfuscated anchor (the
        // same proven anchor the seeder used). p0 = the MainActivity instance
        // (is-a Context). Index 0 is safe: ensureRemoved only touches
        // Context.getDatabasePath + a guarded DELETE, valid before
        // super.onCreate(). Idempotent — removes the `bh_gog_launcher` rows
        // from t_game_library_base / t_game_launch_method every start, so the
        // card vanishes for users who installed a seeding build too.
        // The yv3.invoke launch-intercept (old hook 2) is GONE — it only
        // served the card; the menu row uses BhGogMenuRowClick directly.
        val onCreate = firstMethod {
            definingClass == MAIN_ACTIVITY &&
                name == "onCreate" &&
                parameterTypes == listOf("Landroid/os/Bundle;") &&
                returnType == "V"
        }
        onCreate.addInstructions(
            0,
            "invoke-static {p0}, $EXT->ensureRemoved(Landroid/content/Context;)V",
        )
    }
}
