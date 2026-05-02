package app.revanced.patches.gamehub.misc.sound

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import java.io.File

// Compose loads UI sound effects via the Resources API from the unpacked
// APK assets at this exact path; replacing each .wav in place with a silent
// PCM file (preserving filenames) means every Compose lookup resolves and
// plays inaudibly without the lookup-failure fallback path firing.
private const val SOUND_DIR = "assets/composeResources/com.xiaoji.egggame.core/files/sound"
private const val SILENT_RESOURCE = "/sound/silent.wav"

@Suppress("unused")
val muteUiSoundsPatch = resourcePatch(
    name = "Mute UI sounds",
    description = "Replaces every .wav file in the GameHub Compose UI sound asset folder " +
        "with a 50ms silent PCM clip so all click / focus / launch sounds play silently. " +
        "Filenames are preserved so every Compose audio lookup still resolves.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // The patch is a top-level val, not a class, so we can't write
        // ::class.java directly. Anchor on an anonymous object whose runtime
        // class lives in the same patch-bundle classloader as the resource.
        val silentBytes = object {}.javaClass.getResourceAsStream(SILENT_RESOURCE)
            ?.use { it.readBytes() }
            ?: throw PatchException("Bundled silent.wav not found at $SILENT_RESOURCE in patch resources.")

        val soundDir: File = get(SOUND_DIR)
        if (!soundDir.isDirectory) {
            throw PatchException("Expected directory $SOUND_DIR not found in unpacked APK.")
        }

        val wavs = soundDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?: throw PatchException("Failed to list .wav files in $SOUND_DIR.")
        if (wavs.isEmpty()) {
            throw PatchException("No .wav files found in $SOUND_DIR.")
        }

        wavs.forEach { it.writeBytes(silentBytes) }
    }
}
