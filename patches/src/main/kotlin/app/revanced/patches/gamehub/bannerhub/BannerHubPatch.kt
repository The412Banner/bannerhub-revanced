package app.revanced.patches.gamehub.bannerhub

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.ResourceGroup
import app.revanced.util.asSequence
import app.revanced.util.copyResources
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
            addActivity("ComponentManagerActivity")
            addActivity("ComponentDownloadActivity")

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

            // Feature 56: fix MissingForegroundServiceTypeException on Android 14+
            // These 12 GameHub services start as foreground but lack the type attribute.
            val servicesToFix = listOf(
                "com.xj.landscape.launcher.devicemanagement.DeviceManagementService",
                "com.xj.apk.update.service.DownloadService",
                "com.xj.mapping.MappingService",
                "com.xj.mapping.interaction.KeyboardEditService",
                "com.xj.mapping.interaction.SSLClientService",
                "com.xj.mapping.interaction.virtualtouchutil.ipc.service.VTouchIPCService",
                "com.xj.winemu.download.service.UnzipService",
                "com.xj.winemu.service.EmuFileService",
                "com.xj.module.steam.SteamService",
                "com.streaming.discovery.DiscoveryService",
                "com.streaming.computers.ComputerManagerService",
                "com.streaming.binding.input.driver.UsbDriverService",
            )
            dom.getElementsByTagName("service").asSequence()
                .map { it as? Element }
                .filter { it?.getAttribute("android:name") in servicesToFix }
                .forEach { it?.setAttribute("android:foregroundServiceType", "specialUse") }

            // Feature 56: exclude GameDetailActivity from Recents to avoid stale task on re-launch
            dom.getElementsByTagName("activity").asSequence()
                .map { it as? Element }
                .firstOrNull {
                    it?.getAttribute("android:name") ==
                        "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"
                }
                ?.setAttribute("android:excludeFromRecents", "true")
        }
    }
}

// ─── Phase 3: bytecode hooks ───────────────────────────────────────────────────

// Feature 3a: extend HomeLeftMenuDialog.o1() packed-switch to route menu IDs
// 10 (GOG), 11 (Amazon), 12 (Epic), 13 (BhGameConfigs) to their store activities.
// Method is static: p0=menuId(int), p1=0(int), p2=FragmentActivity.
// Uses p0/p1 only (matching BannerHub's smali approach) to avoid dex verifier
// type=Conflict that occurs when v0/v1 are shared between int and reference types.
// p1 is restored to 0 before the packed-switch so existing cases 0-9 are unaffected.
private val bannerHubMenuPatch = bytecodePatch {
    apply {
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/ui/menu/HomeLeftMenuDialog;" &&
                name == "o1"
        }.apply {
            val packedSwitchIndex = indexOfFirstInstructionOrThrow(Opcode.PACKED_SWITCH)
            // :goto_1 = sget-object p0, Unit.a — the shared return path for all cases.
            val goto1Index = indexOfFirstInstructionReversedOrThrow {
                opcode == Opcode.SGET_OBJECT &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is FieldReference &&
                            it.definingClass == "Lkotlin/Unit;" &&
                            it.name == "a"
                    } == true
            }
            addInstructionsWithLabels(
                packedSwitchIndex,
                """
                    const/16 p1, 0xa
                    if-ne p0, p1, :bh_not_gog
                    new-instance p0, Landroid/content/Intent;
                    const-class p1, Lapp/revanced/extension/gamehub/GogMainActivity;
                    invoke-direct {p0, p2, p1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    invoke-virtual {p2, p0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    goto :bh_goto_1
                    :bh_not_gog
                    const/16 p1, 0xb
                    if-ne p0, p1, :bh_not_amazon
                    new-instance p0, Landroid/content/Intent;
                    const-class p1, Lapp/revanced/extension/gamehub/AmazonMainActivity;
                    invoke-direct {p0, p2, p1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    invoke-virtual {p2, p0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    goto :bh_goto_1
                    :bh_not_amazon
                    const/16 p1, 0xc
                    if-ne p0, p1, :bh_not_epic
                    new-instance p0, Landroid/content/Intent;
                    const-class p1, Lapp/revanced/extension/gamehub/EpicMainActivity;
                    invoke-direct {p0, p2, p1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    invoke-virtual {p2, p0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    goto :bh_goto_1
                    :bh_not_epic
                    const/16 p1, 0xd
                    if-ne p0, p1, :bh_not_bhconfigs
                    new-instance p0, Landroid/content/Intent;
                    const-class p1, Lapp/revanced/extension/gamehub/BhGameConfigsActivity;
                    invoke-direct {p0, p2, p1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    invoke-virtual {p2, p0}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    goto :bh_goto_1
                    :bh_not_bhconfigs
                    const/4 p1, 0x0
                """,
                ExternalLabel("bh_goto_1", instructions[goto1Index]),
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

// ─── Phase 4: HUD overlay + WineActivity performance hooks ────────────────────

// Features 13/14/48/49/50: hook WineActivity.onCreate() for sustained-performance
// mode + max Adreno clocks, and WineActivity.onResume() to inject the correct
// HUD overlay (BhFrameRating / BhDetailedHud / BhKonkrHud) via BhHudInjector.
private const val BH_HUD_INJECTOR = "Lcom/xj/winemu/sidebar/BhHudInjector;"

private val bannerHubHudPatch = bytecodePatch {
    apply {
        // onResume: inject BhHudInjector.injectOrUpdate(this) before PcInGameDelegateManager.onResume()
        firstMethod {
            definingClass == "Lcom/xj/winemu/WineActivity;" && name == "onResume"
        }.apply {
            val pcResumeIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is FieldReference &&
                            it.definingClass == "Lcom/xj/winemu/external/PcInGameDelegateManager;" &&
                            it.name == "a"
                    } == true
            }
            addInstructions(
                pcResumeIndex,
                "invoke-static {p0}, $BH_HUD_INJECTOR->injectOrUpdate(Landroid/app/Activity;)V",
            )
        }

        // onCreate: inject BhHudInjector.onWineCreate(this) before WineActivity.R2()
        // v1 is `this` (aliased from p0 via move-object/from16 at the start of onCreate)
        firstMethod {
            definingClass == "Lcom/xj/winemu/WineActivity;" && name == "onCreate"
        }.apply {
            val r2Index = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "R2" &&
                            it.definingClass == "Lcom/xj/winemu/WineActivity;"
                    } == true
            }
            addInstructions(
                r2Index,
                "invoke-static {v1}, $BH_HUD_INJECTOR->onWineCreate(Landroid/app/Activity;)V",
            )
        }
    }
}

// ─── Phase 5: Component Manager ───────────────────────────────────────────────

// Feature 8: inject into GameSettingViewModel$fetchList$1.invokeSuspend() after
// CommResultEntity.setData(v7) to append locally-registered BannerHub components
// to the list before it is delivered to the UI callback.
// Registers: v5 = lambda this, v7 = the assembled List.
private const val BH_COMPONENT_INJECTOR = "Lapp/revanced/extension/gamehub/ComponentInjectorHelper;"
private const val FETCH_LIST_LAMBDA = "Lcom/xj/winemu/settings/GameSettingViewModel\$fetchList\$1;"
private const val CT_FIELD = "\$contentType"

private val bannerHubComponentManagerPatch = bytecodePatch {
    apply {
        firstMethod {
            definingClass == FETCH_LIST_LAMBDA && name == "invokeSuspend"
        }.apply {
            val setDataIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "setData" &&
                            it.definingClass == "Lcom/xj/common/data/model/CommResultEntity;"
                    } == true
            }
            addInstructions(
                setDataIndex + 1,
                """
                    iget v0, v5, $FETCH_LIST_LAMBDA->$CT_FIELD:I
                    invoke-static {v7, v0}, $BH_COMPONENT_INJECTOR->appendLocalComponents(Ljava/util/List;I)V
                """,
            )
        }
    }
}

// ─── Phase 6: CPU core limit + VRAM unlock ────────────────────────────────────

// Descriptors used across both Phase 6 sub-patches.
private const val ENV_CONTROLLER = "Lcom/winemu/core/controller/EnvironmentController;"
private const val CONFIG_CLASS = "Lcom/winemu/openapi/Config;"
private const val SELECT_DIALOG_COMPANION =
    "Lcom/xj/winemu/settings/SelectAndSingleInputDialog\$Companion;"
private const val PC_GAME_SETTING_OPS = "Lcom/xj/winemu/settings/PcGameSettingOperations;"
private const val CPU_MULTI_SELECT_HELPER =
    "Lapp/revanced/extension/gamehub/CpuMultiSelectHelper;"
private const val DIALOG_LIST_ITEM =
    "Lcom/xj/winemu/bean/DialogSettingListItemEntity;"
private const val PC_SETTING_ENTITY = "Lcom/xj/winemu/bean/PcSettingItemEntity;"
private const val PC_SETTING_ENTITY_COMPANION =
    "Lcom/xj/winemu/bean/PcSettingItemEntity\$Companion;"
// Full parameter descriptor for DialogSettingListItemEntity's Kotlin defaults constructor.
private const val DIALOG_LIST_CTOR_PARAMS =
    "IIZLjava/lang/String;Ljava/lang/String;IILjava/lang/String;ILjava/lang/String;" +
    "JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;II" +
    "Lcom/xj/winemu/api/bean/EnvLayerEntity;ZILjava/lang/String;" +
    "ILkotlin/jvm/internal/DefaultConstructorMarker;"

// Feature 17a: intercept SelectAndSingleInputDialog$Companion.d() for CONTENT_TYPE_CORE_LIMIT
//   and show CpuMultiSelectHelper dialog instead of the single-select picker.
// Feature 17b: override the CPU affinity bitmask formula in EnvironmentController.d() with
//   the raw bitmask stored by CpuMultiSelectHelper (which Config.w() now returns directly).
private val bannerHubCpuPatch = bytecodePatch {
    apply {
        // Feature 17a — SelectAndSingleInputDialog$Companion.d()
        // Inject before the b(int, String) list-retrieval call so we can short-circuit
        // with our multi-select dialog when contentType == CONTENT_TYPE_CORE_LIMIT.
        firstMethod {
            definingClass == SELECT_DIALOG_COMPANION && name == "d"
        }.apply {
            val bCallIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "b" &&
                            it.definingClass == SELECT_DIALOG_COMPANION
                    } == true
            }
            addInstructionsWithLabels(
                bCallIndex,
                """
                    sget-object v0, $PC_SETTING_ENTITY->Companion:$PC_SETTING_ENTITY_COMPANION
                    invoke-virtual {v0}, $PC_SETTING_ENTITY_COMPANION->getCONTENT_TYPE_CORE_LIMIT()I
                    move-result v0
                    if-ne p3, v0, :bh_not_core_limit
                    invoke-static {p1, p2, p3, p4}, $CPU_MULTI_SELECT_HELPER->show(Landroid/view/View;Ljava/lang/String;ILkotlin/jvm/functions/Function1;)V
                    return-void
                    :bh_not_core_limit
                """,
            )
        }

        // Feature 17b — EnvironmentController.d(): override the (1<<count)-1 formula result
        // with the raw bitmask that Config.w() now stores (set by CpuMultiSelectHelper).
        // The formula path: shl-int → sub-int/2addr → [inject here] → :goto_0.
        // Other paths jump directly to :goto_0 and are unaffected.
        firstMethod {
            definingClass == ENV_CONTROLLER && name == "d"
        }.apply {
            val shlIndex = indexOfFirstInstructionOrThrow(Opcode.SHL_INT)
            addInstructions(
                shlIndex + 2,
                """
                    iget-object v2, p0, $ENV_CONTROLLER->b:$CONFIG_CLASS
                    invoke-virtual {v2}, $CONFIG_CLASS->w()I
                    move-result v0
                """,
            )
        }
    }
}

// Feature 18: extend PcGameSettingOperations to expose 6/8/12/16 GB VRAM options.
//   F0() — display-name method: add string cases before the "No Limit" fallback.
//   l0() — list-building method: add 4 new DialogSettingListItemEntity entries before return.
private val bannerHubVramPatch = bytecodePatch {
    apply {
        // Feature 18 — PcGameSettingOperations.F0() display strings
        firstMethod {
            definingClass == PC_GAME_SETTING_OPS && name == "F0"
        }.apply {
            val noLimitIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is FieldReference && it.name == "pc_cc_cpu_core_no_limit"
                    } == true
            }
            addInstructionsWithLabels(
                noLimitIndex,
                """
                    const/16 v0, 0x1800
                    if-ne p0, v0, :bh_f0_not6
                    const-string p0, "6 GB"
                    return-object p0
                    :bh_f0_not6
                    const/16 v0, 0x2000
                    if-ne p0, v0, :bh_f0_not8
                    const-string p0, "8 GB"
                    return-object p0
                    :bh_f0_not8
                    const/16 v0, 0x3000
                    if-ne p0, v0, :bh_f0_not12
                    const-string p0, "12 GB"
                    return-object p0
                    :bh_f0_not12
                    const/16 v0, 0x4000
                    if-ne p0, v0, :bh_f0_not16
                    const-string p0, "16 GB"
                    return-object p0
                    :bh_f0_not16
                """,
            )
        }

        // Feature 18 — PcGameSettingOperations.l0() list entries
        // Inject before return-object v1 (the list). v54/v55 (defaults mask / DCM) and
        // v32/v35-v53 (all-zero fields) are still live from the 4 GB entry setup.
        // Only v31 (VRAM MB), v33 (isSelected), v34 (name) are set per entry.
        // G0() re-reads the current selection so isSelected highlights correctly.
        firstMethod {
            definingClass == PC_GAME_SETTING_OPS && name == "l0"
        }.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            addInstructionsWithLabels(
                returnIndex,
                """
                    invoke-virtual/range {p0 .. p0}, $PC_GAME_SETTING_OPS->G0()I
                    move-result v3
                    const/16 v31, 0x1800
                    const/4 v33, 0x0
                    if-ne v3, v31, :bh_l0_6ns
                    const/4 v33, 0x1
                    :bh_l0_6ns
                    new-instance v30, $DIALOG_LIST_ITEM
                    const-string v34, "6 GB"
                    invoke-direct/range {v30 .. v55}, $DIALOG_LIST_ITEM-><init>($DIALOG_LIST_CTOR_PARAMS)V
                    move-object/from16 v0, v30
                    invoke-interface {v1, v0}, Ljava/util/List;->add(Ljava/lang/Object;)Z
                    const/16 v31, 0x2000
                    const/4 v33, 0x0
                    if-ne v3, v31, :bh_l0_8ns
                    const/4 v33, 0x1
                    :bh_l0_8ns
                    new-instance v30, $DIALOG_LIST_ITEM
                    const-string v34, "8 GB"
                    invoke-direct/range {v30 .. v55}, $DIALOG_LIST_ITEM-><init>($DIALOG_LIST_CTOR_PARAMS)V
                    move-object/from16 v0, v30
                    invoke-interface {v1, v0}, Ljava/util/List;->add(Ljava/lang/Object;)Z
                    const/16 v31, 0x3000
                    const/4 v33, 0x0
                    if-ne v3, v31, :bh_l0_12ns
                    const/4 v33, 0x1
                    :bh_l0_12ns
                    new-instance v30, $DIALOG_LIST_ITEM
                    const-string v34, "12 GB"
                    invoke-direct/range {v30 .. v55}, $DIALOG_LIST_ITEM-><init>($DIALOG_LIST_CTOR_PARAMS)V
                    move-object/from16 v0, v30
                    invoke-interface {v1, v0}, Ljava/util/List;->add(Ljava/lang/Object;)Z
                    const/16 v31, 0x4000
                    const/4 v33, 0x0
                    if-ne v3, v31, :bh_l0_16ns
                    const/4 v33, 0x1
                    :bh_l0_16ns
                    new-instance v30, $DIALOG_LIST_ITEM
                    const-string v34, "16 GB"
                    invoke-direct/range {v30 .. v55}, $DIALOG_LIST_ITEM-><init>($DIALOG_LIST_CTOR_PARAMS)V
                    move-object/from16 v0, v30
                    invoke-interface {v1, v0}, Ljava/util/List;->add(Ljava/lang/Object;)Z
                """,
            )
        }
    }
}

// ─── Phase 7: Wine Task Manager (Feature 51) ──────────────────────────────────

private const val WINE_DRAWER = "Lcom/xj/winemu/sidebar/WineActivityDrawerContent;"
private const val BH_TASK_CLICK_HELPER = "Lcom/xj/winemu/sidebar/BhTaskClickHelper;"
private const val BH_TASK_FRAG = "Lcom/xj/winemu/sidebar/BhTaskManagerFragment;"
private const val SHOW_HIDE_EXT = "Lcom/xj/base/ext/ShowHideExtKt;"
private const val APP_PREFERENCES = "Lcom/xj/common/data/preferences/AppPreferences;"
private const val R_ID = "Lcom/xj/winemu/R\$id;"

// Resource sub-patch: add Task Manager button to the sidebar layout.
private val bannerHubTaskManagerResourcePatch = resourcePatch {
    apply {
        // Copy Task Manager icon drawable
        copyResources(
            "bannerhub",
            ResourceGroup("drawable", "bh_sidebar_taskmanager.xml"),
        )

        // Add bh_sidebar_taskmanager ID to ids.xml
        document("res/values/ids.xml").use { dom ->
            val root = dom.documentElement
            val existing = dom.getElementsByTagName("item").asSequence()
                .map { it as? Element }
                .any { it?.getAttribute("name") == "bh_sidebar_taskmanager" }
            if (!existing) {
                dom.createElement("item").apply {
                    setAttribute("name", "bh_sidebar_taskmanager")
                    setAttribute("type", "id")
                    root.appendChild(this)
                }
            }
        }

        // Insert SidebarTitleItemView for Task Manager after sidebar_setting and before spacer
        document("res/layout/winemu_activitiy_settings_layout.xml").use { dom ->
            val allElements = dom.getElementsByTagName("*")
            for (i in 0 until allElements.length) {
                val el = allElements.item(i) as? Element ?: continue
                if (el.getAttribute("android:id") == "@id/sidebar_setting") {
                    val parent = el.parentNode ?: continue
                    dom.createElement("com.xj.winemu.view.SidebarTitleItemView").apply {
                        setAttribute("android:layout_gravity", "center_horizontal")
                        setAttribute("android:id", "@id/bh_sidebar_taskmanager")
                        setAttribute("android:layout_width", "@dimen/dp_48")
                        setAttribute("android:layout_height", "@dimen/dp_48")
                        setAttribute("android:layout_marginTop", "@dimen/dp_24")
                        setAttribute("app:sidebar_icon", "@drawable/bh_sidebar_taskmanager")
                        val nextSibling = el.nextSibling
                        if (nextSibling != null) parent.insertBefore(this, nextSibling)
                        else parent.appendChild(this)
                    }
                    break
                }
            }
        }
    }
}

// Bytecode sub-patch: wire BhTaskClickHelper + BhTaskManagerFragment into
// WineActivityDrawerContent constructor and U(String) fragment factory.
private val bannerHubTaskManagerPatch = bytecodePatch {
    dependsOn(bannerHubTaskManagerResourcePatch)

    apply {
        // Constructor: inject BhTaskClickHelper.setup(this) before the AppPreferences check,
        // which immediately follows the last setClickListener call in <init>(Context,AttributeSet,int).
        firstMethod {
            definingClass == WINE_DRAWER &&
                name == "<init>" &&
                parameterTypes == listOf(
                    "Landroid/content/Context;", "Landroid/util/AttributeSet;", "I",
                )
        }.apply {
            val appPrefsIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is FieldReference && it.definingClass == APP_PREFERENCES &&
                            it.name == "INSTANCE"
                    } == true
            }
            addInstructions(
                appPrefsIndex,
                "invoke-static {p0}, $BH_TASK_CLICK_HELPER->setup(Landroid/view/View;)V",
            )
        }

        // U(String): inject before hashCode() switch to intercept "BhTaskManagerFragment".
        // At this point: p0=this, p1=key, v0=null (map miss), v1/v2 free.
        firstMethod {
            definingClass == WINE_DRAWER && name == "U"
        }.apply {
            val hashCodeIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "hashCode" &&
                            it.definingClass == "Ljava/lang/String;"
                    } == true
            }
            addInstructionsWithLabels(
                hashCodeIndex,
                """
                    const-string v0, "BhTaskManagerFragment"
                    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :bh_not_task_frag
                    new-instance v2, $BH_TASK_FRAG
                    invoke-direct {v2}, $BH_TASK_FRAG-><init>()V
                    iget-object v0, p0, $WINE_DRAWER->m:Ljava/util/Map;
                    invoke-interface {v0, p1, v2}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    sget v0, $R_ID->layout_container:I
                    iget-object v1, p0, $WINE_DRAWER->l:Landroidx/fragment/app/FragmentManager;
                    invoke-static {v0, v1, v2}, $SHOW_HIDE_EXT->a(ILandroidx/fragment/app/FragmentManager;Landroidx/fragment/app/Fragment;)V
                    return-void
                    :bh_not_task_frag
                """,
            )
        }
    }
}

// ─── Phase 9: Download Service download-badge wiring (Feature 56) ────────────

private const val LAUNCHER_MAIN =
    "Lcom/xj/landscape/launcher/ui/main/LandscapeLauncherMainActivity;"
private const val BASE_VM_ACTIVITY =
    "Lcom/xj/base/base/activity/BaseVmActivity;"
private const val VIEW_DATA_BINDING =
    "Landroidx/databinding/ViewDataBinding;"
private const val BH_DOWNLOAD_BTN =
    "Lapp/revanced/extension/gamehub/BhDashboardDownloadBtn;"

// Feature 56: wire BhDashboardDownloadBtn.attach() into LandscapeLauncherMainActivity.initView().
// Uses getIdentifier() so it gracefully no-ops if iv_bci_launcher isn't in the layout yet
// (it's added by Phase 10 / Feature 2 BCI Launcher Button).
private val bannerHubDownloadBtnPatch = bytecodePatch {
    apply {
        firstMethod {
            definingClass == LAUNCHER_MAIN && name == "initView"
        }.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructionsWithLabels(
                returnIndex,
                """
                    invoke-virtual {p0}, $BASE_VM_ACTIVITY->getMDataBind()$VIEW_DATA_BINDING
                    move-result-object v0
                    if-eqz v0, :bh_no_dl_btn
                    invoke-virtual {v0}, $VIEW_DATA_BINDING->getRoot()Landroid/view/View;
                    move-result-object v0
                    if-eqz v0, :bh_no_dl_btn
                    const-string v1, "iv_bci_launcher"
                    const-string v2, "id"
                    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                    move-result-object v3
                    invoke-virtual {p0}, Landroid/content/Context;->getResources()Landroid/content/Resources;
                    move-result-object v4
                    invoke-virtual {v4, v1, v2, v3}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I
                    move-result v1
                    if-eqz v1, :bh_no_dl_btn
                    invoke-virtual {v0, v1}, Landroid/view/View;->findViewById(I)Landroid/view/View;
                    move-result-object v0
                    if-eqz v0, :bh_no_dl_btn
                    invoke-static {p0, v0}, $BH_DOWNLOAD_BTN->attach(Landroid/content/Context;Landroid/view/View;)V
                    :bh_no_dl_btn
                """,
            )
        }
    }
}

// ─── Phase 8: Config Sharing (Features 52 / 53 / 57) ─────────────────────────

private const val GAME_DETAIL_SETTING_MENU =
    "Lcom/xj/landscape/launcher/ui/gamedetail/GameDetailSettingMenu;"
private const val PC_GAMES_OPTS_CONT =
    "Lcom/xj/landscape/launcher/ui/gamedetail/GameDetailSettingMenu\$getPcGamesOptions\$1;"
private const val GAME_DETAIL_ENTITY = "Lcom/xj/common/service/bean/GameDetailEntity;"
private const val OPTION = "Lcom/xj/common/view/popup/Option;"
private const val BH_EXPORT_LAMBDA =
    "Lcom/xj/landscape/launcher/ui/gamedetail/BhExportLambda;"
private const val BH_IMPORT_LAMBDA =
    "Lcom/xj/landscape/launcher/ui/gamedetail/BhImportLambda;"
private const val BH_FRONTEND_LAMBDA =
    "Lcom/xj/landscape/launcher/ui/gamedetail/BhFrontendExportLambda;"
private const val OPTION_CTOR =
    "$OPTION-><init>(Ljava/lang/String;ZIIILkotlin/jvm/functions/Function1;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"

// Feature 52 (Export Config) + 53 (Import Config) + 57 (Frontend Export):
// Inject three new Option entries into GameDetailSettingMenu.W() (getPcGamesOptions coroutine)
// immediately before the final return-object that returns the options list.
//
// At injection point: v1 = options List; v5 = getPcGamesOptions$1 continuation
// p0 (this, GameDetailSettingMenu) is at register 19 (locals 19 + 5 params) —
// use move-object/from16 v4, p0 to copy into range-accessible v4.
// L$0 in the continuation is always GameDetailEntity across all resume paths.
private val bannerHubConfigSharingPatch = bytecodePatch {
    apply {
        firstMethod {
            definingClass == GAME_DETAIL_SETTING_MENU && name == "W"
        }.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            addInstructions(
                returnIndex,
                """
                    move-object/from16 v4, p0
                    iget-object v3, v5, $PC_GAMES_OPTS_CONT->L${'$'}0:Ljava/lang/Object;
                    check-cast v3, $GAME_DETAIL_ENTITY
                    new-instance v9, $OPTION
                    const-string v10, "Export Config"
                    const/4 v11, 0x0
                    const/4 v12, 0x0
                    const/4 v13, 0x0
                    const/4 v14, 0x0
                    new-instance v15, $BH_EXPORT_LAMBDA
                    invoke-direct {v15, v4, v3}, $BH_EXPORT_LAMBDA-><init>($GAME_DETAIL_SETTING_MENU$GAME_DETAIL_ENTITY)V
                    const/16 v16, 0x1e
                    const/16 v17, 0x0
                    invoke-direct/range {v9 .. v17}, $OPTION_CTOR
                    invoke-interface {v1, v9}, Ljava/util/List;->add(Ljava/lang/Object;)Z
                    new-instance v9, $OPTION
                    const-string v10, "Import Config"
                    const/4 v11, 0x0
                    const/4 v12, 0x0
                    const/4 v13, 0x0
                    const/4 v14, 0x0
                    new-instance v15, $BH_IMPORT_LAMBDA
                    invoke-direct {v15, v4, v3}, $BH_IMPORT_LAMBDA-><init>($GAME_DETAIL_SETTING_MENU$GAME_DETAIL_ENTITY)V
                    const/16 v16, 0x1e
                    const/16 v17, 0x0
                    invoke-direct/range {v9 .. v17}, $OPTION_CTOR
                    invoke-interface {v1, v9}, Ljava/util/List;->add(Ljava/lang/Object;)Z
                    new-instance v9, $OPTION
                    const-string v10, "Frontend Export"
                    const/4 v11, 0x0
                    const/4 v12, 0x0
                    const/4 v13, 0x0
                    const/4 v14, 0x0
                    new-instance v15, $BH_FRONTEND_LAMBDA
                    invoke-direct {v15, v4, v3}, $BH_FRONTEND_LAMBDA-><init>($GAME_DETAIL_SETTING_MENU$GAME_DETAIL_ENTITY)V
                    const/16 v16, 0x1e
                    const/16 v17, 0x0
                    invoke-direct/range {v9 .. v17}, $OPTION_CTOR
                    invoke-interface {v1, v9}, Ljava/util/List;->add(Ljava/lang/Object;)Z
                """,
            )
        }
    }
}

// ─── Phase 10: Offline Steam Skip (Feature 11) + BCI Launcher Button (Feature 2) ──

// Feature 11: when auto-Steam-login fails and the device is offline, skip the
// "show login screen" block and fall through to the direct-launch path instead.
// Injection: after the unique `if-nez p1, :cond_f` (auto-login result check),
// add a NetworkUtils.r() check that jumps forward to :goto_5 when offline.
// At :goto_5, r() returns false again → :cond_13 (direct game launch without login).
private const val STEAM_STRATEGY_CONT =
    "Lcom/xj/landscape/launcher/launcher/strategy/SteamGameByPcEmuLaunchStrategy\$execute\$3;"
private const val NETWORK_UTILS = "Lcom/blankj/utilcode/util/NetworkUtils;"

private val bannerHubOfflineSteamSkipPatch = bytecodePatch {
    apply {
        firstMethod {
            definingClass == STEAM_STRATEGY_CONT && name == "invokeSuspend"
        }.apply {
            // Only if-nez with register p1 (index 1) in this method — the auto-login result.
            val ifNezIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.IF_NEZ &&
                (this as OneRegisterInstruction).registerA == 1
            }
            // The single NetworkUtils.r() call in the original method is at :goto_5.
            // Jumping there when offline: it re-checks r() → sees false → takes :cond_13.
            val goto5Index = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                (this as? ReferenceInstruction)?.reference?.let { ref ->
                    ref is MethodReference &&
                    ref.definingClass == NETWORK_UTILS &&
                    ref.name == "r"
                } == true
            }
            addInstructionsWithLabels(
                ifNezIndex + 1,
                """
                    invoke-static {}, $NETWORK_UTILS->r()Z
                    move-result v11
                    if-eqz v11, :bh_steam_offline
                """,
                ExternalLabel("bh_steam_offline", instructions[goto5Index]),
            )
        }
    }
}

// Feature 2: add the iv_bci_launcher FrameLayout (download-badge button) to the
// launcher toolbar. Phase 9's bannerHubDownloadBtnPatch already wires
// BhDashboardDownloadBtn.attach() when this view is present (via getIdentifier).
private val bannerHubBciLauncherPatch = resourcePatch {
    apply {
        // Register the id
        document("res/values/ids.xml").use { dom ->
            val root = dom.documentElement
            val existing = dom.getElementsByTagName("item").asSequence()
                .map { it as? Element }
                .any { it?.getAttribute("name") == "iv_bci_launcher" }
            if (!existing) {
                dom.createElement("item").apply {
                    setAttribute("name", "iv_bci_launcher")
                    setAttribute("type", "id")
                    root.appendChild(this)
                }
            }
        }

        // Insert FrameLayout after iv_search inside ll_right_top_status
        document("res/layout/llauncher_activity_new_launcher_main.xml").use { dom ->
            val allElements = dom.getElementsByTagName("*")
            for (i in 0 until allElements.length) {
                val el = allElements.item(i) as? Element ?: continue
                if (el.getAttribute("android:id") != "@id/iv_search") continue
                val parent = el.parentNode ?: continue

                // Outer container FrameLayout
                val container = dom.createElement("FrameLayout").apply {
                    setAttribute("android:id", "@id/iv_bci_launcher")
                    setAttribute("android:layout_width", "@dimen/mw_30dp")
                    setAttribute("android:layout_height", "@dimen/mw_30dp")
                    setAttribute("android:layout_marginStart", "@dimen/mw_16dp")
                }

                // Arrow icon
                dom.createElement("TextView").apply {
                    setAttribute("android:text", "⬇")
                    setAttribute("android:textColor", "#ffffffff")
                    setAttribute("android:textSize", "18sp")
                    setAttribute("android:textStyle", "bold")
                    setAttribute("android:gravity", "center")
                    setAttribute("android:alpha", "0.8")
                    setAttribute("android:layout_width", "match_parent")
                    setAttribute("android:layout_height", "match_parent")
                    container.appendChild(this)
                }

                // Download count badge
                dom.createElement("TextView").apply {
                    setAttribute("android:tag", "bh_dl_badge")
                    setAttribute("android:visibility", "gone")
                    setAttribute("android:text", "")
                    setAttribute("android:textColor", "#ffffffff")
                    setAttribute("android:textSize", "9sp")
                    setAttribute("android:textStyle", "bold")
                    setAttribute("android:gravity", "center")
                    setAttribute("android:background", "#FFCC3333")
                    setAttribute("android:layout_gravity", "top|end")
                    setAttribute("android:layout_width", "14dp")
                    setAttribute("android:layout_height", "14dp")
                    container.appendChild(this)
                }

                val nextSibling = el.nextSibling
                if (nextSibling != null) parent.insertBefore(container, nextSibling)
                else parent.appendChild(container)
                break
            }
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
        bannerHubHudPatch,
        bannerHubComponentManagerPatch,
        bannerHubCpuPatch,
        bannerHubVramPatch,
        bannerHubTaskManagerPatch,
        bannerHubConfigSharingPatch,
        bannerHubDownloadBtnPatch,
        bannerHubOfflineSteamSkipPatch,
        bannerHubBciLauncherPatch,
    )
}
