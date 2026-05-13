package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

// =============================================================================
// Strengthens DisableFirebaseAnalyticsPatch — that patch disables Google's
// Ads ID / Android-SSAID *collection* via Firebase manifest meta-data, but the
// <uses-permission> declarations themselves stay in the manifest. Privacy
// scanners like Exodus Privacy flag apps that *declare* AD_ID even if they
// don't actually collect it, and an OS-level permission audit reports them
// as "wants ad-tracking identifier". This patch removes the declarations
// outright so the app no longer carries the trackers-permission fingerprint.
//
// Subset port of 5.3.5 BannerHub's DisableAnalyticsPatch — the rest of that
// patch stripped Umeng / Alibaba-crash / Alibaba-phone-auth native libs and
// matching manifest components, all of which are gone in 6.0.4 (XiaoJi
// swapped analytics backends during the KMP rewrite).
// =============================================================================

private val adIdPermissions = setOf(
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
)

@Suppress("unused")
val stripAdIdPermissionsPatch = resourcePatch(
    name = "Strip Ad-ID permissions",
    description = "Removes the three <uses-permission> declarations that mark the app " +
        "as requesting Google's advertising-ID, AdServices attribution, and AdServices " +
        "ad-ID. Strengthens 'Disable Firebase Analytics': that patch disables runtime " +
        "collection via Firebase's manifest kill-switch, this one removes the declared " +
        "permission fingerprint that privacy scanners (Exodus Privacy etc.) flag.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val manifest = dom.getNode("manifest") as Element

            // Collect first, then remove — modifying a live NodeList while iterating
            // skips elements. Matches the pattern used in DisableMobPushPatch for
            // <meta-data> removal.
            val perms = manifest.getElementsByTagName("uses-permission")
            val toRemove = mutableListOf<Element>()
            for (i in 0 until perms.length) {
                val node = perms.item(i) as Element
                if (node.getAttribute("android:name") in adIdPermissions) {
                    toRemove.add(node)
                }
            }
            toRemove.forEach { manifest.removeChild(it) }
        }
    }
}
