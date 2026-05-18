package app.revanced.patches.gamehub.legacygles2

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.addNativeMethod
import app.revanced.util.redirectVirtualCalls
import java.io.File

// =========================================================================
// THROWAWAY GO/NO-GO — test2 (tier-1 shim). NOT shippable.
//
// test1 result: swapping in the 6.0.2 libxserver.so + libwinemu.so made the
// 6.0.2 libxserver's JNI_OnLoad RegisterNatives abort on the very first
// missing method — `Lcom/winemu/core/server/XServer;.setRenderingEnabled(Z)V`
// (6.0.4 renamed it `setFlipEnabled`). Static enumeration showed 10/11
// libxserver natives already match 6.0.4 exactly; this is the only gap.
//
// test2 = test1's lib swap + ONE added smali native decl
// `setRenderingEnabled(Z)V` on XServer so RegisterNatives can complete.
// Question this answers on-device: does the app now get PAST the JNI abort,
// and if so what does it do next (render? next missing symbol? black via
// the deleted DirectRendering orchestration?). Still no toggle/UI; still
// always-on; still throwaway (revert after).
//
// 6.0.2 libs md5: libxserver e8eb894825da66cca0fc59b242ac0ad5,
// libwinemu 407f274d998335dbce03b2074a187e9f.
// =========================================================================

private const val XSERVER = "Lcom/winemu/core/server/XServer;"
private const val RES_DIR = "/legacygles2"
private const val ABI_DIR = "lib/arm64-v8a"
private val LIBS = listOf("libxserver.so", "libwinemu.so")

@Suppress("unused")
val legacyGles2XServerShimPatch = bytecodePatch(
    name = "Legacy GLES2 XServer JNI shim (THROWAWAY)",
    description = "Adds native setRenderingEnabled(Z)V to " +
        "com.winemu.core.server.XServer so the 6.0.2 libxserver's " +
        "JNI_OnLoad RegisterNatives resolves. Diagnostic only.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        addNativeMethod(XSERVER, "setRenderingEnabled", listOf("Z"), "V")
    }
}

@Suppress("unused")
val legacyGles2SetFlipRedirectPatch = bytecodePatch(
    name = "Legacy GLES2 setFlipEnabled redirect (THROWAWAY)",
    description = "Redirects 6.0.4's XServer.setFlipEnabled(Z)V call sites to " +
        "the 6.0.2-named setRenderingEnabled(Z)V (same fn) the swapped " +
        "libxserver actually binds. Diagnostic only.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        redirectVirtualCalls(XSERVER, "setFlipEnabled", "setRenderingEnabled", "(Z)V")
    }
}

@Suppress("unused")
val legacyGles2LibSwapPatch = resourcePatch(
    name = "Legacy GLES2 lib swap (THROWAWAY)",
    description = "Overwrites the 6.0.4 libxserver.so + libwinemu.so with " +
        "the 6.0.2 GLES2-era pair, always-on. Diagnostic only; do not ship.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(legacyGles2XServerShimPatch, legacyGles2SetFlipRedirectPatch)

    apply {
        LIBS.forEach { lib ->
            val bundled = object {}.javaClass.getResourceAsStream("$RES_DIR/$lib")
                ?.use { it.readBytes() }
                ?: throw PatchException(
                    "Bundled 6.0.2 $lib not found at $RES_DIR/$lib in patch resources.",
                )

            val target: File = get("$ABI_DIR/$lib")
            if (!target.isFile) {
                throw PatchException(
                    "Expected $ABI_DIR/$lib not found in unpacked APK — cannot swap.",
                )
            }
            target.writeBytes(bundled)
        }
    }
}
