package moe.fuck.hyperos.card

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

// 预览与卡片操作使用独立线程，网络原图不会阻塞应用卡面和刷新状态。
internal class CardPreviewLoader(context: Context, private val client: CardClient) : AutoCloseable {
    private val directory = File(context.cacheDir, "card-previews").apply { mkdirs() }
    private val originals = File(context.cacheDir, "card-originals").apply { mkdirs() }
    private val localCache = CardPreviewCache(directory) { CardImages.decode(it, 32).recycle() }
    private val originalCache = CardPreviewCache(originals) { CardImages.decode(it, 32).recycle() }
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val requests = WeakHashMap<ImageView, Future<*>>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var closed = false

    fun load(view: ImageView, name: String?, url: String?) {
        // 视图复用不打断正在进行的图片传输，旧任务通过令牌隔离回调。
        requests.remove(view)?.cancel(false)
        val key = name?.takeIf { validCardImageName(it) }?.let { "local:$it" }
            ?: url?.takeIf { it.startsWith("https://") }?.let { "remote:$it" }
        val token = Any()
        view.tag = token
        view.setImageDrawable(null)
        if (key == null || closed) return
        cache.get(key)?.let { show(view, it, animate = false); return }
        if (key in failed) { unavailable(view); return }
        val reference = WeakReference(view)
        requests[view] = worker.submit {
            if (Thread.currentThread().isInterrupted || closed || reference.get()?.tag !== token) return@submit
            val bitmap = try {
                CardImages.decode(if (name != null) localFile(name) else originalFile(checkNotNull(url)), 512)
                    .also { cache.put(key, it); errors.remove(key) }
            } catch (error: Exception) {
                if (!Thread.currentThread().isInterrupted && !closed) {
                    failed.add(key)
                    val source = if (name != null) "local:${name.take(8)}" else "original"
                    errors[key] = CardPreviewDiagnostics.failed(source, error)
                }
                null
            }
            main.post {
                val target = reference.get()
                if (!closed && target?.tag === token) {
                    if (bitmap != null) show(target, bitmap, animate = true) else unavailable(target)
                    requests.remove(target)
                }
            }
        }
    }

    private fun unavailable(view: ImageView) {
        (view.parent as? CardPreview)?.unavailable()
    }

    private fun show(view: ImageView, bitmap: Bitmap, animate: Boolean) {
        val preview = view.parent as? CardPreview
        if (preview != null) preview.showBitmap(bitmap, animate) else view.setImageBitmap(bitmap)
    }

    fun retryFailed() { failed.clear() }

    fun error(name: String): String? = errors["local:$name"]

    // 导入成功后复用编辑器已生成的图片，首次预览也无需从钱包再下载一遍。
    fun rememberImported(name: String, source: File) {
        try {
            require(validCardImageName(name))
            localCache.read(name) { temporary ->
                source.inputStream().use { input -> temporary.outputStream().use { copyCardImage(input, it) } }
            }
            failed.remove("local:$name")
            errors.remove("local:$name")
        } catch (error: Exception) {
            // 钱包导入已经成功；预览缓存失败不能让界面误报导入失败，稍后仍可回传恢复。
            CardPreviewDiagnostics.failed("local:${name.take(8)}", CardPreviewFailure("保存导入图片预览", error))
        }
    }

    fun localFile(name: String): File {
        require(validCardImageName(name))
        return try {
            localCache.read(name) { temporary ->
                try {
                    temporary.outputStream().use { client.copyImage(name, it) }
                } catch (error: Exception) { throw CardPreviewFailure("分段读取钱包图片", error) }
            }
        } catch (error: CardPreviewFailure) { throw error }
        catch (error: Exception) { throw CardPreviewFailure("校验或保存本地预览", error) }
    }

    private fun originalFile(url: String): File {
        require(URI(url).scheme == "https")
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return originalCache.read(digest) { temporary ->
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                check(connection.responseCode in 200..299) { "原卡面暂不可用" }
                require(connection.contentLengthLong <= MAX_CARD_IMAGE_BYTES)
                connection.inputStream.use { input -> temporary.outputStream().use { copyCardImage(input, it) } }
            } finally { connection.disconnect() }
        }
    }

    override fun close() {
        closed = true
        requests.values.forEach { it.cancel(true) }
        requests.clear()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        cache.evictAll()
    }
}
