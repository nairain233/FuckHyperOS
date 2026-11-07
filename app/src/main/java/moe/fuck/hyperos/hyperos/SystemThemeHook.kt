package moe.fuck.hyperos.hyperos

import io.github.libxposed.api.XposedModule

internal object SystemThemeHook {
    fun install(support: HookSupport, module: XposedModule) = with(support) {
        attempt("system-theme") {
            val success = drmSuccess()
            val validations = load("miui.drm.ThemeReceiver").declaredMethods.filter { it.name == "validateTheme" }
            val checks = load("miui.drm.DrmManager").declaredMethods.filter {
                it.name == "isLegal" && it.returnType.isEnum && it.returnType.isInstance(success)
            }
            check(validations.isNotEmpty() && checks.isNotEmpty()) { "Theme validation methods missing" }
            val scope = ValidationScope()
            // 先装有条件的授权 Hook；即使校验入口安装失败，也不会产生全局放行。
            checks.forEach { method ->
                attempt(method.toString()) {
                    hooks.intercept(method) { chain ->
                        if (active() && scope.isActive) success else chain.proceed()
                    }
                }
            }
            validations.forEach { method ->
                attempt(method.toString()) {
                    hooks.intercept(method) { chain ->
                        if (active()) scope.within { chain.proceed() } else chain.proceed()
                    }
                    // isLegal 可能已被内联到主题校验入口。
                    report("HYPER_DEOPT method=$method success=${module.deoptimize(method)}")
                }
            }
        }
    }
}
