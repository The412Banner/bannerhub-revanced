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
//  (2) INTERCEPT — at GameHub's two NON-suspend launch orchestrators on the
//      launch VM (`po7.F0`/`po7.G0`, both `public final (GameInfo)V`), where
//      the GOG-card tap is dispatched via `vl7.l`'s K/A/B branch. Head guard:
//      if GogLibraryCard.maybeOpenHubFromLaunchCtx(p1) → GogMainActivity
//      already started; `return-void` so the normal Wine launch never runs.
//      Anchoring OFF any suspend fn is deliberate — see the §31 VerifyError.
//
// Risk note (per §22 CI+device loop): hook (1) is high-confidence (exact
// non-obf anchor, pure-Context call). Hook (2) is the iterate-prone piece;
// the F0/G0 fingerprints are letter-free (kept-name classes only) and the
// guard is verifier-safe, but which branch the synthetic sentinel routes
// through is not inspection-determinable, so we guard BOTH and let the
// device test confirm. If (2) misses, the card still appears (seed proves
// the bulk of WS4) and only tap-behaviour iterates — the WS1 one-bug loop.
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

        // ── (2) launch intercept — VERIFIER-SAFE non-suspend anchors ─────────
        // pre4 device (§31): anchoring on the suspend strategy resolver
        // `wel.b(Lwel;Lw4c;Lci3;)` and prepending at index 0 produced
        // `java.lang.VerifyError: Verifier rejected class wel`. Kotlin emits a
        // coroutine state-machine dispatch at a suspend method's head and
        // resume paths branch back into it, so an index-0 splice is reached
        // WITHOUT our invoke (result0=Undefined) → class rejected → instant
        // crash on the GOG card tap. Re-anchor OFF the coroutine entirely.
        //
        // The tap is dispatched (via `vl7.l`'s K/A/B branch) into one of
        // GameHub's two NON-suspend launch orchestrators on the launch VM:
        //   po7.F0(GameInfo)V  — full path (refs GameInfo.getHasAchievements)
        //   po7.G0(GameInfo)V  — lean variant (no getHasAchievements)
        // Both are `public final (GameInfo)V`, reference the kept-name
        // LaunchType enum + GameInfo.getSteamAppId. Head-guarding them is
        // verifier-safe: non-suspend, void → early `return-void`; v0 is a
        // single-clobber before the body re-inits its own regs (the proven
        // OfflineComponentList gof.c / seed-hook technique). p1 = the
        // GameInfo; maybeOpenHubFromLaunchCtx pulls the id obfuscation-proof
        // (deepExtractId → getId()). Fingerprints are letter-free (kept-name
        // classes only) → base-bump resilient. We guard BOTH because which
        // branch the synthetic sentinel routes through is not
        // inspection-determinable (§31).
        // pre5 SEVERE (§31): the F0/G0 fingerprints were factored through a
        // local `bodyRefs` helper called from inside `firstMethod {}` — the
        // patcher matched zero methods (`PatchException: Collection is empty`)
        // and, because a `firstMethod` miss throws, the WHOLE patch silently
        // failed to apply (no card at all — strictly worse than pre4's crash;
        // the CI silent-ship footgun). pre6: (a) inline the scans with the
        // exact opcode-guarded `implementation?.instructions?.any { } == true`
        // idiom proven by hook (1)/pre3 (no local helper in the predicate);
        // (b) `parameterTypes == listOf(gameInfoT)` (the proven seed-hook
        // form); (c) wrap each anchor in try/catch so a fingerprint miss
        // degrades to "card still appears, tap-behaviour iterates" instead of
        // nuking the entire patch — the design-doc §22 philosophy, now
        // structurally enforced.
        val gameInfoT = "Lcom/xiaoji/egggame/game/di/model/game/GameInfo;"
        val launchTypeT = "Lcom/xiaoji/egggame/launcher/model/LaunchType;"

        // Verifier-safe head guard. Direct API use (firstMethod →
        // getInstruction/addInstructionsWithLabels) is byte-for-byte the pre4
        // sequence that provably resolved — no local helper, no reflection,
        // type inferred. p1 = the GameInfo (p0 = this); v0 is freshly written
        // then either returned-from or re-init by the original head on the
        // non-sentinel fall-through (proven OfflineComponentList gof.c /
        // seed-hook technique). Each anchor is self-contained in try/catch so
        // a fingerprint miss degrades to "card still appears, tap iterates"
        // (§22) instead of failing the whole patch (the pre5 footgun).
        val guardSnippet =
            """
                invoke-static {p1}, $EXT->maybeOpenHubFromLaunchCtx(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :bhOrig
                return-void
            """.trimIndent()

        // po7.F0 — full launch path (unique: GameInfo.getHasAchievements).
        try {
            val m = firstMethod {
                returnType == "V" &&
                    parameterTypes == listOf(gameInfoT) &&
                    implementation?.instructions?.any { ins ->
                        (ins.opcode == Opcode.SGET_OBJECT ||
                            ins.opcode == Opcode.INVOKE_VIRTUAL) &&
                            (ins as? ReferenceInstruction)?.reference?.toString()
                                ?.contains(launchTypeT) == true
                    } == true &&
                    implementation?.instructions?.any { ins ->
                        (ins.opcode == Opcode.INVOKE_VIRTUAL ||
                            ins.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                            (ins as? ReferenceInstruction)?.reference?.toString()
                                ?.contains("$gameInfoT->getHasAchievements") == true
                    } == true
            }
            val origHead = m.getInstruction(0)
            m.addInstructionsWithLabels(
                0,
                guardSnippet,
                ExternalLabel("bhOrig", origHead),
            )
        } catch (_: Exception) {
            // Fingerprint miss: seed hook (1) already ran → card still
            // appears; only tap-behaviour iterates (§22).
        }

        // po7.G0 — lean variant (refs getSteamAppId, NOT getHasAchievements).
        try {
            val m = firstMethod {
                returnType == "V" &&
                    parameterTypes == listOf(gameInfoT) &&
                    implementation?.instructions?.any { ins ->
                        (ins.opcode == Opcode.SGET_OBJECT ||
                            ins.opcode == Opcode.INVOKE_VIRTUAL) &&
                            (ins as? ReferenceInstruction)?.reference?.toString()
                                ?.contains(launchTypeT) == true
                    } == true &&
                    implementation?.instructions?.any { ins ->
                        (ins.opcode == Opcode.INVOKE_VIRTUAL ||
                            ins.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                            (ins as? ReferenceInstruction)?.reference?.toString()
                                ?.contains("$gameInfoT->getSteamAppId") == true
                    } == true &&
                    implementation?.instructions?.none { ins ->
                        (ins as? ReferenceInstruction)?.reference?.toString()
                            ?.contains("$gameInfoT->getHasAchievements") == true
                    } == true
            }
            val origHead = m.getInstruction(0)
            m.addInstructionsWithLabels(
                0,
                guardSnippet,
                ExternalLabel("bhOrig", origHead),
            )
        } catch (_: Exception) {
            // Fingerprint miss: seed hook (1) already ran → card still appears.
        }
    }
}
