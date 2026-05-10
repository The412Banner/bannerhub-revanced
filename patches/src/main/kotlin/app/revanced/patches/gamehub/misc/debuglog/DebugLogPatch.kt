package app.revanced.patches.gamehub.misc.debuglog

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private val debuggableManifestPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element
            app.setAttribute("android:debuggable", "true")
        }
    }
}

private const val DEBUG_TRACE = "Lapp/revanced/extension/gamehub/debug/DebugTrace;"

// =========================================================================
// 6.0.2 R8-mangled class letter map for DebugLog probe targets
//
// Y2D_INTERFACE      : in-app error reporter interface (e/i emit Throwables).
//                      Anchor: interface declaring `e(Ljava/lang/Throwable;Lmw6;)V`
//                      where `Lmw6;` is the Function0 type.
//                      6.0.0/6.0.1 → Ly2d;   6.0.2 → Lpgd;
// Y2D_IMPL           : real impl that swallowed Throwables to Crashlytics in
//                      stock builds; with Crashlytics disabled the sink is a
//                      no-op. Anchor: concrete class implementing Y2D_INTERFACE
//                      whose `e()` body delegates to `Y2D_INTERFACE->e` then
//                      writes to a sink.
//                      6.0.0/6.0.1 → Lodb;   6.0.2 → Li86;
// SAVE_REPO          : the GAME_LIB_REPO class (matches BypassLogin's anchor).
//                      6.0.0 → Lxm7;   6.0.1 → Lhp7;   6.0.2 → Luu7;
// SAVE_METHOD        : 3-arg `(GameInfo, LaunchMethod, Continuation)` method
//                      on SAVE_REPO that performs the import save and calls
//                      Y2D_INTERFACE.e in its catch handler.
//                      6.0.0/6.0.1 → "u"   6.0.2 → "v"
// RETRO_REPO_WRAPPER : thin repo wrapper around RetroGameDao.upsert.
//                      Anchor: class with method `b(RetroGameEntity, Cont)`
//                      whose body's only meaningful call is RetroGameDao.upsert.
//                      6.0.0/6.0.1 → Ly4i;   6.0.2 → Lyji;
// IMPORT_TXN         : the inner withTransaction body that does both
//                      GameLaunchMethodDao.insert and GameLibraryBaseDao.insert.
//                      Anchor: class with `invokeSuspend` whose body contains
//                      both interface-invokes. 6.0.2 has multiple candidates
//                      because the import path branches; we pick the one
//                      whose .locals count (70) most closely matches 6.0.0's
//                      original (.locals 69) — i.e. the primary path.
//                      6.0.0/6.0.1 → Lel7;   6.0.2 → Lvs7;
// =========================================================================

@Suppress("unused")
val debugLogPatch = bytecodePatch(
    name = "Debug logging",
    description = "Marks the APK debuggable and routes diagnostic probes through " +
        "DebugTrace. Writes to logcat with tag GH600-DEBUG at Log.i level (the device " +
        "filter strips app-tagged Log.e but lets Log.i through) and also appends to a " +
        "file on external storage at " +
        "/storage/emulated/0/Android/data/com.xiaoji.egggame/files/gh600-debug.log " +
        "as a backup channel.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(debuggableManifestPatch)

    apply {
        // y2d is the in-app error reporter interface (e/i emit Throwables). odb is its
        // real impl: every release-build catch handler funnels caught Throwables here,
        // and disabling Firebase Crashlytics turned its sink into a no-op. Trace every
        // Throwable that reaches odb.e so silently-swallowed exceptions surface.
        firstMethod {
            definingClass == "Li86;" && name == "e"
        }.apply {
            addInstructions(
                0,
                """
                    const-string v0, "y2d.e caught"
                    invoke-static {v0, p1}, $DEBUG_TRACE->write(Ljava/lang/String;Ljava/lang/Throwable;)V
                """,
            )
        }

        // xm7.u(GameInfo, LaunchMethod, Continuation) is the game-import save function.
        // Probe at:
        //   1. Entry — confirms the save use case reached this method
        //   2. Catch path (right before y2d.e) — independent of which y2d impl is bound
        //      to xm7.c, so we capture exceptions even if it is not odb.
        firstMethod {
            definingClass == "Luu7;" && name == "v"
        }.apply {
            // Insert probe 2 first so its index doesn't shift when probe 1 inserts at 0.
            val y2dCallIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    getReference<MethodReference>()?.let {
                        it.definingClass == "Lpgd;" && it.name == "e"
                    } == true
            }
            addInstructions(
                y2dCallIdx,
                """
                    const-string v0, "uu7.v CATCH"
                    invoke-static {v0, p3}, $DEBUG_TRACE->write(Ljava/lang/String;Ljava/lang/Throwable;)V
                """,
            )

            addInstructions(
                0,
                """
                    const-string v0, "uu7.v ENTRY"
                    invoke-static {v0}, $DEBUG_TRACE->write(Ljava/lang/String;)V
                """,
            )
        }

        // y4i.b(RetroGameEntity, Continuation) is the thin repo wrapper around
        // RetroGameDao.upsert. Every retro-game DB write goes through here.
        // If this probe fires after Save → write happened, bug is library-read-side.
        // If it does NOT fire → write was never attempted, bug is upstream.
        // y4i.b has .locals 0, so we use a no-arg marker that needs no register.
        firstMethod {
            definingClass == "Lyji;" && name == "b"
        }.apply {
            addInstructions(
                0,
                "invoke-static {}, $DEBUG_TRACE->markY4iUpsert()V",
            )
        }

        // el7.invokeSuspend is the Room transaction body for game-library import.
        // It does two inserts:
        //   1. GameLaunchMethodDao.insert(GameLaunchMethodTable, Continuation)
        //   2. GameLibraryBaseDao.insert(GameLibraryBaseTable, Continuation)
        // Probe entry plus right before each insert. If both insert markers fire
        // → write side is fine, bug is library-read-side. If neither → transaction
        // body bailed before reaching the inserts.
        firstMethod {
            definingClass == "Lvs7;" && name == "invokeSuspend"
        }.apply {
            // Walk instructions backwards so we can insert without index drift.
            val launchInsertIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    getReference<MethodReference>()?.let {
                        it.definingClass ==
                            "Lcom/xiaoji/egggame/game/database/dao/GameLaunchMethodDao;" &&
                            it.name == "insert"
                    } == true
            }
            val libraryInsertIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    getReference<MethodReference>()?.let {
                        it.definingClass ==
                            "Lcom/xiaoji/egggame/game/database/dao/GameLibraryBaseDao;" &&
                            it.name == "insert"
                    } == true
            }
            // Insert from highest index to lowest so earlier insertions don't
            // shift later target indices.
            val higherIdx = maxOf(launchInsertIdx, libraryInsertIdx)
            val lowerIdx  = minOf(launchInsertIdx, libraryInsertIdx)
            val higherMarker = if (higherIdx == libraryInsertIdx) "markLibraryInsert" else "markLaunchInsert"
            val lowerMarker  = if (lowerIdx  == libraryInsertIdx) "markLibraryInsert" else "markLaunchInsert"
            addInstructions(
                higherIdx,
                "invoke-static {}, $DEBUG_TRACE->$higherMarker()V",
            )
            addInstructions(
                lowerIdx,
                "invoke-static {}, $DEBUG_TRACE->$lowerMarker()V",
            )
            addInstructions(
                0,
                "invoke-static {}, $DEBUG_TRACE->markEl7Entry()V",
            )
        }
    }
}
