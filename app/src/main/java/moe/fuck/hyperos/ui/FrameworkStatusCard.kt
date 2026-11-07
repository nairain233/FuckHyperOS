package moe.fuck.hyperos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.fuck.hyperos.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal sealed interface FrameworkStatus {
    data object Disconnected : FrameworkStatus
    data class Connected(val name: String, val apiVersion: Int? = null, val versionCode: Long? = null) : FrameworkStatus
    data class ReadError(val summary: String? = null) : FrameworkStatus
}

@Composable
internal fun FrameworkStatusCard(state: FrameworkStatus, onRefresh: () -> Unit) {
    val active = state is FrameworkStatus.Connected
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val background = when {
        active && dark -> Color(0xFF1A3825)
        active -> Color(0xFFDFFAE4)
        dark -> Color(0xFF3A1E22)
        else -> Color(0xFFFFE4E1)
    }
    val foreground = if (dark) Color(0xFFF2F2F2) else Color(0xFF202124)
    val accent = when {
        active -> Color(0xFF36D167)
        dark -> Color(0xFFFF8A80)
        else -> Color(0xFFD32F2F)
    }
    val title = stringResource(if (active) R.string.framework_connected else R.string.framework_disconnected)
    val summary = when (state) {
        FrameworkStatus.Disconnected -> stringResource(R.string.framework_disconnected_summary)
        is FrameworkStatus.ReadError -> state.summary ?: stringResource(R.string.framework_error_summary)
        is FrameworkStatus.Connected -> when {
            state.name.isBlank() -> stringResource(R.string.framework_connected_fallback)
            state.apiVersion != null && state.versionCode != null -> stringResource(
                R.string.framework_connected_details, state.name, state.apiVersion.toString(), state.versionCode.toString())
            else -> stringResource(R.string.framework_connected_summary, state.name)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("framework_status_card")
            .semantics(mergeDescendants = true) { stateDescription = "$title。$summary" },
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = background, contentColor = foreground),
        onClick = onRefresh,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(Modifier.fillMaxWidth().clipToBounds().semantics { onClick(label = "刷新 LSP 状态", action = null) }) {
            // 装饰层不参与测量，卡片由文字自然撑高。
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
                StatusIcon(active, accent, Modifier.offset(x = 27.dp, y = 31.dp).size(110.dp))
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = 112.dp)
                    .padding(start = 16.dp, top = 14.dp, end = 72.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, color = foreground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(summary, modifier = Modifier.padding(top = 8.dp), color = foreground.copy(alpha = 0.8f),
                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
