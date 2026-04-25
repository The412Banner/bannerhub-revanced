package app.revanced.patches.gamehub.misc.cleanup

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.util.asSequence
import app.revanced.util.getNode
import org.w3c.dom.Element
import org.w3c.dom.Node

private val manifestCleanupResourcePatch = resourcePatch {
    apply {
        // Remove unused assets.
        // delete("assets/NotoColorEmojiCompat.ttf")
        // delete("assets/auth_intro_timberline.webm")
        // delete("assets/auth_loop_timberline.webm")
        // delete("assets/better-xcloud.user.js")
        // delete("assets/splash_video.mp4")
        delete("assets/libwbsafeedit")
        delete("assets/libwbsafeedit_arm64-v8a")
        delete("assets/libwbsafeedit_armeabi")
        delete("assets/libwbsafeedit_armeabi-v7a")
        // delete("assets/h5_qr_back.png")

        document("AndroidManifest.xml").use { dom ->
            fun removeByName(
                vararg tags: String,
                predicate: (String) -> Boolean,
            ) {
                tags
                    .flatMap { tag ->
                        dom
                            .getElementsByTagName(tag)
                            .asSequence()
                            .filter { predicate(it.attributes.getNamedItem("android:name")?.nodeValue ?: "") }
                            .toList()
                    }.forEach { it.parentNode?.removeChild(it) }
            }

            // Remove unnecessary permissions.
            // Note: ad-tracking permissions (AD_ID, ADSERVICES) are handled by the analytics patch.
            // Note: notification permissions (POST_NOTIFICATIONS, NOTIFICATION_SERVICE) are handled
            // by the push patch.
            removeByName("uses-permission", "uses-permission-sdk-23") {
                it in setOf(
                    "android.permission.REQUEST_INSTALL_PACKAGES",
                    // Apps/Packages querying are used for launching other apps/games from GameHub
                    // "android.permission.REQUEST_INSTALL_PACKAGES",
                    // "android.permission.GET_INSTALLED_APPS",
                    // "android.permission.QUERY_ALL_PACKAGES",
                    // Keep ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION, and BLUETOOTH_ADVERTISE:
                    // needed by RequestBlePermissionDialogFragment for controller pairing via BLE.
                    // "android.permission.ACCESS_COARSE_LOCATION",
                    // "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_BACKGROUND_LOCATION",
                    "android.permission.READ_CONTACTS",
                    "android.permission.READ_PHONE_STATE",
                    "android.permission.BROADCAST_STICKY",
                    "android.permission.RECEIVE_BOOT_COMPLETED",
                    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                    "com.android.providers.tv.permission.READ_EPG_DATA",
                    "com.android.providers.tv.permission.WRITE_EPG_DATA",
                    "com.android.launcher.permission.INSTALL_SHORTCUT",
                    "com.android.launcher.permission.UNINSTALL_SHORTCUT",
                    "com.android.launcher.permission.READ_SETTINGS",
                    "com.android.launcher.permission.WRITE_SETTINGS",
                    // Keep CAMERA + FLASHLIGHT: needed by PcStreamQRCodeScanActivity for QR code scanning.
                    // "android.permission.CAMERA",
                    // "android.permission.FLASHLIGHT",
                    "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
                    "com.google.android.providers.gsf.permission.READ_GSERVICES",
                    "android.permission.EXPAND_STATUS_BAR",
                )
            }

            // Remove payment, auth, and unused components.
            // Note: firebase/umeng/analytics/measurement components are handled by the analytics patch.
            // Note: JPush/Jiguang components are handled by the push patch.
            removeByName("activity", "service", "receiver", "provider") {
                it.startsWith("com.tencent.") ||
                    it.startsWith("com.alipay.") ||
                    it.startsWith("com.google.android.gms.auth") ||
                    it.startsWith("com.google.android.gms.cast") ||
                    it.startsWith("com.google.android.datatransport") ||
                    it.startsWith("com.mobile.auth.gatewayauth") ||
                    it.startsWith("androidx.profileinstaller") ||
                    // Keep androidx.camera.core: needed by QR code scanner (CameraX).
                    // it.startsWith("androidx.camera.core") ||
                    it.contains("WXEntryActivity") ||
                    it.contains("WXPayEntryActivity") ||
                    // Luck Picture library location foreground service
                    it == "com.luck.picture.lib.service.ForegroundService"
            }

            // Remove query package/intent elements for Tencent, Alipay, tbopen
            dom.getElementsByTagName("queries").asSequence().forEach { queriesNode ->
                queriesNode.childNodes
                    .asSequence()
                    .filter { it.nodeType == Node.ELEMENT_NODE }
                    .filter { child ->
                        val el = child as Element
                        // <package android:name="...">
                        val pkgName = el.attributes.getNamedItem("android:name")?.nodeValue ?: ""
                        if (pkgName.startsWith("com.tencent.") ||
                            pkgName.startsWith("com.eg.android.")
                        ) {
                            return@filter true
                        }
                        // <intent> with <data android:scheme="tbopen">
                        val dataNodes = el.getElementsByTagName("data")
                        for (i in 0 until dataNodes.length) {
                            if (dataNodes
                                    .item(i)
                                    .attributes
                                    ?.getNamedItem("android:scheme")
                                    ?.nodeValue ==
                                "tbopen"
                            ) {
                                return@filter true
                            }
                        }
                        false
                    }.toList()
                    .forEach { it.parentNode?.removeChild(it) }
            }

            // Remove unnecessary meta-data
            removeByName("meta-data") {
                it == "com.google.android.gms.version" ||
                    it == "android.adservices.AD_SERVICES_CONFIG" ||
                    it.startsWith("com.sec.android.") ||
                    it.startsWith("com.android.graphics.") ||
                    // Keep android.game_mode_config — needed for Game Mode API / Game Booster.
                    it == "com.alipay.sdk.appId"
            }

            // Remove Samsung multiwindow library
            removeByName("uses-library") {
                it == "com.sec.android.app.multiwindow"
            }

            // Add hardwareAccelerated and game category to <application>.
            // appCategory="game" makes Samsung Game Booster recognize the app,
            // enabling performance mode and memory priority for Wine processes.
            val appNode = dom.getNode("application") as Element
            appNode.setAttribute("android:hardwareAccelerated", "true")
            appNode.setAttribute("android:appCategory", "game")

            // Configure specific activities
            dom
                .getElementsByTagName("activity")
                .asSequence()
                .map { it as Element }
                .filter { it.getAttribute("android:name").contains("WineActivity") }
                .forEach { activity ->
                    activity.setAttribute("android:preferMinimalPostProcessing", "true")
                    activity.setAttribute("android:enableOnBackInvokedCallback", "false")
                    activity.setAttribute("android:resizeableActivity", "false")
                    // Add NVIDIA meta-data
                    dom
                        .createElement("meta-data")
                        .apply {
                            setAttribute("android:name", "com.nvidia.immediateInput")
                            setAttribute("android:value", "true")
                        }.let(activity::appendChild)
                    dom
                        .createElement("meta-data")
                        .apply {
                            setAttribute("android:name", "com.nvidia.rawCursorInput")
                            setAttribute("android:value", "true")
                        }.let(activity::appendChild)
                }

            // Remove foregroundServiceType from specific services
            dom
                .getElementsByTagName("service")
                .asSequence()
                .map { it as Element }
                .filter { service ->
                    val name = service.getAttribute("android:name")
                    name.contains("DeviceManagementService") ||
                        name.contains("MappingService") ||
                        name.contains("SteamService")
                }.forEach { it.removeAttribute("android:foregroundServiceType") }
        }

        // Enable performance game mode so Android's Game Mode API (and Samsung Game Booster)
        // can prioritise CPU/GPU/memory for the app.
        document("res/xml/game_mode_config.xml").use { dom ->
            val root = dom.documentElement as Element
            root.setAttribute("android:supportsPerformanceGameMode", "true")
            root.setAttribute("android:supportsBatteryGameMode", "false")
            root.setAttribute("android:allowGameDownscaling", "true")
            root.setAttribute("android:allowGameFpsOverride", "true")
        }
    }
}

@Suppress("unused")
val manifestCleanupPatch = bytecodePatch(
    name = "Manifest cleanup",
    description = "Removes unnecessary permissions, payment SDKs, unused components from the manifest, " +
        "dead SDK class trees from DEX, and unused assets.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(manifestCleanupResourcePatch, creditsPatch)

    apply {
        // Remove payment/auth/unused SDK class trees from DEX.
        // Keep com/tencent/mmkv — MMKV is used by App.onCreate for key-value storage.
        classDefs.removeIf { classDef ->
            val type = classDef.type
            (
                type.startsWith("Lcom/tencent/") &&
                    !type.startsWith("Lcom/tencent/mmkv/") &&
                    !type.startsWith("Lcom/tencent/vasdolly/") &&
                    !type.startsWith("Lcom/tencent/tpgbox/")
            ) ||
                type.startsWith("Lcom/alipay/") ||
                type.startsWith("Lcom/mobile/auth/")
        }

        addCredit("Manifest cleanup", "PlayDay" to "https://github.com/playday3008")
    }
}
