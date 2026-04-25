package app.revanced.patches.gamehub.ui.statusbar

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.CONTENT_TYPE_CPU_USAGE
import app.revanced.patches.gamehub.EXTENSION_CPU_HELPER
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.patches.gamehub.misc.settings.addSteamSetting
import app.revanced.patches.gamehub.misc.settings.settingsMenuPatch
import app.revanced.util.asSequence
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import org.w3c.dom.Element

private const val EXTENSION_CLASS = EXTENSION_CPU_HELPER

private val cpuLayoutPatch = resourcePatch {
    apply {
        // Add tv_cpu_percent resource ID to ids.xml.
        document("res/values/ids.xml").use { dom ->
            val root = dom.documentElement
            val existingIds = dom
                .getElementsByTagName("item")
                .asSequence()
                .map { (it as Element).getAttribute("name") }
                .toSet()

            if ("tv_cpu_percent" !in existingIds) {
                dom
                    .createElement("item")
                    .apply {
                        setAttribute("name", "tv_cpu_percent")
                        setAttribute("type", "id")
                    }.let(root::appendChild)
            }
        }

        // Insert CPU percentage TextView before the TextClock in each layout.
        val layouts = listOf(
            "res/layout/comm_view_top_bar.xml",
            "res/layout/llauncher_activity_launcher_main.xml",
            "res/layout/llauncher_activity_new_launcher_main.xml",
        )

        for (layoutPath in layouts) {
            try {
                document(layoutPath).use { dom ->
                    val allElements = dom.getElementsByTagName("TextClock")

                    for (i in 0 until allElements.length) {
                        val clockEl = allElements.item(i) as? Element ?: continue
                        val parent = clockEl.parentNode ?: continue
                        val cpuText = createCpuTextView(dom)
                        parent.insertBefore(cpuText, clockEl)
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}

private fun createCpuTextView(dom: org.w3c.dom.Document): Element =
    dom.createElement("TextView").apply {
        setAttribute("android:id", "@+id/tv_cpu_percent")
        setAttribute("android:layout_width", "wrap_content")
        setAttribute("android:layout_height", "wrap_content")
        setAttribute("android:minWidth", "56dp")
        setAttribute("android:text", "")
        setAttribute("android:textSize", "12sp")
        setAttribute("android:textColor", "#ffffffff")
        setAttribute("android:textStyle", "bold")
        setAttribute("android:layout_marginStart", "16dp")
        setAttribute("android:gravity", "center_vertical")
        setAttribute("android:visibility", "gone")
    }

@Suppress("unused")
val cpuDisplayPatch = bytecodePatch(
    name = "CPU usage display",
    description = "Adds a CPU usage percentage text before the clock.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, settingsMenuPatch, cpuLayoutPatch, creditsPatch)

    apply {
        // Hook BatteryUtil.a(Context, ImageView) before RETURN_VOID.
        // This is completely separate from the battery patch which hooks after MOVE_RESULT.
        // We only need p2 (the ImageView) to navigate to tv_cpu_percent in the parent.
        firstMethod {
            definingClass == "Lcom/xj/common/utils/BatteryUtil;" &&
                name == "a" &&
                parameterTypes.size == 2 &&
                parameterTypes[0] == "Landroid/content/Context;" &&
                parameterTypes[1] == "Landroid/widget/ImageView;"
        }.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow {
                opcode == Opcode.RETURN_VOID
            }

            addInstructions(
                returnIndex,
                """
                    invoke-static {p2}, $EXTENSION_CLASS->updateCpuText(Landroid/widget/ImageView;)V
                """,
            )
        }

        // Register toggle in Steam settings menu.
        addSteamSetting(CONTENT_TYPE_CPU_USAGE, "CONTENT_TYPE_CPU_USAGE")

        addCredit("CPU usage display", "PlayDay" to "https://github.com/playday3008")
    }
}
