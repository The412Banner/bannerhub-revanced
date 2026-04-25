package app.revanced.patches.gamehub.bannerhub

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.asSequence
import org.w3c.dom.Element

private const val BH_PKG = "app.revanced.extension.gamehub"
private const val CONFIG_CHANGES =
    "keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize"

// ─── Phase 1: resource-only changes ───────────────────────────────────────────

private val bannerHubResourcesPatch = resourcePatch {
    apply {
        // Feature 1: rename bottom-nav "My" tab → "My Games"
        document("res/values/strings.xml").use { dom ->
            dom.getElementsByTagName("string").asSequence()
                .map { it as? Element }
                .firstOrNull { it?.getAttribute("name") == "llauncher_main_page_title_my" }
                ?.textContent = "My Games"
        }
    }
}

// ─── Phase 2: extension classes + manifest registration ───────────────────────

private val bannerHubManifestPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getElementsByTagName("application").item(0) as Element

            fun addActivity(name: String, extra: Map<String, String> = emptyMap()) {
                dom.createElement("activity").apply {
                    setAttribute("android:name", "$BH_PKG.$name")
                    setAttribute("android:configChanges", CONFIG_CHANGES)
                    setAttribute("android:screenOrientation", "sensorLandscape")
                    setAttribute("android:exported", "false")
                    extra.forEach { (k, v) -> setAttribute(k, v) }
                    app.appendChild(this)
                }
            }

            fun addPermission(name: String) {
                val existing = dom.getElementsByTagName("uses-permission").asSequence()
                    .map { it as Element }
                    .any { it.getAttribute("android:name") == name }
                if (!existing) {
                    dom.createElement("uses-permission").apply {
                        setAttribute("android:name", name)
                        dom.documentElement.insertBefore(this, app)
                    }
                }
            }

            // GOG activities
            addActivity("GogMainActivity")
            addActivity("GogLoginActivity")
            addActivity("GogGamesActivity")
            addActivity("GogGameDetailActivity")

            // Epic activities
            addActivity("EpicMainActivity")
            addActivity("EpicLoginActivity")
            addActivity("EpicGamesActivity")
            addActivity("EpicGameDetailActivity")
            addActivity("EpicFreeGamesActivity")

            // Amazon activities
            addActivity("AmazonMainActivity")
            addActivity("AmazonLoginActivity")
            addActivity("AmazonGamesActivity")
            addActivity("AmazonGameDetailActivity")

            // BannerHub utility activities
            addActivity("FolderPickerActivity")
            addActivity(
                "BhGameConfigsActivity",
                mapOf("android:windowSoftInputMode" to "adjustResize"),
            )
            addActivity("BhDownloadsActivity")

            // Download service
            dom.createElement("service").apply {
                setAttribute("android:name", "$BH_PKG.BhDownloadService")
                setAttribute("android:foregroundServiceType", "dataSync")
                setAttribute("android:exported", "false")
                app.appendChild(this)
            }

            // Permissions required by BhDownloadService
            addPermission("android.permission.FOREGROUND_SERVICE")
            addPermission("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
            addPermission("android.permission.POST_NOTIFICATIONS")
        }
    }
}

// ─── Main BannerHub patch ──────────────────────────────────────────────────────

@Suppress("unused")
val bannerHubPatch = bytecodePatch(
    name = "BannerHub",
    description = "Ports all BannerHub features to GameHub as a ReVanced patch.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, bannerHubResourcesPatch, bannerHubManifestPatch)
}
