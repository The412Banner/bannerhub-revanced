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
//  (2) INTERCEPT — at po7's by-id launch dispatch
//      `G(Lpo7;Ljava/lang/String;Lci3;)Ljava/lang/Object;` (suspend; p1 = the
//      launch game-id String; body references the kept-name LaunchType class).
//      Head guard: if GogLibraryCard.maybeOpenHubById(p1) → GogMainActivity
//      already started; complete the suspend fn with kotlin.Unit.INSTANCE
//      (the correct "completed, returned Unit" value — NOT null) so the normal
//      Wine launch never runs and no exe/container validation fires.
//
// Risk note (per §22 CI+device loop): hook (1) is high-confidence (exact
// non-obf anchor, pure-Context call). Hook (2) is the iterate-prone piece —
// the obfuscated suspend method/return is inspection-unverifiable; structural
// anchor + Unit.INSTANCE return is the best first-cut; device test drives any
// refinement. If (2) misses, the card still appears (seed proves the bulk of
// WS4) and only tap-behaviour iterates — same shape as the WS1 one-bug loop.
// =============================================================================

private const val EXT = "Lapp/revanced/extension/gamehub/gog/GogLibraryCard;"
private const val MAIN_ACTIVITY = "Lcom/xiaoji/egggame/MainActivity;"
private const val LAUNCH_TYPE =
    "Lcom/xiaoji/egggame/launcher/model/LaunchType;"

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

        // ── (2) launch intercept ─────────────────────────────────────────────
        // Structural fingerprint for po7.G: a static method
        // (<self>, String, Lci3;) -> Object whose body references the
        // kept-name LaunchType class. (Lci3; = the Kotlin Continuation type
        // on this base; p1 = the launch id String.)
        val launchById = firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes.size == 3 &&
                parameterTypes[1] == "Ljava/lang/String;" &&
                parameterTypes[2] == "Lci3;" &&
                (implementation?.instructions?.any { ins ->
                    (ins.opcode == Opcode.SGET_OBJECT ||
                        ins.opcode == Opcode.INVOKE_VIRTUAL ||
                        ins.opcode == Opcode.INVOKE_STATIC) &&
                        (ins as? ReferenceInstruction)?.reference?.toString()
                            ?.contains(LAUNCH_TYPE) == true
                } ?: false)
        }

        val orig = launchById.getInstruction(0)
        // p1 = id String. v0 used as a fresh scratch for the boolean result +
        // the Unit return; the original body re-initialises its own registers,
        // so a single index-0 clobber of v0 before falling through is safe
        // dalvik (same technique as OfflineComponentListPatch's gof.c head).
        launchById.addInstructionsWithLabels(
            0,
            """
                invoke-static {p1}, $EXT->maybeOpenHubById(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :bhOrig
                sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                return-object v0
            """.trimIndent(),
            ExternalLabel("bhOrig", orig),
        )
    }
}
