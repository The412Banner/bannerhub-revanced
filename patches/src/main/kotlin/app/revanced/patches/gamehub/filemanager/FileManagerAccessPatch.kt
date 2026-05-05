package app.revanced.patches.gamehub.filemanager

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.all.misc.packagename.changePackageNamePatch
import app.revanced.patches.all.misc.packagename.packageNameOption
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.asSequence
import app.revanced.util.getNode

private const val PROVIDER_CLASS =
    "app.revanced.extension.gamehub.filemanager.MTDataFilesProvider"
private const val WAKEUP_CLASS =
    "app.revanced.extension.gamehub.filemanager.MTDataFilesWakeUpActivity"

@Suppress("unused")
val fileManagerAccessPatch = resourcePatch(
    name = "File manager access",
    description = "Adds a DocumentsProvider so that MT File Manager and other Storage Access " +
        "Framework clients can browse the app's internal storage directories.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, changePackageNamePatch)

    afterDependents {
        document("AndroidManifest.xml").use { dom ->
            val manifestNode = dom.getNode("manifest")
            val manifestPackage = manifestNode.attributes.getNamedItem("package")?.nodeValue
                ?: throw IllegalStateException("AndroidManifest.xml is missing 'package' attribute")

            // Source of truth for the variant package: the CLI option passed to "Change package name".
            // Reading manifest@package isn't reliable here — patcher schedule does not guarantee that
            // ChangePackageNamePatch's rename has been applied by the time we run, even with a dependsOn
            // declared. The option's value is set by CLI parsing, before any patch runs.
            val variantPackage = packageNameOption.value
                ?.takeIf { it != packageNameOption.default }
                ?: manifestPackage

            val providerAuthority = "$variantPackage.$PROVIDER_CLASS"
            val wakeUpTaskAffinity = "$variantPackage.MTDataFilesWakeUp"

            val applicationNode = dom.getNode("application")

            // Guard: skip if the provider is already registered (idempotency).
            val existingProviders = dom.getElementsByTagName("provider").asSequence()
            if (existingProviders.any {
                    it.attributes.getNamedItem("android:name")?.nodeValue == PROVIDER_CLASS
                }
            ) {
                return@afterDependents
            }

            // Register the wake-up activity.
            dom
                .createElement("activity")
                .apply {
                    setAttribute("android:name", WAKEUP_CLASS)
                    setAttribute("android:exported", "true")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:noHistory", "true")
                    setAttribute("android:taskAffinity", wakeUpTaskAffinity)
                }.let(applicationNode::appendChild)

            // Register the documents provider.
            dom
                .createElement("provider")
                .apply {
                    setAttribute("android:name", PROVIDER_CLASS)
                    setAttribute("android:authorities", providerAuthority)
                    setAttribute("android:exported", "true")
                    setAttribute("android:grantUriPermissions", "true")
                    setAttribute("android:permission", "android.permission.MANAGE_DOCUMENTS")

                    dom
                        .createElement("intent-filter")
                        .apply {
                            dom
                                .createElement("action")
                                .apply {
                                    setAttribute("android:name", "android.content.action.DOCUMENTS_PROVIDER")
                                }.let(this::appendChild)
                        }.let(this::appendChild)
                }.let(applicationNode::appendChild)
        }
    }
}
