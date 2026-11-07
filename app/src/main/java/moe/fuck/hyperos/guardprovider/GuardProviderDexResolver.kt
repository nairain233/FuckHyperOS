package moe.fuck.hyperos.guardprovider

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.reflect.Modifier

internal object GuardProviderDexResolver {
    private const val APP_LIST_URL = "https://flash.sec.miui.com/detect/app"

    fun appListUpload(bridge: DexKitBridge): List<MethodData> {
        // 旧版将日志标签与 URL 放在同一方法，保留该定位，但不接受歧义。
        val legacy = bridge.findMethod {
            matcher { usingStrings("AntiDefraudAppManager", APP_LIST_URL) }
        }
        if (legacy.isNotEmpty()) return listOf(legacy.unique("app-list-upload"))

        // 3.1.4 将应用列表组装与 HTTP 请求拆开，分别定位并校验直接调用边。
        val request = bridge.findMethod {
            matcher {
                usingStrings(APP_LIST_URL)
                paramTypes("com.miui.guardprovider.GuardApplication", "org.json.JSONObject")
                returnType = "java.lang.String"
            }
        }.unique("app-list-request")
        val builder = bridge.findMethod {
            matcher {
                usingStrings("AntiDefraudAppManager", "getAllUnSystemAppsStatus error")
                paramTypes("java.util.ArrayList")
                returnType = "java.lang.String"
            }
        }.filter { it.invokes.any { called -> called.descriptor == request.descriptor } }
            .unique("app-list-builder")
        return listOf(builder, request)
    }

    fun rootChecks(bridge: DexKitBridge): List<MethodData> {
        val direct = bridge.findMethod {
            matcher {
                usingStrings("/system/bin/")
                returnType = "boolean"
            }
        }.filter(::checksFileExists)

        // 新版把 su 路径放入类的静态数组；检查读取数组并调用 File.exists 的独立查询。
        val arrayChecks = bridge.findClass {
            matcher { usingStrings("/system/bin/su", "/system/xbin/su", "/sbin/su") }
        }.flatMap { owner ->
            bridge.findMethod {
                matcher {
                    declaredClass = owner.name
                    returnType = "boolean"
                    paramTypes()
                }
            }.filter { method ->
                checksFileExists(method) && method.usingFields.any {
                    it.usingType == FieldUsingType.Read && it.field.className == owner.name &&
                        it.field.typeName == "java.lang.String[]" && Modifier.isStatic(it.field.modifiers)
                }
            }
        }
        return (direct + arrayChecks).distinctBy { it.descriptor }.also {
            check(it.isNotEmpty()) { "GuardProvider Java root queries missing" }
        }
    }

    private fun checksFileExists(method: MethodData) = method.invokes.any {
        it.className == "java.io.File" && it.name == "exists" && it.paramTypeNames.isEmpty()
    }

    private fun List<MethodData>.unique(label: String): MethodData {
        check(size == 1) { "GuardProvider $label matches=$size" }
        return single()
    }
}
