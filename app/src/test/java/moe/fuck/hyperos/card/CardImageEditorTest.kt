package moe.fuck.hyperos.card

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardImageEditorTest {
    @Test fun drawingAndSavingTolerateRoundingAtCropEdges() {
        val source = Bitmap.createBitmap(1600, 1049, Bitmap.Config.ARGB_8888)
        val editor = CardImageEditor(RuntimeEnvironment.getApplication(), source)
        val preview = Bitmap.createBitmap(836, 525, Bitmap.Config.ARGB_8888)
        try {
            editor.layout(0, 0, preview.width, preview.height)
            editor.draw(Canvas(preview))
            editor.rotate()
            editor.draw(Canvas(preview))
            editor.setZoom(3f)
            editor.draw(Canvas(preview))
            val rendered = editor.render()
            try {
                assertEquals(1120, rendered.width)
                assertEquals(704, rendered.height)
            } finally { rendered.recycle() }
        } finally {
            editor.release()
            preview.recycle()
        }
    }
}
