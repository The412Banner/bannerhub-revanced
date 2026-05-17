package app.revanced.patches.gamehub.legacygles2

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import java.io.File

// =========================================================================
// THROWAWAY GO/NO-GO LOAD TEST — NOT a shippable feature.
//
// 6.0.4 rewrote libxserver.so (GLES2+EGL -> Vulkan) and shrank libwinemu.so
// (the ASurfaceTransaction plane compositor was deleted). This patch force-
// overwrites BOTH 6.0.4 native libs with the 6.0.2 (GLES2-era) pair, with
// NO toggle, NO pref, NO UI and NO DirectRendering smali stubs. It exists
// only to answer one question on-device: does the 6.0.2 libxserver+libwinemu
// pair even load and render anything under the 6.0.4 Kotlin runtime, or does
// it hard-crash on JNI symbol/package drift (6.0.2 XServer was
// com.winemu.ui.XServer; 6.0.4 is com.winemu.core.server.XServer + the
// deleted DirectRendering callbacks)?
//
// Expected outcomes:
//   - UnsatisfiedLinkError / GetMethodID-failed on launch  -> the real
//     feature needs JNI shim + restored DirectRendering stubs (scope it).
//   - Launches but every game black-screens               -> lib-pair
//     coupling broken; legacy-toggle idea likely dead.
//   - A game actually renders                              -> the 6.0.2
//     pair is viable on 6.0.4; proceed to the real toggle design.
//
// 6.0.2 libs (md5, verified): libxserver e8eb894825da66cca0fc59b242ac0ad5,
// libwinemu 407f274d998335dbce03b2074a187e9f.
// =========================================================================

private const val RES_DIR   = "/legacygles2"
private const val ABI_DIR   = "lib/arm64-v8a"
private val LIBS = listOf("libxserver.so", "libwinemu.so")

@Suppress("unused")
val legacyGles2RendererTestPatch = resourcePatch(
    name = "Legacy GLES2 renderer (THROWAWAY load test)",
    description = "Diagnostic only: replaces the 6.0.4 libxserver.so + " +
        "libwinemu.so with the 6.0.2 GLES2-era pair, always-on, no toggle. " +
        "Used to learn whether the old renderer pair survives on the 6.0.4 " +
        "runtime. Do not ship.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

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
