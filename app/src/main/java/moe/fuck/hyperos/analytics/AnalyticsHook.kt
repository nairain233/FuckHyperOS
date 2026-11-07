package moe.fuck.hyperos.analytics

import io.github.libxposed.api.XposedModule
import moe.fuck.hyperos.hyperos.HookSupport
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

internal object AnalyticsHook {
    fun install(support: HookSupport, module: XposedModule) = with(support) {
        attempt("analytics-upload") {
            System.loadLibrary("dexkit")
            DexKitBridge.create(classLoader, false).use { bridge ->
                // 先完成全部解析，定位失败时不安装一半规则，也不回退到旧混淆名。
                val targets = AnalyticsDexResolver.resolve(bridge)
                val methods = targets.map { it.method.getMethodInstance(classLoader) }
                targets.zip(methods).forEach { (target, method) ->
                    val result = target.result
                    hooks.intercept(method) { chain ->
                        if (active()) result else chain.proceed()
                    }
                    report("ANALYTICS_HOOK_READY feature=${target.label} method=${target.method.descriptor}")
                }
                // 网络判断很小，直接调用方可能已内联；反优化以便现有进程热重载后生效。
                targets.flatMap { it.method.callers }.distinctBy { it.descriptor }.forEach { caller ->
                    if (caller.name != "<init>" && caller.name != "<clinit>" &&
                        !Modifier.isAbstract(caller.modifiers) && !Modifier.isNative(caller.modifiers)) {
                        attempt("analytics-deopt-${caller.descriptor}", reportSuccess = false) {
                            report("ANALYTICS_DEOPT method=${caller.descriptor} success=${module.deoptimize(caller.getMethodInstance(classLoader))}")
                        }
                    }
                }
            }
        }
    }
}
