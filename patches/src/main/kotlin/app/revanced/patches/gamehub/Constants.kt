package app.revanced.patches.gamehub

// App metadata
internal const val GAMEHUB_PACKAGE = "com.xiaoji.egggame"
internal const val GAMEHUB_VERSION = "6.0.0"

// Extension class descriptors (smali format)
internal const val EXTENSION_PREFS = "Lapp/revanced/extension/gamehub/prefs/GameHubPrefs;"
internal const val EXTENSION_TOKEN_PROVIDER = "Lapp/revanced/extension/gamehub/token/TokenProvider;"
internal const val EXTENSION_BATTERY_HELPER = "Lapp/revanced/extension/gamehub/ui/BatteryHelper;"
internal const val EXTENSION_GAME_ID_HELPER = "Lapp/revanced/extension/gamehub/ui/GameIdHelper;"
internal const val EXTENSION_CURRENCY_HELPER = "Lapp/revanced/extension/gamehub/ui/AccountCurrencyHelper;"
internal const val EXTENSION_COMPAT_CACHE = "Lapp/revanced/extension/gamehub/ui/CompatibilityCache;"
internal const val EXTENSION_STEAM_CDN_HELPER = "Lapp/revanced/extension/gamehub/network/SteamCdnHelper;"
internal const val EXTENSION_CPU_HELPER = "Lapp/revanced/extension/gamehub/ui/CpuUsageHelper;"
internal const val EXTENSION_PERF_METRICS = "Lapp/revanced/extension/gamehub/ui/PerformanceMetricsHelper;"
internal const val EXTENSION_PLAYTIME_HELPER = "Lapp/revanced/extension/gamehub/playtime/PlaytimeHelper;"
internal const val EXTENSION_CREDITS_HELPER = "Lapp/revanced/extension/gamehub/ui/CreditsHelper;"

// Content-type constants for custom settings menu items.
// 0x19 is reserved (unused).
internal const val CONTENT_TYPE_SD_CARD_STORAGE = 0x18
internal const val CONTENT_TYPE_API = 0x1a
internal const val CONTENT_TYPE_LOG_REQUESTS = 0x1b
internal const val CONTENT_TYPE_CPU_USAGE = 0x1c
internal const val CONTENT_TYPE_PERF_METRICS = 0x1d
internal const val CONTENT_TYPE_MUTE_SOUNDS = 0x1e
internal const val CONTENT_TYPE_CREDITS = 0x1f

// Extension class descriptor for RTS touch controls helper
internal const val EXTENSION_RTS_HELPER = "Lapp/revanced/extension/gamehub/rts/RtsTouchHelper;"
