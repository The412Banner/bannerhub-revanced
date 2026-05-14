package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// Pure-stub neutralization of XiaoJi's WineGameUsageTracker. Zero network egress
// for playtime telemetry AND zero on-device tracking overhead. UX trade-off: the
// in-app playtime / recently-played UI will render empty.
//
// Companion to (replaces) the shelved Path-1 local-tracker variant preserved at
// tag archive/plan8c-local-tracker-pre3 — that version kept the UI populated by
// recording sessions to SharedPreferences, at the cost of per-tick JSON encode
// + disk write + reflection bookkeeping that impacted in-game perf. This patch
// throws the feature away in exchange for zero per-tick cost.
//
// Surface (anchors valid for GameHub 6.0.4; will need re-derivation on minor
// base bumps — anchor strings stay stable across R8 reshuffles, the class
// letters do not):
//
//   - Lfeo;->invokeSuspend  body contains "heartbeat/game/start"
//   - Lheo;->invokeSuspend  body contains "heartbeat/game/update"   (30s tick)
//   - Laeo;->invokeSuspend  body contains "heartbeat/game/end"
//   - Lse7;->c              body contains "heartbeat/game/getUserPlayTimeList"
//
// invokeSuspend bodies are SuspendLambda continuations — returning Unit.INSTANCE
// short-circuits the coroutine state machine cleanly. getUserPlayTimeList
// returns Lo55 (sealed wrapper); we return Ln55(empty ArrayList) so the UI's
// iterator runs zero passes instead of crashing.
// =============================================================================

private const val LFEO = "Lfeo;"
private const val LHEO = "Lheo;"
private const val LAEO = "Laeo;"
private const val LSE7 = "Lse7;"
private const val LN55 = "Ln55;"
private const val UNIT = "Lkotlin/Unit;"

private val unitReturn = """
    sget-object v0, $UNIT->INSTANCE:$UNIT
    return-object v0
""".trimIndent()

private val emptyListWrapped = """
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    new-instance v1, $LN55
    invoke-direct {v1, v0}, $LN55-><init>(Ljava/lang/Object;)V
    return-object v1
""".trimIndent()

@Suppress("unused")
val disableHeartbeatPatch = bytecodePatch(
    name = "Disable heartbeat",
    description = "Disables XiaoJi's WineGameUsageTracker server-heartbeat " +
        "(heartbeat/game/{start,update,end}) and the getUserPlayTimeList GET. " +
        "All four methods return-early so no network call is made and no per-tick " +
        "work runs. Trade-off: in-app playtime UI will be empty (no on-device " +
        "tracker; see archive/plan8c-local-tracker-pre3 tag for the variant that " +
        "kept the UI populated at the cost of per-tick disk + reflection work).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod {
            name == "invokeSuspend" &&
                definingClass == LFEO &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/start" } == true
                } == true
        }.addInstructions(0, unitReturn)

        firstMethod {
            name == "invokeSuspend" &&
                definingClass == LHEO &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/update" } == true
                } == true
        }.addInstructions(0, unitReturn)

        firstMethod {
            name == "invokeSuspend" &&
                definingClass == LAEO &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/end" } == true
                } == true
        }.addInstructions(0, unitReturn)

        firstMethod {
            name == "c" &&
                definingClass == LSE7 &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/getUserPlayTimeList" } == true
                } == true
        }.addInstructions(0, emptyListWrapped)
    }
}
