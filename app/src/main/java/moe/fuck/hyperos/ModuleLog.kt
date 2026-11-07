package moe.fuck.hyperos

import android.util.Log

// 日志在输出端统一过滤，调用方必须显式标记错误，不依赖消息文本猜测等级。
internal class LogReporter(private val output: (Int, String) -> Unit) {
    operator fun invoke(message: String) = write(Log.INFO, message)
    fun warn(message: String) = write(Log.WARN, message)
    fun error(message: String) = write(Log.ERROR, message)

    fun write(priority: Int, message: String) {
        if (ModuleLog.detailedEnabled || priority >= Log.ERROR) output(priority, message)
    }
}

internal object ModuleLog {
    const val PREFERENCES = "logging"
    const val DETAILED = "detailed"
    @Volatile var detailedEnabled = false

    fun reporter(tag: String, framework: ((Int, String) -> Unit)? = null) = LogReporter { priority, message ->
        framework?.invoke(priority, message)
        Log.println(priority, tag, message)
    }
}
