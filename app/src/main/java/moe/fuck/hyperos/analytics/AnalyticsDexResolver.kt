package moe.fuck.hyperos.analytics

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

internal data class AnalyticsDexTarget(val label: String, val method: MethodData, val result: Any?)

internal object AnalyticsDexResolver {
    fun resolve(bridge: DexKitBridge): List<AnalyticsDexTarget> = buildList {
        // 移植自 HyperCeiler，许可证：AGPL-3.0；返回类型与参数同时校验。
        val usage = bridge.findMethod {
            matcher {
                usingStrings("Don't collect usage below Android KK 4.4")
                returnType = "org.json.JSONObject"
            }
        }.filter { it.paramTypeNames == listOf("long", "long") ||
            it.paramTypeNames == listOf("long", "long", "boolean") }
        check(usage.isNotEmpty()) { "Analytics usage processors missing" }
        usage.forEach { add(AnalyticsDexTarget("usage", it, null)) }

        // R8 会合并工具类；只按所在类与签名匹配会误中版本号、路径和开关方法。
        val state = bridge.findMethod {
            matcher {
                usingStrings("NetworkUtil", "getNetworkState error")
                paramTypes("android.content.Context")
                returnType = "java.lang.String"
            }
        }.singleTarget("network-state")
        add(AnalyticsDexTarget("network-state", state, "NONE"))

        val connected = bridge.findMethod {
            matcher {
                usingStrings("NetworkUtil", "isNetworkConnected exception")
                paramTypes()
                returnType = "boolean"
            }
        }.singleTarget("network-connected")
        add(AnalyticsDexTarget("network-connected", connected, false))

        // 旧版 Analytics 上传链路使用另一套 NetworkUtils，网络类型不在上面的合并类内。
        val legacyState = bridge.findMethod {
            matcher {
                usingStrings("NetworkUtils", "getNetState")
                paramTypes("android.content.Context")
                returnType = "com.miui.analytics.internal.util.NetState"
            }
        }.singleTarget("legacy-network-state")
        val legacyType = bridge.findMethod {
            matcher {
                declaredClass = legacyState.className
                paramTypes("android.content.Context")
                returnType = "int"
            }
        }.filter { candidate -> candidate.invokes.any { it.descriptor == legacyState.descriptor } }
            .singleTarget("legacy-network-type")
        add(AnalyticsDexTarget("legacy-network-type", legacyType, 0))

        val accessible = bridge.findMethod {
            matcher {
                usingStrings("NetworkUtils", "isNetAccessible e")
                declaredClass = legacyState.className
                paramTypes("android.content.Context")
                returnType = "boolean"
            }
        }.singleTarget("legacy-network-accessible")
        add(AnalyticsDexTarget("legacy-network-accessible", accessible, false))
    }

    private fun List<MethodData>.singleTarget(label: String): MethodData {
        check(size == 1) { "Analytics $label matches=$size" }
        return single().also { check(Modifier.isStatic(it.modifiers)) { "Analytics $label is not static" } }
    }
}
