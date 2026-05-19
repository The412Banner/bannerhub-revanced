package app.revanced.patches.gamehub.gog

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

// PHASE 1 (GOG_LIBRARY_TAB_DESIGN §20/§21) — register the ported BannerHub-3.7.x
// GOG activities so login + library + download can be exercised standalone.
//
// GogMainActivity is the hub (login card / library card). It is exported in
// Phase 1 ONLY as the temporary dev/validation entry point:
//   adb shell am start -n <pkg>/app.revanced.extension.gamehub.gog.GogMainActivity
// The production entry (a "GOG" row on the Profile screen) is deferred WS4/P-A
// (§16/§20); when that lands, GogMainActivity should be flipped to exported=false
// and launched via the in-app injected row instead.
//
// All other activities are internal (exported=false) — reached only by explicit
// Intent from within the GOG flow.

private const val PKG = "app.revanced.extension.gamehub.gog"

// name -> exported. Order is cosmetic. WebView OAuth, library, detail, the
// shared downloads screen, and the folder picker are all internal.
private val ACTIVITIES = listOf(
    "$PKG.GogMainActivity" to true,   // TEMP dev entry (Phase 1 only) — see note above
    "$PKG.GogLoginActivity" to false, // WebView GOG OAuth
    "$PKG.GogGamesActivity" to false, // owned-library list
    "$PKG.GogGameDetailActivity" to false,
    "$PKG.BhDownloadsActivity" to false, // shared download manager screen
    "$PKG.FolderPickerActivity" to false, // install-location picker
)

@Suppress("unused")
val gogManifestPatch = resourcePatch(
    name = "GOG activities (Phase 1)",
    description = "Registers the ported BannerHub-3.7.x GOG activities (login / " +
        "library / detail / downloads / folder-picker). Phase 1 = standalone login + " +
        "owned-library + download validation; the GameHub-library/launch bridge and " +
        "the production Profile-screen entry row are deferred to Phase 2. " +
        "GogMainActivity is exported in Phase 1 only as the temporary adb dev entry.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element

            // Collect already-registered activity names (idempotent re-runs).
            val existing = HashSet<String>()
            val nodes = app.getElementsByTagName("activity")
            for (i in 0 until nodes.length) {
                existing.add((nodes.item(i) as Element).getAttribute("android:name"))
            }

            for ((name, exported) in ACTIVITIES) {
                if (name in existing) continue
                val activity = dom.createElement("activity").apply {
                    setAttribute("android:name", name)
                    setAttribute("android:exported", if (exported) "true" else "false")
                    setAttribute("android:theme", "@android:style/Theme.Black.NoTitleBar")
                    setAttribute(
                        "android:configChanges",
                        "orientation|screenSize|keyboardHidden",
                    )
                    // §34: orientation history — pre9 forced `sensorLandscape`
                    // (broke explore/portrait), pre10 reverted to unspecified
                    // (didn't actively follow the mode). The user wants the
                    // GOG screens to AUTO-ROTATE to fit whichever mode they're
                    // in (handheld=landscape, explore=portrait). `fullSensor`
                    // = free sensor-driven rotation through all 4 orientations,
                    // ignoring the OS auto-rotate lock so it reliably matches
                    // however the device is physically held in each mode. The
                    // `configChanges` above keeps the activity from recreating
                    // on each rotation (smooth in-place re-layout).
                    setAttribute("android:screenOrientation", "fullSensor")
                }
                app.appendChild(activity)
            }

            // BhDownloadService is an android.app.Service (foreground download
            // worker, startForeground + dataSync). Activities alone are not
            // enough — an unregistered Service silently fails to start, which
            // is why M3 downloads did not begin. Mirror the proven
            // BannerHub-3.7.x registration (foregroundServiceType="dataSync",
            // exported=false). Required perms (INTERNET, FOREGROUND_SERVICE,
            // POST_NOTIFICATIONS, FOREGROUND_SERVICE_DATA_SYNC) are already
            // declared by the base GameHub 6.0.4 manifest — no perm patch.
            val serviceName = "$PKG.BhDownloadService"
            val services = app.getElementsByTagName("service")
            var serviceExists = false
            for (i in 0 until services.length) {
                if ((services.item(i) as Element).getAttribute("android:name") == serviceName) {
                    serviceExists = true
                    break
                }
            }
            if (!serviceExists) {
                val service = dom.createElement("service").apply {
                    setAttribute("android:name", serviceName)
                    setAttribute("android:exported", "false")
                    setAttribute("android:foregroundServiceType", "dataSync")
                }
                app.appendChild(service)
            }
        }
    }
}
