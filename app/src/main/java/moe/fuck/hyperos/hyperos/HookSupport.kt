package moe.fuck.hyperos.hyperos

import io.github.libxposed.api.error.HookFailedError
import moe.fuck.hyperos.ReloadableHooks
import moe.fuck.hyperos.LogReporter
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class HookSupport(
    val classLoader: ClassLoader,
    val hooks: ReloadableHooks,
    val active: () -> Boolean,
    val report: LogReporter,
) {
    fun load(name: String): Class<*> = Class.forName(name, false, classLoader)

    // 只隔离模块自己的解析、安装或附加操作；原宿主调用必须放在此函数外。
    fun attempt(label: String, reportSuccess: Boolean = true, block: () -> Unit) {
        try {
            block()
            if (reportSuccess) report("HYPER_READY feature=$label")
        } catch (error: Exception) {
            skipped(label, error)
        } catch (error: LinkageError) {
            skipped(label, error)
        } catch (error: HookFailedError) {
            skipped(label, error)
        }
    }

    private fun skipped(label: String, error: Throwable) {
        report.error("HYPER_SKIPPED feature=$label type=${error.javaClass.simpleName} reason=${error.message}")
    }

    fun booleanMethods(
        className: String,
        name: String,
        value: Boolean,
        allowVoid: Boolean = false,
        paramCount: Int? = null,
    ) = attempt("$className#$name") {
        val methods = load(className).declaredMethods.filter {
            it.name == name && !Modifier.isAbstract(it.modifiers) &&
                (paramCount == null || it.parameterCount == paramCount) &&
                (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType ||
                    (allowVoid && it.returnType == Void.TYPE))
        }
        check(methods.isNotEmpty()) { "No compatible method" }
        methods.forEach { method ->
            attempt(method.toString()) {
                hooks.intercept(method) { chain ->
                    if (!active()) chain.proceed() else if (method.returnType == Void.TYPE) null else value
                }
            }
        }
    }

    fun drmSuccess(): Any {
        val type = load("miui.drm.DrmManager\$DrmResult")
        return checkNotNull(type.enumConstants?.singleOrNull { (it as Enum<*>).name == "DRM_SUCCESS" }) {
            "DRM_SUCCESS missing"
        }
    }
}

internal fun uniqueMethod(methods: List<Method>, compatible: (Method) -> Boolean): Method {
    val compatibleMethods = methods.filter(compatible)
    check(compatibleMethods.size == 1) {
        "Expected one compatible DEX match, found ${compatibleMethods.size} from ${methods.size}"
    }
    return compatibleMethods.single()
}
