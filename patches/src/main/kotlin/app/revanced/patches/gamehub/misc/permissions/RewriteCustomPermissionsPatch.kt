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
private const val ORIGINAL_PERMISSION_PREFIX = "$ORIGINAL_PACKAGE.permission."

@Suppress("unused")
val rewriteCustomPermissionsPatch = resourcePatch(
    name = "Rewrite custom permissions per variant",
    description = "Renames upstream-baked custom permissions (e.g. com.xiaoji.egggame.permission.C2D_MESSAGE) " +
        "to use the variant package, so multiple variants can install side-by-side without " +
        "INSTALL_FAILED_DUPLICATE_PERMISSION on Android 7+ (which surfaces as " +
        "\"package conflicts with a current package\" in the package installer UI). " +
        "ChangePackageNamePatch's updatePermissions option only rewrites the hardcoded " +
        "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION; this patch handles the rest.",
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

            val permissionElements = manifest.getElementsByTagName("permission").asSequence()
            val usesPermissionElements = manifest.getElementsByTagName("uses-permission").asSequence()

            (permissionElements + usesPermissionElements)
                .map { it as Element }
                .forEach { node ->
                    val name = node.getAttribute("android:name")
                    if (name.startsWith(ORIGINAL_PERMISSION_PREFIX)) {
                        val suffix = name.removePrefix(ORIGINAL_PACKAGE)
                        node.setAttribute("android:name", "$variantPackage$suffix")
                    }
                }
        }
    }
}
