package moe.fuck.hyperos.card

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import moe.fuck.hyperos.LogReporter
import java.util.concurrent.Executors

internal class CardCenter(
    val store: CardStore,
    private val loadCards: () -> List<WalletCard>,
    private val enabled: () -> Boolean,
    private val moduleUid: Int,
    private val report: LogReporter,
) : Binder(), AutoCloseable {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "XiaoAiCardList").apply { isDaemon = true } }
    @Volatile private var retired = false
    @Volatile private var loading = false
    @Volatile private var failure: String? = null
    @Volatile private var cards = emptyList<WalletCard>()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in CardProtocol.SNAPSHOT..CardProtocol.IMAGE_CHUNK) return super.onTransact(code, data, reply, flags)
        checkNotNull(reply)
        // 借用的是钱包公开 Provider，但服务只允许本模块 UID 访问。
        if (getCallingUid() != moduleUid) throw SecurityException("Card service caller rejected")
        data.enforceInterface(CardProtocol.DESCRIPTOR)
        val args = data.readBundle(javaClass.classLoader) ?: Bundle()
        val descriptor = data.readTypedObject(ParcelFileDescriptor.CREATOR)
        data.enforceNoDataAvail()
        val identity = clearCallingIdentity()
        try {
            val result = synchronized(lock) {
                check(!retired) { "卡面服务已重载，请刷新后重试" }
                when (code) {
                    CardProtocol.SNAPSHOT -> store.snapshot(cards).apply {
                        putBoolean("loading", loading)
                        putBoolean("enabled", enabled())
                        putString("warning", failure)
                    }
                    CardProtocol.REFRESH -> { refresh(); Bundle() }
                    CardProtocol.IMPORT -> Bundle().apply {
                        putString("image", store.importImage(checkNotNull(descriptor) { "没有收到图片" }))
                    }
                    CardProtocol.BIND -> {
                        val aid = args.getString("aid")
                        val card = cards.singleOrNull { it.aid == aid } ?: error("卡片已变化，请重新刷新")
                        // 通用图片加载没有 AID；只有不与其他卡共用的地址才能作为别名。
                        store.bind(card.copy(urls = unsharedCardUrls(card, cards)), args.getString("image").orEmpty())
                        Bundle()
                    }
                    CardProtocol.RESET -> { store.reset(args.getString("aid").orEmpty()); Bundle() }
                    CardProtocol.DELETE -> { store.delete(args.getString("image").orEmpty()); Bundle() }
                    CardProtocol.IMAGE -> error("卡面预览协议已更新，请重新打开模块设置")
                    CardProtocol.IMAGE_CHUNK -> Bundle().apply {
                        val name = args.getString("image").orEmpty()
                        val offset = args.getLong("offset", -1)
                        val chunk = readCardImageChunk(store.imageFile(name), offset,
                            args.getLong("total", -1), args.getLong("modified", -1))
                        putByteArray("bytes", chunk.bytes)
                        putLong("total", chunk.total)
                        putLong("modified", chunk.modified)
                        if (offset + chunk.bytes.size == chunk.total) {
                            report("CARD_IMAGE_TRANSFER_READY image=${name.take(8)} bytes=${chunk.total}")
                        }
                    }
                    else -> error("未知卡面操作")
                }
            }
            reply.writeNoException()
            reply.writeBundle(result)
        } catch (error: Exception) {
            report.error("CARD_SERVICE_FAILED operation=$code type=${error.javaClass.simpleName}")
            reply.writeNoException()
            reply.writeBundle(Bundle().apply { putString("error", error.message ?: "卡面操作失败") })
        } finally {
            descriptor?.close()
            restoreCallingIdentity(identity)
        }
        return true
    }

    private fun refresh() {
        if (loading) return
        loading = true
        failure = null
        worker.execute {
            try {
                val loaded = loadCards().distinctBy { it.aid }
                synchronized(lock) { if (!retired) cards = loaded }
                report("CARD_LIST_READY count=${loaded.size}")
            } catch (error: Exception) {
                failure = "读取卡片失败，请确认钱包已登录并重试（${error.javaClass.simpleName}）"
                report.error("CARD_LIST_FAILED type=${error.javaClass.simpleName} reason=${error.message}")
            } finally { loading = false }
        }
    }

    override fun close() = synchronized(lock) {
        retired = true
        worker.shutdownNow()
        Unit
    }
}
