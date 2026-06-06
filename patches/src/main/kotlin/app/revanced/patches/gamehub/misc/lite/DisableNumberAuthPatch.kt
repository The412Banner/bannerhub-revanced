package app.revanced.patches.gamehub.misc.lite

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// "BannerHub V6 Lite" — Tier 1 / privacy-adjacent.
//
// libpns-2.14.17-LogOnlineStandardCuumRelease_alijtca_plus.so (~0.5 MB,
// arm64-only) is the native core of Alibaba/Aliyun's carrier one-tap phone
// login SDK (com.mobile.auth.gatewayauth.*). It is loaded by exactly one site:
//
//   k7e.smali:60  ->  const-string "pns-2.14.17-...alijtca_plus"
//                     invoke-static System.loadLibrary
//
// k7e.a() is called only from com.mobile.auth.gatewayauth.* (LoginAuthActivity,
// PhoneNumberAuthHelperProxy, and defensively from CheckRoot / EmulatorDetector
// / CheckHook / CheckProxy / AESUtils / ...). Under BannerHub's BypassLogin the
// real phone-auth flow is never completed, so this SDK is dead weight AND an
// identity / anti-tamper fingerprint surface.
//
// gamehub-lite (Producdevity/gamehub-lite) deletes the 5.1.0 sibling
// libpns-2.12.17-...alijtca_plus.so outright and relies on login-bypass making
// the load site unreachable. That is fragile across R8 base bumps. We harden
// it: stub the load site to a no-op FIRST, then delete the .so. With the
// loadLibrary call gone unconditionally, deleting the native lib cannot raise
// UnsatisfiedLinkError no matter what defensive static-init still runs.
//
// Structural anchor (survives R8 letter reshuffles on base bumps): the unique
// method whose body holds a const-string starting with "pns-" AND invokes
// System.loadLibrary. Body is replaced with an immediate return-void.
// =============================================================================

private const val PNS_LIB_NAME_PREFIX = "pns-"
private const val PNS_SO_FILE_PREFIX = "libpns-"
private const val PNS_SO_FILE_MARKER = "alijtca_plus"

private val stripNumberAuthSoPatch = resourcePatch {
    apply {
        val libDir = get("lib")
        if (libDir.exists() && libDir.isDirectory) {
            libDir.listFiles()?.forEach { archDir ->
                if (archDir.isDirectory) {
                    archDir.listFiles()?.forEach { f ->
                        val n = f.name
                        if (n.startsWith(PNS_SO_FILE_PREFIX) &&
                            n.contains(PNS_SO_FILE_MARKER) &&
                            n.endsWith(".so")
                        ) {
                            delete("lib/${archDir.name}/$n")
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val disableNumberAuthPatch = bytecodePatch(
    name = "Disable Aliyun NumberAuth",
    description = "Neutralises the Alibaba/Aliyun carrier one-tap phone-login " +
        "SDK (com.mobile.auth.gatewayauth.*). Stubs the sole System.loadLibrary " +
        "site for libpns-*-alijtca_plus.so to a no-op, then strips the native " +
        "lib. Dead weight under BannerHub's login bypass and an identity / " +
        "anti-tamper fingerprint surface. ~0.5 MB APK reduction.",
    // Ported to gamehub-607-build as privacy hardening (applies by default on the
    // full build; was opt-in `use = false` on the Lite-variant branch).
    use = true,
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(stripNumberAuthSoPatch)

    apply {
        firstMethod {
            val ins = implementation?.instructions ?: return@firstMethod false
            val hasPnsString = ins.any { i ->
                (i as? ReferenceInstruction)?.reference
                    ?.let { it is StringReference && it.string.startsWith(PNS_LIB_NAME_PREFIX) } == true
            }
            val loadsLibrary = ins.any { i ->
                (i as? ReferenceInstruction)?.reference?.toString()
                    ?.contains("Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V") == true
            }
            hasPnsString && loadsLibrary
        }.apply {
            addInstruction(0, "return-void")
        }
    }
}
