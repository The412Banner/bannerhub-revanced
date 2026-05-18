package app.revanced.patches.gamehub.misc.offlinecache

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// ============================================================================
// Offline component-picker fix (WRITE-SIDE, at the Room API cache).
//
// Forensically-established root cause (2026-05-18): the 6.0.4 pickers are fed
// by the cached `winemu_game_config` API response (Room `api_cache` in
// egggame.db); its JSON `component[]` is the per-type option list. Online the
// repo merges the user's downloaded components in; offline the cached row is
// served WITHOUT them (merge is network-gated; sp_winemu_unified_resources is
// never read offline — proven by inotify + sink-verified probes). So offline
// pickers show only the server-recommended set.
//
// Earlier read-side attempts hooked the wrong layer (mci.a — dead; so7.<init>
// — not constructed offline, sink-verified). This instead hooks the single
// Room WRITE chokepoint, `ApiCacheDao.saveCache(ApiCacheEntity)`: before any
// winemu_game_config response is persisted, ApiCacheAugment unions the user's
// saved catalog (sp_winemu_unified_resources) into `component[]`. The on-disk
// cache the offline picker reads then already contains every downloaded
// component (the picker sub-filters by `type`). Dedupe by name; server set
// preserved/first; any failure leaves the entity untouched (cache & picker
// can never be broken). Takes effect after one online winemu_game_config
// fetch (happens whenever the user is online / downloads components).
//
// Register-safe: single invoke-static + move-result reusing the entity arg
// register; check-cast restores its static type for the verifier. Anchors are
// non-obfuscated (ApiCacheDao_Impl.saveCache, ApiCacheEntity) so a base bump
// can't silently break the hook the way an obfuscated anchor would.
// ============================================================================

private const val DAO_IMPL =
    "Lcom/xiaoji/egggame/core/database/dao/ApiCacheDao_Impl;"
private const val API_CACHE_ENTITY =
    "Lcom/xiaoji/egggame/core/database/entity/ApiCacheEntity;"
private const val AUGMENT =
    "Lapp/revanced/extension/gamehub/winemu/ApiCacheAugment;"

@Suppress("unused")
val apiCacheAugmentPatch = bytecodePatch(
    name = "Offline component picker — API cache merge",
    description = "Unions the user's saved/downloaded components into the " +
        "cached winemu_game_config response at ApiCacheDao.saveCache, so the " +
        "GPU driver / DXVK / VKD3D / FEXCore / Box64 / container pickers list " +
        "downloaded components offline (write-side; dedupe-safe; fail-safe).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // ApiCacheDao_Impl.saveCache(ApiCacheEntity, Continuation) -> Object
        // p0=this, p1=ApiCacheEntity, p2=Continuation. Anchor on the
        // non-obfuscated entity param type (Continuation type is obfuscated).
        firstMethod {
            definingClass == DAO_IMPL &&
                name == "saveCache" &&
                parameterTypes.size == 2 &&
                parameterTypes[0] == API_CACHE_ENTITY
        }.apply {
            addInstructions(
                0,
                """
                    invoke-static {p1}, $AUGMENT->augment(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                    check-cast p1, $API_CACHE_ENTITY
                """.trimIndent(),
            )
        }
    }
}
