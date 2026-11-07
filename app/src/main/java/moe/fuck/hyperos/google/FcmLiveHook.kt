package moe.fuck.hyperos.google

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import io.github.libxposed.api.XposedModule
import moe.fuck.hyperos.hyperos.HookSupport
import moe.fuck.hyperos.hyperos.HyperOsSettings

// 功能来源：howard20181/HyperOS_FCM_Live（GPL-3.0）。各目标独立安装，版本差异不阻断其余 Hook。
internal object FcmLiveHook {
    internal const val GMS = "com.google.android.gms"
    internal const val PERSISTENT = "com.google.android.gms.persistent"
    internal const val RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
    internal val HEARTBEATS = setOf("com.google.android.intent.action.GCM_RECONNECT", "com.google.android.gcm.DISCONNECTED",
        "com.google.android.gcm.CONNECTED", "com.google.android.gms.gcm.HEARTBEAT_ALARM")

    internal fun allowBroadcast(caller: String?, callee: String?, action: String?, wakeAllowed: Boolean = true) =
        (caller == GMS && action == RECEIVE && wakeAllowed) || (callee in setOf(GMS, PERSISTENT) && action in HEARTBEATS)

    fun install(s: HookSupport, module: XposedModule, packageName: String, wakeAllowed: (String?) -> Boolean) {
        if (packageName == HyperOsSettings.SYSTEM) installSystem(s, module, wakeAllowed)
        if (packageName == HyperOsSettings.POWER_KEEPER) installPowerKeeper(s, module)
    }

    private fun installSystem(s: HookSupport, module: XposedModule, wakeAllowed: (String?) -> Boolean) = with(s) {
        val greeze = "com.miui.server.greeze.GreezeManagerService"
        attempt("fcm-allow-broadcast") {
            val type = load(greeze)
            val method = type.getDeclaredMethod("isAllowBroadcast", Int::class.javaPrimitiveType, String::class.java,
                Int::class.javaPrimitiveType, String::class.java, String::class.java)
            val lookup = type.getDeclaredMethod("getPackageNameFromUid", Int::class.javaPrimitiveType)
            hooks.intercept(method) { chain ->
                var callee = chain.args[3] as? String
                if (active()) attempt("fcm-callee-package", false) {
                    callee = module.getInvoker(lookup).invoke(chain.thisObject, chain.args[2]) as? String ?: callee
                }
                if (active() && allowBroadcast(chain.args[1] as? String, callee, chain.args[4] as? String, wakeAllowed(callee))) true else chain.proceed()
            }
            module.deoptimize(method)
        }
        attempt("fcm-heartbeat-broadcast") {
            val method = load(greeze).getDeclaredMethod("deferBroadcastForMiui", String::class.java)
            hooks.intercept(method) { chain -> if (active() && chain.args[0] in HEARTBEATS) false else chain.proceed() }
            module.deoptimize(method)
        }
        attempt("fcm-gms-limit") {
            val type = load(greeze)
            val method = try { type.getDeclaredMethod("triggerGMSLimitAction", Boolean::class.javaPrimitiveType) }
                catch (_: NoSuchMethodException) { type.getDeclaredMethod("triggerGMSLimitAction") }
            val field = if (method.parameterCount == 0) type.getDeclaredField("mGmsLimitEnabled").apply { isAccessible = true } else null
            hooks.intercept(method) { chain ->
                if (!active()) chain.proceed() else if (field != null) {
                    attempt("fcm-disable-gms-limit", false) { field.setBoolean(chain.thisObject, false) }
                    chain.proceed()
                } else chain.proceed(chain.args.toTypedArray().also { it[0] = false })
            }
            module.deoptimize(method)
        }
        attempt("fcm-framework-network-status") {
            // 新版电量和性能通过 Binder 上报限制状态；保持原方法的确认返回值和系统副作用。
            val method = load(greeze).getDeclaredMethod("updateGmsNetStatus", Boolean::class.javaPrimitiveType)
            hooks.intercept(method) { chain ->
                if (active()) chain.proceed(chain.args.toTypedArray().also { it[0] = false }) else chain.proceed()
            }
            module.deoptimize(method)
        }
        attempt("fcm-domestic-policy") {
            val method = load("com.miui.server.greeze.DomesticPolicyManager").getDeclaredMethod("deferBroadcast", String::class.java)
            hooks.intercept(method) { chain -> if (active()) false else chain.proceed() }
            module.deoptimize(method)
        }
        installListApps(s, module)
        attempt("fcm-autostart") {
            val record = load("com.android.server.am.BroadcastRecord")
            val caller = record.getDeclaredField("callerPackage").apply { isAccessible = true }
            val intent = record.getDeclaredField("intent").apply { isAccessible = true }
            val method = load("com.android.server.am.BroadcastQueueModernStubImpl").getDeclaredMethod("checkApplicationAutoStart",
                load("com.android.server.am.BroadcastQueue"), record, ResolveInfo::class.java)
            hooks.intercept(method) { chain ->
                var allow = false
                if (active()) attempt("fcm-check-autostart", false) {
                    val message = intent.get(chain.args[1]) as? Intent
                    // ResolveInfo 是当前接收方；不能只依据广播包名，否则可能遗漏显式组件或多个接收方。
                    val target = (chain.args[2] as? ResolveInfo)?.activityInfo?.packageName ?: message?.let(::targetPackage)
                    allow = caller.get(chain.args[1]) == GMS && message?.action == RECEIVE && wakeAllowed(target)
                }
                if (allow) true else chain.proceed()
            }
            module.deoptimize(method)
        }
        attempt("fcm-process-whitelist") {
            val method = load("com.android.server.am.ProcessPolicy").getDeclaredMethod("getWhiteList", Int::class.javaPrimitiveType)
            hooks.intercept(method) { chain ->
                val result = chain.proceed()
                if (active() && (chain.args[0] as Int and 1) != 0 && result is List<*>) {
                    // 返回副本以兼容不可变列表，并避免反复调用累积重复项。
                    ArrayList(result).apply { if (!contains(GMS)) add(GMS); if (!contains(PERSISTENT)) add(PERSISTENT) }
                } else result
            }
        }
        attempt("fcm-network-blacklist") {
            val type = load("com.miui.server.greeze.power.AwareResourceControl")
            val field = type.getDeclaredField("mNoNetworkBlackUids").apply { isAccessible = true }
            type.declaredConstructors.forEach { constructor ->
                hooks.intercept(constructor) { chain ->
                    val result = chain.proceed()
                    if (active()) attempt("fcm-remove-network-blacklist", false) {
                        (field.get(chain.thisObject) as? MutableCollection<*>)?.remove(GMS)
                    }
                    result
                }
                module.deoptimize(constructor)
            }
        }
        attempt("fcm-broadcast-delivery") { installDelivery(s, module, wakeAllowed) }
    }

    private fun installListApps(s: HookSupport, module: XposedModule) = with(s) {
        // 黑名单字段在部分 ROM 已删除，不能让该差异跳过白名单 Hook。
        attempt("fcm-system-blacklist") {
            val type = load("com.miui.server.greeze.power.ListAppsManager")
            val field = FcmCompatibility.listField(type, "mSystemBlackList", "SYSTEM_BLACK_LIST", List::class.java)
            fun removeGms(receiver: Any?) {
                attempt("fcm-remove-system-blacklist", false) {
                    (field.get(receiver) as? MutableCollection<*>)?.remove(GMS)
                }
            }
            type.declaredConstructors.forEach { constructor ->
                hooks.intercept(constructor) { chain ->
                    val result = chain.proceed()
                    if (active()) removeGms(chain.thisObject)
                    result
                }
                module.deoptimize(constructor)
            }
            // 静态名单可在热重载前已经初始化，也可能被系统更新，查询时再次处理。
            val query = type.getDeclaredMethod("isIncludeSystemBlack", Int::class.javaPrimitiveType)
            hooks.intercept(query) { chain ->
                if (active()) removeGms(chain.thisObject)
                chain.proceed()
            }
            module.deoptimize(query)
        }
        attempt("fcm-data-whitelist") {
            val type = load("com.miui.server.greeze.power.ListAppsManager")
            val field = FcmCompatibility.listField(type, "mUseDataWhiteList", "USE_DATA_WHITE_LIST", Set::class.java)
            val method = type.getDeclaredMethod("isInWhiteList", String::class.java)
            hooks.intercept(method) { chain ->
                if (active()) attempt("fcm-add-data-whitelist", false) {
                    @Suppress("UNCHECKED_CAST")
                    (field.get(chain.thisObject) as? MutableSet<String>)?.add(GMS)
                }
                chain.proceed()
            }
        }
    }

    private fun installPowerKeeper(s: HookSupport, module: XposedModule) = with(s) {
        attempt("fcm-netd-chain") {
            val rule = FcmCompatibility.networkRule(load("com.miui.powerkeeper.utils.NetdExecutor"))
            hooks.intercept(rule.method) { chain ->
                if (active()) chain.proceed(chain.args.toTypedArray().also { it[rule.index] = rule.value }) else chain.proceed()
            }
            module.deoptimize(rule.method)
        }
        // 不依赖 NetdExecutor 安装成功，缺少旧接口时仍然解除闹钟、网络和唤醒锁限制。
        attempt("fcm-observer") {
            FcmCompatibility.observerRules(load("com.miui.powerkeeper.utils.GmsObserver")).forEach { rule ->
                attempt("fcm-${rule.method.name}") {
                    hooks.intercept(rule.method) { chain ->
                        if (active()) chain.proceed(chain.args.toTypedArray().also { it[rule.index] = rule.value }) else chain.proceed()
                    }
                    module.deoptimize(rule.method)
                }
            }
        }
        attempt("fcm-doze-whitelist") {
            val method = load("com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper").getDeclaredMethod("getDozeWhiteListApps", Bundle::class.java)
            hooks.intercept(method) { chain ->
                val result = chain.proceed()
                if (active() && result is List<*>) ArrayList(result).apply { if (!contains(GMS)) add(GMS) } else result
            }
        }
    }

    internal fun targetPackage(intent: Intent): String? = intent.component?.packageName ?: intent.`package`

    private fun installDelivery(s: HookSupport, module: XposedModule, wakeAllowed: (String?) -> Boolean) = with(s) {
        val type = load("com.android.server.am.ActivityManagerService")
        val context = type.getDeclaredField("mContext").apply { isAccessible = true }
        val thread = load("android.app.IApplicationThread")
        val record = load("com.android.server.am.ProcessRecord").getDeclaredField("info").apply { isAccessible = true }
        val getRecord = type.getDeclaredMethod("getRecordForAppLOSP", thread)
        val method = type.getDeclaredMethod("broadcastIntentWithFeature", thread, String::class.java, Intent::class.java,
            String::class.java, load("android.content.IIntentReceiver"), Int::class.javaPrimitiveType, String::class.java,
            Bundle::class.java, Array<String>::class.java, Array<String>::class.java, Array<String>::class.java,
            Int::class.javaPrimitiveType, Bundle::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType)
        val exemption = load("android.os.PowerExemptionManager")
        val constructor = exemption.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val allow = exemption.getDeclaredMethod("addToTemporaryAllowList", String::class.java, Int::class.javaPrimitiveType,
            String::class.java, Long::class.javaPrimitiveType)
        hooks.intercept(method) { chain ->
            if (active()) attempt("fcm-deliver-message", false) {
                val intent = chain.args[2] as? Intent
                val target = intent?.let(::targetPackage)
                if (intent?.action == RECEIVE && wakeAllowed(target)) {
                    val caller = module.getInvoker(getRecord).invoke(chain.thisObject, chain.args[0])
                    if (caller != null && (record.get(caller) as? ApplicationInfo)?.packageName == GMS) {
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        target?.let {
                            val manager = constructor.newInstance(context.get(chain.thisObject))
                            module.getInvoker(allow).invoke(manager, target, 102, "GOOGLE_C2DM", 2000L)
                        }
                    }
                }
            }
            chain.proceed()
        }
        module.deoptimize(method)
    }
}
