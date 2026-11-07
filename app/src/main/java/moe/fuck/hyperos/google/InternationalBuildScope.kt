package moe.fuck.hyperos.google

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// 仅在凭据页面的同步调用期间切换，嵌套调用和宿主异常均恢复原值。
internal class InternationalBuildScope(private val read: () -> Boolean, private val write: (Boolean) -> Unit) {
    private val lock = ReentrantLock(true)

    fun <T> within(block: () -> T): T = lock.withLock {
        val previous = read()
        try {
            if (!previous) write(true)
            block()
        } finally {
            write(previous)
        }
    }
}
