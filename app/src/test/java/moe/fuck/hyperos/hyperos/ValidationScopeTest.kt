package moe.fuck.hyperos.hyperos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class ValidationScopeTest {
    @Test fun nestedValidationKeepsOuterScopeActive() {
        val scope = ValidationScope()
        assertFalse(scope.isActive)
        scope.within {
            assertTrue(scope.isActive)
            scope.within { assertTrue(scope.isActive) }
            assertTrue(scope.isActive)
        }
        assertFalse(scope.isActive)
    }

    @Test fun exceptionRestoresScopeAndPreservesOriginalException() {
        val scope = ValidationScope()
        val failure = IllegalStateException("host failure")
        try {
            scope.within {
                try {
                    scope.within { throw failure }
                } catch (error: IllegalStateException) {
                    assertTrue(scope.isActive)
                    throw error
                }
            }
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertFalse(scope.isActive)
    }

    @Test fun validationNeverAuthorizesAnotherThread() {
        val scope = ValidationScope()
        scope.within {
            val check = FutureTask {
                assertFalse(scope.isActive)
                scope.within { assertTrue(scope.isActive) }
                assertFalse(scope.isActive)
            }
            Thread(check, "theme-validation-test").start()
            check.get(5, TimeUnit.SECONDS)
            assertTrue(scope.isActive)
        }
        assertFalse(scope.isActive)
    }
}
