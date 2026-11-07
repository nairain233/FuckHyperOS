package moe.fuck.hyperos.google

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.core.content.edit
import moe.fuck.hyperos.LogReporter
import moe.fuck.hyperos.ModuleLog

internal object FcmWakeSettings {
    const val PREFERENCES = "fcm_wake"
    const val ALLOWLIST = "allowlist"

    fun read(preferences: SharedPreferences): Set<String> =
        preferences.getStringSet(ALLOWLIST, emptySet())?.toSet() ?: emptySet()

    // 空名单兼容上游原行为；无法读取配置时不额外放行，也不干预系统原策略。
    fun allows(packages: Set<String>?, target: String?): Boolean =
        packages != null && !target.isNullOrBlank() && (packages.isEmpty() || target in packages)

    // 正常保存必须检查 commit() 的结果；KTX edit 只返回 Unit，不能替代此处的失败检测。
    @SuppressLint("UseKtx")
    fun save(preferences: SharedPreferences, packages: Set<String>) {
        val previous = read(preferences)
        try {
            check(preferences.edit().putStringSet(ALLOWLIST, HashSet(packages)).commit()) { "白名单保存失败" }
        } catch (error: Exception) {
            // RemotePreferences 在远端提交前先更新本地缓存，失败时恢复上次已保存的名单。
            runCatching { preferences.edit(commit = true) { putStringSet(ALLOWLIST, HashSet(previous)) } }
            throw error
        }
    }
}

// 独立于保活开关快照订阅名单变化，不在 system_server 注册广播接收器。
internal class FcmWakePreferences(
    private val report: LogReporter = ModuleLog.reporter("FcmWake"),
    private val onChanged: (Set<String>?) -> Unit = {},
) : AutoCloseable {
    @Volatile var packages: Set<String>? = null
        private set
    private class Binding(val preferences: SharedPreferences) {
        lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    }
    private val lock = Any()
    private var binding: Binding? = null

    fun allows(target: String?) = FcmWakeSettings.allows(packages, target)

    fun bind(updated: SharedPreferences?) {
        close()
        if (updated == null) return
        val next = Binding(updated)
        next.listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == FcmWakeSettings.ALLOWLIST) refresh(next)
        }
        synchronized(lock) { binding = next }
        try {
            updated.registerOnSharedPreferenceChangeListener(next.listener)
            refresh(next)
        } catch (error: Exception) {
            close()
            report.error("FCM_WAKE_CONFIG_UNAVAILABLE type=${error.javaClass.simpleName}")
        }
    }

    private fun refresh(current: Binding) = synchronized(lock) {
        if (binding === current) {
            try {
                val snapshot = FcmWakeSettings.read(current.preferences)
                packages = snapshot
                onChanged(snapshot)
            } catch (error: Exception) {
                // 保留上一次有效快照，不能把读取失败当作空名单而放行全部应用。
                report.error("FCM_WAKE_CONFIG_UNAVAILABLE type=${error.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        val previous = synchronized(lock) {
            val old = binding
            binding = null
            packages = null
            onChanged(null)
            old
        }
        // LSPosed 在其监听集合锁内回调，注销时不能持有本类锁，避免热重载与回调互相等待。
        previous?.preferences?.unregisterOnSharedPreferenceChangeListener(previous.listener)
    }
}
