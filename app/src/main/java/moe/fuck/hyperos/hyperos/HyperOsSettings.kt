package moe.fuck.hyperos.hyperos

internal enum class HyperFeature { THEME, WIDGET, ADS, ANALYTICS, GUARD_ROOT, GUARD_UPLOAD, PASSKEY, FCM }

internal data class HyperOsSettings(
    val themeUnlock: Boolean = false,
    val widgetUnlock: Boolean = false,
    val removeAds: Boolean = false,
    val blockAnalytics: Boolean = false,
    val disableGuardRootCheck: Boolean = false,
    val blockGuardAppListUpload: Boolean = false,
    val passkey: Boolean = false,
    val fcmLive: Boolean = false,
) {
    fun featuresFor(packageName: String): Set<HyperFeature> = buildSet {
        if (themeUnlock && packageName in setOf(SYSTEM, THEME_MANAGER)) add(HyperFeature.THEME)
        if (widgetUnlock && packageName in setOf(THEME_MANAGER, ASSISTANT)) add(HyperFeature.WIDGET)
        if (removeAds && packageName == THEME_MANAGER) add(HyperFeature.ADS)
        if (blockAnalytics && packageName == ANALYTICS) add(HyperFeature.ANALYTICS)
        if (disableGuardRootCheck && packageName == GUARD_PROVIDER) add(HyperFeature.GUARD_ROOT)
        if (blockGuardAppListUpload && packageName == GUARD_PROVIDER) add(HyperFeature.GUARD_UPLOAD)
        if (passkey && packageName in setOf(SYSTEM, SETTINGS, SECURITY_CENTER, SCANNER)) add(HyperFeature.PASSKEY)
        if (fcmLive && packageName in setOf(SYSTEM, POWER_KEEPER)) add(HyperFeature.FCM)
    }

    companion object {
        const val PREFERENCES = "hyperos"
        const val THEME_UNLOCK = "theme_unlock"
        const val WIDGET_UNLOCK = "widget_unlock"
        const val REMOVE_ADS = "remove_ads"
        const val BLOCK_ANALYTICS = "block_analytics"
        const val ANALYTICS = "com.miui.analytics"
        const val DISABLE_GUARD_ROOT_CHECK = "guard_disable_root_check"
        const val BLOCK_GUARD_APP_LIST_UPLOAD = "guard_block_app_list_upload"
        const val GUARD_PROVIDER = "com.miui.guardprovider"
        const val SYSTEM = "android"
        const val THEME_MANAGER = "com.android.thememanager"
        const val ASSISTANT = "com.miui.personalassistant"
        const val PASSKEY = "passkey"
        const val FCM_LIVE = "fcm_live"
        const val SETTINGS = "com.android.settings"
        const val SECURITY_CENTER = "com.miui.securitycenter"
        const val SCANNER = "com.xiaomi.scanner"
        const val POWER_KEEPER = "com.miui.powerkeeper"
        val PACKAGES = setOf(THEME_MANAGER, ASSISTANT, ANALYTICS, GUARD_PROVIDER,
            SETTINGS, SECURITY_CENTER, SCANNER, POWER_KEEPER)

        fun read(readBoolean: (String) -> Boolean) = HyperOsSettings(
            readBoolean(THEME_UNLOCK), readBoolean(WIDGET_UNLOCK), readBoolean(REMOVE_ADS),
            readBoolean(BLOCK_ANALYTICS),
            readBoolean(DISABLE_GUARD_ROOT_CHECK), readBoolean(BLOCK_GUARD_APP_LIST_UPLOAD),
            readBoolean(PASSKEY), readBoolean(FCM_LIVE),
        )
    }
}
