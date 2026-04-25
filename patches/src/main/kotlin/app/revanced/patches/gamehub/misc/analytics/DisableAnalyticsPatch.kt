package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.stability.appNullSafetyPatch
import app.revanced.util.asSequence
import app.revanced.util.returnEarly

/** Native libraries belonging to analytics/crash/tracking SDKs. */
private val ANALYTICS_NATIVE_LIBS = listOf(
    "libumeng-spy.so",
    "libcrashsdk.so",
    "libucrash-core.so",
    "libucrash.so",
    "libumonitor.so",
    "libalicomphonenumberauthsdk_core.so",
)

/** Substrings that identify analytics/tracking components in `android:name`. */
private val ANALYTICS_PATTERNS = listOf("firebase", "umeng", "analytics", "measurement")

/** Manifest permissions used exclusively for ad tracking. */
private val AD_TRACKING_PERMISSIONS = setOf(
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
)

private fun String.isAnalyticsComponent() = ANALYTICS_PATTERNS.any { lowercase().contains(it) }

private val analyticsCleanupResourcePatch = resourcePatch {
    apply {
        // Remove analytics/crash/tracking SDK native libraries.
        val libDir = get("lib")
        if (libDir.exists() && libDir.isDirectory) {
            libDir.listFiles()?.forEach { archDir ->
                if (archDir.isDirectory) {
                    ANALYTICS_NATIVE_LIBS.forEach { lib ->
                        if (archDir.resolve(lib).exists()) delete("lib/${archDir.name}/$lib")
                    }
                }
            }
        }

        // Remove Tencent Open SDK tracking config.
        delete("assets/com.tencent.open.config.json")

        // Remove Firebase resources.
        delete("res/drawable-xxhdpi/firebase_lockup_400.png")
        delete("res/raw/firebase_common_keep.xml")
        delete("res/raw/firebase_crashlytics_keep.xml")

        // Remove analytics components, meta-data, and ad-tracking permissions from the manifest.
        document("AndroidManifest.xml").use { dom ->
            // Application-level components (providers, services, activities, receivers, meta-data).
            listOf("provider", "service", "activity", "receiver", "meta-data")
                .flatMap { tag ->
                    dom
                        .getElementsByTagName(tag)
                        .asSequence()
                        .filter { node ->
                            node.attributes
                                .getNamedItem("android:name")
                                ?.nodeValue
                                ?.isAnalyticsComponent() == true
                        }.toList()
                }.forEach { node ->
                    node.parentNode.removeChild(node)
                }

            // Ad-tracking permissions.
            dom
                .getElementsByTagName("uses-permission")
                .asSequence()
                .filter { node ->
                    node.attributes.getNamedItem("android:name")?.nodeValue in AD_TRACKING_PERMISSIONS
                }.toList()
                .forEach { node ->
                    node.parentNode.removeChild(node)
                }
        }

        // Belt-and-suspenders: set the analytics-deactivated bool to true.
        document("res/values/bools.xml").use { dom ->
            dom
                .getElementsByTagName("bool")
                .asSequence()
                .filter { node ->
                    node.attributes.getNamedItem("name")?.nodeValue == "FIREBASE_ANALYTICS_DEACTIVATED"
                }.forEach { node ->
                    node.textContent = "true"
                }
        }
    }
}

@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = "Disable analytics",
    description = "Disables Umeng, Firebase, and Jiguang analytics, removes tracking components " +
        "from the manifest, strips ad-tracking permissions, and deletes analytics/crash native libraries.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(analyticsCleanupResourcePatch, appNullSafetyPatch, creditsPatch)

    apply {
        // Umeng — disable wrapper methods in app code.
        firstMethod { definingClass == "Lcom/xj/umeng/UmengApp;" && name == "b" }.returnEarly()
        firstMethod { definingClass == "Lcom/xj/umeng/UmengApp;" && name == "a" }.returnEarly()
        firstMethod { definingClass == "Lcom/xj/umeng/service/IUmengServiceImpl;" && name == "a" }.returnEarly()
        firstMethod { definingClass == "Lcom/xj/umeng/service/IUmengServiceImpl;" && name == "b" }.returnEarly()
        firstMethod { definingClass == "Lcom/xj/umeng/service/IUmengServiceImpl;" && name == "c" }.returnEarly()
        firstMethod { definingClass == "Lcom/xj/umeng/service/IUmengServiceImpl;" && name == "d" }.returnEarly()
        firstMethod { definingClass == "Lcom/xj/umeng/service/IUmengServiceImpl;" && name == "e" }.returnEarly()
        firstMethod { definingClass == "Lcom/xj/umeng/service/IUmengServiceImpl;" && name == "f" }.returnEarly()
        firstMethod {
            definingClass == "Lcom/xj/umeng/service/IUmengServiceImpl;" &&
                name == "onEvent" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == "Ljava/lang/String;"
        }.returnEarly()

        // Remove analytics/tracking SDK class trees from DEX.
        classDefs.removeIf { classDef ->
            val type = classDef.type
            type.startsWith("Lcom/umeng/") ||
                type.startsWith("Lcn/jiguang/") ||
                type.startsWith("Lcom/google/firebase/") ||
                type.startsWith("Lcom/uc/crashsdk/")
        }

        addCredit(
            "Disable analytics",
            "PlayDay" to "https://github.com/playday3008",
            "Producdevity" to "https://github.com/Producdevity/gamehub-lite",
        )
    }
}
