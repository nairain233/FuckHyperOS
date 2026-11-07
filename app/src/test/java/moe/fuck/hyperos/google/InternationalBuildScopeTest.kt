package moe.fuck.hyperos.google

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

class InternationalBuildScopeTest {
    @Test fun nestedCallsRestoreDomesticFlagAfterHostException() {
        var international = false
        val scope = InternationalBuildScope({ international }, { international = it })
        val failure = IllegalStateException("host failure")
        try {
            scope.within {
                assertTrue(international)
                scope.within { assertTrue(international) }
                assertTrue(international)
                throw failure
            }
            fail("Expected host exception")
        } catch (error: IllegalStateException) {
            assertSame(failure, error)
        }
        assertFalse(international)
    }

    @Test fun internationalDeviceKeepsOriginalFlag() {
        var international = true
        val scope = InternationalBuildScope({ international }, { international = it })
        assertEquals("result", scope.within { "result" })
        assertTrue(international)
    }

    @Test fun concurrentCredentialCallsCannotRestoreAnotherCallsFlag() {
        var international = false
        val scope = InternationalBuildScope({ international }, { international = it })
        val started = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val task = FutureTask {
            started.countDown()
            scope.within { assertTrue(international); entered.countDown() }
        }
        scope.within {
            Thread(task, "passkey-scope-test").start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(entered.await(50, TimeUnit.MILLISECONDS))
            assertTrue(international)
        }
        task.get(5, TimeUnit.SECONDS)
        assertFalse(international)
    }
}
