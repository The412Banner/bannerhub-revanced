package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// Neutralizes XiaoJi's two analytics-event reporters by REDIRECTING their POST
// URLs to loopback (http://127.0.0.1) — every analytics URL const-string in the
// reporter method is overwritten immediately after it is loaded, so the HTTP
// client targets a dead local address and the upload fails (connection refused)
// without any data leaving the device. The reporters' own coroutine error paths
// handle the failure (these are fire-and-forget telemetry posts whose responses
// the app does not depend on), so no fake result object has to be fabricated.
//
// Why redirect (not the 6.0.4 stub-return approach): 6.0.4 early-returned a fake
// success instance (Lyw5 for /events, Lxnm for device-performance-config). 6.0.7
// replaced that result model — the success types come back through coroutine
// continuations (no clean caller check-cast to read the class off), and the
// related error type Lg50 extends RuntimeException rather than being a value
// wrapper. Fabricating the wrong success type APPLIES cleanly but crashes at
// runtime (apply-only CI can't catch it). The URL redirect is the same proven,
// crash-safe technique used by DisableOtaUpdatesPatch and is anchored purely on
// the stable URL strings, so it survives R8 class-letter reshuffles.
//
// Surface (6.0.7; both methods env-switch the URL across dev2 / beta / prod, so
// ALL of them get redirected):
//   - /events            POST batch    -> Lzy5;->a(Ljava/util/Collection;Lkq3;)  (was Lcx5;->a)
//   - /events/device-performance-config -> Lb34;->invokeSuspend                  (was Lnh4;->invokeSuspend)
// Anchors: the production /events URL (unique to the batch reporter — the
// device-perf strings carry the longer .../device-performance-config suffix),
// and any const-string ending in "/events/device-performance-config".
//
// 6.0.9: patch is string/marker-anchored so the R8 class reshuffle is a no-op
// (batch reporter is now Ll76;, device-perf Lvw3;), BUT the device-perf ENDPOINT
// PATH CHANGED: ".../events/device-performance-config" -> ".../events/device-
// performance-session-summary". The plain /events URL + the "vgabc.com/events"
// marker are unchanged (marker still substring-matches all dev2/beta/prod
// variants of BOTH reporters). Only DEVICE_PERF_SUFFIX needed updating.
// =============================================================================

private const val EVENTS_URL = "https://statistic-gamehub-api.vgabc.com/events"
private const val DEVICE_PERF_SUFFIX = "/events/device-performance-session-summary"
private const val ANALYTICS_MARKER = "vgabc.com/events"
private const val LOOPBACK = "http://127.0.0.1"

@Suppress("unused")
val stubAnalyticsEventsPatch = bytecodePatch(
    name = "Stub analytics events",
    description = "Redirects XiaoJi's analytics-event POST URLs (the /events batch reporter " +
        "and /events/device-performance-config) to http://127.0.0.1 so no telemetry reaches " +
        "statistic-gamehub-api.vgabc.com. Anchored on the stable URL strings (all dev2/beta/prod " +
        "environment variants are redirected); the reporters' own error paths swallow the " +
        "connection-refused, so nothing crashes.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // /events batch reporter (Lzy5;->a). The plain /events URL is unique to
        // this method — the device-perf reporter's strings carry a longer suffix.
        firstMethod {
            implementation?.instructions?.any { ins ->
                (ins as? ReferenceInstruction)?.reference
                    ?.let { it is StringReference && it.string == EVENTS_URL } == true
            } == true
        }.apply {
            val idxs = implementation!!.instructions.toList().withIndex().filter { (_, ins) ->
                (ins as? ReferenceInstruction)?.reference
                    ?.let { it is StringReference && it.string.contains(ANALYTICS_MARKER) } == true
            }.map { it.index }
            require(idxs.isNotEmpty()) {
                "StubAnalyticsEventsPatch: no /events URL const-string in batch reporter"
            }
            // Reverse order so earlier indices stay valid after each insertion.
            idxs.reversed().forEach { i ->
                val reg = getInstruction<OneRegisterInstruction>(i).registerA
                addInstruction(i + 1, "const-string v$reg, \"$LOOPBACK\"")
            }
        }

        // /events/device-performance-config reporter (Lb34;->invokeSuspend).
        firstMethod {
            implementation?.instructions?.any { ins ->
                (ins as? ReferenceInstruction)?.reference
                    ?.let { it is StringReference && it.string.endsWith(DEVICE_PERF_SUFFIX) } == true
            } == true
        }.apply {
            val idxs = implementation!!.instructions.toList().withIndex().filter { (_, ins) ->
                (ins as? ReferenceInstruction)?.reference
                    ?.let { it is StringReference && it.string.contains(ANALYTICS_MARKER) } == true
            }.map { it.index }
            require(idxs.isNotEmpty()) {
                "StubAnalyticsEventsPatch: no device-performance-config URL const-string"
            }
            idxs.reversed().forEach { i ->
                val reg = getInstruction<OneRegisterInstruction>(i).registerA
                addInstruction(i + 1, "const-string v$reg, \"$LOOPBACK\"")
            }
        }
    }
}
