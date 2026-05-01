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
            definingClass == "Lodb;" && name == "e"
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
            definingClass == "Lxm7;" && name == "u"
        }.apply {
            // Insert probe 2 first so its index doesn't shift when probe 1 inserts at 0.
            val y2dCallIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    getReference<MethodReference>()?.let {
                        it.definingClass == "Ly2d;" && it.name == "e"
                    } == true
            }
            addInstructions(
                y2dCallIdx,
                """
                    const-string v0, "xm7.u CATCH"
                    invoke-static {v0, p3}, $DEBUG_TRACE->write(Ljava/lang/String;Ljava/lang/Throwable;)V
                """,
            )

            addInstructions(
                0,
                """
                    const-string v0, "xm7.u ENTRY"
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
            definingClass == "Ly4i;" && name == "b"
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
            definingClass == "Lel7;" && name == "invokeSuspend"
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
