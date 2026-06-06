package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Element

// =============================================================================
// Layer B — Manifest neutralization.
//
// Mob auto-initialises via <provider android:name="com.mob.MobProvider">, which
// Android's ContentProvider machinery brings up BEFORE Application.onCreate.
// Without disabling that registration we can't actually keep the SDK dormant
// just by stripping bytecode init calls — by the time onCreate runs, MobProvider
// has already had a chance to fire collection routines.
//
// Strategy: set android:enabled="false" on every <provider>/<service>/<receiver>/
// <activity> whose android:name starts with com.mob. or cn.fly. (Mob's analytics
// submodule, same vendor). <meta-data> entries can't be disabled by attribute
// so they're removed outright.
// =============================================================================

private val mobNamePrefixes = listOf(
    "com.mob.",   // core + pushsdk + plugins (fcm/honor/huawei/meizu/oppo/vivo/xiaomi) + MobID + tools
    "cn.fly.",    // Mob's analytics submodule, same vendor
)

private val componentTagsToDisable = listOf(
    "provider", "service", "receiver", "activity",
)

private fun Element.hasMobNamespacedName(): Boolean {
    val name = getAttribute("android:name")
    return mobNamePrefixes.any { name.startsWith(it) }
}

private val disableMobPushManifestPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element

            componentTagsToDisable.forEach { tag ->
                val nodes = app.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    if (node.hasMobNamespacedName()) {
                        node.setAttribute("android:enabled", "false")
                    }
                }
            }

            // <meta-data> doesn't support android:enabled — remove outright.
            val metas = app.getElementsByTagName("meta-data")
            val toRemove = mutableListOf<Element>()
            for (i in 0 until metas.length) {
                val node = metas.item(i) as Element
                if (node.hasMobNamespacedName()) toRemove.add(node)
            }
            toRemove.forEach { app.removeChild(it) }
        }
    }
}

// =============================================================================
// Layer A — Bytecode call-site removal.
//
// Strips the three Mob init invocations from XiaoJi's own bootstrap code:
//
//   BaseAndroidApp.onCreate (stable class name, doesn't reshuffle):
//     1. Lcom/mob/MobSDK;->submitPolicyGrantResult(Z)V      [consent gate]
//     2. Lcom/mob/pushsdk/MobPush;->addPushReceiverInMain   [receiver register]
//
//   <obfuscated-helper>.N(Landroid/content/Context;)V (R8-mangled class letter
//   reshuffles every minor version — anchored STRUCTURALLY by predicate
//   rather than class letter so this survives base bumps):
//     3. Lcom/mob/MobSDK;->submitPolicyGrantResult(Z)V      [second consent]
//
// All three are void-returning single invoke-static instructions with no
// move-result follow-on, so removal is verifier-safe (compare to
// DisableCrashlyticsPatch which had to preserve a const/4 between removals
// to keep v2's type consistent across the join point).
//
// Downstream calls in the helper method (setClickNotificationToLaunchMainActivity,
// getRegistrationId, restartPush x2) are intentionally LEFT in place. Without
// the policy grant the SDK stays dormant and these calls either no-op or
// throw NPE that the existing :try_start/.catchall around restartPush()
// already catches. Surgically removing them mid-method would break the
// try-catch label structure.
// =============================================================================

private const val MOB_SDK_PREFIX = "Lcom/mob/MobSDK;->submitPolicyGrantResult"
private const val MOB_PUSH_PREFIX = "Lcom/mob/pushsdk/MobPush;->addPushReceiverInMain"

@Suppress("unused")
val disableMobPushPatch = bytecodePatch(
    name = "Disable Mob Push tracking",
    description = "Stops the bundled Mob Push SDK (com.mob.*) from initialising. " +
        "Removes the two policy-grant call sites in XiaoJi's bootstrap code and the " +
        "push-receiver registration in BaseAndroidApp.onCreate, then disables every " +
        "Mob-namespaced <provider>/<service>/<receiver>/<activity> in the manifest so " +
        "the SDK can't bootstrap via its ContentProvider auto-init either. Inbound push " +
        "delivery and Mob's device-ID collection (MobIDService/MobIDActivity) are both " +
        "stopped. The 'cn.fly.*' Mob analytics submodule is neutralised by the same rules.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(disableMobPushManifestPatch)

    apply {
        // 1. App-class init helper. The actual Mob calls live in an initializer
        //    method (`a()V` in 6.0.4, `b()V` in 6.0.7) called from onCreate, NOT
        //    in onCreate itself — onCreate just delegates. Anchor structurally on
        //    "method on the app class that contains the submitPolicyGrantResult
        //    invoke" so the helper name doesn't matter. 6.0.7 renamed the app
        //    class Lcom/xiaoji/egggame/BaseAndroidApp; → Lcom/xiaoji/egggame/AndroidApp;.
        firstMethod {
            definingClass == "Lcom/xiaoji/egggame/AndroidApp;" &&
                implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_STATIC &&
                        (ins as? ReferenceInstruction)?.reference?.toString()
                            ?.startsWith(MOB_SDK_PREFIX) == true
                } == true
        }.apply {
            val policyGrantIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    (this as ReferenceInstruction).reference.toString()
                        .startsWith(MOB_SDK_PREFIX)
            }
            val addReceiverIdx = indexOfFirstInstructionOrThrow(policyGrantIdx) {
                opcode == Opcode.INVOKE_STATIC &&
                    (this as ReferenceInstruction).reference.toString()
                        .startsWith(MOB_PUSH_PREFIX)
            }
            // Reverse order so earlier indices remain valid after removal.
            removeInstruction(addReceiverIdx)
            removeInstruction(policyGrantIdx)
        }

        // 2. Helper method on the R8-mangled config class (`Lnt5;->N` in 6.0.4,
        //    `Lns8;->D` in 6.0.7 — name changes every minor version).
        //    Structural anchor: a method whose only parameter is
        //    Landroid/content/Context, returns V, lives outside the app class,
        //    and contains a submitPolicyGrantResult invoke.
        firstMethod {
            definingClass != "Lcom/xiaoji/egggame/AndroidApp;" &&
                returnType == "V" &&
                parameters.size == 1 &&
                parameters[0].toString() == "Landroid/content/Context;" &&
                implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_STATIC &&
                        (ins as? ReferenceInstruction)?.reference?.toString()
                            ?.startsWith(MOB_SDK_PREFIX) == true
                } == true
        }.apply {
            val policyGrantIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    (this as ReferenceInstruction).reference.toString()
                        .startsWith(MOB_SDK_PREFIX)
            }
            removeInstruction(policyGrantIdx)
        }
    }
}
