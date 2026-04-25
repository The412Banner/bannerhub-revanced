package app.revanced.patches.gamehub.misc.sound

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.CONTENT_TYPE_MUTE_SOUNDS
import app.revanced.patches.gamehub.EXTENSION_PREFS
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.patches.gamehub.misc.settings.addSteamSetting
import app.revanced.patches.gamehub.misc.settings.settingsMenuPatch

private const val SOUND_HELPER = "Lcom/xj/common/utils/GHSoundPlayHelper;"

// The :play label needs a target instruction; nop serves as the fall-through
// anchor when injected at index 0 (the original first instruction follows it).
private const val MUTE_GUARD =
    """
    invoke-static {}, $EXTENSION_PREFS->isSoundMuted()Z
    move-result v0
    if-eqz v0, :play
    return-void
    :play
    nop
"""

@Suppress("unused")
val muteUiSoundsPatch = bytecodePatch(
    name = "Mute UI sounds",
    description = "Adds a toggle to silence UI click and scroll sound effects.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(sharedGamehubExtensionPatch, settingsMenuPatch, creditsPatch)

    apply {
        addSteamSetting(CONTENT_TYPE_MUTE_SOUNDS, "CONTENT_TYPE_MUTE_SOUNDS")

        // Guard each sound method individually with firstMethod so the patcher
        // properly proxies them as mutable. Methods a-g:
        //   a() = loads sounds into SoundPool (skip on mute to save memory)
        //   b() = play with 300ms throttle (calls c())
        //   c()-g() = individual sound playback via SoundPool.play()
        for (name in listOf("a", "b", "c", "d", "e", "f", "g")) {
            firstMethod {
                definingClass == SOUND_HELPER &&
                    this.name == name &&
                    returnType == "V" &&
                    parameterTypes.isEmpty()
            }.addInstructions(0, MUTE_GUARD)
        }

        addCredit("Mute UI sounds", "PlayDay" to "https://github.com/playday3008")
    }
}
