package app.revanced.patches.gamehub.misc.permissions

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.all.misc.packagename.changePackageNamePatch
import app.revanced.patches.all.misc.packagename.packageNameOption
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.asSequence
import app.revanced.util.getNode
import org.w3c.dom.Element

private const val ORIGINAL_PACKAGE = "com.xiaoji.egggame"
private const val ORIGINAL_PREFIX = "$ORIGINAL_PACKAGE."

/** Attributes whose value names a permission. */
private val PERMISSION_ATTRIBUTES = listOf(
    "android:permission",
    "android:readPermission",
    "android:writePermission",
)

@Suppress("unused")
val rewriteCustomPermissionsPatch = resourcePatch(
    name = "Rewrite custom permissions per variant",
    description = "Renames upstream-baked custom permissions (e.g. com.xiaoji.egggame.permission.C2D_MESSAGE) " +
        "to use the variant package, so multiple variants can install side-by-side without " +
        "INSTALL_FAILED_DUPLICATE_PERMISSION on Android 7+ (which surfaces as " +
        "\"package conflicts with a current package\" in the package installer UI). " +
        "ChangePackageNamePatch's updatePermissions option only rewrites the hardcoded " +
        "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION; this patch handles the rest. Every " +
        "<permission>/<uses-permission> whose name starts with the original package is " +
        "renamed (6.1+ added com.xiaoji.egggame.push.permission.MESSAGE, outside the old " +
        "'.permission.' prefix, which collided with stock GameHub 6.x and any other-key " +
        "GameHub 6.x mod), and android:permission / readPermission / writePermission guards " +
        "on components are rewritten to match.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(changePackageNamePatch)

    afterDependents {
        // packageNameOption.value is the source of truth for the variant package; reading
        // manifest@package isn't reliable here because the patcher's resource-patch schedule
        // does not guarantee ChangePackageNamePatch's rename has been applied yet, even with
        // dependsOn declared. The CLI parses options before any patch runs, so the option
        // is always set by the time we get here.
        val variantPackage = packageNameOption.value
            ?.takeIf { it != packageNameOption.default }
            ?: return@afterDependents

        document("AndroidManifest.xml").use { dom ->
            val manifest = dom.getNode("manifest") as Element

            fun rewrite(name: String) = variantPackage + name.removePrefix(ORIGINAL_PACKAGE)

            // 1) Declarations and requests: any name under the original package.
            val permissionElements = manifest.getElementsByTagName("permission").asSequence()
            val usesPermissionElements = manifest.getElementsByTagName("uses-permission").asSequence()
            (permissionElements + usesPermissionElements)
                .map { it as Element }
                .forEach { node ->
                    val name = node.getAttribute("android:name")
                    if (name.startsWith(ORIGINAL_PREFIX)) node.setAttribute("android:name", rewrite(name))
                }

            // 2) Guards on components (android:permission="com.xiaoji.egggame.permission.PROCESS_PUSH_MSG"
            //    on two push receivers in 6.3.1): keep them pointing at the renamed permission.
            manifest.getElementsByTagName("*").asSequence()
                .map { it as Element }
                .forEach { node ->
                    for (attr in PERMISSION_ATTRIBUTES) {
                        if (!node.hasAttribute(attr)) continue
                        val value = node.getAttribute(attr)
                        if (value.startsWith(ORIGINAL_PREFIX)) node.setAttribute(attr, rewrite(value))
                    }
                }
        }
    }
}
