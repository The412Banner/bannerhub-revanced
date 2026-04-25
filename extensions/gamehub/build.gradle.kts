android {
    defaultConfig {
        minSdk = 29
    }
    lint {
        // BannerHub targets Android 12+ in practice; suppress API-level false positives.
        // NotificationPermission: permission is requested at runtime in the detail activities.
        disable += setOf("NewApi", "NotificationPermission")
    }
}

dependencies {
    compileOnly(project(":extensions:gamehub:stub"))
}
