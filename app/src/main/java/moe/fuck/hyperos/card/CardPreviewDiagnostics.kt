package moe.fuck.hyperos.card

import moe.fuck.hyperos.ModuleLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

internal class CardPreviewFailure(val stage: String, cause: Exception) : Exception(stage, cause)

internal object CardPreviewDiagnostics {
    const val TAG = "XiaoAiCardPreview"
    private val report = ModuleLog.reporter(TAG)
    private val history = ConcurrentLinkedDeque<String>()

    fun failed(source: String, error: Exception): String {
        val stage = (error as? CardPreviewFailure)?.stage ?: "解码预览图片"
        val detail = buildString {
            append(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()))
            append(" source=$source stage=$stage\n")
            append(error.stackTraceToString().replace(Regex("https?://[^\\s]+"), "<url>"))
        }
        report.error("PREVIEW_FAILED $detail")
        history.addLast(detail)
        while (history.size > 12) history.pollFirst()
        return detail
    }

    fun summary(): String = history.joinToString("\n\n").ifBlank {
        "本次打开页面尚未记录预览错误。请先刷新并打开“我的卡面”，再复制诊断。"
    }
}
