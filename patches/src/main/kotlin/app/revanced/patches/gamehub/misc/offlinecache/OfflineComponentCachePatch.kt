package app.revanced.patches.gamehub.misc.offlinecache

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

// ============================================================================
// ⚠ KNOWN BROKEN ON 6.0.4 (2026-05-12)
//
// The patch applies cleanly (CI green, all 9 variants patched on v1.0.0-604)
// but at runtime it doesn't deliver the intended cached-entry fallback. The
// structural anchors and reflective field lookups apparently survived the
// 6.0.2 → 6.0.4 R8 reshuffle on the surface — patcher-side everything looks
// fine — but the runtime behavior regressed somewhere downstream.
//
// Investigation pending. Possible angles to check on the next pass:
//   - Has `mci.a(RepoCategory, Continuation)` itself changed shape beyond
//     the `:goto_2` sentinel? Walk the full method body and compare against
//     the 6.0.2 `eci.a` body the patch was designed around.
//   - Did `xxo`'s field layout / `xxo.c` `ConcurrentHashMap` type change?
//     `PickerCacheFallback.fromXxo` uses single-letter field lookups (`a`,
//     `c`) plus a runtime type sanity check; if `c`'s declared type is no
//     longer assignable to `Map`, the sanity check returns the empty
//     ArrayList silently — visible only via `DebugTrace`.
//   - Is `u6o.<init>` still the disk-hydrator? If 6.0.4 renamed/restructured
//     the hydrator the map could simply be empty at the time the picker
//     consults it, in which case the patch IS firing but has nothing to
//     return.
//
// Online behavior is unchanged because the hook only fires on the
// network-returned-empty path. Safe to leave enabled in v1.0.0-604 (no
// regression for online users); safe to disable via revanced-cli `-d
// "Offline component cache fallback"` for users who want a leaner build.
//
// ============================================================================
// 6.0.2 R8-mangled letter map
//
// ECI_CLASS                  : the ViewModel-side per-category list fetcher
//                              with method
//                              `a(RepoCategory, Continuation) -> Serializable`.
//                              Has three instance fields: `a:Lxxo;` (registry),
//                              `b:Lfei;` (per-category Uaa resolver), and
//                              `c:Lhja;`. Discovered by being the only class
//                              whose `a` method matches this exact signature
//                              in the entire dex tree of GameHub 6.0.2.
// REPO_CATEGORY              : host enum class — NOT R8-mangled, package
//                              `com.xiaoji.egggame.common.winemu.bean`.
// KOTLIN_EMPTY_LIST_CLASS    : Kotlin stdlib `kotlin.collections.EmptyList`,
//                              the sentinel singleton accessed via its
//                              static `a` field. Used as the anchor for the
//                              instruction we replace (the `sget-object` that
//                              loads it). R8 renames it; in 6.0.2 it's `Lz85;`.
//
// To re-derive on a new base APK: decompile (`apktool d --no-res`) and find:
//   - ECI_CLASS by grepping for the unique method signature:
//       grep -rln '\.method public final a(Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;L[a-z0-9]\+;)Ljava/io/Serializable;' smali*/
//     The second parameter is Kotlin coroutine Continuation (also R8-renamed
//     each version); pull it from the matched method's signature.
//   - KOTLIN_EMPTY_LIST_CLASS by searching the matched method for the
//     `sget-object` that's wrapped by `goto :goto_2` returns — the field's
//     `definingClass` is EmptyList.
// 6.0.4: ECI_CLASS Leci; → Lmci;; CONTINUATION_TYPE Lai3; → Lci3;;
//        KOTLIN_EMPTY_LIST_CLASS Lz85; → Lw85;.
private const val ECI_CLASS               = "Lmci;"
private const val REPO_CATEGORY           = "Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;"
private const val CONTINUATION_TYPE       = "Lci3;"
private const val KOTLIN_EMPTY_LIST_CLASS = "Lw85;"

private const val PICKER_FALLBACK =
    "Lapp/revanced/extension/gamehub/winemu/PickerCacheFallback;"

@Suppress("unused")
val offlineComponentCachePatch = bytecodePatch(
    name = "Offline component cache fallback",
    description = "Lets the GPU driver / DXVK / VKD3D / FEXCore / Box64 / " +
        "container pickers show cached entries (from " +
        "sp_winemu_unified_resources.xml) when the device is offline. " +
        "Without this patch, when the network-backed Uaa flow returns an " +
        "empty list, eci.a falls back to Kotlin's EmptyList and the picker " +
        "renders only the embedded built-in versions. With this patch, that " +
        "empty path instead reads xxo.c — the in-memory ConcurrentHashMap " +
        "that u6o.<init> already hydrates from disk at app start — and " +
        "filters by RepoCategory. Online behavior is unchanged because the " +
        "hook only fires on the EmptyList return path; any non-empty Uaa " +
        "list goes through the original filter loop as before.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // ---------------------------------------------------------------
        // eci.a(RepoCategory, Continuation) -> Serializable
        //
        // Two control paths return Kotlin EmptyList:
        //   1. fei.a(category) returned null  (no Uaa wired for this
        //      category — should be unreachable in practice, but the
        //      original method handles it).
        //   2. Uaa.b(cont) emitted an empty list  (network failed /
        //      offline / catalog refresh returned nothing).
        // Both paths `goto :goto_2`. The :goto_2 body is:
        //     sget-object p0, Lz85;->a:Lz85;
        //     return-object p0
        //
        // We swap the sget-object for an invoke-static to our extension,
        // which reads xxo.c (already populated by hydration) and returns
        // a category-filtered ArrayList. The `return-object p0` that
        // follows is left untouched — it now returns our cached list (or
        // an empty ArrayList if the cache itself is empty).
        //
        // Register safety at :goto_2:
        //   - Path 1: p1 (RepoCategory) was just used by `fei.a(p1)` and
        //     was never moved.
        //   - Path 2: the post-suspension resumption rotates registers so
        //     that p1 holds the original RepoCategory again
        //     (`cci.L$0` -> p1 via move-object).
        //   - p0 (this = eci) is intact on both paths because the body
        //     only reads from `iget-object .. p0, Leci;` fields and never
        //     reassigns p0.
        //
        // Why we use the helper instead of doing the read in smali:
        //   - The ConcurrentHashMap iteration + filter + ArrayList build
        //     is ~30 instructions and would need to bump `.locals`.
        //     Java handles it in three lines with reflection, no
        //     register growth required.
        firstMethod {
            definingClass == ECI_CLASS &&
                name == "a" &&
                parameterTypes == listOf(REPO_CATEGORY, CONTINUATION_TYPE) &&
                returnType == "Ljava/io/Serializable;"
        }.apply {
            val sgetIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT &&
                    getReference<FieldReference>()?.definingClass == KOTLIN_EMPTY_LIST_CLASS
            }

            removeInstruction(sgetIdx)
            addInstructions(
                sgetIdx,
                """
                    invoke-static {p0, p1}, $PICKER_FALLBACK->fromXxo(Ljava/lang/Object;Ljava/lang/Object;)Ljava/io/Serializable;
                    move-result-object p0
                """.trimIndent(),
            )
        }
    }
}
