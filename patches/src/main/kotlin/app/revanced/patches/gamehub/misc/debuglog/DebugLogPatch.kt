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

@Suppress("unused")
val debugLogPatch = bytecodePatch(
    name = "Debug logging",
    description = "Marks the APK debuggable so logcat readers (and ADB run-as) can see app " +
        "output, and pipes every Throwable handed to the app's catch-handler reporter " +
        "(odb.e, the y2d implementation that Firebase Crashlytics — now disabled — " +
        "originally consumed) through Log.e so silently-swallowed exceptions surface in logcat.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(debuggableManifestPatch)

    apply {
        // y2d is the in-app error reporter interface (e/i emit Throwables, others emit
        // breadcrumbs). odb is the real impl that every release-build catch handler routes
        // its caught Throwable into. Disabling Firebase Crashlytics turned its downstream
        // sink into a no-op, so caught exceptions vanish silently. Prepend a Log.e call to
        // odb.e so the Throwable hits logcat before reaching the (now stub) sink.
        firstMethod {
            definingClass == "Lodb;" && name == "e"
        }.apply {
            addInstructions(
                0,
                """
                    const-string v0, "GH600-DEBUG"
                    const-string v1, "y2d.e caught"
                    invoke-static {v0, v1, p1}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
                """,
            )
        }

        // xm7.u(GameInfo, LaunchMethod, Continuation) is the game-import save function.
        // We need definitive evidence of whether it's even being called and which path it
        // exits through. Probe at:
        //   1. Entry — proves the save use case reached this method
        //   2. Catch path (right before y2d.e) — independent of which y2d impl is bound,
        //      so we capture exceptions even if xm7.c is not the odb instance our other
        //      hook covers.
        firstMethod {
            definingClass == "Lxm7;" && name == "u"
        }.apply {
            // Probe 2 first so the index for the catch path stays stable (probe 1 inserts at 0).
            // Pattern: …iget-object pX, vY, Lxm7;->c:Ly2d; → invoke-interface {pX, p3, p0}, Ly2d;->e(…)
            // Insert Log.e right BEFORE the invoke-interface (p3 is the Throwable at this point).
            val y2dCallIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                    getReference<MethodReference>()?.let {
                        it.definingClass == "Ly2d;" && it.name == "e"
                    } == true
            }
            addInstructions(
                y2dCallIdx,
                """
                    const-string v0, "GH600-DEBUG"
                    const-string v1, "xm7.u CATCH"
                    invoke-static {v0, v1, p3}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
                """,
            )

            // Probe 1: entry. xm7.u has .locals 10 so v0/v1 are free locals at the top.
            addInstructions(
                0,
                """
                    const-string v0, "GH600-DEBUG"
                    const-string v1, "xm7.u ENTRY"
                    invoke-static {v0, v1}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I
                """,
            )
        }
    }
}
