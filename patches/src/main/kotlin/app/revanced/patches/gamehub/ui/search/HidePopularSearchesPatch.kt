package app.revanced.patches.gamehub.ui.search

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val hidePopularSearchesPatch = bytecodePatch(
    name = "Hide popular searches",
    description = "Removes the popular game recommendations from the search view.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(creditsPatch)

    apply {
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/data/repository/SearchGameRepositoryV4;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("getSearchRecommend")
                } == true
        }.addInstructions(
            0,
            """
                new-instance v0, Lcom/xj/landscape/launcher/data/model/entity/SearchEntity;
                invoke-direct {v0}, Ljava/lang/Object;-><init>()V
                invoke-static {}, Lkotlin/collections/CollectionsKt;->p()Ljava/util/List;
                move-result-object p0
                iput-object p0, v0, Lcom/xj/landscape/launcher/data/model/entity/SearchEntity;->classGroup:Ljava/util/List;
                return-object v0
            """,
        )

        addCredit("Hide popular searches", "PlayDay" to "https://github.com/playday3008")
    }
}
