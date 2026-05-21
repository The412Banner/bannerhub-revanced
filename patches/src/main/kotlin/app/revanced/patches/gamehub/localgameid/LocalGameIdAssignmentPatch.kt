package app.revanced.patches.gamehub.localgameid

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// =============================================================================
// Local-game-id assignment patch.
//
// Hooks BaseAndroidApp.onCreate to kick off a one-shot scan of
// db_game_library.db that rewrites every row with `server_game_id = -1` to a
// deterministic synthetic integer in [0x40000000, 0x7FFFFFFF] derived from
// the row's stable `id` (local_*) TEXT column. After the scan, previously
// "-1" PC-imported games become individually addressable from external
// launchers (Beacon / ES-DE / Daijishou) which key on server_game_id via the
// existing ExternalLauncher patch.
//
// Hook target:
//   Lcom/xiaoji/egggame/BaseAndroidApp;->onCreate()V
//
// Stability:
//   The BaseAndroidApp class name is fully qualified, non-mangled, and is
//   GameHub's Application subclass — confirmed stable across the 5.x→6.x
//   line by DisableMobPushPatch which anchors the same class structurally.
//
// Inject position:
//   Index 0 (top of onCreate). The scanner pushes work to a daemon thread
//   immediately, so it never blocks Application init. We pass `p0` (the
//   Application "this" reference, a Context) directly to scanAndAssign.
//
// Idempotence:
//   The extension's `started` flag suppresses re-entry within the same
//   process, and the SELECT only matches `server_game_id = -1`, so a row
//   already in the synthetic range stays put.
//
// Safety:
//   The scanner is self-contained: any throwable inside is caught and
//   logged, never propagated. Application.onCreate will return normally
//   regardless of DB state. If db_game_library.db is missing (fresh
//   install with no library yet), the scan no-ops.
// =============================================================================

private const val BASE_ANDROID_APP_SMALI = "Lcom/xiaoji/egggame/BaseAndroidApp;"

@Suppress("unused")
val localGameIdAssignmentPatch = bytecodePatch(
    name = "Local game-id assignment",
    description = "On app start, scans GameHub's library DB for PC-imported games whose " +
        "catalog match failed (server_game_id = -1) and rewrites each one with a stable " +
        "synthetic integer derived from the row's local_* UUID. After the scan, those games " +
        "become individually addressable from external launchers (Beacon / ES-DE / Daijishou), " +
        "which couldn't tell unmatched games apart while they all shared id -1. Idempotent " +
        "and self-healing: rows whose ID is later overwritten by GameHub with a real catalog " +
        "value are left alone on re-run.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        firstMethod {
            definingClass == BASE_ANDROID_APP_SMALI && name == "onCreate"
        }.addInstructions(
            0,
            """
                invoke-static {p0}, Lapp/revanced/extension/gamehub/localgameid/LocalGameIdAssignment;->scanAndAssign(Landroid/content/Context;)V
            """.trimIndent(),
        )
    }
}
