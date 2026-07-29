package app.revanced.patches.gamehub.pcengine

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// pcengine signature bypass — GATE 3 of 3 (ComboLite InstallerManager, in the
// :pcengine host process).
//
// Discovered on-device 2026-07-29: after GATE 1 (framework ValidationStrategy)
// and GATE 2 (pcengine manager's own check) let the plugin download + verify,
// the actual install — dispatched to PcEnginePluginHostService running in the
// :pcengine process — still failed with:
//
//   java.lang.IllegalArgumentException: Plugin signatures do not exactly match
//     host signatures
//     at e43.q -> ivi.D0 -> ivi.H0 -> ivi.C -> PcEnginePluginHostService
//
// This is a THIRD, independent signature check inside ComboLite's obfuscated
// InstallerManager (`ivi`). Unlike GATE 1 it is HARD-CODED — it is NOT gated by
// ValidationStrategy, so forcing Insecure does not reach it. It runs at commit
// time in the plugin host process.
//
// The throwing method — `ivi.D0(Application, PackageInfo, rti)V` — is a pure
// `void` install validator: it checks, in order, plugin package id, metadata
// presence, SCHEMA_VERSION, ABI, host signatures readable, plugin signatures
// readable, and finally host-sig == plugin-sig, calling e43.q(<message>) to
// throw on any failure and returning void on success. For a genuine XiaoJi
// plugin every check passes EXCEPT the signature match (host is re-signed with
// our key). Forcing the method to `return-void` at its head skips the whole
// validator — safe, because the other checks (schema/abi/package) already pass
// for the genuine artifact and only exist to reject a tampered one.
//
// Anchored on the unique, non-localized English string it throws, so it targets
// exactly ivi.D0 regardless of the obfuscated class/method letters. Verified
// globally unique on 6.1.0.
// =============================================================================

private const val INSTALL_MISMATCH_ANCHOR =
    "Plugin signatures do not exactly match host signatures"

@Suppress("unused")
val pcEnginePluginInstallVerifyPatch = bytecodePatch(
    name = "PC engine plugin — bypass install-time signature check",
    description = "Neutralizes ComboLite's install-time host-vs-plugin signature enforcement in the " +
        ":pcengine host process (the IllegalArgumentException 'Plugin signatures do not exactly match " +
        "host signatures'). This is a third check, hard-coded and independent of ValidationStrategy, " +
        "that runs when the plugin is committed. Forces the void install-validator method to return " +
        "immediately so a re-signed BannerHub host can install XiaoJi's genuine pcengine plugin on " +
        "GameHub 6.1.0+. Anchored on the unique error string.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod {
            returnType == "V" &&
                implementation?.instructions?.any { instruction ->
                    (instruction as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == INSTALL_MISMATCH_ANCHOR } == true
                } == true
        }.addInstructions(0, "return-void")
    }
}
