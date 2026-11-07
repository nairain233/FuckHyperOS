package moe.fuck.hyperos.guardprovider

import io.github.libxposed.api.XposedModule
import moe.fuck.hyperos.hyperos.HookSupport
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object GuardProviderHook {
    fun install(support: HookSupport, module: XposedModule, disableRootCheck: Boolean, blockAppListUpload: Boolean) = with(support) {
        attempt("guardprovider-dex") {
            System.loadLibrary("dexkit")
            DexKitBridge.create(classLoader, false).use { bridge ->
                // 两项独立解析、安装；某项不兼容时记录原因，另一项仍可生效。
                if (disableRootCheck) attempt("guardprovider-root-check") {
                    installTargets(support, module, "root-check", GuardProviderDexResolver.rootChecks(bridge), false)
                }
                if (blockAppListUpload) attempt("guardprovider-app-list-upload") {
                    installTargets(support, module, "app-list-upload", GuardProviderDexResolver.appListUpload(bridge), null)
                }
            }
        }
    }

    private fun installTargets(s: HookSupport, module: XposedModule, label: String, targets: List<MethodData>, result: Any?) {
        // 全部反射解析和签名校验通过后才安装，禁止对基本类型返回 null。
        val methods = targets.map { it.getMethodInstance(s.classLoader) }
        methods.forEach { validateMethod(it, result) }
        targets.zip(methods).forEach { (target, method) ->
            s.hooks.intercept(method) { chain -> if (s.active()) result else chain.proceed() }
            s.report("GUARD_HOOK_READY feature=$label method=${target.descriptor}")
        }
        targets.flatMap { it.callers }.distinctBy { it.descriptor }.forEach { caller ->
            if (caller.name != "<init>" && caller.name != "<clinit>" &&
                !Modifier.isAbstract(caller.modifiers) && !Modifier.isNative(caller.modifiers)) {
                s.attempt("guardprovider-deopt-${caller.descriptor}", reportSuccess = false) {
                    s.report("GUARD_DEOPT method=${caller.descriptor} success=${module.deoptimize(caller.getMethodInstance(s.classLoader))}")
                }
            }
        }
    }

    internal fun validateMethod(method: Method, result: Any?) {
        check(!Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers)) { "GuardProvider method has no Java body: $method" }
        check(if (result == null) method.returnType == Void.TYPE || !method.returnType.isPrimitive
            else result == false && method.returnType == Boolean::class.javaPrimitiveType) { "GuardProvider incompatible result: $method" }
    }
}
