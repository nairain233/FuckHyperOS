package moe.fuck.hyperos.hyperos

import io.github.libxposed.api.XposedModule
import moe.fuck.hyperos.ReloadableHooks
import moe.fuck.hyperos.LogReporter
import moe.fuck.hyperos.analytics.AnalyticsHook
import moe.fuck.hyperos.guardprovider.GuardProviderHook
import moe.fuck.hyperos.google.PasskeyHook
import moe.fuck.hyperos.google.FcmLiveHook

internal object HyperOsHooks {
    fun install(
        module: XposedModule,
        hooks: ReloadableHooks,
        settings: HyperOsSettings,
        packageName: String,
        classLoader: ClassLoader,
        active: () -> Boolean,
        report: LogReporter,
        wakeAllowed: (String?) -> Boolean = { !it.isNullOrBlank() },
    ) {
        val features = settings.featuresFor(packageName)
        report("HYPER_CONFIG package=$packageName features=$features")
        if (features.isEmpty()) return
        val support = HookSupport(classLoader, hooks, active, report)
        if (HyperFeature.THEME in features) {
            if (packageName == HyperOsSettings.SYSTEM) SystemThemeHook.install(support, module)
            else ThemeManagerHook.install(support)
        }
        if (HyperFeature.WIDGET in features) WidgetHook.install(support, packageName)
        if (HyperFeature.ADS in features) ThemeAdsHook.install(support)
        if (HyperFeature.ANALYTICS in features) AnalyticsHook.install(support, module)
        if (HyperFeature.GUARD_ROOT in features || HyperFeature.GUARD_UPLOAD in features) {
            GuardProviderHook.install(support, module,
                disableRootCheck = HyperFeature.GUARD_ROOT in features,
                blockAppListUpload = HyperFeature.GUARD_UPLOAD in features)
        }
        if (HyperFeature.PASSKEY in features) PasskeyHook.install(support, module, packageName)
        if (HyperFeature.FCM in features) FcmLiveHook.install(support, module, packageName, wakeAllowed)
    }
}
