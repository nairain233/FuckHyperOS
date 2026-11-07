package moe.fuck.hyperos.card

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CardPreviewCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun cache() = CardPreviewCache(temporary.root) { file ->
        require(file.readText() == "complete") { "无法解码缓存" }
    }

    @Test fun validCachedImageDoesNotReadWalletAgain() {
        val cached = temporary.newFile("image.png").apply { writeText("complete") }
        assertEquals(cached, cache().read("image.png") { fail("不应重新读取") })
    }

    @Test fun truncatedCachedImageIsReplacedWithFreshImage() {
        temporary.newFile("image.png").writeText("truncated")
        var fetched = false
        val restored = cache().read("image.png") { fetched = true; it.writeText("complete") }
        assertTrue(fetched)
        assertEquals("complete", restored.readText())
    }

    @Test fun failedTransferDoesNotPublishPartialCacheAndCanBeRetried() {
        val cache = cache()
        try {
            cache.read("image.png") { it.writeText("partial"); throw IOException("读取中断") }
            fail("应报告传输错误")
        } catch (_: IOException) { }
        assertFalse(temporary.root.resolve("image.png").exists())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        assertEquals("complete", cache.read("image.png") { it.writeText("complete") }.readText())
    }

    @Test fun undecodableDownloadIsNeverPublished() {
        try {
            cache().read("image.png") { it.writeText("not an image") }
            fail("应报告解码错误")
        } catch (_: IllegalArgumentException) { }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun concurrentReadersFetchOneCompleteCopy() {
        val cache = cache()
        val reads = AtomicInteger()
        val worker = Executors.newFixedThreadPool(4)
        try {
            val results = worker.invokeAll((1..8).map {
                Callable { cache.read("image.png") { reads.incrementAndGet(); it.writeText("complete") }.readText() }
            })
            assertTrue(results.all { it.get() == "complete" })
            assertEquals(1, reads.get())
        } finally { worker.shutdownNow() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathTraversalIsRejected() { cache().read("../outside.png") { fail("不应读取") } }
}
