package app.revanced.patches.gamehub.bannerhub

import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.asSequence
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
import app.revanced.patcher.firstMethod

private const val BH_PKG = "app.revanced.extension.gamehub"
private const val CONFIG_CHANGES =
    "keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize"

// SharedPreferences$Editor descriptor — $ must not be in raw Kotlin string templates.
private const val SP_EDITOR = "Landroid/content/SharedPreferences\$Editor;"

// ─── Phase 1: resource-only changes ───────────────────────────────────────────

private val bannerHubResourcesPatch = resourcePatch {
    apply {
        // Feature 1: rename bottom-nav "My" tab → "My Games"
        document("res/values/strings.xml").use { dom ->
            dom.getElementsByTagName("string").asSequence()
                .map { it as? Element }
                .firstOrNull { it?.getAttribute("name") == "llauncher_main_page_title_my" }
                ?.textContent = "My Games"
        }
    }
}

// ─── Phase 2: extension classes + manifest registration ───────────────────────

private val bannerHubManifestPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getElementsByTagName("application").item(0) as Element

            fun addActivity(name: String, extra: Map<String, String> = emptyMap()) {
                dom.createElement("activity").apply {
                    setAttribute("android:name", "$BH_PKG.$name")
                    setAttribute("android:configChanges", CONFIG_CHANGES)
                    setAttribute("android:screenOrientation", "sensorLandscape")
                    setAttribute("android:exported", "false")
                    extra.forEach { (k, v) -> setAttribute(k, v) }
                    app.appendChild(this)
                }
            }

            fun addPermission(name: String) {
                val existing = dom.getElementsByTagName("uses-permission").asSequence()
                    .map { it as Element }
                    .any { it.getAttribute("android:name") == name }
                if (!existing) {
                    dom.createElement("uses-permission").apply {
                        setAttribute("android:name", name)
                        dom.documentElement.insertBefore(this, app)
                    }
                }
            }

            // GOG activities
            addActivity("GogMainActivity")
            addActivity("GogLoginActivity")
            addActivity("GogGamesActivity")
            addActivity("GogGameDetailActivity")

            // Epic activities
            addActivity("EpicMainActivity")
            addActivity("EpicLoginActivity")
            addActivity("EpicGamesActivity")
            addActivity("EpicGameDetailActivity")
            addActivity("EpicFreeGamesActivity")

            // Amazon activities
            addActivity("AmazonMainActivity")
            addActivity("AmazonLoginActivity")
            addActivity("AmazonGamesActivity")
            addActivity("AmazonGameDetailActivity")

            // BannerHub utility activities
            addActivity("FolderPickerActivity")
            addActivity(
                "BhGameConfigsActivity",
                mapOf("android:windowSoftInputMode" to "adjustResize"),
            )
            addActivity("BhDownloadsActivity")

            // Download service
            dom.createElement("service").apply {
                setAttribute("android:name", "$BH_PKG.BhDownloadService")
                setAttribute("android:foregroundServiceType", "dataSync")
                setAttribute("android:exported", "false")
                app.appendChild(this)
            }

            // Permissions required by BhDownloadService
            addPermission("android.permission.FOREGROUND_SERVICE")
            addPermission("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
            addPermission("android.permission.POST_NOTIFICATIONS")
        }
    }
}

// ─── Phase 3: bytecode hooks ───────────────────────────────────────────────────

// Feature 3a: extend HomeLeftMenuDialog.o1() packed-switch to route menu IDs
// 10 (GOG), 11 (Amazon), 12 (Epic), 13 (BhGameConfigs) to their store activities.
// At the packed-switch point: p0=menuId, p2=FragmentActivity (context), v0=0, v1=free.
private val bannerHubMenuPatch = bytecodePatch {
    apply {
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/ui/menu/HomeLeftMenuDialog;" &&
                name == "o1"
        }.apply {
            val packedSwitchIndex = indexOfFirstInstructionOrThrow(Opcode.PACKED_SWITCH)
            addInstructionsWithLabels(
                packedSwitchIndex,
                """
                    const/16 v1, 0xa
                    if-ne p0, v1, :no_gog
                    new-instance v1, Landroid/content/Intent;
                    const-class v0, Lapp/revanced/extension/gamehub/GogMainActivity;
                    invoke-direct {v1, p2, v0}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    invoke-virtual {p2, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    sget-object v1, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v1
                    :no_gog
                    const/16 v1, 0xb
                    if-ne p0, v1, :no_amazon
                    new-instance v1, Landroid/content/Intent;
                    const-class v0, Lapp/revanced/extension/gamehub/AmazonMainActivity;
                    invoke-direct {v1, p2, v0}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    invoke-virtual {p2, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    sget-object v1, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v1
                    :no_amazon
                    const/16 v1, 0xc
                    if-ne p0, v1, :no_epic
                    new-instance v1, Landroid/content/Intent;
                    const-class v0, Lapp/revanced/extension/gamehub/EpicMainActivity;
                    invoke-direct {v1, p2, v0}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    invoke-virtual {p2, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    sget-object v1, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v1
                    :no_epic
                    const/16 v1, 0xd
                    if-ne p0, v1, :no_bhconfigs
                    new-instance v1, Landroid/content/Intent;
                    const-class v0, Lapp/revanced/extension/gamehub/BhGameConfigsActivity;
                    invoke-direct {v1, p2, v0}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    invoke-virtual {p2, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    sget-object v1, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v1
                    :no_bhconfigs
                """,
            )
        }
    }
}

// Feature 3b: inject into LandscapeLauncherMainActivity.onResume() to consume
// pending-launch prefs written by store activities (GOG, Amazon, Epic) and call
// B3(exePath) to trigger game launch in the main activity context.
// Injected immediately after invoke-super onResume(); base method has .locals 3
// so v0/v1/v2 are all available at that point.
private val bannerHubPendingLaunchPatch = bytecodePatch {
    apply {
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity;" &&
                name == "onResume"
        }.apply {
            val superIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_SUPER &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "onResume"
                    } == true
            }
            addInstructionsWithLabels(
                superIndex + 1,
                """
                    const-string v0, "bh_gog_prefs"
                    const/4 v1, 0x0
                    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                    move-result-object v0
                    const-string v1, "pending_gog_exe"
                    const/4 v2, 0x0
                    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    if-eqz v1, :bh_no_gog
                    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()$SP_EDITOR
                    move-result-object v2
                    const-string v0, "pending_gog_exe"
                    invoke-interface {v2, v0}, $SP_EDITOR->remove(Ljava/lang/String;)$SP_EDITOR
                    move-result-object v2
                    invoke-interface {v2}, $SP_EDITOR->apply()V
                    invoke-virtual {p0, v1}, Lcom/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity;->B3(Ljava/lang/String;)V
                    :bh_no_gog
                    const-string v0, "bh_amazon_prefs"
                    const/4 v1, 0x0
                    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                    move-result-object v0
                    const-string v1, "pending_amazon_exe"
                    const/4 v2, 0x0
                    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    if-eqz v1, :bh_no_amazon
                    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()$SP_EDITOR
                    move-result-object v2
                    const-string v0, "pending_amazon_exe"
                    invoke-interface {v2, v0}, $SP_EDITOR->remove(Ljava/lang/String;)$SP_EDITOR
                    move-result-object v2
                    invoke-interface {v2}, $SP_EDITOR->apply()V
                    invoke-virtual {p0, v1}, Lcom/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity;->B3(Ljava/lang/String;)V
                    :bh_no_amazon
                    const-string v0, "bh_epic_prefs"
                    const/4 v1, 0x0
                    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
                    move-result-object v0
                    const-string v1, "pending_epic_exe"
                    const/4 v2, 0x0
                    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    if-eqz v1, :bh_no_epic
                    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()$SP_EDITOR
                    move-result-object v2
                    const-string v0, "pending_epic_exe"
                    invoke-interface {v2, v0}, $SP_EDITOR->remove(Ljava/lang/String;)$SP_EDITOR
                    move-result-object v2
                    invoke-interface {v2}, $SP_EDITOR->apply()V
                    invoke-virtual {p0, v1}, Lcom/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity;->B3(Ljava/lang/String;)V
                    :bh_no_epic
                """,
            )
        }
    }
}

// ─── Main BannerHub patch ──────────────────────────────────────────────────────

@Suppress("unused")
val bannerHubPatch = bytecodePatch(
    name = "BannerHub",
    description = "Ports all BannerHub features to GameHub as a ReVanced patch.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(
        sharedGamehubExtensionPatch,
        bannerHubResourcesPatch,
        bannerHubManifestPatch,
        bannerHubMenuPatch,
        bannerHubPendingLaunchPatch,
    )
}
