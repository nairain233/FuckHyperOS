package moe.fuck.hyperos.card

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile

class CardImageTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun imageIsTransferredExactlyInBoundedChunks() {
        val bytes = ByteArray(CARD_IMAGE_CHUNK_BYTES * 3 + 71) { (it % 251).toByte() }
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val output = ByteArrayOutputStream()
        val offsets = mutableListOf<Long>()
        receiveCardImage(output) { offset, total, modified ->
            offsets.add(offset)
            readCardImageChunk(file, offset, total, modified).also {
                assertTrue(it.bytes.size <= CARD_IMAGE_CHUNK_BYTES)
            }
        }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(listOf(0L, 65536L, 131072L, 196608L), offsets)
    }

    @Test fun singleByteImageFinishesAfterOneRead() {
        val output = ByteArrayOutputStream()
        var calls = 0
        receiveCardImage(output) { offset, total, modified ->
            calls++
            assertEquals(0L, offset)
            assertEquals(-1L, total)
            assertEquals(-1L, modified)
            CardImageChunk(byteArrayOf(42), 1, 100)
        }
        assertEquals(1, calls)
        assertArrayEquals(byteArrayOf(42), output.toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedWalletFileIsRejectedBeforeAllocatingAReply() {
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(MAX_CARD_IMAGE_BYTES + 1) }
        readCardImageChunk(file, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyWalletFileIsRejected() { readCardImageChunk(temporary.newFile(), 0) }

    @Test(expected = IllegalArgumentException::class)
    fun negativeOffsetIsRejected() { readCardImageChunk(temporary.newFile().apply { writeText("image") }, -1) }

    @Test(expected = IllegalStateException::class)
    fun changedWalletFileIsRejected() {
        val file = temporary.newFile().apply { writeText("image") }
        readCardImageChunk(file, 0, file.length() + 1, file.lastModified())
    }

    @Test(expected = IllegalStateException::class)
    fun changedTimestampIsRejected() {
        val file = temporary.newFile().apply { writeText("image") }
        readCardImageChunk(file, 0, file.length(), file.lastModified() + 2000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dishonestReplySizeIsRejected() {
        receiveCardImage(ByteArrayOutputStream()) { _, _, _ -> CardImageChunk(byteArrayOf(1), MAX_CARD_IMAGE_BYTES + 1, 1) }
    }

    @Test(expected = IllegalStateException::class)
    fun truncatedReplyDoesNotLoopForever() {
        receiveCardImage(ByteArrayOutputStream()) { _, _, _ -> CardImageChunk(byteArrayOf(), 10, 1) }
    }

    @Test(expected = IllegalStateException::class)
    fun metadataMustRemainStableBetweenReplies() {
        receiveCardImage(ByteArrayOutputStream()) { offset, _, _ ->
            if (offset == 0L) CardImageChunk(ByteArray(CARD_IMAGE_CHUNK_BYTES), CARD_IMAGE_CHUNK_BYTES + 1L, 100)
            else CardImageChunk(byteArrayOf(1), CARD_IMAGE_CHUNK_BYTES + 1L, 101)
        }
    }

    @Test fun cancellationStopsBeforeRequestingAnotherChunk() {
        Thread.currentThread().interrupt()
        try {
            receiveCardImage(ByteArrayOutputStream()) { _, _, _ -> fail("不应提交读取请求"); error("unreachable") }
            fail("应中止读取")
        } catch (_: InterruptedIOException) {
            // 中断由调用方管理，不在传输函数中清除。
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
