package app.revanced.patches.gamehub.vibration

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// Path inside the staged APK where the native shim must end up. Android's
// dynamic linker reads from `lib/<abi>/` of the installed package; the
// LD_PRELOAD smali inject in VibrationPatch resolves it via
// ApplicationInfo.nativeLibraryDir at runtime, so the location must
// match Android's lib-dir convention exactly.
private const val LIB_PATH = "lib/arm64-v8a/libevshim.so"

// Same name inside the patch bundle's resources. CI's NDK build step
// stages the compiled .so under patches/src/main/resources/<this path>
// before the gradle build packages everything into the .rvp.
private const val RESOURCE_PATH = "lib/arm64-v8a/libevshim.so"

// Sentinel class for classloader access. Referring to vibrationLibPatch
// from inside its own initializer trips Kotlin's recursive type-inference
// because the patch's type is being inferred at the same site.
private object VibrationLibResources

@Suppress("unused")
val vibrationLibPatch = resourcePatch(
    name = "Vibration native shim",
    description = "Copies libevshim.so into lib/arm64-v8a/ of the patched APK. " +
        "The shim is LD_PRELOAD'd into Wine to re-issue SDL_JoystickRumble every " +
        "500ms with a 2s duration so SDL2's 1s rumble_expiration auto-stop never " +
        "fires — required for sustained rumble holds. Silently no-op if the " +
        "shim is missing from the patch bundle (CI may build without it for " +
        "smoke tests); the LD_PRELOAD inject in VibrationPatch is " +
        "File.exists()-guarded so basic rumble still works.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val resourceStream = VibrationLibResources::class.java.classLoader
            ?.getResourceAsStream(RESOURCE_PATH)
            ?: return@apply  // CI build step didn't stage the .so; skip.

        val destination = get(LIB_PATH)
        destination.parentFile?.mkdirs()
        resourceStream.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
