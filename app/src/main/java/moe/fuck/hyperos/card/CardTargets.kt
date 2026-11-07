package moe.fuck.hyperos.card

import moe.fuck.hyperos.LogReporter

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class CardTargets(
    val imageUrl: Method,
    val islandImage: Method,
    val islandLoader: Method,
    val islandCallback: Method,
    val islandCallers: List<Method>,
    val requestClass: Class<*>,
    val httpClient: Method,
    val httpExecute: Method,
)

internal object CardDexResolver {
    fun resolve(loader: ClassLoader, report: LogReporter): CardTargets {
        System.loadLibrary("dexkit")
        return DexKitBridge.create(loader, false).use { bridge ->
            val cardInfo = Class.forName("com.miui.tsmclient.entity.CardInfo", false, loader)
            val urlClass = bridge.findClass {
                searchPackages("com.miui.tsmclient.util")
                matcher { source("CustomGlideUrl.java") }
            }.single().getInstance(loader)
            val urlMethod = urlClass.declaredMethods.single {
                Modifier.isStatic(it.modifiers) && it.returnType == Any::class.java &&
                    it.parameterTypes.toList() == listOf(String::class.java)
            }
            val islandClassName = bridge.findMethod {
                searchPackages("com.miui.tsmclient.util")
                matcher { usingStrings("miui.focus.param.custom") }
            }.single().className
            val islandClass = Class.forName(islandClassName, false, loader)
            val islandImage = islandClass.declaredMethods.single {
                Modifier.isStatic(it.modifiers) && it.returnType == String::class.java &&
                    it.parameterTypes.toList() == listOf(cardInfo)
            }
            val islandLoader = islandClass.declaredMethods.single {
                val args = it.parameterTypes
                Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && args.size == 3 &&
                    args[0] == Context::class.java && args[1] == String::class.java && args[2].isInterface
            }
            val callback = islandLoader.parameterTypes[2].declaredMethods.single {
                it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(android.graphics.drawable.Icon::class.java)
            }
            val request = bridge.findMethod {
                matcher { usingStrings("/api/%s/cardpacket/query/deviceCards") }
            }.single().className.let { Class.forName(it, false, loader) }
            // HttpClient 也被混淆；按源文件和请求参数结构选择，不能硬编码 o5.c。
            val clients = bridge.findClass { matcher { source("HttpClient.java") } }.map { it.getInstance(loader) }
            val candidates = clients.flatMap { type ->
                val getter = type.declaredMethods.singleOrNull {
                    Modifier.isStatic(it.modifiers) && it.returnType == type &&
                        it.parameterTypes.toList() == listOf(Context::class.java)
                }
                val execute = type.declaredMethods.singleOrNull {
                    !Modifier.isStatic(it.modifiers) && it.returnType != Void.TYPE &&
                        it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(request)
                }
                if (getter != null && execute != null) listOf(getter to execute) else emptyList()
            }
            val (getter, execute) = candidates.single()
            CardTargets(urlMethod, islandImage, islandLoader, callback,
                islandClass.declaredMethods.filter {
                    it.parameterTypes.toList().take(2) == listOf(Context::class.java, cardInfo)
                }, request, getter, execute).also {
                report("CARD_TARGETS_READY url=$urlMethod island=$islandImage loader=$islandLoader")
            }
        }
    }
}

internal fun cardMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method =
    generateSequence(type) { it.superclass }.mapNotNull {
        try { it.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }
        catch (_: NoSuchMethodException) { null }
    }.firstOrNull() ?: throw NoSuchMethodException("${type.name}#$name")

internal fun cardField(receiver: Any, name: String): Any? =
    generateSequence(receiver.javaClass) { it.superclass }.mapNotNull {
        try { it.getDeclaredField(name).apply { isAccessible = true } }
        catch (_: NoSuchFieldException) { null }
    }.firstOrNull()?.get(receiver)
