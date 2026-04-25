package app.revanced.patches.gamehub.misc.heartbeat

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val disableHeartbeatPatch = bytecodePatch(
    name = "Disable heartbeat",
    description = "Disables game usage heartbeat requests sent to the server " +
        "(heartbeat/game/start, update, end).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(creditsPatch)

    apply {
        // WineGameUsageTracker periodic heartbeats.
        firstMethod {
            returnType == "V" &&
                parameterTypes.isEmpty() &&
                definingClass == "Lcom/xj/winemu/utils/WineGameUsageTracker;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("startHeartbeatTime")
                } == true
        }.returnEarly()

        firstMethod {
            returnType == "V" &&
                parameterTypes.isEmpty() &&
                definingClass == "Lcom/xj/winemu/utils/WineGameUsageTracker;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("updateHeartbeatTime")
                } == true
        }.returnEarly()

        firstMethod {
            returnType == "V" &&
                parameterTypes.isEmpty() &&
                definingClass == "Lcom/xj/winemu/utils/WineGameUsageTracker;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("endHeartbeatTime")
                } == true
        }.returnEarly()

        // SteamGameByPcEmuLaunchStrategy.checkCanStartSteamGame — server permission check
        // that also hits heartbeat/game/start. Return Pair(true, "") to always allow launch.
        firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes == listOf("Ljava/lang/String;", "Lkotlin/coroutines/Continuation;") &&
                definingClass == "Lcom/xj/landscape/launcher/launcher/strategy/SteamGameByPcEmuLaunchStrategy;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("checkCanStartSteamGame")
                } == true
        }.addInstructions(
            0,
            """
                new-instance v0, Lkotlin/Pair;
                sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                const-string v2, ""
                invoke-direct {v0, v1, v2}, Lkotlin/Pair;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V
                return-object v0
            """,
        )

        addCredit(
            "Disable heartbeat",
            "Producdevity" to "https://github.com/Producdevity/gamehub-lite",
            "PlayDay" to "https://github.com/playday3008",
        )
    }
}
