package moe.fuck.hyperos.google

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.credentials.CredentialManager
import android.os.Build
import android.os.CancellationSignal
import android.service.credentials.CallingAppInfo
import android.view.View
import android.widget.CompoundButton
import io.github.libxposed.api.XposedModule
import moe.fuck.hyperos.hyperos.HookSupport
import moe.fuck.hyperos.hyperos.HyperOsSettings
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

// 功能来源：howard20181/HyperPasskey（GPL-3.0），保留系统、设置、管家和扫码四部分行为。
internal object PasskeyHook {
    fun install(s: HookSupport, module: XposedModule, packageName: String) {
        when (packageName) {
            HyperOsSettings.SYSTEM -> installSystem(s, module)
            HyperOsSettings.SETTINGS -> installSettings(s, module)
            HyperOsSettings.SECURITY_CENTER -> withDex(s) { bridge -> installSecurityCenter(s, module, bridge) }
            HyperOsSettings.SCANNER -> s.attempt("passkey-scanner") {
                val method = s.load("com.xiaomi.scanner.module.code.utils.bean.MiFiDoBean").getDeclaredMethod("getAppPackageName")
                check(method.returnType == String::class.java)
                s.hooks.intercept(method) { chain -> if (s.active()) "" else chain.proceed() }
            }
        }
    }

    private fun installSystem(s: HookSupport, module: XposedModule) = with(s) {
        attempt("passkey-hybrid-service") {
            val type = load("com.android.server.credentials.RequestSession")
            val field = type.getDeclaredField("mHybridService").apply { isAccessible = true }
            check(field.type == String::class.java)
            val constructor = type.getDeclaredConstructor(Context::class.java,
                load("com.android.server.credentials.RequestSession\$SessionLifetime"), Any::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java, Any::class.java,
                String::class.java, CallingAppInfo::class.java, Set::class.java, CancellationSignal::class.java,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            hooks.intercept(constructor) { chain ->
                val result = chain.proceed()
                if (active()) attempt("passkey-set-hybrid", false) {
                    PasskeyFields.set(field, chain.thisObject, "com.google.android.gms/.auth.api.credentials.credman.service.RemoteService")
                }
                result
            }
            listOf("ProviderGetSession", "ProviderCreateSession").forEach { name ->
                attempt("passkey-deopt-$name", false) {
                    load("com.android.server.credentials.$name").declaredMethods.filter { it.name == "createNewSession" }
                        .forEach { module.deoptimize(it) }
                }
            }
        }
        attempt("passkey-credential-chooser") {
            val builder = load("android.credentials.selection.IntentCreationResult\$Builder")
            val params = if (Build.VERSION.SDK_INT >= 36) arrayOf(Context::class.java, builder, Int::class.javaPrimitiveType!!)
                else arrayOf(Context::class.java, builder)
            val method = load("android.credentials.selection.IntentFactory").getDeclaredMethod("getOemOverrideComponentName", *params)
            val setPackage = builder.getDeclaredMethod("setOemUiPackageName", String::class.java)
            val status = load("android.credentials.selection.IntentCreationResult\$OemUiUsageStatus")
            val success = status.getDeclaredField("SUCCESS").get(null)
            val setStatus = builder.getDeclaredMethod("setOemUiUsageStatus", status)
            hooks.intercept(method) { chain ->
                var component: ComponentName? = null
                if (active()) attempt("passkey-select-chooser", false) {
                    val context = chain.args[0] as Context
                    val candidate = ComponentName("com.google.android.gms", "com.google.android.gms.identitycredentials.ui.CredentialChooserActivity")
                    val info = context.packageManager.getActivityInfo(candidate,
                        PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()))
                    val state = context.packageManager.getComponentEnabledSetting(candidate)
                    val enabled = when (state) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> info.enabled
                        else -> false
                    }
                    if (enabled && info.exported) {
                        module.getInvoker(setPackage).invoke(chain.args[1], candidate.packageName)
                        module.getInvoker(setStatus).invoke(chain.args[1], success)
                        component = candidate
                    }
                }
                component ?: chain.proceed()
            }
        }
    }

    private fun installSettings(s: HookSupport, module: XposedModule) = with(s) {
        attempt("passkey-settings") {
            val international = load("miui.os.Build").getDeclaredField("IS_INTERNATIONAL_BUILD").apply { isAccessible = true }
            val scope = InternationalBuildScope({ international.getBoolean(null) }, { PasskeyFields.set(international, null, it) })
            fun hook(method: Method) {
                hooks.intercept(method) { chain -> if (active()) scope.within { chain.proceed() } else chain.proceed() }
                module.deoptimize(method)
            }
            attempt("passkey-default-picker") {
                hook(load("com.android.settings.applications.credentials.DefaultCombinedPicker").getDeclaredMethod("setDefaultKey", String::class.java))
            }
            attempt("passkey-provider-list") {
                hook(load("com.android.settings.applications.credentials.DefaultCombinedPreferenceController")
                    .getDeclaredMethod("getCombinedProviderInfos", CredentialManager::class.java, Int::class.javaPrimitiveType))
            }
            withDex(s) { bridge ->
                attempt("passkey-provider-click") {
                    val matcher = MethodMatcher.create().name("onLeftSideClicked").paramCount(0).anyOf(
                        MethodMatcher.create().addInvoke("Lcom/android/settings/applications/credentials/CombinedProviderInfo;->launchSettingsActivityIntent(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;I)Z"),
                        MethodMatcher.create().addInvoke("Lcom/android/settings/applications/credentials/CombinedProviderInfo;->launchSettingsActivityIntent(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;I)V"))
                    val methods = bridge.findMethod(FindMethod.create().searchPackages("com.android.settings.applications.credentials").matcher(matcher))
                    check(methods.isNotEmpty()) { "Credential provider click methods missing" }
                    methods.forEach { hook(it.getMethodInstance(classLoader)) }
                }
                if (Build.VERSION.SDK_INT >= 37) attempt("passkey-provider-switch") { installProviderSwitch(s, module, bridge) }
            }
        }
    }

    private fun installProviderSwitch(s: HookSupport, module: XposedModule, bridge: DexKitBridge) = with(s) {
        val combi = load("com.android.settings.applications.credentials.CredentialManagerPreferenceController\$CombiPreference")
        val bind = combi.getDeclaredMethod("onBindViewHolder", load("androidx.preference.PreferenceViewHolder"))
        val checked = combi.getDeclaredField("mChecked").apply { isAccessible = true }
        val listener = combi.getDeclaredField("mOnClickListener").apply { isAccessible = true }
        val switch = combi.getDeclaredField("mSwitch").apply { isAccessible = true }
        val description = combi.getDeclaredMethod("maybeUpdateContentDescription")
        val itemView = load("androidx.recyclerview.widget.RecyclerView\$ViewHolder").getDeclaredField("itemView").apply { isAccessible = true }
        val switchId = load("com.android.settingslib.R\$id").getDeclaredField("switchWidget").getInt(null)
        val matcher = MethodMatcher.create().name("onCheckChanged").paramTypes(combi, Boolean::class.javaPrimitiveType).anyOf(
            MethodMatcher.create().addInvoke("Lcom/android/settings/applications/credentials/CredentialManagerPreferenceController;->togglePackageNameEnabled(Ljava/lang/String;)Z"),
            MethodMatcher.create().addInvoke("Lcom/android/settings/applications/credentials/CredentialManagerPreferenceController;->togglePackageNameDisabled(Ljava/lang/String;)V"))
        val matches = bridge.findMethod(FindMethod.create().searchPackages("com.android.settings.applications.credentials").matcher(matcher))
        check(matches.size == 1) { "Credential switch callback matches=${matches.size}" }
        val onChange = matches.single().getMethodInstance(classLoader)
        hooks.intercept(bind) { chain ->
            val result = chain.proceed()
            if (active()) attempt("passkey-bind-switch", false) {
                val preference = chain.thisObject
                if (switch.get(preference) == null) {
                    val button = (itemView.get(chain.args[0]) as View).findViewById<View>(switchId) as? CompoundButton
                    if (button != null) {
                        button.isChecked = checked.getBoolean(preference)
                        button.setOnClickListener {
                            if (active()) attempt("passkey-switch-click", false) {
                                val callback = listener.get(preference)
                                if (callback != null && module.getInvoker(onChange).invoke(callback, preference, button.isChecked) == false) {
                                    PasskeyFields.set(checked, preference, false)
                                    button.isChecked = false
                                }
                            }
                        }
                        PasskeyFields.set(switch, preference, button)
                        module.getInvoker(description).invoke(preference)
                    }
                }
            }
            result
        }
    }

    private fun installSecurityCenter(s: HookSupport, module: XposedModule, bridge: DexKitBridge) = with(s) {
        val classes = listOf("com.miui.securitycenter.Application", "com.miui.securitycenter.service.CacheService")
        fun targets(array: Boolean) {
            val resourceCall = if (array) "Landroid/content/res/Resources;->getStringArray(I)[Ljava/lang/String;"
                else "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;"
            val helperMatcher = MethodMatcher.create().anyOf(
                MethodMatcher.create().paramTypes(Context::class.java, String::class.java, Int::class.javaPrimitiveType),
                MethodMatcher.create().paramTypes(String::class.java, Int::class.javaPrimitiveType))
                .addInvoke(resourceCall)
                .addInvoke("Landroid/provider/Settings\$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
            val helpers = bridge.findMethod(FindMethod.create().matcher(helperMatcher)).filter { it.className in classes }
            check(helpers.size == 1) { "Credential config helper matches=${helpers.size}" }
            val helper = helpers.single()
            val matcher = MethodMatcher.create().addInvoke(helper.descriptor)
            if (array) matcher.usingEqStrings("credential_service", "credential_service_primary") else matcher.addEqString("autofill_service")
            val methods = bridge.findMethod(FindMethod.create().matcher(matcher)).filter { it.className in classes }
            check(methods.size == 1) { "Credential reset method matches=${methods.size}" }
            val method = methods.single().getMethodInstance(classLoader)
            check(method.returnType == Void.TYPE) { "Credential reset must return void" }
            hooks.intercept(method) { chain -> if (active()) null else chain.proceed() }
            module.deoptimize(helper.getMethodInstance(classLoader))
        }
        attempt("passkey-prevent-autofill-reset") { targets(false) }
        attempt("passkey-prevent-credential-reset") { targets(true) }
    }

    private fun withDex(s: HookSupport, block: (DexKitBridge) -> Unit) = s.attempt("passkey-dex") {
        System.loadLibrary("dexkit")
        DexKitBridge.create(s.classLoader, false).use(block)
    }
}
