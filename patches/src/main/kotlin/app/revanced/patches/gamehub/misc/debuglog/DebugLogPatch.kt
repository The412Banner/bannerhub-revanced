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
        "DebugTrace, an extension helper that appends to a file on external storage " +
        "(/storage/emulated/0/Android/data/com.xiaoji.egggame/files/gh600-debug.log). " +
        "Logcat readers on this device filter out app-tagged Log.e calls; file output " +
        "is the only reliable channel.",
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
    }
}
