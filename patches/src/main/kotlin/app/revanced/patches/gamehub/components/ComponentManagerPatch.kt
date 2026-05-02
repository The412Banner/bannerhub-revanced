package app.revanced.patches.gamehub.components

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.asSequence
import app.revanced.util.getNode

private const val MANAGER_CLASS =
    "app.revanced.extension.gamehub.components.ComponentManagerActivity"
private const val DOWNLOAD_CLASS =
    "app.revanced.extension.gamehub.components.ComponentDownloadActivity"
private const val LAUNCHER_ALIAS_NAME =
    "app.revanced.extension.gamehub.components.ComponentManagerLauncher"

@Suppress("unused")
val componentManagerPatch = resourcePatch(
    name = "Component manager",
    description = "Registers the BannerHub Component Manager + Download activities in " +
        "the AndroidManifest and adds a launcher-icon activity-alias so the manager is " +
        "reachable from the device home screen as a separate launcher entry.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        document("AndroidManifest.xml").use { dom ->
            val applicationNode = dom.getNode("application")

            // Idempotency guard — skip if already registered.
            val existingActivities = dom.getElementsByTagName("activity").asSequence()
            if (existingActivities.any {
                    it.attributes.getNamedItem("android:name")?.nodeValue == MANAGER_CLASS
                }
            ) {
                return@apply
            }

            // Register ComponentManagerActivity.
            dom
                .createElement("activity")
                .apply {
                    setAttribute("android:name", MANAGER_CLASS)
                    setAttribute("android:exported", "true")
                    setAttribute("android:label", "Component Manager")
                    setAttribute(
                        "android:configChanges",
                        "keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize",
                    )
                    setAttribute("android:theme", "@style/Theme.AppCompat.NoActionBar")
                }.let(applicationNode::appendChild)

            // Register ComponentDownloadActivity (launched from the manager's
            // "Add custom component" button — not externally exported).
            dom
                .createElement("activity")
                .apply {
                    setAttribute("android:name", DOWNLOAD_CLASS)
                    setAttribute("android:exported", "false")
                    setAttribute("android:label", "Add component")
                    setAttribute(
                        "android:configChanges",
                        "keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize",
                    )
                    setAttribute("android:theme", "@style/Theme.AppCompat.NoActionBar")
                }.let(applicationNode::appendChild)

            // Launcher icon — activity-alias with MAIN/LAUNCHER intent filter so
            // the Component Manager appears as its own launcher entry on the
            // device home screen, distinct from the main GameHub icon.
            dom
                .createElement("activity-alias")
                .apply {
                    setAttribute("android:name", LAUNCHER_ALIAS_NAME)
                    setAttribute("android:targetActivity", MANAGER_CLASS)
                    setAttribute("android:exported", "true")
                    setAttribute("android:label", "BH Components")
                    setAttribute("android:enabled", "true")

                    dom
                        .createElement("intent-filter")
                        .apply {
                            dom
                                .createElement("action")
                                .apply {
                                    setAttribute("android:name", "android.intent.action.MAIN")
                                }.let(this::appendChild)
                            dom
                                .createElement("category")
                                .apply {
                                    setAttribute(
                                        "android:name",
                                        "android.intent.category.LAUNCHER",
                                    )
                                }.let(this::appendChild)
                        }.let(this::appendChild)
                }.let(applicationNode::appendChild)
        }
    }
}
