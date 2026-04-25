group = "com.github.the412banner"

patches {
    about {
        name = "BannerHub for ReVanced"
        description = "BannerHub patches for GameHub — community configs, frontend export, download manager, and more."
        source = "https://github.com/The412Banner/bannerhub-revanced"
        author = "The412Banner"
        contact = "https://github.com/The412Banner"
        website = "https://github.com/The412Banner/bannerhub-revanced"
        license = "GNU General Public License v3.0"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters",
        )
    }
}

afterEvaluate {
    val extConfig = configurations.findByName("extensionConfiguration") ?: return@afterEvaluate

    sourceSets.named("main") {
        resources.setSrcDirs(listOf("src/main/resources"))
    }

    tasks.named<Copy>("processResources") {
        from(extConfig)
    }
}
