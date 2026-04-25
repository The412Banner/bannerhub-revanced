package app.revanced.patches.gamehub.steam.input

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.EXTENSION_PREFS
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.network.apiServerSwitchPatch

@Suppress("unused")
val forceSteamInputPatch = bytecodePatch(
    name = "Force Steam Input",
    description =
        "Forces Steam Input to always be enabled when using the EmuReady API, " +
            "preventing the unsupported --disablesteaminput flag from being passed to SteamAgent.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(apiServerSwitchPatch, creditsPatch)

    apply {
        // SteamGameInfo.p() returns field 'r' (steamInputEnable).
        // When false, SteamGameLauncherKt appends " --disablesteaminput" to launch args.
        // The EmuReady API ships an outdated SteamAgent.exe that does not understand this
        // flag, causing launch failures. Delegate to the extension which checks whether
        // EmuReady is active — if so, always returns true; otherwise passes through.
        firstMethod {
            definingClass == $$"Lcom/winemu/openapi/Config$SteamGameInfo;" &&
                name == "p" &&
                returnType == "Z" &&
                parameters.isEmpty()
        }.addInstructions(
            0,
            $$"""
                iget-boolean p0, p0, Lcom/winemu/openapi/Config$SteamGameInfo;->r:Z
                invoke-static {p0}, $${EXTENSION_PREFS}->shouldForceEnableSteamInput(Z)Z
                move-result p0
                return p0
            """,
        )

        addCredit("Force Steam Input", "PlayDay" to "https://github.com/playday3008")
    }
}
