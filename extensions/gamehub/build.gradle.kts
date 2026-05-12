android {
    defaultConfig {
        minSdk = 29
    }

    // Extension code is injected into a host APK at patch time; the host
    // manifest (and the host-targeting NewApi gate via Build.VERSION.SDK_INT)
    // is the source of truth, not this module's own lint. Suppress the
    // false-positive lint errors that fire when checking the extension in
    // isolation against its compile-only stubs.
    lint {
        disable += setOf(
            "MissingPermission",
            "NewApi",
            "WrongConstant",
        )
        abortOnError = false
    }
}

dependencies {
    compileOnly(project(":extensions:gamehub:stub"))
}
