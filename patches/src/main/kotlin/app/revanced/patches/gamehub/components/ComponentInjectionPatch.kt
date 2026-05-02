package app.revanced.patches.gamehub.components

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

// Ll9o;->z(RepoCategory)ArrayList<WinEmuRepo> is the read-side accessor that
// per-game settings dropdowns consume. It iterates the in-memory ConcurrentHashMap
// at l9o.c (the registry cache hydrated from API + sp_winemu_unified_resources.xml),
// filters by category, and returns an ArrayList. Single non-suspending body, one
// return-object — clean hook for read-time injection.
//
// Hooking the READ side (rather than the registry XML write side) keeps sidecar
// entries safe from API-driven overwrites: we never touch sp_winemu_unified_resources;
// our entries live in sp_bh_components.xml and get unioned into the returned
// ArrayList every time the dropdown opens.
//
// Previous attempts (Lgxh;->a, Lm13;->b) targeted code paths that don't run on
// dropdown open in this build.
private const val L9O_CLASS = "Ll9o;"
private const val REPO_CATEGORY_CLASS =
    "Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;"
private const val INJECTOR_DESCRIPTOR =
    "Lapp/revanced/extension/gamehub/components/ComponentInjector;"

@Suppress("unused")
val componentInjectionPatch = bytecodePatch(
    name = "Component injection",
    description = "Merges sidecar-registered (BannerHub Component Manager-injected) " +
        "components into the host's in-memory registry list at the " +
        "Ll9o;->z(RepoCategory) read-side boundary, so manager-added entries " +
        "appear alongside server/cache-supplied ones in per-game-settings dropdowns " +
        "every time the dropdown opens.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        firstMethod {
            definingClass == L9O_CLASS &&
                name == "z" &&
                parameterTypes.size == 1 &&
                parameterTypes[0] == REPO_CATEGORY_CLASS
        }.apply {
            // Single return-object at end of method. p1=RepoCategory, v0=ArrayList result.
            val finalReturnIdx = findInstructionIndicesReversedOrThrow(
                Opcode.RETURN_OBJECT,
            ).first()

            addInstructions(
                finalReturnIdx,
                """
                    invoke-static {p1, v0}, $INJECTOR_DESCRIPTOR->appendByCategory(Ljava/lang/Object;Ljava/util/List;)Ljava/util/ArrayList;
                    move-result-object v0
                """,
            )
        }
    }
}
