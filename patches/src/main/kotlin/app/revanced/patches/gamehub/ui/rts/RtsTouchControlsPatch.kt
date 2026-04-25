package app.revanced.patches.gamehub.ui.rts

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.EXTENSION_RTS_HELPER
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.asSequence
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val EXTENSION = EXTENSION_RTS_HELPER

private val rtsLayoutPatch = resourcePatch {
    apply {
        // Add resource IDs to ids.xml (idempotent)
        document("res/values/ids.xml").use { dom ->
            val root = dom.documentElement
            val existingIds = dom
                .getElementsByTagName("item")
                .asSequence()
                .map { (it as Element).getAttribute("name") }
                .toSet()

            for (idName in listOf("switch_rts_touch_controls", "btn_rts_gesture_settings")) {
                if (idName !in existingIds) {
                    dom
                        .createElement("item")
                        .apply {
                            setAttribute("name", idName)
                            setAttribute("type", "id")
                        }.let(root::appendChild)
                }
            }
        }

        // Insert RTS switch + gear button into the sidebar controls layout
        // after the Screen Trackpad switch
        document("res/layout/winemu_sidebar_controls_fragment.xml").use { dom ->
            val allElements = dom.getElementsByTagName("*")
            for (i in 0 until allElements.length) {
                val el = allElements.item(i) as? Element ?: continue
                val id = el.getAttribute("android:id") ?: ""
                if (id.contains("switch_touch_screen_mouse_control")) {
                    val parent = el.parentNode ?: continue

                    // Container row: switch + gear button
                    val container = dom.createElement("LinearLayout").apply {
                        setAttribute("android:orientation", "horizontal")
                        setAttribute("android:layout_width", "fill_parent")
                        setAttribute("android:layout_height", "@dimen/dp_56")
                        setAttribute("android:gravity", "center_vertical")
                    }

                    // RTS toggle switch (fills remaining space)
                    container.appendChild(
                        dom.createElement("com.xj.winemu.view.SidebarSwitchItemView").apply {
                            setAttribute("android:id", "@id/switch_rts_touch_controls")
                            setAttribute("android:layout_width", "0dp")
                            setAttribute("android:layout_height", "@dimen/dp_56")
                            setAttribute("android:layout_weight", "1")
                            setAttribute("app:switch_title", "RTS Touch Controls")
                        },
                    )

                    // Gear button for gesture settings (initially hidden)
                    container.appendChild(
                        dom.createElement("ImageButton").apply {
                            setAttribute("android:id", "@id/btn_rts_gesture_settings")
                            setAttribute("android:layout_width", "@dimen/dp_40")
                            setAttribute("android:layout_height", "@dimen/dp_40")
                            setAttribute("android:layout_marginEnd", "@dimen/dp_8")
                            setAttribute("android:background", "@android:color/transparent")
                            setAttribute("android:src", "@drawable/ic_settings")
                            setAttribute("android:scaleType", "centerInside")
                            setAttribute("android:padding", "8dp")
                            setAttribute("android:contentDescription", "RTS Gesture Settings")
                            setAttribute("android:visibility", "gone")
                        },
                    )

                    // Insert after the touch screen mouse control switch
                    val nextSibling = el.nextSibling
                    if (nextSibling != null) {
                        parent.insertBefore(container, nextSibling)
                    } else {
                        parent.appendChild(container)
                    }
                    break
                }
            }
        }
    }
}

@Suppress("unused")
val rtsTouchControlsPatch = bytecodePatch(
    name = "RTS touch controls",
    description = "Adds configurable touch gesture controls for RTS and strategy games.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, rtsLayoutPatch, creditsPatch)

    apply {
        // 1. Hook WineActivity: inject overlay initialization after InputControlsView is added.
        //
        // Target method: WineActivity.i2(Z)V — contains InputControlsView creation and addView.
        // We find the addView call that adds InputControlsView to btnLayout and inject after it.
        firstMethod {
            definingClass == "Lcom/xj/winemu/WineActivity;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("InputControlsView;-><init>")
                } == true
        }.apply {
            // Find the addView call that adds InputControlsView to the FrameLayout
            val addViewIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference &&
                            it.name == "addView" &&
                            it.definingClass == "Landroid/view/ViewGroup;"
                    } == true
            }

            // At this point: p0=WineActivity, p1=btnLayout(FrameLayout),
            // v0=InputControlsView (loaded from WineActivity.w)
            // We need WinUIBridge from WineActivity.h
            addInstructions(
                addViewIndex + 1,
                """
                    iget-object v1, p0, Lcom/xj/winemu/WineActivity;->h:Lcom/winemu/openapi/WinUIBridge;
                    invoke-static {p0, p1, v1, v0}, $EXTENSION->initOverlay(Landroid/app/Activity;Landroid/view/ViewGroup;Lcom/winemu/openapi/WinUIBridge;Lcom/xj/pcvirtualbtn/inputcontrols/InputControlsView;)V
                """,
            )
        }

        // 2. Hook WineActivity.onDestroy: clean up static references.
        firstMethod {
            definingClass == "Lcom/xj/winemu/WineActivity;" &&
                name == "onDestroy" &&
                returnType == "V"
        }.addInstructions(
            0,
            """
                invoke-static {}, $EXTENSION->cleanup()V
            """,
        )

        // 3. Hook SidebarControlsFragment.m0(Bundle): set up sidebar RTS switch.
        //
        // m0 is the initialization method that sets up all sidebar switches.
        // We find the reference to switchTouchScreenMouseControl and inject after
        // its click listener is set. The extension code finds our views by resource ID.
        firstMethod {
            definingClass == "Lcom/xj/winemu/sidebar/SidebarControlsFragment;" &&
                name == "m0" &&
                parameterTypes == listOf("Landroid/os/Bundle;")
        }.apply {
            val touchScreenSwitchIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IGET_OBJECT &&
                    (this as? ReferenceInstruction)
                        ?.reference
                        ?.toString()
                        ?.contains("switchTouchScreenMouseControl") == true
            }

            // Find the next setClickListener call after that
            val clickListenerIndex = indexOfFirstInstructionOrThrow(touchScreenSwitchIndex) {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "setClickListener"
                    } == true
            }

            // Inject after setClickListener returns: get fragment view, call extension setup
            addInstructions(
                clickListenerIndex + 1,
                """
                    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->getView()Landroid/view/View;
                    move-result-object v0
                    invoke-static {v0}, $EXTENSION->setupSidebarControls(Landroid/view/View;)V
                """,
            )
        }

        addCredit(
            "RTS touch controls",
            "PlayDay" to "https://github.com/playday3008",
            "The412Banner" to "https://github.com/The412Banner",
            "Nightwalker743" to "https://github.com/Nightwalker743",
        )
    }
}
