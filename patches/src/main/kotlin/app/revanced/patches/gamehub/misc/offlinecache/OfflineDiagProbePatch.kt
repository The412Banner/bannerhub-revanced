package app.revanced.patches.gamehub.misc.offlinecache

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// ============================================================================
// ⚠ THROWAWAY DIAGNOSTIC — DO NOT MERGE / DO NOT SHIP
//
// MODEL-FREE round. Every hypothesised picker source has been empirically
// refuted by the proven Context-free sink: mci.a (dead), so7.<init> (not
// constructed offline), and — pre3 online+offline — kek.o / j7o.<init> /
// f4o.a / myo.w fire on NEITHER run (only `beacon`). So the working online
// menu is NOT built by the catalog→j7o/myo path either. Stop guessing.
//
// The picker shows saved-catalog content (sp_winemu_unified_resources). Two
// unavoidable chokepoints, hooked with a STACK-TRACE dump so the real,
// obfuscated caller chain names itself:
//   1. beacon()        @ BaseAndroidApp.onCreate  — run separator (online
//                        cold-launch vs offline cold-launch; 2 beacons).
//   2. catalogAccess() @ je6.invoke() sp_winemu_unified_resources branch —
//                        THE sole accessor of the saved-catalog prefs. Stack
//                        = whoever reads the catalog (incl. the working
//                        online picker).
//   3. apiCacheRead()  @ ApiCacheDao_Impl.getCache$lambda$0 (sync Room read;
//                        p0 = cache_key) — whoever reads the cached
//                        winemu_game_config. Confirms (or refutes) that the
//                        OFFLINE picker renders the cached config → decides
//                        whether the worker-side fix lands.
//
// Read: compare the two timestamped runs. The frames that appear in the
// online run but not offline (CATALOG-PREFS / API-CACHE stacks) are the real
// picker path. All injected calls are register-safe (zero-arg, or one
// already-live param reg passed by value).
// ============================================================================

private const val APP_CLASS = "Lcom/xiaoji/egggame/BaseAndroidApp;"
private const val JE6_CLASS = "Lje6;"
private const val API_DAO_IMPL =
    "Lcom/xiaoji/egggame/core/database/dao/ApiCacheDao_Impl;"
private const val CATALOG_PREFS = "sp_winemu_unified_resources"
private const val DIAG =
    "Lapp/revanced/extension/gamehub/winemu/OfflineDiag;"

@Suppress("unused")
val offlineDiagProbePatch = bytecodePatch(
    name = "Offline picker diagnostic probe (THROWAWAY)",
    description = "Model-free stack-trace probes (beacon + catalog-prefs " +
        "accessor + api_cache read) to name the real picker data path " +
        "online vs offline. Throwaway.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // --- 1: CONTROL beacon @ BaseAndroidApp.onCreate ------------------
        firstMethod {
            definingClass == APP_CLASS &&
                name == "onCreate" &&
                returnType == "V" &&
                parameterTypes.isEmpty()
        }.apply {
            val superIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_SUPER &&
                    getReference<MethodReference>()?.name == "onCreate"
            }
            addInstructions(superIdx + 1, "invoke-static {}, $DIAG->beacon()V")
        }

        // --- 2: catalog-prefs accessor (je6.invoke, the
        //        sp_winemu_unified_resources packed-switch branch) ----------
        firstMethod {
            definingClass == JE6_CLASS &&
                name == "invoke" &&
                returnType == "Ljava/lang/Object;" &&
                parameterTypes.isEmpty()
        }.apply {
            // inject right before the const-string that selects the catalog
            // prefs → fires only for genuine catalog reads.
            val idx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CONST_STRING &&
                    getReference<StringReference>()?.string == CATALOG_PREFS
            }
            addInstructions(idx, "invoke-static {}, $DIAG->catalogAccess()V")
        }

        // --- 3: api_cache sync read worker (getCache$lambda$0;
        //        p0 = cache_key String) ---------------------------------
        firstMethod {
            definingClass == API_DAO_IMPL &&
                name == "getCache\$lambda\$0" &&
                parameterTypes.size == 3 &&
                parameterTypes[0] == "Ljava/lang/String;"
        }.apply {
            addInstructions(
                0,
                "invoke-static {p0}, $DIAG->apiCacheRead(Ljava/lang/Object;)V",
            )
        }
    }
}
