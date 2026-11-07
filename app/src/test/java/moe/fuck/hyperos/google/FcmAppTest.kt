package moe.fuck.hyperos.google

import org.junit.Assert.*
import org.junit.Test

class FcmAppTest {
    private val apps = listOf(FcmApp("app.beta", "Beta", false), FcmApp("app.alpha", "Alpha", false),
        FcmApp("app.system", "System", true))

    @Test fun hidesSystemAppsByDefaultButKeepsSelectedSystemAppsEditable() {
        assertEquals(listOf("app.alpha", "app.beta"), visibleFcmApps(apps, emptySet(), "", false).map { it.packageName })
        assertEquals(listOf("app.system", "app.alpha", "app.beta"), visibleFcmApps(apps, setOf("app.system"), "", false).map { it.packageName })
        assertEquals(3, visibleFcmApps(apps, emptySet(), "", true).size)
    }

    @Test fun searchesLabelsAndPackageNamesIgnoringCaseAndKeepsSelectedItemsFirst() {
        assertEquals(listOf("app.beta"), visibleFcmApps(apps, emptySet(), " BETA ", false).map { it.packageName })
        assertEquals(listOf("app.beta", "app.alpha"), visibleFcmApps(apps, setOf("app.beta"), "APP.", false).map { it.packageName })
        assertTrue(visibleFcmApps(apps, emptySet(), "no-match", true).isEmpty())
    }

    @Test fun uninstalledSelectionsRemainVisibleAndCanBeRemoved() {
        val result = visibleFcmApps(apps, setOf("app.removed"), "", false)
        assertEquals("app.removed", result.first().packageName)
        assertFalse(result.first().installed)
    }
}
