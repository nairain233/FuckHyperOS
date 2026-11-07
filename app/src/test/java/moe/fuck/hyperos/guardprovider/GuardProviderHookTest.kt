package moe.fuck.hyperos.guardprovider

import org.junit.Assert.assertTrue
import org.junit.Test

class GuardProviderHookTest {
    @Test fun uploadNeverUsesNullForPrimitiveResults() {
        GuardProviderHook.validateMethod(Fixture::class.java.getDeclaredMethod("upload"), null)
        GuardProviderHook.validateMethod(Fixture::class.java.getDeclaredMethod("update"), null)
        assertTrue(runCatching { GuardProviderHook.validateMethod(Fixture::class.java.getDeclaredMethod("root"), null) }.isFailure)
        assertTrue(runCatching { GuardProviderHook.validateMethod(Fixture::class.java.getDeclaredMethod("version"), null) }.isFailure)
    }

    @Test fun rootReplacementRejectsOtherSignaturesAndNativeMethods() {
        GuardProviderHook.validateMethod(Fixture::class.java.getDeclaredMethod("root"), false)
        assertTrue(runCatching { GuardProviderHook.validateMethod(Fixture::class.java.getDeclaredMethod("upload"), false) }.isFailure)
        assertTrue(runCatching { GuardProviderHook.validateMethod(Fixture::class.java.getDeclaredMethod("version"), false) }.isFailure)
        assertTrue(runCatching { GuardProviderHook.validateMethod(Fixture::class.java.getDeclaredMethod("nativeRoot"), false) }.isFailure)
    }

    class Fixture {
        fun upload(): String? = null
        fun update() = Unit
        fun root() = true
        fun version() = 1
        external fun nativeRoot(): Boolean
    }
}
