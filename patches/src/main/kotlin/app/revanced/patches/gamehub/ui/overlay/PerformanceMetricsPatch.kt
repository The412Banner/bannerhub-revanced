package app.revanced.patches.gamehub.ui.overlay

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.CONTENT_TYPE_PERF_METRICS
import app.revanced.patches.gamehub.EXTENSION_PERF_METRICS
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.patches.gamehub.misc.settings.addSteamSetting
import app.revanced.patches.gamehub.misc.settings.settingsMenuPatch
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS = EXTENSION_PERF_METRICS

@Suppress("unused")
val performanceMetricsPatch = bytecodePatch(
    name = "Performance metrics display",
    description = "Adds CPU, GPU, and RAM usage metrics to the Performance tab of the game overlay.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, settingsMenuPatch, creditsPatch)

    apply {
        // Inject into SidebarPerformanceFragment.m0(Bundle)V before return-void.
        // Retrieves the performanceFl (FocusableLinearLayout) from the binding and
        // passes it to the extension to append metric TextViews.
        firstMethod {
            definingClass == "Lcom/xj/winemu/sidebar/SidebarPerformanceFragment;" &&
                name == "m0" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == "Landroid/os/Bundle;"
        }.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow {
                opcode == Opcode.RETURN_VOID
            }

            addInstructions(
                returnIndex,
                """
                    invoke-virtual {p0}, Lcom/xj/base/base/fragment/BaseVmFragment;->e0()Landroidx/databinding/ViewDataBinding;
                    move-result-object v0
                    check-cast v0, Lcom/xj/winemu/databinding/WinemuSidebarHubTypeFragmentBinding;
                    iget-object v0, v0, Lcom/xj/winemu/databinding/WinemuSidebarHubTypeFragmentBinding;->performanceFl:Lcom/xj/common/view/focus/focus/view/FocusableLinearLayout;
                    invoke-static {v0}, $EXTENSION_CLASS->initMetrics(Landroid/view/ViewGroup;)V
                """,
            )
        }

        // Register toggle in Steam settings menu.
        addSteamSetting(CONTENT_TYPE_PERF_METRICS, "CONTENT_TYPE_PERF_METRICS")

        addCredit("Performance metrics display", "PlayDay" to "https://github.com/playday3008")
    }
}
