package moe.fuck.hyperos

import android.content.SharedPreferences

// 设置进程与注入进程都订阅同一远程配置；热重载和框架重连时释放旧监听。
internal class LoggingPreferences(private val report: LogReporter = ModuleLog.reporter("XiaoAiModule")) : AutoCloseable {
    @Volatile private var preferences: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun bind(updated: SharedPreferences?) {
        close()
        ModuleLog.detailedEnabled = false
        if (updated == null) return
        preferences = updated
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (preferences === updated && (key == ModuleLog.DETAILED || key == null)) {
                try {
                    ModuleLog.detailedEnabled = updated.getBoolean(ModuleLog.DETAILED, false)
                } catch (error: Exception) {
                    ModuleLog.detailedEnabled = false
                    report.error("LOG_CONFIG_UNAVAILABLE ${error.javaClass.simpleName}")
                }
            }
        }
        try {
            updated.registerOnSharedPreferenceChangeListener(listener)
            ModuleLog.detailedEnabled = updated.getBoolean(ModuleLog.DETAILED, false)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() {
        val previous = preferences
        val previousListener = listener
        preferences = null
        listener = null
        if (previousListener != null) previous?.unregisterOnSharedPreferenceChangeListener(previousListener)
    }
}

internal fun setDetailedLogging(preferences: SharedPreferences, enabled: Boolean) {
    val previous = preferences.getBoolean(ModuleLog.DETAILED, false)
    try {
        check(preferences.edit().putBoolean(ModuleLog.DETAILED, enabled).commit()) { "日志设置保存失败" }
        ModuleLog.detailedEnabled = enabled
    } catch (error: Exception) {
        // RemotePreferences 在远端提交前就更新本地缓存并通知监听；失败时一起恢复。
        runCatching { preferences.edit().putBoolean(ModuleLog.DETAILED, previous).commit() }
        ModuleLog.detailedEnabled = previous
        throw error
    }
}
