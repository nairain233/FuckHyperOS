package moe.fuck.hyperos.card

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

// 缓存文件必须可解码；旧版本留下的空文件或中断文件不能永久遮住钱包中的原图。
internal class CardPreviewCache(
    private val directory: File,
    private val validate: (File) -> Unit,
) {
    private val locks = ConcurrentHashMap<String, Any>()

    fun read(name: String, fetch: (File) -> Unit): File {
        require(name.isNotEmpty() && File(name).name == name && name != "." && name != "..")
        return synchronized(locks.getOrPut(name) { Any() }) {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建预览缓存目录" }
            val cached = File(directory, name)
            if (cached.isFile) {
                val valid = try { validate(cached); true } catch (_: Exception) { false }
                if (valid) return@synchronized cached
                check(cached.delete()) { "无法清理损坏的预览缓存" }
            }
            val temporary = File.createTempFile("preview-", ".tmp", directory)
            try {
                fetch(temporary)
                validate(temporary)
                try {
                    Files.move(temporary.toPath(), cached.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                cached
            } finally { temporary.delete() }
        }
    }
}
