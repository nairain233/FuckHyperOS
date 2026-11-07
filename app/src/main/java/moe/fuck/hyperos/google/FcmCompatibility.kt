package moe.fuck.hyperos.google

import java.lang.reflect.Field
import java.lang.reflect.Method

internal object FcmCompatibility {
    data class ArgumentRule(val method: Method, val index: Int, val value: Any)

    fun networkRule(type: Class<*>): ArgumentRule = try {
        ArgumentRule(type.getDeclaredMethod("initGmsChain", String::class.java, Int::class.javaPrimitiveType, String::class.java), 2, "ACCEPT")
    } catch (_: NoSuchMethodException) {
        // PowerKeeper 4.2.00 移除旧链接口；新接口的 true 表示 deny，false 表示 allow。
        ArgumentRule(type.getDeclaredMethod("setGmsDnsBlockerState", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), 1, false)
    }

    fun observerRules(type: Class<*>): List<ArgumentRule> {
        val modern = try { type.getDeclaredMethod("updateFrameworkGmsNetStatus", Boolean::class.javaPrimitiveType) }
            catch (_: NoSuchMethodException) { null }
        return if (modern != null) {
            // 通知入口传“可达”，系统状态接口传“不限制”，两者布尔语义相反。
            listOf(ArgumentRule(type.getDeclaredMethod("notifyFrameworkGmsNetworkChanged", Boolean::class.javaPrimitiveType), 0, true),
                ArgumentRule(modern, 0, false))
        } else listOf("updateGmsAlarm", "updateGmsNetWork", "updateGoogleReletivesWakelock").mapNotNull { name ->
            try { ArgumentRule(type.getDeclaredMethod(name, Boolean::class.javaPrimitiveType), 0, false) }
            catch (_: NoSuchMethodException) { null }
        }.also { check(it.isNotEmpty()) { "No supported GmsObserver methods" } }
    }

    fun listField(type: Class<*>, legacy: String, modern: String, collectionType: Class<*>): Field {
        val field = try { type.getDeclaredField(legacy) } catch (_: NoSuchFieldException) { type.getDeclaredField(modern) }
        check(collectionType.isAssignableFrom(field.type)) { "Incompatible FCM collection: $field" }
        return field.apply { isAccessible = true }
    }
}
