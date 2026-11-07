package moe.fuck.hyperos.card

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

// 回复只携带小块字节，不把钱包私有文件的描述符传回设置进程。
internal const val CARD_IMAGE_CHUNK_BYTES = 64 * 1024

internal class CardImageChunk(val bytes: ByteArray, val total: Long, val modified: Long)

internal fun readCardImageChunk(
    file: File,
    offset: Long,
    expectedTotal: Long = -1,
    expectedModified: Long = -1,
): CardImageChunk = RandomAccessFile(file, "r").use { input ->
    val total = input.length()
    val modified = file.lastModified()
    require(total in 1..MAX_CARD_IMAGE_BYTES) { "卡面图片为空或超过 16 MiB" }
    require(offset >= 0 && offset < total) { "卡面图片读取位置无效" }
    check(expectedTotal < 0 || expectedTotal == total) { "卡面图片已变化，请刷新后重试" }
    check(expectedModified < 0 || expectedModified == modified) { "卡面图片已变化，请刷新后重试" }
    val data = ByteArray(minOf(CARD_IMAGE_CHUNK_BYTES.toLong(), total - offset).toInt())
    input.seek(offset)
    input.readFully(data)
    check(input.length() == total && file.lastModified() == modified) { "卡面图片已变化，请刷新后重试" }
    CardImageChunk(data, total, modified)
}

internal fun receiveCardImage(
    output: OutputStream,
    read: (offset: Long, total: Long, modified: Long) -> CardImageChunk,
) {
    var offset = 0L
    var total = -1L
    var modified = -1L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("卡面图片读取已取消")
        val chunk = read(offset, total, modified)
        require(chunk.total in 1..MAX_CARD_IMAGE_BYTES) { "卡面图片长度无效" }
        if (total < 0) { total = chunk.total; modified = chunk.modified }
        check(chunk.total == total && chunk.modified == modified) { "卡面图片已变化，请刷新后重试" }
        val expected = minOf(CARD_IMAGE_CHUNK_BYTES.toLong(), total - offset).toInt()
        check(expected > 0 && chunk.bytes.size == expected) { "钱包返回的图片分段不完整" }
        output.write(chunk.bytes)
        offset += chunk.bytes.size
        if (offset == total) return
    }
}
