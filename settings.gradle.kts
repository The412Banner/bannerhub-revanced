rootProject.name = "bannerhub-revanced"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/revanced/gamehub-patches")
            credentials(PasswordCredentials::class)
        }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.11"
}

settings {
    extensions {
        defaultNamespace = "app.revanced.extension"
    }
}
