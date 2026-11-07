package moe.fuck.hyperos

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.inputmethod.InputMethodInfo
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import moe.fuck.hyperos.inline.ImeDexResolver
import moe.fuck.hyperos.inline.ImeTargets
import moe.fuck.hyperos.inline.XiaoAiImeHook
import moe.fuck.hyperos.hyperos.HyperOsHooks
import moe.fuck.hyperos.hyperos.HyperOsSettings
import moe.fuck.hyperos.card.CardSettings
import moe.fuck.hyperos.card.WalletHook
import moe.fuck.hyperos.google.FcmWakePreferences
import moe.fuck.hyperos.google.FcmWakeSettings

class ModuleEntry : XposedModule() {
    private val report = ModuleLog.reporter(TAG) { priority, message -> log(priority, TAG, message) }
    private val loggingPreferences = LoggingPreferences(report)
    private val fcmWakePreferences = FcmWakePreferences(report)
    private var preferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var processName = ""
    @Volatile private var enabled = false
    private var imeHook: XiaoAiImeHook? = null
    private var imeTargets: ImeTargets? = null
    private var systemInstalled = false
    private var targetClassLoader: ClassLoader? = null
    @Volatile private var retiring = false
    private var hooks = ReloadableHooks(this)
    private var hyperSettings = HyperOsSettings()
    private var hyperPackage: String? = null
    private var walletHook: WalletHook? = null
    private var walletPackage: String? = null
    private var cardPreferences: SharedPreferences? = null
    private var cardListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var cardEnabled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        initialize(param)
    }

    private fun initialize(param: ModuleLoadedParam) {
        processName = param.processName
        try {
            loggingPreferences.bind(getRemotePreferences(ModuleLog.PREFERENCES))
        } catch (error: Exception) {
            report.error("LOG_CONFIG_UNAVAILABLE ${error.javaClass.simpleName}")
        }
        report("MODULE_LOADED process=${param.processName} api=$apiVersion framework=$frameworkVersionCode")
        try {
            preferences = getRemotePreferences(PREFERENCES).also { prefs ->
                enabled = prefs.getBoolean(ENABLED, DEFAULT_ENABLED)
                preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { updated, key ->
                    if (!retiring && key == ENABLED) {
                        enabled = updated.getBoolean(ENABLED, DEFAULT_ENABLED)
                        imeHook?.onEnabledChanged(enabled)
                        report("CONFIG enabled=$enabled")
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
            }
        } catch (error: Exception) {
            report.error("CONFIG_UNAVAILABLE ${error.javaClass.simpleName}")
        }
        try {
            cardPreferences = getRemotePreferences(CardSettings.PREFERENCES).also { prefs ->
                cardEnabled = prefs.getBoolean(CardSettings.ENABLED, false)
                cardListener = SharedPreferences.OnSharedPreferenceChangeListener { updated, key ->
                    if (!retiring && key == CardSettings.ENABLED) {
                        cardEnabled = updated.getBoolean(CardSettings.ENABLED, false)
                        report("CARD_CONFIG enabled=$cardEnabled")
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(cardListener)
            }
        } catch (error: Exception) {
            cardEnabled = false
            report.error("CARD_CONFIG_UNAVAILABLE ${error.javaClass.simpleName}")
        }
        // HyperOS 开关按启动或热重载时的快照安装，读取失败时保持关闭。
        hyperSettings = try {
            val prefs = getRemotePreferences(HyperOsSettings.PREFERENCES)
            HyperOsSettings.read { prefs.getBoolean(it, false) }
        } catch (error: Exception) {
            report.error("HYPER_CONFIG_UNAVAILABLE ${error.javaClass.simpleName}")
            HyperOsSettings()
        }
        if (hyperSettings.fcmLive) {
            try {
                fcmWakePreferences.bind(getRemotePreferences(FcmWakeSettings.PREFERENCES))
            } catch (error: Exception) {
                report.error("FCM_WAKE_CONFIG_UNAVAILABLE type=${error.javaClass.simpleName}")
            }
        }
    }

    // 此入口专用于系统侧 Hook，目标签名已用当前 ROM 的 services.jar 校验。
    @SuppressLint("PrivateApi")
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        targetClassLoader = param.classLoader
        try {
            installSystemHooks(param.classLoader)
        } finally {
            installHyperHooks(HyperOsSettings.SYSTEM, param.classLoader)
        }
    }

    @SuppressLint("PrivateApi")
    private fun installSystemHooks(classLoader: ClassLoader) {
        if (systemInstalled) return
        try {
            val controller = Class.forName(
                "com.android.server.inputmethod.AutofillSuggestionsController", false, classLoader
            )
            val gate = controller.getDeclaredMethod(
                "isInlineSuggestionsEnabled", InputMethodInfo::class.java, Boolean::class.javaPrimitiveType
            )
            hooks.intercept(gate) { chain ->
                val info = chain.args[0] as? InputMethodInfo
                val touchExploration = chain.args[1] as Boolean
                if (enabled && !touchExploration && info?.packageName == HOST_PACKAGE) {
                    report("CAPABILITY_ALLOWED")
                    true
                } else {
                    chain.proceed()
                }
            }
            // 小方法可能被编译器内联；只处理实机已确认的直接调用方。
            controller.declaredMethods.filter { it.name == "onCreateInlineSuggestionsRequest" }
                .forEach { report("SYSTEM_DEOPT success=${deoptimize(it)}") }
            systemInstalled = true
            report("SYSTEM_HOOK_READY enabled=$enabled")
        } catch (error: Exception) {
            report.error("SYSTEM_HOOK_FAILED ${error.javaClass.simpleName}")
            throw error
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == CardSettings.PACKAGE && processName == CardSettings.PACKAGE) {
            targetClassLoader = param.classLoader
            installWalletHooks(param.classLoader)
            return
        }
        if (param.packageName in HyperOsSettings.PACKAGES) {
            targetClassLoader = param.classLoader
            installHyperHooks(param.packageName, param.classLoader)
            return
        }
        if (param.packageName != HOST_PACKAGE || processName != HOST_PACKAGE || imeHook != null) return
        targetClassLoader = param.classLoader
        try {
            installImeHooks(param.classLoader)
        } catch (error: Exception) {
            hooks.rollback()
            report.error("IME_HOOK_UNAVAILABLE type=${error.javaClass.simpleName} reason=${error.message}")
        } catch (error: LinkageError) {
            hooks.rollback()
            report.error("IME_HOOK_UNAVAILABLE type=${error.javaClass.simpleName}")
        }
    }

    private fun installHyperHooks(packageName: String, classLoader: ClassLoader) {
        if (hyperPackage != null) return
        // 即使所有开关关闭，也保留宿主身份，便于后续更新时重新读取配置。
        hyperPackage = packageName
        HyperOsHooks.install(this, hooks, hyperSettings, packageName, classLoader, { !retiring }, report, fcmWakePreferences::allows)
    }

    private fun installWalletHooks(classLoader: ClassLoader) {
        if (walletHook != null) return
        walletPackage = CardSettings.PACKAGE
        val candidate = WalletHook(this, hooks, classLoader, { cardEnabled && !retiring }, report)
        try {
            candidate.install()
            walletHook = candidate
        } catch (error: Exception) {
            candidate.close()
            hooks.rollback()
            report.error("CARD_HOOK_UNAVAILABLE type=${error.javaClass.simpleName} reason=${error.message}")
        } catch (error: LinkageError) {
            candidate.close()
            hooks.rollback()
            report.error("CARD_HOOK_UNAVAILABLE type=${error.javaClass.simpleName}")
        } catch (error: io.github.libxposed.api.error.HookFailedError) {
            candidate.close()
            hooks.rollback()
            report.error("CARD_HOOK_UNAVAILABLE type=${error.javaClass.simpleName}")
        }
    }

    private fun installImeHooks(classLoader: ClassLoader) {
        try {
            val targets = imeTargets ?: ImeDexResolver.resolve(classLoader, report).also { imeTargets = it }
            imeHook = XiaoAiImeHook(this, hooks, targets, { enabled && !retiring }, report)
                .also { it.install() }
            report("IME_HOOK_READY enabled=$enabled")
        } catch (error: Exception) {
            report.error("IME_HOOK_FAILED ${error.javaClass.simpleName}")
            throw error
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        return MainThread.run {
            val imeState = imeHook?.prepareForReload()
            if (imeHook != null && imeState == null) {
                report("HOT_RELOAD_DEFERRED pending-inflation")
                return@run false
            }
            retiring = true
            loggingPreferences.close()
            fcmWakePreferences.close()
            preferenceListener?.let { preferences?.unregisterOnSharedPreferenceChangeListener(it) }
            preferenceListener = null
            preferences = null
            cardListener?.let { cardPreferences?.unregisterOnSharedPreferenceChangeListener(it) }
            cardListener = null
            cardPreferences = null
            walletHook?.close()
            walletHook = null
            // 仅传递宿主/系统对象和基础类型，不把旧模块对象带到新的 ClassLoader。
            param.setSavedInstanceState(hashMapOf(
                "classLoader" to targetClassLoader,
                "ime" to imeState,
                "targets" to imeTargets?.save(),
                "hyperPackage" to hyperPackage,
                "walletPackage" to walletPackage,
            ))
            imeHook = null
            imeTargets = null
            report("HOT_RELOAD_PREPARED process=$processName")
            true
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        hooks = ReloadableHooks(this, param.oldHookHandles)
        try {
            initialize(param)
            val state = param.savedInstanceState as? Map<*, *>
            targetClassLoader = state?.get("classLoader") as? ClassLoader
            (state?.get("targets") as? Map<*, *>)?.let {
                imeTargets = ImeTargets.restore(it, checkNotNull(targetClassLoader))
                report("DEX_TARGETS_RESTORED")
            }
            if (param.isSystemServer) {
                installSystemHooks(checkNotNull(targetClassLoader) { "System classloader missing" })
                installHyperHooks(HyperOsSettings.SYSTEM, checkNotNull(targetClassLoader))
            } else if (processName == HOST_PACKAGE) {
                MainThread.run {
                    installImeHooks(checkNotNull(targetClassLoader) { "IME classloader missing" })
                    imeHook?.restoreAfterReload(state?.get("ime") as? Map<*, *>)
                }
            } else if (processName == CardSettings.PACKAGE && state?.get("walletPackage") == CardSettings.PACKAGE) {
                installWalletHooks(checkNotNull(targetClassLoader) { "Wallet classloader missing" })
                check(walletHook != null) { "Wallet hooks not restored" }
            } else {
                val packageName = state?.get("hyperPackage") as? String
                if (packageName in HyperOsSettings.PACKAGES) {
                    installHyperHooks(checkNotNull(packageName), checkNotNull(targetClassLoader) { "HyperOS classloader missing" })
                }
            }
            hooks.removeObsolete()
            report("HOT_RELOAD_READY process=$processName")
        } catch (error: Throwable) {
            retiring = true
            loggingPreferences.close()
            fcmWakePreferences.close()
            preferenceListener?.let { preferences?.unregisterOnSharedPreferenceChangeListener(it) }
            cardListener?.let { cardPreferences?.unregisterOnSharedPreferenceChangeListener(it) }
            walletHook?.close()
            MainThread.run { imeHook?.prepareForReload() }
            hooks.rollback()
            report.error("HOT_RELOAD_FAILED type=${error.javaClass.simpleName}")
            throw error
        }
    }

    companion object {
        const val TAG = "XiaoAiInline"
        const val PREFERENCES = "inline"
        const val ENABLED = "enabled"
        const val DEFAULT_ENABLED = false
        const val HOST_PACKAGE = "com.xiaomi.type"
    }
}
