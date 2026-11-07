package moe.fuck.hyperos.hyperos

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object ThemeManagerHook {
    fun install(support: HookSupport) = with(support) {
        attempt("theme-dex") {
            System.loadLibrary("dexkit")
            // 同一次安装共用扫描实例，覆盖宿主 ClassLoader 中的 DEX 和 split APK。
            DexKitBridge.create(classLoader, false).use { bridge ->
                val matches = bridge.batchFindMethodUsingStrings {
                    searchPackages("com.android.thememanager")
                    groups(METHOD_STRINGS, StringMatchType.Equals)
                }
                fun methods(key: String) = matches[key].orEmpty().map { it.getMethodInstance(classLoader) }
                attempt("theme-price") { installPrice(support, methods(PRICE)) }
                attempt("theme-drm") { installDrm(support, methods(DRM)) }
                attempt("theme-large-icon") { installLargeIcon(support, methods(LARGE_ICON)) }
            }
        }
        attempt("theme-bought") {
            val type = load("com.android.thememanager.detail.theme.model.OnlineResourceDetail")
            val bought = generateSequence<Class<*>>(type) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }.first { it.name == "bought" }
                .apply { isAccessible = true }
            check(bought.type == Boolean::class.javaPrimitiveType || bought.type == Boolean::class.javaObjectType)
            val methods = type.declaredMethods.filter { it.name == "toResource" && !Modifier.isStatic(it.modifiers) }
            check(methods.isNotEmpty()) { "toResource missing" }
            methods.forEach { method ->
                attempt(method.toString()) {
                    hooks.intercept(method) { chain ->
                        val result = chain.proceed()
                        if (active()) attempt("theme-bought-update", reportSuccess = false) { bought.set(chain.thisObject, true) }
                        result
                    }
                }
            }
        }
    }

    private fun installPrice(s: HookSupport, matches: List<Method>) {
        val method = uniqueMethod(matches) {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.toList() == listOf(Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) &&
                it.returnType == String::class.java
        }
        s.hooks.intercept(method) { chain ->
            if (!s.active()) chain.proceed() else chain.proceed(chain.args.toTypedArray().also { it[1] = 0 })
        }
    }

    private fun installDrm(s: HookSupport, matches: List<Method>) {
        val success = s.drmSuccess()
        val method = uniqueMethod(matches) {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.singleOrNull()?.name == RESOURCE_CLASS &&
                it.returnType.isEnum && it.returnType.isInstance(success)
        }
        s.hooks.intercept(method) { chain -> if (s.active()) success else chain.proceed() }
    }

    private fun installLargeIcon(s: HookSupport, matches: List<Method>) {
        val method = uniqueMethod(matches) {
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                it.parameterTypes.singleOrNull()?.componentType == Void::class.java
        }
        val resourceType = s.load(RESOURCE_CLASS)
        val fields = generateSequence<Class<*>>(method.declaringClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type == resourceType && !Modifier.isStatic(it.modifiers) }.toList()
        check(fields.size == 1) { "Resource field matches=${fields.size}" }
        val resourceField = fields.single().apply { isAccessible = true }
        val productId = resourceType.getMethod("getProductId")
        s.hooks.intercept(method) { chain ->
            if (s.active()) s.attempt("theme-large-icon-rights", reportSuccess = false) {
                val resource = checkNotNull(resourceField.get(chain.thisObject)) { "Resource missing" }
                val id = checkNotNull(productId.invoke(resource)) { "Product ID missing" }.toString()
                val files = externalFiles(s)
                LargeIconRights.ensure(File(files, "MIUI/theme/.data/rights/theme"), id)
            }
            chain.proceed()
        }
    }

    private const val PRICE = "price"
    private const val DRM = "drm"
    private const val LARGE_ICON = "large-icon"
    private const val RESOURCE_CLASS = "com.android.thememanager.basemodule.resource.model.Resource"
    private val METHOD_STRINGS = mapOf(
        PRICE to listOf("%.2f"),
        DRM to listOf("ThemeManagerTag", "check rights isLegal: "),
        LARGE_ICON to listOf("/data/system/theme/large_icons/", "relativePackageList is empty"),
    )

    @SuppressLint("PrivateApi")
    private fun externalFiles(s: HookSupport): File {
        val application = s.load("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .invoke(null) as? Application
        check(application?.packageName == HyperOsSettings.THEME_MANAGER) { "Theme application unavailable" }
        return checkNotNull(application.getExternalFilesDir(null)) { "External files unavailable" }
    }
}
