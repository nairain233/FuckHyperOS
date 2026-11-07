package moe.fuck.hyperos.card

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import io.github.libxposed.service.XposedService
import moe.fuck.hyperos.App
import moe.fuck.hyperos.ui.setMiuixContent
import moe.fuck.hyperos.ui.showMessage
import java.io.File
import java.util.concurrent.Executors

internal sealed interface CardOverlay {
    data class CardMenu(val card: Bundle) : CardOverlay
    data class ImageMenu(val name: String) : CardOverlay
    data class ChooseCard(val image: String) : CardOverlay
    data class ChooseImage(val card: Bundle) : CardOverlay
    data class Editor(val view: CardImageEditor) : CardOverlay
    data class DeleteImage(val name: String) : CardOverlay
    data class Diagnostics(val text: String) : CardOverlay
    data object Help : CardOverlay
}

class CardActivity : ComponentActivity(), App.ServiceStateListener {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "XiaoAiCardUi") }
    private val handler = Handler(Looper.getMainLooper())
    private val client by lazy { CardClient(this) }
    private val previews by lazy { CardPreviewLoader(this, client) }
    private lateinit var page: CardPage
    private var busy = false
    private var available = false
    private var boundService: XposedService? = null
    private var session = 0
    private var initialLoadStarted = false
    private var pendingAid: String? = null
    private var state = Bundle()
    private var pollAttempt = 0
    private var pickingImage = false
    private var selectedImage: Uri? = null
    @Volatile private var disposed = false
    private val pendingBitmaps = mutableSetOf<Bitmap>()
    private var overlay by mutableStateOf<CardOverlay?>(null)
    private var overlayVisible by mutableStateOf(false)
    private var afterOverlayClosed: (() -> Unit)? = null
    private val pollRequest = object : Runnable {
        override fun run() {
            if (!available) return
            if (pickingImage || overlay != null) handler.postDelayed(this, 750)
            else poll(pollAttempt)
        }
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickingImage = false
        if (uri == null) { pendingAid = null; return@registerForActivityResult }
        selectedImage = uri
        startPendingImage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAid = savedInstanceState?.getString("pendingAid")
        pickingImage = savedInstanceState?.getBoolean("pickingImage") ?: false
        selectedImage = savedInstanceState?.getParcelable("selectedImage", Uri::class.java)
        page = CardPage(::finish, ::refresh, { pickImage(null) }, ::showHelp, ::setEnabled,
            ::chooseImage, { openOverlay(CardOverlay.CardMenu(it)) }, ::chooseCard,
            { openOverlay(CardOverlay.ImageMenu(it)) }, previews::load)
        page.selectTab(savedInstanceState?.getInt("selectedTab") ?: 0)
        setMiuixContent {
            page.Content(overlay != null) {
                CardOverlays(page, overlay, overlayVisible, available && !busy,
                    onDismiss = { closeOverlay() }, onDismissFinished = ::overlayClosed,
                    onChooseImage = ::chooseImage, onChooseCard = ::chooseCard,
                    onImport = ::pickImage, onReset = ::reset, onBind = ::bind,
                    onEdit = ::editImage, onDelete = { openOverlay(CardOverlay.DeleteImage(it)) },
                    onConfirmDelete = ::deleteImage, onSaveEditor = ::saveEditor,
                    onDiagnostics = ::showPreviewDiagnostics, previewError = previews::error,
                    onCopy = { text ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("卡面预览诊断", text))
                        showMessage("诊断已复制")
                    })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }
    override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingAid", pendingAid)
        outState.putBoolean("pickingImage", pickingImage)
        outState.putParcelable("selectedImage", selectedImage)
        outState.putInt("selectedTab", page.selectedTab)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        disposed = true
        session++
        handler.removeCallbacksAndMessages(null)
        afterOverlayClosed = null
        (overlay as? CardOverlay.Editor)?.view?.release()
        synchronized(pendingBitmaps) {
            pendingBitmaps.forEach { if (!it.isRecycled) it.recycle() }
            pendingBitmaps.clear()
        }
        previews.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    // 新浮层等旧浮层退出后再展示，裁剪位图也只在退出结束后回收。
    private fun openOverlay(value: CardOverlay) {
        if (isDestroyed) {
            (value as? CardOverlay.Editor)?.view?.release()
            return
        }
        if (overlay != null) closeOverlay { openOverlay(value) }
        else { overlay = value; overlayVisible = true }
    }
    private fun closeOverlay(after: (() -> Unit)? = null) {
        if (overlay == null) { after?.invoke(); return }
        if (!overlayVisible) {
            if (after != null) afterOverlayClosed = after
            return
        }
        afterOverlayClosed = after
        overlayVisible = false
    }
    private fun overlayClosed() {
        if (overlayVisible) return
        (overlay as? CardOverlay.Editor)?.view?.release()
        overlay = null
        val next = afterOverlayClosed
        afterOverlayClosed = null
        if (!isDestroyed) {
            if (next != null) next()
            else if (!initialLoadStarted && available && !busy && !pickingImage) {
                initialLoadStarted = true
                refresh()
            }
        }
    }

    override fun onServiceStateChanged(service: XposedService?) = runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        if (boundService !== service) {
            session++
            boundService = service
            initialLoadStarted = false
            handler.removeCallbacks(pollRequest)
        }
        try {
            available = service != null && service.apiVersion >= 102 && CardSettings.PACKAGE in service.scope
            val checked = service?.takeIf { it.apiVersion >= 102 }
                ?.getRemotePreferences(CardSettings.PREFERENCES)?.getBoolean(CardSettings.ENABLED, false) ?: false
            page.setEnabled(available, checked)
            if (!available) {
                handler.removeCallbacks(pollRequest)
                return@runOnUiThread
            }
            if (selectedImage != null) startPendingImage()
            else if (!initialLoadStarted && !busy) { initialLoadStarted = true; refresh() }
        } catch (_: Exception) {
            available = false
            page.setEnabled(false, page.state.enabled)
            handler.removeCallbacks(pollRequest)
        }
    }

    private fun setEnabled(checked: Boolean) {
        if (!available || busy) return
        try {
            val service = checkNotNull(App.mService)
            check(service.apiVersion >= 102 && CardSettings.PACKAGE in service.scope)
            check(service.getRemotePreferences(CardSettings.PREFERENCES).edit().putBoolean(CardSettings.ENABLED, checked).commit())
            state.putBoolean("enabled", checked)
            page.setEnabled(available, checked)
            page.showStatus(if (checked) "已启用 · 点击卡面即可更换" else "已停用 · 重新打开钱包页面后恢复原卡面")
        } catch (_: Exception) { showMessage("设置保存失败") }
    }

    private fun <T> task(label: String, work: () -> T, discard: (T) -> Unit = {}, done: (T) -> Unit) {
        if (busy || !available || isDestroyed) return
        busy = true
        val startedSession = session
        handler.removeCallbacks(pollRequest)
        page.setBusy(true)
        page.showStatus(label, working = true)
        worker.execute {
            val result = runCatching(work)
            if (disposed) { result.getOrNull()?.let(discard); return@execute }
            handler.post {
                if (disposed || session != startedSession || !available) {
                    result.getOrNull()?.let(discard)
                    if (!disposed) {
                        busy = false
                        page.setBusy(false)
                        if (available) { initialLoadStarted = true; refresh() }
                    }
                    return@post
                }
                busy = false
                page.setBusy(false)
                result.fold(done) { page.showStatus(it.message ?: "操作失败，请重试", error = true) }
            }
        }
    }
    private fun refresh() {
        if (!available || busy) return
        previews.retryFailed()
        page.retryPreviews()
        task("正在读取钱包卡片…", { client.call(CardProtocol.REFRESH) }) { poll(0) }
    }
    private fun poll(attempt: Int): Unit = task("正在读取卡片…", { client.call(CardProtocol.SNAPSHOT) }) { snapshot ->
        initialLoadStarted = true
        state = snapshot
        val loading = snapshot.getBoolean("loading")
        if (!loading) page.render(snapshot)
        page.showStatus(when {
            loading -> "正在读取卡片…"
            snapshot.getString("warning") != null -> snapshot.getString("warning").orEmpty()
            !snapshot.getBoolean("enabled") -> "已停用 · 开启后应用自定义卡面"
            else -> page.hint()
        }, working = loading, error = snapshot.getString("warning") != null)
        if (loading && attempt < 40) {
            pollAttempt = attempt + 1
            handler.postDelayed(pollRequest, 750)
        } else if (loading) page.showStatus("读取时间较长，请稍后刷新")
    }
    private fun chooseCard(image: String) {
        if (!available || busy) return
        if (page.state.cards.isEmpty()) { showMessage("请先刷新钱包卡片"); page.selectTab(0); closeOverlay(); return }
        openOverlay(CardOverlay.ChooseCard(image))
    }
    private fun chooseImage(card: Bundle) {
        if (!available || busy) return
        if (page.state.images.isEmpty()) pickImage(card.getString("aid"))
        else openOverlay(CardOverlay.ChooseImage(card))
    }
    private fun reset(aid: String) {
        if (!available || busy) return
        closeOverlay {
            task("正在恢复原卡面…", { client.call(CardProtocol.RESET, Bundle().apply { putString("aid", aid) }) }) { poll(0) }
        }
    }
    private fun bind(aid: String, image: String) {
        if (!available || busy) return
        closeOverlay {
            task("正在应用卡面…", { client.call(CardProtocol.BIND, Bundle().apply { putString("aid", aid); putString("image", image) }) }) {
                page.selectTab(0); poll(0)
            }
        }
    }
    private fun deleteImage(image: String) {
        if (!available || busy) return
        closeOverlay {
            task("正在删除…", { client.call(CardProtocol.DELETE, Bundle().apply { putString("image", image) }) }) { poll(0) }
        }
    }
    private fun editImage(image: String) {
        if (!available || busy) return
        closeOverlay {
            pendingAid = null
            task("正在打开图片…", { trackBitmap(CardImages.decode(previews.localFile(image))) }, discard = ::discardBitmap) { showEditor(it) }
        }
    }
    private fun showHelp() { openOverlay(CardOverlay.Help) }
    private fun showPreviewDiagnostics(detail: String?) {
        val text = "Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}\n" +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n${detail ?: CardPreviewDiagnostics.summary()}"
        openOverlay(CardOverlay.Diagnostics(text))
    }
    private fun pickImage(aid: String?) {
        if (!available || busy || pickingImage) return
        closeOverlay {
            if (!available) return@closeOverlay
            pendingAid = aid
            pickingImage = true
            try { imagePicker.launch(arrayOf("image/*")) }
            catch (_: Exception) {
                pickingImage = false
                pendingAid = null
                showMessage("无法打开图片选择器")
            }
        }
    }
    private fun startPendingImage() {
        val uri = selectedImage ?: return
        if (!available || busy || disposed) return
        selectedImage = null
        task("正在打开图片…", { trackBitmap(decodeSelected(uri)) }, discard = ::discardBitmap) { showEditor(it) }
    }
    private fun decodeSelected(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
        require(info.size.width.toLong() * info.size.height <= 64_000_000L) { "图片尺寸过大" }
        val factor = (maxOf(info.size.width, info.size.height) / 1600f).coerceAtLeast(1f)
        decoder.setTargetSize((info.size.width / factor).toInt().coerceAtLeast(1), (info.size.height / factor).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    private fun trackBitmap(bitmap: Bitmap): Bitmap = synchronized(pendingBitmaps) {
        if (disposed) bitmap.recycle() else pendingBitmaps.add(bitmap)
        bitmap
    }
    private fun discardBitmap(bitmap: Bitmap) = synchronized(pendingBitmaps) {
        pendingBitmaps.remove(bitmap)
        if (!bitmap.isRecycled) bitmap.recycle()
    }
    private fun showEditor(bitmap: Bitmap) {
        synchronized(pendingBitmaps) { pendingBitmaps.remove(bitmap) }
        page.showStatus(page.hint())
        openOverlay(CardOverlay.Editor(CardImageEditor(this, bitmap)))
    }
    private fun saveEditor(view: CardImageEditor) {
        if (!available || busy || !overlayVisible) return
        val rendered = try { trackBitmap(view.render()) } catch (_: Exception) { showMessage("生成卡面失败，请重试"); return }
        val aid = pendingAid
        pendingAid = null
        closeOverlay()
        task("正在同步图片…", {
            synchronized(pendingBitmaps) {
                pendingBitmaps.remove(rendered)
                check(!rendered.isRecycled) { "编辑器已关闭" }
            }
            try {
                val temp = File.createTempFile("card-", ".png", cacheDir)
                try {
                    temp.outputStream().use { check(rendered.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val name = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use {
                        client.call(CardProtocol.IMPORT, file = it).getString("image")!!
                    }
                    previews.rememberImported(name, temp)
                    name
                } finally { temp.delete() }
            } finally { rendered.recycle() }
        }) { name ->
            if (aid != null) bind(aid, name)
            else { page.selectTab(1); poll(0) }
        }
    }
}
