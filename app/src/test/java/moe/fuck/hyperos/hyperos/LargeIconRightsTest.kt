package moe.fuck.hyperos.hyperos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LargeIconRightsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun createsRightsFileAndAcceptsExistingFile() {
        val directory = File(temporary.root, "rights/theme")
        LargeIconRights.ensure(directory, "product-123")
        val file = File(directory, "product-123-largeicons.mra")
        assertTrue(file.isFile)
        file.writeText("existing rights")
        LargeIconRights.ensure(directory, "product-123")
        assertTrue(file.readText() == "existing rights")
    }

    @Test fun rejectsInvalidIdsBeforeCreatingDirectories() {
        val directory = File(temporary.root, "missing")
        listOf("", " ", ".", "..", "../escape", "nested/id", "nested\\id", "id\u0000").forEach { id ->
            val result = runCatching { LargeIconRights.ensure(directory, id) }
            assertTrue("Invalid ID accepted: $id", result.exceptionOrNull() is IllegalArgumentException)
        }
        assertFalse(directory.exists())
    }

    @Test fun reportsDirectoryAndFileCreationFailures() {
        val blocker = temporary.newFile("blocked")
        assertTrue(runCatching { LargeIconRights.ensure(blocker, "id") }.isFailure)
        val directory = temporary.newFolder("rights")
        File(directory, "id-largeicons.mra").mkdir()
        assertTrue(runCatching { LargeIconRights.ensure(directory, "id") }.isFailure)
    }
}
