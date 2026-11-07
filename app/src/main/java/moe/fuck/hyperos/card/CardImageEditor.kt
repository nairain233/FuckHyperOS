package moe.fuck.hyperos.card

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withClip
import kotlin.math.max

// 编辑范围固定为卡片比例；拖动调整位置，缩放和旋转由页面控件控制。
@SuppressLint("ViewConstructor") // 只由卡面编辑页面创建，不用于 XML 布局或布局预览。
internal class CardImageEditor(context: Context, private var source: Bitmap) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 44f / 70f).toInt())
    }

    private fun matrix(width: Int, height: Int): Matrix {
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height) * zoom
        // 图片贴边时浮点舍入可能产生微小负数，拖动范围必须保持非负。
        val xLimit = ((source.width * scale - width) / 2).coerceAtLeast(0f)
        val yLimit = ((source.height * scale - height) / 2).coerceAtLeast(0f)
        val x = (offsetX * width).coerceIn(-xLimit, xLimit)
        val y = (offsetY * height).coerceIn(-yLimit, yLimit)
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - source.width * scale) / 2 + x, (height - source.height * scale) / 2 + y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (source.isRecycled) return
        canvas.withClip(0, 0, width, height) {
            drawColor(Color.BLACK)
            drawBitmap(source, matrix(width, height), paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                offsetX += (event.x - lastX) / width.coerceAtLeast(1)
                offsetY += (event.y - lastY) / height.coerceAtLeast(1)
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    fun setZoom(value: Float) { zoom = value.coerceIn(1f, 3f); invalidate() }

    fun rotate() {
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(90f) }, true)
        if (rotated !== source) source.recycle()
        source = rotated
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    fun render(): Bitmap = Bitmap.createBitmap(1120, 704, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(Color.BLACK); drawBitmap(source, matrix(it.width, it.height), paint) }
    }

    fun release() { if (!source.isRecycled) source.recycle() }
}
