package app.revanced.patches.gamehub.steamchat

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Bakes the bundled incoming-call ringtones into the APK as
// assets/bh_ringtones/<file>.mp3 so the Steam chat overlay's call-settings
// screen can offer them as built-in ringtone options (alongside the
// code-synthesized tones, a user-picked custom MP3, and Silent).
//
// Source: patches/src/main/resources/ringtones/*.mp3. The extension lists and
// plays them at runtime via the host AssetManager (BhRingtone). MP3 is on
// aapt's default no-compress list, so the assets stay seekable.
// =========================================================================

private const val SRC_DIR = "ringtones"
private const val DEST_DIR = "assets/bh_ringtones"

// Filename → also the menu label is derived from this in BhRingtone.
private val RINGTONES = listOf(
    "basic.mp3",
    "columns_enigma.mp3",
    "doom_classic.mp3",
    "super_mario_brothers.mp3",
    "tututu.mp3",
)

// Sentinel for classloader access — same trick as exploreManifestAssetPatch.
private object RingtoneResources

@Suppress("unused")
val steamChatRingtonesAssetPatch = resourcePatch(
    name = "Steam chat ringtone assets",
    description = "Bundles the built-in incoming-call ringtones " +
        "(assets/bh_ringtones/*.mp3) used by the in-game Steam chat overlay's " +
        "call settings.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val classLoader = RingtoneResources::class.java.classLoader
            ?: error("classloader unavailable for ringtone assets")

        for (file in RINGTONES) {
            classLoader.getResourceAsStream("$SRC_DIR/$file")?.use { input ->
                val dest = get("$DEST_DIR/$file")
                dest.parentFile?.mkdirs()
                dest.outputStream().use { input.copyTo(it) }
            } ?: error("missing $SRC_DIR/$file in patch bundle resources")
        }
    }
}
