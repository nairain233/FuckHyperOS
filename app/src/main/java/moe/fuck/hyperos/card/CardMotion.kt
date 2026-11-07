package moe.fuck.hyperos.card

import androidx.compose.animation.core.*
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

internal enum class CardPreviewPhase(val description: String) {
    Empty("选择卡面"), Loading("正在加载"), Ready("卡面已加载"), Failed("预览暂不可用"),
}

@Composable
internal fun CardDrawable(drawable: Drawable, progress: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val fraction = progress().coerceIn(0f, 1f)
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = maxOf(size.width / width, size.height / height) * (0.985f + 0.015f * fraction)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val previousAlpha = drawable.alpha
            native.save()
            try {
                native.translate((size.width - width * scale) / 2, (size.height - height * scale) / 2)
                native.scale(scale, scale)
                drawable.setBounds(0, 0, width, height)
                drawable.alpha = (previousAlpha * fraction).roundToInt()
                drawable.draw(native)
            } finally { drawable.alpha = previousAlpha; native.restore() }
        }
    }
}

// 入场只改变绘制，不折叠布局；Lazy 容器保存已展示状态，回滚列表时不会反复闪动。
@Composable
internal fun CardArrival(index: Int, content: @Composable () -> Unit) {
    var appeared by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (appeared) 1f else 0f) }
    val distance = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(Unit) {
        if (!appeared) {
            progress.animateTo(1f, tween(320, delayMillis = index.coerceAtMost(4) * 35, easing = FastOutSlowInEasing))
            appeared = true
        }
    }
    Box(Modifier.graphicsLayer {
        alpha = progress.value
        translationY = distance * (1f - progress.value)
        scaleX = 0.98f + 0.02f * progress.value
        scaleY = scaleX
    }) { content() }
}

@Composable
internal fun CardLoadingPreview(phase: CardPreviewPhase, modifier: Modifier = Modifier) {
    val background = MiuixTheme.colorScheme.secondaryContainer
    val highlight = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val shimmer = if (phase == CardPreviewPhase.Loading) {
        rememberInfiniteTransition(label = "卡面加载").animateFloat(0f, 1f,
            infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "卡面扫光")
    } else null
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(background)
        .drawBehind {
            shimmer?.let {
                val width = size.width * 0.65f
                val x = (size.width + width * 2) * it.value - width
                drawRect(Brush.linearGradient(listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(x - width, 0f), end = Offset(x, size.height)))
            }
        }, contentAlignment = Alignment.Center) {
        Text(if (phase == CardPreviewPhase.Ready) "正在加载" else phase.description,
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
internal fun LoadingWalletCard() {
    Card(Modifier.semantics { contentDescription = "正在读取钱包卡片" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("正在读取钱包卡片", style = MiuixTheme.textStyles.headline1)
            CardLoadingPreview(CardPreviewPhase.Loading, Modifier.fillMaxWidth().aspectRatio(70f / 44f))
        }
    }
}
