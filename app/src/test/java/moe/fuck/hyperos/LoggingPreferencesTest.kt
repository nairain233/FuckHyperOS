package moe.fuck.hyperos

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class LoggingPreferencesTest {
    @After fun restore() { ModuleLog.detailedEnabled = false }

    @Test fun absentSettingDefaultsToErrorsOnly() {
        ModuleLog.detailedEnabled = true
        LoggingPreferences().use { it.bind(Preferences().proxy); assertFalse(ModuleLog.detailedEnabled) }
    }

    @Test fun preferenceChangesImmediatelyUpdateTheExistingReporter() {
        val preferences = Preferences()
        val messages = mutableListOf<String>()
        val report = LogReporter { _, message -> messages.add(message) }
        LoggingPreferences().use {
            it.bind(preferences.proxy)
            report("hidden")
            preferences.update(true)
            report("visible")
            preferences.update(false)
            report("hidden again")
            report.error("error")
        }
        assertEquals(listOf("visible", "error"), messages)
        assertTrue(preferences.listeners.isEmpty())
    }

    @Test fun unrelatedChangesDoNotAffectLoggingAndClearRestoresDefault() {
        val preferences = Preferences(true)
        LoggingPreferences().use {
            it.bind(preferences.proxy)
            preferences.enabled = false
            preferences.notify("another-setting")
            assertTrue(ModuleLog.detailedEnabled)
            preferences.notify(null)
            assertFalse(ModuleLog.detailedEnabled)
        }
    }

    @Test fun rebindAndCloseIgnoreLateNotificationsFromOldPreferences() {
        val old = Preferences(true)
        val current = Preferences(false)
        val binding = LoggingPreferences()
        binding.bind(old.proxy)
        val oldListener = old.listeners.single()
        binding.bind(current.proxy)
        assertTrue(old.listeners.isEmpty())
        oldListener.onSharedPreferenceChanged(old.proxy, ModuleLog.DETAILED)
        assertFalse(ModuleLog.detailedEnabled)
        current.update(true)
        val currentListener = current.listeners.single()
        binding.close()
        current.enabled = false
        currentListener.onSharedPreferenceChanged(current.proxy, ModuleLog.DETAILED)
        assertTrue(ModuleLog.detailedEnabled)
        assertTrue(current.listeners.isEmpty())
    }

    @Test fun disconnectDisablesDetailsAndReleasesListener() {
        val preferences = Preferences(true)
        LoggingPreferences().use {
            it.bind(preferences.proxy)
            it.bind(null)
            assertFalse(ModuleLog.detailedEnabled)
            assertTrue(preferences.listeners.isEmpty())
        }
    }

    @Test fun initialReadFailureReleasesListenerAndKeepsErrorsOnly() {
        val preferences = Preferences(true).apply { failRead = true }
        LoggingPreferences().use {
            try {
                it.bind(preferences.proxy)
                fail("应报告配置读取失败")
            } catch (_: IllegalStateException) { }
            assertFalse(ModuleLog.detailedEnabled)
            assertTrue(preferences.listeners.isEmpty())
        }
    }

    @Test fun failedRemoteCommitRestoresLocalCacheAndTheLogThreshold() {
        listOf(false, true).forEach { initial ->
            val preferences = Preferences(initial).apply { commitSucceeded = false }
            LoggingPreferences().use {
                it.bind(preferences.proxy)
                try {
                    setDetailedLogging(preferences.proxy, !initial)
                    fail("应报告远端保存失败")
                } catch (_: IllegalStateException) { }
                assertEquals(initial, preferences.enabled)
                assertEquals(initial, ModuleLog.detailedEnabled)
            }
        }
    }

    @Test fun successfulSavePersistsTheSettingAndNotifiesListeners() {
        val preferences = Preferences()
        LoggingPreferences().use {
            it.bind(preferences.proxy)
            setDetailedLogging(preferences.proxy, true)
            assertEquals(true, preferences.enabled)
            assertTrue(ModuleLog.detailedEnabled)
            setDetailedLogging(preferences.proxy, false)
            assertFalse(ModuleLog.detailedEnabled)
        }
    }

    @Test fun failedLiveReadFallsBackToErrorsAndReportsAnError() {
        val preferences = Preferences(true)
        val priorities = mutableListOf<Int>()
        LoggingPreferences(LogReporter { priority, _ -> priorities.add(priority) }).use {
            it.bind(preferences.proxy)
            preferences.failRead = true
            preferences.notify(ModuleLog.DETAILED)
            assertFalse(ModuleLog.detailedEnabled)
            assertEquals(listOf(android.util.Log.ERROR), priorities)
        }
    }

    private class Preferences(var enabled: Boolean? = null) {
        var failRead = false
        var commitSucceeded = true
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        private var pending: Boolean? = null
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { instance, method, args ->
            when (method.name) {
                "putBoolean" -> { pending = args!![1] as Boolean; instance }
                "commit" -> { enabled = pending; notify(ModuleLog.DETAILED); commitSucceeded }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val proxy = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> { check(!failRead); enabled ?: args!![1] }
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener" -> { listeners.add(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener); null }
                "unregisterOnSharedPreferenceChangeListener" -> { listeners.remove(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener); null }
                else -> error("Unexpected call: ${method.name}")
            }
        } as SharedPreferences

        fun update(value: Boolean) { enabled = value; notify(ModuleLog.DETAILED) }
        fun notify(key: String?) { listeners.toList().forEach { it.onSharedPreferenceChanged(proxy, key) } }
    }
}
