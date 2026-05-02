package app.revanced.patches.gamehub.components

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

// Lgxh;->a(RepoCategory, Continuation): Serializable is the canonical "give me
// every component of this category" entry point. It's a Kotlin suspend function
// compiled to a coroutine state machine — multiple return-object exits exist;
// most return COROUTINE_SUSPENDED at suspension boundaries, only the final
// (last-in-source-order) one returns the assembled ArrayList<WinEmuRepo>.
// findInstructionIndicesReversedOrThrow(RETURN_OBJECT).first() picks that one.
//
// p1 = the RepoCategory enum instance (caller supplied)
// v0 = the ArrayList that the suspend function is about to return (assembled by
//      the time we reach the final return-object — after the last suspension
//      resume completes)
private const val GXH_CLASS = "Lgxh;"
private const val REPO_CATEGORY_CLASS = "Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;"
private const val INJECTOR_DESCRIPTOR =
    "Lapp/revanced/extension/gamehub/components/ComponentInjector;"

@Suppress("unused")
val componentInjectionPatch = bytecodePatch(
    name = "Component injection",
    description = "Merges sidecar-registered (BannerHub Component Manager-injected) " +
        "components into the host app's per-category component list at the " +
        "Lgxh;->a(RepoCategory, Continuation) boundary, so manager-added entries " +
        "appear alongside server-supplied ones in per-game-settings dropdowns.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod {
            definingClass == GXH_CLASS &&
                name == "a" &&
                parameterTypes.size == 2 &&
                parameterTypes[0] == REPO_CATEGORY_CLASS
        }.apply {
            // Suspend functions have multiple return-object exits — the LAST
            // one (in source order) is the success path returning the assembled
            // list. findInstructionIndicesReversedOrThrow returns highest-first;
            // the highest index IS the last return-object in source order.
            //
            // We hook only that one, not the intermediate suspension returns,
            // because ComponentInjector.append should run exactly once per
            // dropdown load — when the assembled list is ready — not on every
            // resume of the state machine.
            val finalReturnIdx = findInstructionIndicesReversedOrThrow(
                Opcode.RETURN_OBJECT,
            ).first()

            addInstructions(
                finalReturnIdx,
                """
                    invoke-static {p1, v0}, $INJECTOR_DESCRIPTOR->append(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
                    move-result-object v0
                """,
            )
        }
    }
}
