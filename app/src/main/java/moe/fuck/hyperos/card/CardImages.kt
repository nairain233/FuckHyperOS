package moe.fuck.hyperos.card

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Icon
import java.io.File

internal object CardImages {
    fun decode(file: File, maxSize: Int = 1200): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000L) { "图片尺寸无效或过大" }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return requireNotNull(file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }) { "图片无法解码" }
    }

    // 超级岛收到的是已解码的位图，不让 SystemUI 跨进程读取钱包的私有 file://。
    fun islandIcon(context: Context, file: File): Icon {
        val source = decode(file, 256)
        try {
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val padding = 6f * density
            val card = RectF(padding, padding, padding + 70f * density, padding + 44f * density)
            val bitmap = Bitmap.createBitmap((82 * density).toInt(), (56 * density).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val radius = 8f * density
            val path = Path().apply { addRoundRect(card, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(source, null, card, paint)
            canvas.restore()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.6f * density
            paint.color = Color.argb(80, 255, 255, 255)
            canvas.drawRoundRect(card, radius, radius, paint)
            return Icon.createWithBitmap(bitmap)
        } finally {
            source.recycle()
        }
    }
}
