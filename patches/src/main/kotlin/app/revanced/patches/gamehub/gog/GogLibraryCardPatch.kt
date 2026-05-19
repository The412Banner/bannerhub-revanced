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

        // ── (2) launch intercept — REAL card-tap resolver wel.b ──────────────
        // pre3 device test (§30): tapping the card hit GameHub's launch-
        // strategy resolver `wel.b(Lwel;Lw4c;Lci3;)Object` and failed with the
        // logged string "No strategy found: type=Unknown" — i.e. po7.G was the
        // WRONG anchor; the card tap dies in wel.b before any per-type
        // dispatch. Re-anchor on that STABLE (non-obfuscated) string literal:
        // the suspend method whose body emits "No strategy found: type=".
        // p1 = the obfuscated launch-context (`w4c`, holds a kept-name
        // GameInfo). Inject at head → maybeOpenHubFromLaunchCtx(p1)
        // reflectively pulls the GameInfo/id (obfuscation-proof); sentinel →
        // GogMainActivity already started, complete the suspend fn with
        // kotlin.Unit.INSTANCE so the strategy lookup / error never runs.
        val launchResolver = firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes.size == 3 &&
                parameterTypes[2] == "Lci3;" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.CONST_STRING &&
                        (ins as? ReferenceInstruction)?.reference?.toString()
                            ?.contains("No strategy found: type=") == true
                } ?: false)
        }

        val orig = launchResolver.getInstruction(0)
        // p1 = launch-context. v0 = fresh scratch for the boolean + Unit
        // return; original body re-inits its own regs, so an index-0 v0
        // clobber before fall-through is safe dalvik (OfflineComponentList
        // gof.c technique). Suspend completes with Unit.INSTANCE (the correct
        // "returned Unit" value — not null).
        launchResolver.addInstructionsWithLabels(
            0,
            """
                invoke-static {p1}, $EXT->maybeOpenHubFromLaunchCtx(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :bhOrig
                sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                return-object v0
            """.trimIndent(),
            ExternalLabel("bhOrig", orig),
        )
    }
}
