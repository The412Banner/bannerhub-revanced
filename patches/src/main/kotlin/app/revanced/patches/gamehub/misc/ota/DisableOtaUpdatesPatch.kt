package app.revanced.patches.gamehub.misc.ota

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// Port of 5.3.5 BannerHub's DisableOtaUpdatesPatch with one adjustment for
// 6.0.4: the URL string lost its trailing slash between versions. 5.3.5 had
// "https://www.xiaoji.com/firmware/update/x1/" (trailing slash);
// 6.0.4 has "https://www.xiaoji.com/firmware/update/x1" (no trailing slash) at
// smali_classes4/ki4.smali:6451 inside method
//     ki4.d(Ljava/lang/String;Ljava/lang/String;ILci3;)Ljava/lang/Object;
// (suspending fn — `ci3` is the Continuation parameter).
//
// Strategy: leave the const-string in place but overwrite the same register
// immediately after, so the HTTP client sees the loopback URL. The OTA call
// then fails silently (connection refused on 127.0.0.1) without altering
// the host code's control flow or try-catch structure.
//
// JieLi cleanup: the 5.3.5 patch also strips libJieLiUsbOta.so and
// libjl_ota_auth.so — both are present in 6.0.4 (1 arch dir each). JieLi is
// the firmware vendor for XiaoJi's hardware gamepad line; these native libs
// are dead weight on a phone install but otherwise harmless. Stripped here
// for parity and to remove a vendor-fingerprint from the APK.
// =============================================================================

private const val OTA_URL_PREFIX = "https://www.xiaoji.com/firmware/update"
private const val LOOPBACK_URL = "http://127.0.0.1"

private val otaCleanupResourcePatch = resourcePatch {
    apply {
        val libDir = get("lib")
        if (libDir.exists() && libDir.isDirectory) {
            libDir.listFiles()?.forEach { archDir ->
                if (archDir.isDirectory) {
                    listOf("libJieLiUsbOta.so", "libjl_ota_auth.so").forEach { lib ->
                        if (archDir.resolve(lib).exists()) delete("lib/${archDir.name}/$lib")
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val disableOtaUpdatesPatch = bytecodePatch(
    name = "Disable OTA updates",
    description = "Neutralises the periodic phone-home check at " +
        "https://www.xiaoji.com/firmware/update/x1 by overwriting the URL " +
        "register with http://127.0.0.1 immediately after the const-string " +
        "load. The OTA call then fails silently with a connection-refused. " +
        "Also strips the JieLi gamepad-firmware native libs (libJieLiUsbOta.so, " +
        "libjl_ota_auth.so) which are dead weight on phone installs.",
) {
    // 6.0.7: the OTA phone-home URL (https://www.xiaoji.com/firmware/update/x1)
    // is GONE entirely — the firmware-update check was removed from the build,
    // so the const-string anchor finds nothing and there is nothing to neutralise
    // (the 6.0.7 build runs fine on-device with this patch UNapplied). Pin
    // compatibility to 6.0.4 → patcher skips it on 6.0.7 (skipped, not a failure).
    // The JieLi-lib cleanup dependency is skipped alongside it (those libs are
    // dead weight at worst; not worth keeping the patch alive). Kept for ≤6.0.4.
    compatibleWith(GAMEHUB_PACKAGE("6.0.4"))
    dependsOn(otaCleanupResourcePatch)

    apply {
        // Anchor structurally: any method containing a const-string load whose
        // value starts with the OTA URL prefix. Survives R8 reshuffles AND the
        // trailing-slash change the URL went through between 5.3.5 and 6.0.4.
        firstMethod {
            implementation?.instructions?.any { ins ->
                (ins as? ReferenceInstruction)?.reference
                    ?.let { it is StringReference && it.string.startsWith(OTA_URL_PREFIX) } == true
            } == true
        }.apply {
            val urlIndex = indexOfFirstInstructionOrThrow {
                (this as? ReferenceInstruction)?.reference
                    ?.let { it is StringReference && it.string.startsWith(OTA_URL_PREFIX) } == true
            }
            val urlReg = getInstruction<OneRegisterInstruction>(urlIndex).registerA
            addInstruction(urlIndex + 1, "const-string v$urlReg, \"$LOOPBACK_URL\"")
        }
    }
}
