package app.revanced.patches.gamehub.components

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

// Lm13;->b(Lexh;)Ljava/lang/Object; is the x0a implementation for
// RepoCategory.COMPONENT (m13's ctor binds it to that category). Its body is a
// short non-suspending dispatcher that awaits a coroutine result and returns
// the assembled ArrayList<WinEmuRepo>. Per-game settings dropdowns
// (FEXCore / Box64 / DXVK / VKD3D / GPU pickers) consume this list and filter
// downstream by EnvLayerEntity.type + name prefix.
//
// The previous hook on Lgxh;->a(RepoCategory, Continuation) was on the wrong
// path — that's image-fs / container processing, not the component-list feed.
//
// At the single return-object, p0 holds the awaited list (after move-result-object).
// We pass it to ComponentInjector.appendComponents and replace it with the merged
// result. No category check is needed in the extension because m13 is COMPONENT-only.
private const val M13_CLASS = "Lm13;"
private const val EXH_CLASS = "Lexh;"
private const val INJECTOR_DESCRIPTOR =
    "Lapp/revanced/extension/gamehub/components/ComponentInjector;"

@Suppress("unused")
val componentInjectionPatch = bytecodePatch(
    name = "Component injection",
    description = "Merges sidecar-registered (BannerHub Component Manager-injected) " +
        "components into the host's per-category component list at the " +
        "Lm13;->b(Lexh;) boundary, so manager-added entries appear alongside " +
        "API-supplied ones in per-game-settings dropdowns.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        firstMethod {
            definingClass == M13_CLASS &&
                name == "b" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == EXH_CLASS
        }.apply {
            // m13.b has exactly one return-object — the awaited list. Hook there.
            val finalReturnIdx = findInstructionIndicesReversedOrThrow(
                Opcode.RETURN_OBJECT,
            ).first()

            addInstructions(
                finalReturnIdx,
                """
                    invoke-static {p0}, $INJECTOR_DESCRIPTOR->appendComponents(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p0
                """,
            )
        }
    }
}
