android {
    defaultConfig {
        minSdk = 29
    }
    lint {
        // BannerHub targets Android 12+ in practice; suppress API-level false positives.
        disable += "NewApi"
    }
}

dependencies {
    compileOnly(project(":extensions:gamehub:stub"))
}
