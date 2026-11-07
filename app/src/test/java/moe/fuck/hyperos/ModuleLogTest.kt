package moe.fuck.hyperos

import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModuleLogTest {
    @Before fun reset() { ModuleLog.detailedEnabled = false }
    @After fun restore() { ModuleLog.detailedEnabled = false }

    @Test fun disabledLoggingEmitsOnlyErrorsAndAssertions() {
        val written = mutableListOf<Int>()
        val report = LogReporter { priority, _ -> written.add(priority) }
        (Log.VERBOSE..Log.ASSERT).forEach { report.write(it, "event") }
        assertEquals(listOf(Log.ERROR, Log.ASSERT), written)
    }

    @Test fun liveChangesAffectAnAlreadyCreatedReporter() {
        val written = mutableListOf<String>()
        val report = LogReporter { _, message -> written.add(message) }
        report("hidden")
        report.error("first error")
        ModuleLog.detailedEnabled = true
        report("details")
        report.warn("warning")
        ModuleLog.detailedEnabled = false
        report.warn("hidden warning")
        report.error("last error")
        assertEquals(listOf("first error", "details", "warning", "last error"), written)
    }

    @Test fun messageContentsCannotChangeItsExplicitLevel() {
        val written = mutableListOf<Pair<Int, String>>()
        val report = LogReporter { priority, message -> written.add(priority to message) }
        report("FAILED is just a diagnostic message")
        report.error("operation could not finish")
        assertEquals(listOf(Log.ERROR to "operation could not finish"), written)
    }
}
