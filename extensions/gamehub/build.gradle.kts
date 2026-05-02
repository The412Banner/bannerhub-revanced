android {
    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    compileOnly(project(":extensions:gamehub:stub"))

    // Bundled into the extension dex so the host APK can read .wcp tarballs.
    // GameHub 6.0 ships only the `archivers/zip` subpackage of commons-compress;
    // `archivers/tar/*` is absent and was crashing ComponentInjectorHelper.
    implementation("org.apache.commons:commons-compress:1.26.2")
}
