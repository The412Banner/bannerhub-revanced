package app.revanced.patches.gamehub.ui.accountvalue

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.EXTENSION_CURRENCY_HELPER
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS = EXTENSION_CURRENCY_HELPER

@Suppress("unused")
val accountCurrencyPatch = bytecodePatch(
    name = "Account currency display",
    description = "Shows the real currency code instead of hardcoded ¥/￥ " +
        "in the Steam account value and game price labels.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, creditsPatch)

    apply {
        // Injection A: Capture the currency string from every PICS price conversion.
        // SteamServiceImpl.C() starts with:
        //   0: const-string p0, "<this>"
        //   1: invoke-static {p1, p0}, Intrinsics.g(...)
        // We inject at index 2, using v0 as a scratch register (it hasn't been assigned yet).
        firstMethod {
            returnType == "Lcom/xj/common/bean/SteamGamePriceEntity;" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == "Lcom/xj/standalone/steam/data/db/tables/apps/SteamPicsAppPrice;"
        }.addInstructions(
            2,
            """
                invoke-virtual {p1}, Lcom/xj/standalone/steam/data/db/tables/apps/SteamPicsAppPrice;->getCurrency()Ljava/lang/String;
                move-result-object v0
                invoke-static {v0}, $EXTENSION_CLASS->setCurrency(Ljava/lang/String;)V
            """,
        )

        // Injection B: Update the account value title on the home screen user info card.
        // SteamUserInfoViewHolder.z(SteamAccount) ends with return-void.
        // p0 = this (VBViewHolder), so we call f() to get the binding.
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/ui/steam/SteamUserInfoViewHolder;" &&
                name == "z" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == "Lcom/xj/common/bean/SteamAccount;"
        }.apply {
            val returnIndex = implementation!!.instructions.size - 1
            addInstructions(
                returnIndex,
                """
                    invoke-virtual {p0}, Lcom/xj/common/view/adapter/VBViewHolder;->f()Landroidx/viewbinding/ViewBinding;
                    move-result-object v0
                    check-cast v0, Lcom/xj/landscape/launcher/databinding/LlauncherItemSteamUserInfoBinding;
                    iget-object v0, v0, Lcom/xj/landscape/launcher/databinding/LlauncherItemSteamUserInfoBinding;->tvAccountValueTitle:Landroid/widget/TextView;
                    invoke-static {v0}, $EXTENSION_CLASS->updateLabel(Landroid/widget/TextView;)V
                """,
            )
        }

        // Injection C: Update the account value title and game price title on the personal info screen.
        // SteamPersonalInfoFragment.V0() casts p0 to the binding, then extracts tvAccountValue.
        // We inject right after the check-cast (while p0 is still the binding) to grab
        // tvAccountValueT (the title) and update it, then also grab viewGamePrice to find
        // and update the anonymous "Game Price (￥)" sibling label. v0 is safe to use as scratch.
        firstMethod {
            definingClass == "Lcom/xj/winemu/ui/gamelibrary/steam/ui/SteamPersonalInfoFragment;" &&
                name == "V0" &&
                parameterTypes.size == 2 &&
                parameterTypes[1] == "Ljava/lang/Float;"
        }.apply {
            val checkCastIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CHECK_CAST &&
                    (this as? ReferenceInstruction)?.reference?.toString() ==
                    "Lcom/xj/winemu/databinding/WinemuFSteamPersonalInfoBinding;"
            }
            addInstructions(
                checkCastIndex + 1,
                """
                    iget-object v0, p0, Lcom/xj/winemu/databinding/WinemuFSteamPersonalInfoBinding;->tvAccountValueT:Landroid/widget/TextView;
                    invoke-static {v0}, $EXTENSION_CLASS->updateLabel(Landroid/widget/TextView;)V
                    iget-object v0, p0, Lcom/xj/winemu/databinding/WinemuFSteamPersonalInfoBinding;->viewGamePrice:Lcom/xj/lib/shape/view/ShapeView;
                    invoke-static {v0}, $EXTENSION_CLASS->updateGamePriceLabel(Landroid/view/View;)V
                """,
            )
        }

        addCredit("Account currency display", "PlayDay" to "https://github.com/playday3008")
    }
}
