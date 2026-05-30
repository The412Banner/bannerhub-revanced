package app.revanced.patches.gamehub.explore

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

// Registers the BannerHub-owned Explore screen (BannerExploreActivity), opened
// when ExploreTabHijackPatch intercepts the bottom-nav "Explore" tap. Internal
// (exported=false) — reached only by our injected hijack hook / card handlers.
//
// Orientation = "behind" for the same reason as the GOG activities
// (GogManifestPatch §34/§34a): GameHub programmatically sets MainActivity's
// orientation per mode (landscape=handheld, portrait=explore); launching into
// the same task, "behind" inherits that runtime orientation so the screen
// matches the mode the user is in. configChanges keeps re-layout smooth if the
// mode flips while we're open.

private const val PKG = "app.revanced.extension.gamehub.explore"

@Suppress("unused")
val exploreManifestPatch = resourcePatch(
    name = "Explore screen activity",
    description = "Registers the BannerHub-owned Explore screen " +
        "(BannerExploreActivity), shown when the Explore bottom-nav tab is " +
        "tapped. Internal activity; content comes from a bundled manifest and " +
        "cards route to BannerHub's own handlers (GOG, etc.).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element

            // Both Explore screens: the homepage and the article/detail page.
            val names = listOf(
                "$PKG.BannerExploreActivity",
                "$PKG.BannerExploreArticleActivity",
            )

            val existing = app.getElementsByTagName("activity")
            val present = HashSet<String>()
            for (i in 0 until existing.length) {
                present.add((existing.item(i) as Element).getAttribute("android:name"))
            }

            for (name in names) {
                if (present.contains(name)) continue // idempotent
                val activity = dom.createElement("activity").apply {
                    setAttribute("android:name", name)
                    setAttribute("android:exported", "false")
                    setAttribute("android:theme", "@android:style/Theme.Black.NoTitleBar")
                    setAttribute("android:configChanges", "orientation|screenSize|keyboardHidden")
                    setAttribute("android:screenOrientation", "behind")
                }
                app.appendChild(activity)
            }
        }
    }
}
