package moe.fuck.hyperos.hyperos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsSettingsTest {
    @Test fun defaultsInstallNothingInAnyHost() {
        val settings = HyperOsSettings.read { false }
        assertEquals(HyperOsSettings(), settings)
        (HyperOsSettings.PACKAGES + HyperOsSettings.SYSTEM + "com.xiaomi.type").forEach {
            assertTrue(settings.featuresFor(it).isEmpty())
        }
    }

    @Test fun themeOnlyTargetsSystemAndThemeManager() {
        val settings = HyperOsSettings(themeUnlock = true)
        assertEquals(setOf(HyperFeature.THEME), settings.featuresFor(HyperOsSettings.SYSTEM))
        assertEquals(setOf(HyperFeature.THEME), settings.featuresFor(HyperOsSettings.THEME_MANAGER))
        assertTrue(settings.featuresFor("com.miui.home").isEmpty())
        assertTrue(settings.featuresFor(HyperOsSettings.ASSISTANT).isEmpty())
    }

    @Test fun widgetsAndAdsRemainIndependent() {
        val settings = HyperOsSettings(widgetUnlock = true, removeAds = true)
        assertEquals(setOf(HyperFeature.WIDGET, HyperFeature.ADS), settings.featuresFor(HyperOsSettings.THEME_MANAGER))
        assertTrue(settings.featuresFor("com.miui.home").isEmpty())
        assertTrue("com.miui.home" !in HyperOsSettings.PACKAGES)
        assertEquals(setOf(HyperFeature.WIDGET), settings.featuresFor(HyperOsSettings.ASSISTANT))
        assertTrue(settings.featuresFor(HyperOsSettings.SYSTEM).isEmpty())
        assertTrue(settings.featuresFor("com.xiaomi.type").isEmpty())
    }

    @Test fun readsEachSettingFromItsOwnKey() {
        val keys = mutableListOf<String>()
        val settings = HyperOsSettings.read { keys.add(it); it == "widget_unlock" }
        assertEquals(HyperOsSettings(widgetUnlock = true), settings)
        assertEquals(listOf("theme_unlock", "widget_unlock", "remove_ads", "block_analytics",
            "guard_disable_root_check", "guard_block_app_list_upload", "passkey", "fcm_live"), keys)
    }

    @Test fun googleFeaturesHaveIndependentKeysAndOnlyTargetTheirOwnHosts() {
        val passkey = HyperOsSettings.read { it == HyperOsSettings.PASSKEY }
        val fcm = HyperOsSettings.read { it == HyperOsSettings.FCM_LIVE }
        assertEquals(HyperOsSettings(passkey = true), passkey)
        assertEquals(HyperOsSettings(fcmLive = true), fcm)
        val passkeyHosts = setOf(HyperOsSettings.SYSTEM, HyperOsSettings.SETTINGS, HyperOsSettings.SECURITY_CENTER, HyperOsSettings.SCANNER)
        val fcmHosts = setOf(HyperOsSettings.SYSTEM, HyperOsSettings.POWER_KEEPER)
        (HyperOsSettings.PACKAGES + HyperOsSettings.SYSTEM + "com.google.android.gms").forEach { host ->
            assertEquals(if (host in passkeyHosts) setOf(HyperFeature.PASSKEY) else emptySet<HyperFeature>(), passkey.featuresFor(host))
            assertEquals(if (host in fcmHosts) setOf(HyperFeature.FCM) else emptySet<HyperFeature>(), fcm.featuresFor(host))
        }
        assertEquals(setOf(HyperFeature.PASSKEY, HyperFeature.FCM), HyperOsSettings(passkey = true, fcmLive = true).featuresFor(HyperOsSettings.SYSTEM))
    }

    @Test fun guardProviderSwitchesAreIndependentAndConfinedToGuardProvider() {
        val root = HyperOsSettings.read { it == HyperOsSettings.DISABLE_GUARD_ROOT_CHECK }
        val upload = HyperOsSettings.read { it == HyperOsSettings.BLOCK_GUARD_APP_LIST_UPLOAD }
        assertEquals(setOf(HyperFeature.GUARD_ROOT), root.featuresFor(HyperOsSettings.GUARD_PROVIDER))
        assertEquals(setOf(HyperFeature.GUARD_UPLOAD), upload.featuresFor(HyperOsSettings.GUARD_PROVIDER))
        val both = HyperOsSettings(disableGuardRootCheck = true, blockGuardAppListUpload = true)
        assertEquals(setOf(HyperFeature.GUARD_ROOT, HyperFeature.GUARD_UPLOAD), both.featuresFor(HyperOsSettings.GUARD_PROVIDER))
        (HyperOsSettings.PACKAGES - HyperOsSettings.GUARD_PROVIDER + HyperOsSettings.SYSTEM).forEach {
            assertTrue(both.featuresFor(it).isEmpty())
        }
    }

    @Test fun analyticsOnlyTargetsAnalyticsAndHasAnIndependentSwitch() {
        val settings = HyperOsSettings.read { it == HyperOsSettings.BLOCK_ANALYTICS }
        assertEquals(HyperOsSettings(blockAnalytics = true), settings)
        assertEquals(setOf(HyperFeature.ANALYTICS), settings.featuresFor(HyperOsSettings.ANALYTICS))
        (HyperOsSettings.PACKAGES - HyperOsSettings.ANALYTICS + HyperOsSettings.SYSTEM).forEach {
            assertTrue(settings.featuresFor(it).isEmpty())
        }
        assertTrue(HyperOsSettings(themeUnlock = true, widgetUnlock = true, removeAds = true)
            .featuresFor(HyperOsSettings.ANALYTICS).isEmpty())
    }
}
