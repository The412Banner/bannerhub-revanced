package app.revanced.patches.gamehub.misc.launcher

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.all.misc.packagename.changePackageNamePatch
import app.revanced.patches.all.misc.packagename.packageNameOption
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.asSequence
import app.revanced.util.getNode
import org.w3c.dom.Element

// =============================================================================
// External launcher support — port of PlayDay's 5.3.5 patch to GameHub 6.0.4.
//
// Lets external frontends (ES-DE, Daijishou, Beacon, etc.) launch games via
// the 5.3.5 intent contract:
//
//   action  = "<variant_package>.LAUNCH_GAME"
//             (e.g. gamehub.lite.LAUNCH_GAME, com.tencent.ig.LAUNCH_GAME)
//   extras  = steamAppId : int       (Steam app id, optional)
//             localGameId : int      (XiaoJi game id, preferred)
//             autoStartGame : bool   (skip detail screen and launch directly)
//             type : string          (5.3.5-only, ignored on 6.0.4)
//
// 5.3.5 implementation (playday/playday-build):
//   - Adds <intent-filter> to GameDetailActivity with the LAUNCH_GAME action.
//   - Patches GameDetailActivity.initView() so the "type" extra defaults to
//     "0" instead of "" (otherwise the detail lookup fails when no `type`
//     is sent by the frontend).
//
// 6.0.4 implementation (this patch):
//   - GameDetailActivity is GONE — game detail is a Compose screen reached
//     via Compose navigation, no separate Activity to intent-filter onto.
//   - DeepLinkActivity (com.xiaoji.egggame.DeepLinkActivity) ALREADY handles
//     the equivalent dispatch via intent extras, including:
//       app_nav_target           ("game_detail" routes to detail screen)
//       app_nav_game_id          (String, parsed to int)
//       app_nav_steam_app_id     (int, optional metadata)
//       app_nav_auto_start_game  (boolean — already powers auto-launch!)
//     This dispatch lives in DeepLinkActivity.onCreate's sswitch_data_1
//     block (sswitch_8 = game_detail case at ~line 3507 of the 6.0.4 smali).
//
// So the port is purely glue:
//   1. Resource: add intent-filter on DeepLinkActivity for
//      <variant_pkg>.LAUNCH_GAME (variant_pkg taken from packageNameOption
//      after ChangePackageNamePatch runs, falling back to manifest@package).
//      DeepLinkActivity is already exported in 6.0.4; we set it again
//      defensively.
//   2. Bytecode: at the top of DeepLinkActivity.onCreate, call
//      ExternalLauncher.rewriteIntent(intent), which translates the 5.3.5
//      extras into the native app_nav_* form. The existing dispatch then
//      routes us to game detail (and auto-launches if requested) with no
//      further patching. The Java extension matches any action ending in
//      ".LAUNCH_GAME" — that covers every per-variant action AND PlayDay's
//      literal "gamehub.lite.LAUNCH_GAME" as a forgiveness fallback for
//      stale 5.3.5-Lite-style Beacon configs against a renamed variant.
//
// Beacon instructions for 6.0.4 (per variant):
//   am launch -n <variant_pkg>/com.xiaoji.egggame.DeepLinkActivity \
//       -a <variant_pkg>.LAUNCH_GAME \
//       --es localGameId {file_content} --es steamAppId {file_content} \
//       --ez autoStartGame true
//
// Note the activity class name is com.xiaoji.egggame.DeepLinkActivity for
// ALL variants — ChangePackageNamePatch only rewrites the manifest's
// `package=` attribute, not class FQNs. This differs from the 5.3.5 doc,
// which referenced com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity.
//
// Anchor strategy (no hardcoded smali line):
//   - definingClass + method name. DeepLinkActivity has exactly one onCreate
//     in 6.0.4 (verified). The method declares .locals 34, which aliases
//     p0 to v34 — too high for ANY non-range invoke's 4-bit register form
//     (both {p0} alone and {p0, v0} fail). The injection first uses
//     `move-object/from16 v0, p0` to bring p0 down into v0, then operates
//     entirely on low registers — pattern borrowed from VibrationPatch's
//     ENV_BUILDER hook. v0 reuse is safe — the original onCreate's next
//     instruction (sget-object v0, Lejm;->a:Lghd;) writes v0 but its result
//     is never read.
//
// Re-derivation on future base bumps:
//   - DeepLinkActivity is at the package root, not behind R8 letter renames
//     — stable.
//   - The native app_nav_* extra names appear as const-string literals in
//     the smali — grep DeepLinkActivity for "app_nav_target",
//     "app_nav_game_id", "app_nav_auto_start_game" to verify they are still
//     consumed in a future base.
// =============================================================================

private const val DEEPLINK_ACTIVITY = "com.xiaoji.egggame.DeepLinkActivity"
private const val DEEPLINK_ACTIVITY_SMALI = "Lcom/xiaoji/egggame/DeepLinkActivity;"
private const val ACTION_SUFFIX = ".LAUNCH_GAME"

private val externalLauncherManifestPatch = resourcePatch {
    dependsOn(changePackageNamePatch)

    afterDependents {
        document("AndroidManifest.xml").use { dom ->
            val manifestPackage = (dom.getNode("manifest") as Element).getAttribute("package")

            // Mirror FileManagerAccessPatch: prefer the user-supplied package
            // name option (per-variant rename) when it is non-default; fall
            // back to whatever ChangePackageNamePatch's afterDependents wrote
            // into manifest@package.
            val variantPackage = packageNameOption.value
                ?.takeIf { it != packageNameOption.default }
                ?: manifestPackage

            val action = "$variantPackage$ACTION_SUFFIX"

            dom.getElementsByTagName("activity").asSequence()
                .map { it as Element }
                .filter { it.getAttribute("android:name") == DEEPLINK_ACTIVITY }
                .forEach { activity ->
                    activity.setAttribute("android:exported", "true")

                    // Idempotency: skip if an intent-filter for OUR action
                    // suffix is already present.
                    val alreadyHasFilter = activity.getElementsByTagName("intent-filter")
                        .asSequence()
                        .map { it as Element }
                        .any { filter ->
                            filter.getElementsByTagName("action").asSequence()
                                .map { it as Element }
                                .any { it.getAttribute("android:name").endsWith(ACTION_SUFFIX) }
                        }
                    if (alreadyHasFilter) return@forEach

                    dom.createElement("intent-filter").apply {
                        dom.createElement("action").apply {
                            setAttribute("android:name", action)
                        }.let(this::appendChild)
                        dom.createElement("category").apply {
                            setAttribute("android:name", "android.intent.category.DEFAULT")
                        }.let(this::appendChild)
                    }.let(activity::appendChild)
                }
        }
    }
}

@Suppress("unused")
val externalLauncherPatch = bytecodePatch(
    name = "External launcher support",
    description = "Enables launching games from external frontends like ES-DE, Daijishou, " +
        "and Beacon. Keeps PlayDay's 5.3.5 '<variant_package>.LAUNCH_GAME' intent contract; " +
        "translates the 5.3.5 extras (steamAppId / localGameId / autoStartGame) onto " +
        "GameHub 6.0.4's native DeepLinkActivity 'app_nav_*' dispatch.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(sharedGamehubExtensionPatch, externalLauncherManifestPatch)

    apply {
        firstMethod {
            definingClass == DEEPLINK_ACTIVITY_SMALI && name == "onCreate"
        }.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                invoke-virtual {v0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
                move-result-object v0
                invoke-static {v0}, Lapp/revanced/extension/gamehub/launcher/ExternalLauncher;->rewriteIntent(Landroid/content/Intent;)V
            """.trimIndent(),
        )
    }
}
