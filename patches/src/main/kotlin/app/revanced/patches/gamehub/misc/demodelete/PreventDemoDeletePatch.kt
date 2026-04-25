package app.revanced.patches.gamehub.misc.demodelete

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val preventDemoDeletePatch = bytecodePatch(
    name = "Prevent demo game deletion",
    description = "Prevents automatic deletion of demo/PC emulator games after a session ends. " +
        "Manual uninstall from the game detail screen still works.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(creditsPatch)

    apply {
        // UninstallGameHelper.uninstallPcDemoGame — suspend function that dispatches a coroutine
        // to delete game files and reset state. Called automatically by GameDetailVM.refreshDownloadStatusUI
        // and by the DEMO_AUTO uninstall path. Return kotlin.Unit immediately to prevent deletion.
        firstMethod {
            returnType == "Ljava/lang/Object;" &&
                parameterTypes == listOf("Ljava/lang/String;", "Lkotlin/coroutines/Continuation;") &&
                definingClass == "Lcom/xj/game/UninstallGameHelper;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("uninstallPcDemoGame")
                } == true
        }.addInstructions(
            0,
            """
                sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                return-object v0
            """,
        )

        addCredit(
            "Prevent demo game deletion",
            "Producdevity" to "https://github.com/Producdevity/gamehub-lite",
            "PlayDay" to "https://github.com/playday3008",
        )
    }
}
