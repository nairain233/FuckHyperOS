package moe.fuck.hyperos.google

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

@Suppress("UNUSED_PARAMETER")
class FcmCompatibilityTest {
    @Test fun powerKeeper40200UsesDnsAllowInsteadOfRemovedInitGmsChain() {
        val rule = FcmCompatibility.networkRule(ModernNetd::class.java)
        assertEquals("setGmsDnsBlockerState", rule.method.name)
        assertEquals(1, rule.index)
        assertEquals(false, rule.value)
    }

    @Test fun legacyNetdKeepsAcceptArgumentAtCorrectPosition() {
        val rule = FcmCompatibility.networkRule(LegacyNetd::class.java)
        assertEquals("initGmsChain", rule.method.name)
        assertEquals(2, rule.index)
        assertEquals("ACCEPT", rule.value)
    }

    @Test fun modernObserverUsesOppositeReachabilityAndRestrictionValues() {
        val rules = FcmCompatibility.observerRules(ModernObserver::class.java)
        assertEquals(mapOf("notifyFrameworkGmsNetworkChanged" to true, "updateFrameworkGmsNetStatus" to false),
            rules.associate { it.method.name to it.value })
    }

    @Test fun legacyObserverCanRetainAvailableHooksWhenOneOldMethodIsMissing() {
        val rules = FcmCompatibility.observerRules(PartialLegacyObserver::class.java)
        assertEquals(listOf("updateGmsAlarm", "updateGoogleReletivesWakelock"), rules.map { it.method.name })
        assertTrue(rules.all { it.index == 0 && it.value == false })
    }

    @Test fun modernStaticListsReplaceMissingInstanceFields() {
        val black = FcmCompatibility.listField(ModernLists::class.java, "mSystemBlackList", "SYSTEM_BLACK_LIST", List::class.java)
        val white = FcmCompatibility.listField(ModernLists::class.java, "mUseDataWhiteList", "USE_DATA_WHITE_LIST", Set::class.java)
        assertTrue(Modifier.isStatic(black.modifiers))
        assertTrue(Modifier.isStatic(white.modifiers))
        assertEquals("SYSTEM_BLACK_LIST", black.name)
        assertEquals("USE_DATA_WHITE_LIST", white.name)
    }

    @Test fun legacyInstanceListsAreStillAccepted() {
        val field = FcmCompatibility.listField(LegacyLists::class.java, "mSystemBlackList", "SYSTEM_BLACK_LIST", List::class.java)
        assertFalse(Modifier.isStatic(field.modifiers))
    }

    @Test fun incompatibleFieldOrMethodSignaturesAreRejected() {
        assertTrue(runCatching { FcmCompatibility.networkRule(WrongNetd::class.java) }.isFailure)
        assertTrue(runCatching { FcmCompatibility.observerRules(String::class.java) }.isFailure)
        assertTrue(runCatching { FcmCompatibility.listField(WrongLists::class.java, "mSystemBlackList", "SYSTEM_BLACK_LIST", List::class.java) }.isFailure)
    }

    @Test fun broadcastBypassOnlyAppliesToGmsPushOrGmsHeartbeats() {
        assertTrue(FcmLiveHook.allowBroadcast(FcmLiveHook.GMS, "app.target", FcmLiveHook.RECEIVE))
        assertFalse(FcmLiveHook.allowBroadcast(FcmLiveHook.GMS, "app.target", FcmLiveHook.RECEIVE, wakeAllowed = false))
        FcmLiveHook.HEARTBEATS.forEach { action ->
            assertTrue(FcmLiveHook.allowBroadcast(null, FcmLiveHook.GMS, action))
            assertTrue(FcmLiveHook.allowBroadcast(null, FcmLiveHook.GMS, action, wakeAllowed = false))
            assertTrue(FcmLiveHook.allowBroadcast(null, FcmLiveHook.PERSISTENT, action))
            assertFalse(FcmLiveHook.allowBroadcast(null, "unrelated.app", action))
        }
        assertFalse(FcmLiveHook.allowBroadcast("unrelated.app", "app.target", FcmLiveHook.RECEIVE))
        assertFalse(FcmLiveHook.allowBroadcast(FcmLiveHook.GMS, "app.target", "unrelated.action"))
        assertFalse(FcmLiveHook.allowBroadcast(null, null, null))
    }

    class ModernNetd { fun setGmsDnsBlockerState(uid: Int, deny: Boolean) = Unit }
    class LegacyNetd { fun initGmsChain(chain: String, uid: Int, rule: String) = Unit }
    class WrongNetd { fun initGmsChain(chain: String, uid: String, rule: String) = Unit }
    class ModernObserver {
        fun notifyFrameworkGmsNetworkChanged(reachable: Boolean) = Unit
        fun updateFrameworkGmsNetStatus(limit: Boolean) = true
    }
    class PartialLegacyObserver {
        fun updateGmsAlarm(limit: Boolean) = Unit
        fun updateGoogleReletivesWakelock(limit: Boolean) = Unit
    }
    class ModernLists {
        companion object {
            @JvmField val SYSTEM_BLACK_LIST: List<String> = mutableListOf()
            @JvmField val USE_DATA_WHITE_LIST: Set<String> = mutableSetOf()
        }
    }
    class LegacyLists { @JvmField val mSystemBlackList: List<String> = mutableListOf() }
    class WrongLists { @JvmField val SYSTEM_BLACK_LIST: String = "invalid" }
}
