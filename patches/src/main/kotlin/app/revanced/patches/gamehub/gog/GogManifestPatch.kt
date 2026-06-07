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
                    // §34/§34a: orientation history — pre9 `sensorLandscape`
                    // (broke explore), pre10 `unspecified` (didn't follow
                    // mode), pre12 `fullSensor` (didn't rotate — wrong target,
                    // we don't want sensor-driven, we want MODE-driven). The
                    // §32b reasoning was wrong: GOG activities launch into
                    // GameHub's SAME task (default taskAffinity = same
                    // package; FLAG_ACTIVITY_NEW_TASK only switches tasks
                    // across affinities), so MainActivity IS the activity
                    // below ours. GameHub's mode toggle programmatically
                    // setRequestedOrientation()s MainActivity to landscape in
                    // handheld and portrait in explore. `behind` inherits
                    // that RUNTIME orientation at launch — so opening the GOG
                    // hub in handheld → landscape, in explore → portrait,
                    // matching the mode the user picked in GameHub. The
                    // `configChanges` above keeps the in-place re-layout
                    // smooth if the mode changes while we're open.
                    setAttribute("android:screenOrientation", "behind")
                }
                app.appendChild(activity)
            }

            // BhDownloadService is an android.app.Service (foreground download
            // worker, startForeground + dataSync). Activities alone are not
            // enough — an unregistered Service silently fails to start, which
            // is why M3 downloads did not begin. Mirror the proven
            // BannerHub-3.7.x registration (foregroundServiceType="dataSync",
            // exported=false).
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

            // FOREGROUND_SERVICE_DATA_SYNC is required to start a `dataSync`
            // foreground service on targetSDK 34+. On GameHub 6.0.4 the base
            // manifest declared it, so BhDownloadService rode on it. 6.0.7
            // swapped its own FGS dataSync→specialUse and DROPPED the
            // FOREGROUND_SERVICE_DATA_SYNC permission (base now only has
            // FOREGROUND_SERVICE_SPECIAL_USE) — so on 6.0.7 starting our
            // download service threw SecurityException and crashed the app at
            // BhDownloadService.onStartCommand. Declare the permission
            // ourselves (idempotent — no-op where the base already has it) so
            // the GOG download FGS is self-sufficient across versions.
            val requiredPerm = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
            val perms = dom.documentElement.getElementsByTagName("uses-permission")
            var permExists = false
            for (i in 0 until perms.length) {
                if ((perms.item(i) as Element).getAttribute("android:name") == requiredPerm) {
                    permExists = true
                    break
                }
            }
            if (!permExists) {
                val perm = dom.createElement("uses-permission").apply {
                    setAttribute("android:name", requiredPerm)
                }
                dom.documentElement.appendChild(perm)
            }
        }
    }
}
