package moe.fuck.hyperos

import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

// 每个方法或构造器只有一个模块 Hook；重载时原子替换，最后移除新版不再使用的 Hook。
internal class ReloadableHooks(
    private val module: XposedModule,
    oldHandles: List<HookHandle> = emptyList(),
) {
    private val remaining = oldHandles.toMutableList()
    private val installed = mutableListOf<HookHandle>()

    fun intercept(method: Executable, hooker: Hooker) {
        val old = remaining.firstOrNull { it.executable == method }
        val handle = if (old != null) old.replaceHook(hooker) else module.hook(method).intercept(hooker)
        remaining.remove(old)
        installed.add(handle)
    }

    fun removeObsolete() {
        remaining.forEach { it.unhook() }
        remaining.clear()
    }

    fun rollback() {
        installed.forEach { it.unhook() }
        installed.clear()
        removeObsolete()
    }
}
