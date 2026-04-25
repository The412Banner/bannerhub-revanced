package app.revanced.patches.gamehub.ui.gamedetail

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch

@Suppress("unused")
val hideRecommendedGamesPatch = bytecodePatch(
    name = "Hide recommended games",
    description = "Removes the recommended games section from the game detail view.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(creditsPatch)

    apply {
        // GameDetailVM.A(GameDetailEntity) builds the CardLineData that feeds the
        // recommendation adapter. Returning null makes the ConcatAdapter skip it.
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/vm/GameDetailVM;" &&
                name == "A" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == "Lcom/xj/common/service/bean/GameDetailEntity;" &&
                returnType == "Lcom/xj/common/service/bean/CardLineData;"
        }.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )

        addCredit(
            "Hide recommended games",
            "PlayDay" to "https://github.com/playday3008",
            "Producdevity" to "https://github.com/Producdevity/gamehub-lite",
        )
    }
}
