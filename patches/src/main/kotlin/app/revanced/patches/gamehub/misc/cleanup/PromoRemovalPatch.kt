package app.revanced.patches.gamehub.misc.cleanup

import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.util.returnEarly
import org.w3c.dom.Element

private val hideCommunityBannerPatch = resourcePatch {
    apply {
        document("res/layout/llauncher_view_my_top_platform_pc_emulator_info.xml").use { dom ->
            (dom.documentElement as Element).apply {
                setAttribute("android:visibility", "gone")
                setAttribute("android:layout_height", "0dp")
                removeAttribute("android:paddingBottom")
            }
        }
    }
}

@Suppress("unused")
val popupRemovalPatch = bytecodePatch(
    name = "Remove promotional materials",
    description = "Removes promotional popup dialogs and the join community banner.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(hideCommunityBannerPatch, creditsPatch)

    apply {
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/view/popup/PromotionalDialogFragment;" &&
                name == "initView"
        }.returnEarly()

        addCredit("Remove promotional materials", "PlayDay" to "https://github.com/playday3008")
    }
}
