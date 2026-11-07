package moe.fuck.hyperos.hyperos

// 只放行当前线程中的主题校验；嵌套调用和异常退出都必须恢复进入前的状态。
internal class ValidationScope {
    private val depth = ThreadLocal<Int>()
    val isActive: Boolean get() = (depth.get() ?: 0) > 0

    fun <T> within(block: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        try {
            return block()
        } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}
