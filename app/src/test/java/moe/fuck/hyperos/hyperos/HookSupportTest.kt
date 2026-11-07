package moe.fuck.hyperos.hyperos

import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import moe.fuck.hyperos.ReloadableHooks
import moe.fuck.hyperos.LogReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Executable

class HookSupportTest {
    private val module = object : XposedModule() {}

    @Test fun ambiguousAndMissingDexTargetsAreRejectedBeforeSignatureSelection() {
        val method = String::class.java.getDeclaredMethod("isEmpty")
        assertTrue(runCatching { uniqueMethod(emptyList()) { true } }.isFailure)
        assertTrue(runCatching { uniqueMethod(listOf(method, method)) { true } }.isFailure)
        assertTrue(runCatching { uniqueMethod(listOf(method)) { false } }.isFailure)
        assertSame(method, uniqueMethod(listOf(method)) { it.returnType == Boolean::class.javaPrimitiveType })
        val length = String::class.java.getDeclaredMethod("length")
        assertSame(method, uniqueMethod(listOf(length, method)) {
            it.returnType == Boolean::class.javaPrimitiveType
        })
    }

    @Test fun missingClassDoesNotBlockNextHookAndVoidUsesNull() {
        val query = Handle(Fixture::class.java.getDeclaredMethod("query"))
        val setter = Handle(Fixture::class.java.getDeclaredMethod("setSupportAd", Boolean::class.javaPrimitiveType))
        val reports = mutableListOf<String>()
        val support = HookSupport(javaClass.classLoader!!, ReloadableHooks(module, listOf(query, setter)), { true }, LogReporter { _, message -> reports.add(message) })
        support.booleanMethods("missing.Host", "query", false)
        support.booleanMethods(Fixture::class.java.name, "query", false)
        support.booleanMethods(Fixture::class.java.name, "setSupportAd", false, allowVoid = true)
        assertTrue(reports.any { it.contains("HYPER_SKIPPED") && it.contains("missing.Host") })
        assertTrue(query.replacement != null)
        assertTrue(setter.replacement != null)
        val unexpectedOriginal = { fail("Original should be bypassed"); null }
        assertEquals(false, query.replacement!!.intercept(Call(query.executable, unexpectedOriginal)))
        assertNull(setter.replacement!!.intercept(Call(setter.executable, unexpectedOriginal)))
    }

    @Test fun retiringHookPreservesOriginalResultAndException() {
        val query = Handle(Fixture::class.java.getDeclaredMethod("query"))
        var active = true
        val support = HookSupport(javaClass.classLoader!!, ReloadableHooks(module, listOf(query)), { active }, LogReporter { _, _ -> })
        support.booleanMethods(Fixture::class.java.name, "query", false)
        active = false
        assertEquals(true, query.replacement!!.intercept(Call(query.executable) { true }))
        val failure = IllegalStateException("original host exception")
        try {
            query.replacement!!.intercept(Call(query.executable) { throw failure })
            fail("Original exception swallowed")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
    }

    @Test fun disabledDispatcherNeverLoadsHostClassesOrInstallsHooks() {
        val loader = object : ClassLoader(null) {
            override fun loadClass(name: String): Class<*> = throw AssertionError("Unexpected class load: $name")
        }
        (HyperOsSettings.PACKAGES + HyperOsSettings.SYSTEM).forEach { packageName ->
            HyperOsHooks.install(module, ReloadableHooks(module), HyperOsSettings(), packageName, loader, { true }, LogReporter { _, _ -> })
        }
    }

    class Fixture {
        fun query(): Boolean = true
        @Suppress("UNUSED_PARAMETER") fun setSupportAd(value: Boolean) = Unit
    }

    private class Handle(private val target: Executable) : HookHandle {
        var replacement: Hooker? = null
        override fun getExecutable() = target
        override fun getId(): String? = null
        override fun unhook() = Unit
        override fun replaceHook(hooker: Hooker): HookHandle {
            replacement = hooker
            return Handle(target)
        }
    }

    private class Call(private val target: Executable, private val original: () -> Any?) : Chain {
        override fun getExecutable() = target
        override fun getThisObject(): Any = Fixture()
        override fun getArgs(): List<Any> = emptyList()
        override fun getArg(index: Int): Any = throw IndexOutOfBoundsException(index)
        override fun proceed(): Any? = original()
        override fun proceed(args: Array<out Any?>): Any? = original()
        override fun proceedWith(thisObject: Any): Any? = original()
        override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? = original()
    }
}
