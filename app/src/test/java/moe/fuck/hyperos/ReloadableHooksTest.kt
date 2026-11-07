package moe.fuck.hyperos

import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Executable

class ReloadableHooksTest {
    private val module = object : XposedModule() {}
    private val hooker = Hooker { it.proceed() }
    private val first = String::class.java.getDeclaredMethod("isEmpty")
    private val second = String::class.java.getDeclaredMethod("length")

    @Test fun replacesMatchingMethodAndRemovesObsoleteHook() {
        val kept = Handle(first)
        val obsolete = Handle(second)
        val hooks = ReloadableHooks(module, listOf(kept, obsolete))
        // 反射返回不同 Method 实例也必须识别为同一方法。
        hooks.intercept(String::class.java.getDeclaredMethod("isEmpty"), hooker)
        hooks.removeObsolete()
        assertNotNull(kept.replacement)
        assertEquals(0, kept.unhookCalls)
        assertEquals(0, kept.replacement!!.unhookCalls)
        assertNull(obsolete.replacement)
        assertEquals(1, obsolete.unhookCalls)
    }

    @Test fun failedReloadRemovesNewAndUnreplacedOldHooks() {
        val replaced = Handle(first)
        val remaining = Handle(second)
        val hooks = ReloadableHooks(module, listOf(replaced, remaining))
        hooks.intercept(first, hooker)
        hooks.rollback()
        assertEquals(0, replaced.unhookCalls)
        assertEquals(1, replaced.replacement!!.unhookCalls)
        assertEquals(1, remaining.unhookCalls)
    }

    @Test fun repeatedReloadAdoptsTheLatestHandle() {
        val old = Handle(first)
        ReloadableHooks(module, listOf(old)).apply {
            intercept(first, hooker)
            removeObsolete()
        }
        val current = old.replacement!!
        ReloadableHooks(module, listOf(current)).apply {
            intercept(first, hooker)
            removeObsolete()
        }
        assertNotNull(current.replacement)
        assertEquals(0, current.unhookCalls)
    }

    @Test fun replacesConstructorAndRemovesDisabledMethodHook() {
        val constructor = Handle(String::class.java.getDeclaredConstructor())
        val disabled = Handle(first)
        ReloadableHooks(module, listOf(constructor, disabled)).apply {
            intercept(String::class.java.getDeclaredConstructor(), hooker)
            removeObsolete()
        }
        assertNotNull(constructor.replacement)
        assertEquals(0, constructor.unhookCalls)
        assertEquals(1, disabled.unhookCalls)
    }

    @Test fun failedConstructorReloadRemovesReplacementAndRemainingConstructor() {
        val replaced = Handle(String::class.java.getDeclaredConstructor())
        val remaining = Handle(String::class.java.getDeclaredConstructor(ByteArray::class.java))
        ReloadableHooks(module, listOf(replaced, remaining)).apply {
            intercept(String::class.java.getDeclaredConstructor(), hooker)
            rollback()
        }
        assertEquals(1, replaced.replacement!!.unhookCalls)
        assertEquals(1, remaining.unhookCalls)
    }

    @Test fun allDisabledFeaturesRemoveAllOldConstructorsAndMethods() {
        val old = listOf(Handle(first), Handle(String::class.java.getDeclaredConstructor()))
        ReloadableHooks(module, old).removeObsolete()
        old.forEach { assertEquals(1, it.unhookCalls) }
    }

    private class Handle(private val method: Executable) : HookHandle {
        var replacement: Handle? = null
        var unhookCalls = 0
        override fun getExecutable() = method
        override fun getId(): String? = null
        override fun unhook() { unhookCalls++ }
        override fun replaceHook(hooker: Hooker): HookHandle = Handle(method).also { replacement = it }
    }
}
