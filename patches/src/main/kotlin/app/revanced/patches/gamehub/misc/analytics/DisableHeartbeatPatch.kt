package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// Pure-stub neutralization of XiaoJi's WineGameUsageTracker server-heartbeat.
// Zero network egress for the AUTOMATIC playtime telemetry (start / 30s-tick
// update / end), the privacy-critical surface.
//
// ANCHORING (6.0.7): anchored PURELY on the stable URL-path string + the
// SuspendLambda method name "invokeSuspend" — NOT on R8 class letters (those
// reshuffle every minor version). 6.0.7 letters, for reference:
//   - "heartbeat/game/start"  -> Lk3n;->invokeSuspend  (this method ALSO contains
//                                "heartbeat/game/update" — one lambda drives both
//                                the start request and the 30s update tick, so a
//                                single Unit-return short-circuits both)
//   - "heartbeat/game/end"    -> Lg3n;->invokeSuspend
// invokeSuspend bodies are SuspendLambda continuations — returning Unit.INSTANCE
// (universal, never obfuscated) short-circuits the coroutine state machine at
// state 0, before any network call.
//
// SCOPE NOTE (6.0.7): the 6.0.4 patch also stubbed getUserPlayTimeList (a READ
// GET) by returning an empty success wrapper (Ln55(ArrayList) of the Lo55 sealed
// result). 6.0.7 replaced that result model — getUserPlayTimeList (Lcb7;->c)
// now returns a deserialized HTTP type, and the error type Lg50 extends
// RuntimeException (not a value wrapper), so fabricating an empty success is
// both non-trivial and crash-risky (a wrong wrapper APPLIES but crashes at
// runtime; apply-only CI can't catch it). That read is user-initiated (only
// when viewing the playtime UI), not automatic egress, so it is intentionally
// LEFT ACTIVE here. Re-add a getUserPlayTimeList stub only with the real 6.0.7
// success type + an on-device test.
// =============================================================================

private const val UNIT = "Lkotlin/Unit;"

private val unitReturn = """
    sget-object v0, $UNIT->INSTANCE:$UNIT
    return-object v0
""".trimIndent()

@Suppress("unused")
val disableHeartbeatPatch = bytecodePatch(
    name = "Disable heartbeat",
    description = "Disables XiaoJi's WineGameUsageTracker automatic server-heartbeat " +
        "(heartbeat/game/{start,update,end}) so no playtime telemetry is sent and no " +
        "per-tick work runs. Anchored on the stable URL-path strings, not class letters. " +
        "On 6.0.7 the user-initiated getUserPlayTimeList read is left active (its result " +
        "wrapper changed and is crash-risky to fabricate); the automatic egress is stopped.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // start + 30s update tick (same SuspendLambda in 6.0.7 — Lk3n)
        firstMethod {
            name == "invokeSuspend" &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/start" } == true
                } == true
        }.addInstructions(0, unitReturn)

        // end (Lg3n)
        firstMethod {
            name == "invokeSuspend" &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/end" } == true
                } == true
        }.addInstructions(0, unitReturn)
    }
}
