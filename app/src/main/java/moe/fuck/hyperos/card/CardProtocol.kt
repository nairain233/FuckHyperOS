package moe.fuck.hyperos.card

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.OutputStream
import moe.fuck.hyperos.ModuleLog
import moe.fuck.hyperos.BuildConfig

internal object CardProtocol {
    const val SERVICE = BuildConfig.APPLICATION_ID + ".card.service"
    const val DESCRIPTOR = BuildConfig.APPLICATION_ID + ".card.ICardCenter.v1"
    val URI: Uri = Uri.parse("content://com.miui.tsmclient.provider.public")
    const val SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION
    const val REFRESH = SNAPSHOT + 1
    const val IMPORT = SNAPSHOT + 2
    const val BIND = SNAPSHOT + 3
    const val RESET = SNAPSHOT + 4
    const val DELETE = SNAPSHOT + 5
    const val IMAGE = SNAPSHOT + 6
    const val IMAGE_CHUNK = SNAPSHOT + 7
}

internal data class WalletCard(val aid: String, val title: String, val type: String, val urls: Set<String>) {
    fun bundle() = Bundle().apply {
        putString("aid", aid)
        putString("title", title)
        putString("type", type)
        putStringArrayList("urls", ArrayList(urls))
    }

}

// 每次重新取 Binder：宿主重启或热重载后不会继续持有旧模块服务。
internal class CardClient(private val context: Context) {
    private val report = ModuleLog.reporter(CardPreviewDiagnostics.TAG)
    fun call(code: Int, args: Bundle = Bundle(), file: ParcelFileDescriptor? = null): Bundle {
        return try { callOnce(code, args, file) }
        catch (error: DeadObjectException) {
            // 只重试无副作用的读取；导入、删除等操作不能因断连而重复执行。
            if (code != CardProtocol.SNAPSHOT && code != CardProtocol.IMAGE_CHUNK) throw error
            report.warn("CARD_READ_RECONNECT operation=$code")
            callOnce(code, args, file)
        }
    }

    private fun callOnce(code: Int, args: Bundle, file: ParcelFileDescriptor?): Bundle {
        val binder = context.contentResolver.call(CardProtocol.URI, CardProtocol.SERVICE, null, null)
            ?.getBinder(CardProtocol.SERVICE)
            ?: error("卡面服务未加载，请勾选小米智能卡作用域并重新打开小米智能卡")
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(CardProtocol.DESCRIPTOR)
            request.writeBundle(args)
            request.writeTypedObject(file, 0)
            check(binder.transact(code, request, reply, 0)) {
                "小米智能卡仍运行旧版卡面服务，请强行停止后重新打开小米智能卡"
            }
            reply.readException()
            val result = checkNotNull(reply.readBundle(javaClass.classLoader))
            result.getString("error")?.let { error(it) }
            return result
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    fun copyImage(name: String, output: OutputStream) {
        require(validCardImageName(name)) { "卡面文件名无效" }
        receiveCardImage(output) { offset, total, modified ->
            val result = call(CardProtocol.IMAGE_CHUNK, Bundle().apply {
                putString("image", name)
                putLong("offset", offset)
                putLong("total", total)
                putLong("modified", modified)
            })
            CardImageChunk(checkNotNull(result.getByteArray("bytes")) { "钱包没有返回图片分段" },
                result.getLong("total", -1), result.getLong("modified", -1))
        }
    }
}
