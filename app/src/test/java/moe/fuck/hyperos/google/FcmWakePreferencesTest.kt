package moe.fuck.hyperos.google

import android.content.SharedPreferences
import moe.fuck.hyperos.LogReporter
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class FcmWakePreferencesTest {
    private val report = LogReporter { _, _ -> }

    @Test fun emptyListAllowsAllButUnknownTargetsAndUnreadableConfigurationDoNotBypass() {
        assertTrue(FcmWakeSettings.allows(emptySet(), "app.target"))
        assertTrue(FcmWakeSettings.allows(setOf("app.target"), "app.target"))
        assertFalse(FcmWakeSettings.allows(setOf("app.target"), "app.other"))
        assertFalse(FcmWakeSettings.allows(null, "app.target"))
        assertFalse(FcmWakeSettings.allows(emptySet(), null))
        assertFalse(FcmWakeSettings.allows(emptySet(), ""))
    }

    @Test fun editsImmediatelyUpdateExistingHookPolicyAndClearRestoresWakeAll() {
        val prefs = Preferences()
        FcmWakePreferences(report).use { policy ->
            policy.bind(prefs.proxy)
            assertTrue(policy.allows("app.other"))
            prefs.update(setOf("app.target"))
            assertTrue(policy.allows("app.target"))
            assertFalse(policy.allows("app.other"))
            prefs.value = null
            prefs.notify(null)
            assertTrue(policy.allows("app.other"))
        }
        assertTrue(prefs.listeners.isEmpty())
    }

    @Test fun readFailureKeepsLastValidPolicyAndInitialFailureDoesNotBecomeWakeAll() {
        val prefs = Preferences(setOf("app.target"))
        FcmWakePreferences(report).use { policy ->
            policy.bind(prefs.proxy)
            prefs.failRead = true
            prefs.notify(FcmWakeSettings.ALLOWLIST)
            assertTrue(policy.allows("app.target"))
            assertFalse(policy.allows("app.other"))
        }
        FcmWakePreferences(report).use { policy ->
            policy.bind(prefs.proxy)
            assertFalse(policy.allows("app.target"))
            prefs.failRead = false
            prefs.update(emptySet())
            assertTrue(policy.allows("app.target"))
        }
    }

    @Test fun rebindAndCloseReleaseListenersAndIgnoreLateCallbacks() {
        val old = Preferences(setOf("app.old"))
        val current = Preferences(setOf("app.current"))
        val policy = FcmWakePreferences(report)
        policy.bind(old.proxy)
        val stale = old.listeners.single()
        policy.bind(current.proxy)
        assertTrue(old.listeners.isEmpty())
        old.value = emptySet()
        stale.onSharedPreferenceChanged(old.proxy, FcmWakeSettings.ALLOWLIST)
        assertTrue(policy.allows("app.current"))
        assertFalse(policy.allows("app.old"))
        val late = current.listeners.single()
        policy.close()
        assertTrue(current.listeners.isEmpty())
        late.onSharedPreferenceChanged(current.proxy, FcmWakeSettings.ALLOWLIST)
        assertFalse(policy.allows("app.current"))
    }

    @Test fun snapshotDoesNotShareMutablePreferenceSetAndIgnoresUnrelatedKeys() {
        val values = mutableSetOf("app.target")
        val prefs = Preferences(values)
        FcmWakePreferences(report).use { policy ->
            policy.bind(prefs.proxy)
            values.clear()
            prefs.notify("unrelated")
            assertFalse(policy.allows("app.other"))
            prefs.notify(FcmWakeSettings.ALLOWLIST)
            assertTrue(policy.allows("app.other"))
        }
    }

    @Test fun failedCommitRestoresLocalCacheAndKeepsPreviousPolicy() {
        val prefs = Preferences(setOf("app.target")).apply { commitSucceeded = false }
        FcmWakePreferences(report).use { policy ->
            policy.bind(prefs.proxy)
            assertTrue(runCatching { FcmWakeSettings.save(prefs.proxy, emptySet()) }.isFailure)
            assertEquals(setOf("app.target"), prefs.value)
            assertFalse(policy.allows("app.other"))
        }
    }

    @Test fun successfulCommitCopiesCallerSetAndUpdatesLivePolicy() {
        val prefs = Preferences()
        val selected = mutableSetOf("app.target")
        FcmWakePreferences(report).use { policy ->
            policy.bind(prefs.proxy)
            FcmWakeSettings.save(prefs.proxy, selected)
            selected.clear()
            assertEquals(setOf("app.target"), prefs.value)
            assertFalse(policy.allows("app.other"))
        }
    }

    @Test fun closingDuringFrameworkCallbackCannotDeadlockOrPublishRetiredState() {
        val prefs = Preferences(setOf("app.target"))
        val policy = FcmWakePreferences(report)
        policy.bind(prefs.proxy)
        val closing = CountDownLatch(1)
        val callbackHeld = CountDownLatch(1)
        prefs.beforeUnregister = {
            closing.countDown()
            check(callbackHeld.await(5, TimeUnit.SECONDS))
        }
        prefs.beforeNotify = { callbackHeld.countDown() }
        val close = FutureTask { policy.close() }
        Thread(close, "fcm-close-test").apply { isDaemon = true; start() }
        assertTrue(closing.await(5, TimeUnit.SECONDS))
        val notify = FutureTask { prefs.notify(FcmWakeSettings.ALLOWLIST) }
        Thread(notify, "fcm-framework-callback-test").apply { isDaemon = true; start() }
        close.get(5, TimeUnit.SECONDS)
        notify.get(5, TimeUnit.SECONDS)
        assertTrue(prefs.listeners.isEmpty())
        assertFalse(policy.allows("app.target"))
    }

    private class Preferences(var value: Set<String>? = null) {
        var failRead = false
        var commitSucceeded = true
        var beforeUnregister: (() -> Unit)? = null
        var beforeNotify: (() -> Unit)? = null
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        private var pending: Set<String>? = null
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { instance, method, args ->
            when (method.name) {
                "putStringSet" -> {
                    @Suppress("UNCHECKED_CAST")
                    pending = args!![1] as Set<String>
                    instance
                }
                "commit" -> { value = pending; notify(FcmWakeSettings.ALLOWLIST); commitSucceeded }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val proxy = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getStringSet" -> { check(!failRead); value ?: args!![1] }
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener" -> { synchronized(listeners) { listeners.add(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener) }; null }
                "unregisterOnSharedPreferenceChangeListener" -> {
                    beforeUnregister?.invoke()
                    synchronized(listeners) { listeners.remove(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener) }
                    null
                }
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        fun update(packages: Set<String>) { value = packages; notify(FcmWakeSettings.ALLOWLIST) }
        fun notify(key: String?) {
            synchronized(listeners) {
                beforeNotify?.invoke()
                listeners.toList().forEach { it.onSharedPreferenceChanged(proxy, key) }
            }
        }
    }
}
