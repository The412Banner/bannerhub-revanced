package app.revanced.patches.gamehub.misc.playtime

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// Disables XiaoJi's WineGameUsageTracker server-heartbeat (heartbeat/game/*)
// AND replaces the playtime data source with a local-only SharedPreferences
// tracker via BhPlayTimeTracker (Java extension). Privacy goal: zero network
// egress for playtime. UX goal: keep the in-app playtime display working.
//
// Architecture (all references valid for GameHub 6.0.4; class letters will
// reshuffle on every minor base bump and the smali field refs below need
// to be re-derived from a fresh decompile):
//
//   - Lieo;          ← XiaoJi's tracker (returns "WineGameUsageTracker" from
//                       getValue(); 5 String fields a-e carry game metadata)
//   - Lfeo;          ← SuspendLambda for heartbeat/game/start
//   - Lheo;          ← SuspendLambda for heartbeat/game/update
//   - Laeo;          ← SuspendLambda for heartbeat/game/end
//   - Lse7;          ← Retrofit-impl wrapper. Method c(Lci3;)Object is
//                       getUserPlayTimeList — the GET the UI consumes.
//
// Each SuspendLambda holds a `final synthetic this$0:Lieo;` field pointing
// at the outer Lieo tracker. We read Lieo's a-e Strings via this$0, hand them
// to BhPlayTimeTracker, and return Kotlin Unit so the coroutine state machine
// short-circuits cleanly. The original method body becomes unreachable dead
// code, which dexlib + ART are both fine with.
//
// Structural anchors find methods by their body containing a particular
// heartbeat URL const-string. If R8 reshuffles class letters but the URL
// strings stay the same (they have for every minor version so far), the
// methods are still findable — but the iget refs below WILL need re-derivation
// against the new letters. On a minor bump, expect SEVERE on apply with
// "Field not found" — re-derive Lieo / Lfeo / Lheo / Laeo / Lse7 from a fresh
// decompile and update the constants here.
// =============================================================================

// Hard-coded against 6.0.4 — re-derive on minor bumps.
private const val LIEO = "Lieo;"
private const val LFEO = "Lfeo;"
private const val LHEO = "Lheo;"
private const val LAEO = "Laeo;"
private const val LSE7 = "Lse7;"

private const val EXT = "Lapp/revanced/extension/gamehub/playtime/BhPlayTimeTracker;"
private const val UNIT = "Lkotlin/Unit;"
private const val STR = "Ljava/lang/String;"

// Reads Lieo.a..e into v1..v5, calls the named extension method, returns Unit.
// Caller must declare .locals >= 6 (existing SuspendLambda invokeSuspend bodies
// always have far more than that — typical 9-10 locals).
private fun heartbeatHookBody(suspendLambdaCls: String, extMethod: String): String = """
    iget-object v0, p0, $suspendLambdaCls->this${'$'}0:$LIEO
    iget-object v1, v0, $LIEO->a:$STR
    iget-object v2, v0, $LIEO->b:$STR
    iget-object v3, v0, $LIEO->c:$STR
    iget-object v4, v0, $LIEO->d:$STR
    iget-object v5, v0, $LIEO->e:$STR
    invoke-static {v1, v2, v3, v4, v5}, $EXT->$extMethod($STR$STR$STR$STR$STR)V
    sget-object v0, $UNIT->INSTANCE:$UNIT
    return-object v0
""".trimIndent()

private fun methodContainsString(literal: String): (com.android.tools.smali.dexlib2.iface.Method).() -> Boolean = {
    implementation?.instructions?.any { ins ->
        (ins as? ReferenceInstruction)?.reference
            ?.let { it is StringReference && it.string == literal } == true
    } == true
}

@Suppress("unused")
val disableHeartbeatLocalTrackerPatch = bytecodePatch(
    name = "Local playtime tracker",
    description = "Replaces XiaoJi's WineGameUsageTracker server-heartbeat with an " +
        "on-device SharedPreferences-backed tracker. Stops all heartbeat/game/{start," +
        "update,end} POSTs and the getUserPlayTimeList GET from reaching the server; " +
        "the UI keeps showing playtime, sourced locally instead. Cloud games (b30/" +
        "CloudGamePlayTimeEntity path) not yet covered — they fall back to zero playtime " +
        "and can be added in a follow-up patch.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // 1. heartbeat/game/start  →  recordStart
        firstMethod {
            name == "invokeSuspend" &&
                definingClass == LFEO &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/start" } == true
                } == true
        }.apply {
            addInstructions(0, heartbeatHookBody(LFEO, "recordStart"))
        }

        // 2. heartbeat/game/update  →  tick
        firstMethod {
            name == "invokeSuspend" &&
                definingClass == LHEO &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/update" } == true
                } == true
        }.apply {
            addInstructions(0, heartbeatHookBody(LHEO, "tick"))
        }

        // 3. heartbeat/game/end  →  recordEnd
        firstMethod {
            name == "invokeSuspend" &&
                definingClass == LAEO &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/end" } == true
                } == true
        }.apply {
            addInstructions(0, heartbeatHookBody(LAEO, "recordEnd"))
        }

        // 4. getUserPlayTimeList  →  return local entity list
        //    Lse7;->c(Lci3;)Ljava/lang/Object;  (Lci3 is the kotlin Continuation type)
        //    Body becomes: invoke-static BhPlayTimeTracker.getPcEntityList()Object;
        //                  move-result-object v0
        //                  return-object v0
        firstMethod {
            name == "c" &&
                definingClass == LSE7 &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == "heartbeat/game/getUserPlayTimeList" } == true
                } == true
        }.apply {
            addInstructions(0, """
                invoke-static {}, $EXT->getPcEntityList()Ljava/lang/Object;
                move-result-object v0
                return-object v0
            """.trimIndent())
        }
    }
}
