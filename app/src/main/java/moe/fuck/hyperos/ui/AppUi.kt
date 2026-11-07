package moe.fuck.hyperos.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import moe.fuck.hyperos.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

internal fun ComponentActivity.setMiuixContent(content: @Composable () -> Unit) {
    enableEdgeToEdge()
    setContent {
        val dark = isSystemInDarkTheme()
        SideEffect {
            window.insetsController?.apply {
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                setSystemBarsAppearance(if (dark) 0 else mask, mask)
            }
        }
        XiaoAiTheme(content = content)
    }
}

@Composable
internal fun XiaoAiTheme(mode: ColorSchemeMode = ColorSchemeMode.System, content: @Composable () -> Unit) {
    val controller = remember(mode) { ThemeController(mode) }
    MiuixTheme(controller = controller, content = content)
}

@Composable
internal fun AppPage(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, Modifier) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val compactHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() < 480.dp }
    Scaffold(
        topBar = {
            if (compactHeight) {
                SmallTopAppBar(title = title, navigationIcon = {
                    if (onBack != null) AppIconButton(R.drawable.card_back, "返回", onClick = onBack)
                }, actions = actions)
            } else TopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) AppIconButton(R.drawable.card_back, "返回", onClick = onBack)
                },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        content(padding, Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection))
    }
}

@Composable
internal fun SettingsList(padding: PaddingValues, modifier: Modifier, content: LazyListScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().padding(padding)
                .consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
internal fun SectionTitle(title: String) {
    SmallTitle(title, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun UsageInstructions(text: String) {
    val foreground = colorResource(R.color.hint_foreground)
    Card(modifier = Modifier.fillMaxWidth().testTag("usage_instructions"),
        colors = CardDefaults.defaultColors(color = colorResource(R.color.hint_background), contentColor = foreground)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium, color = foreground)
        }
    }
}

@Composable
internal fun InfoCard(text: String, working: Boolean = false, error: Boolean = false) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (working) CircularProgressIndicator(size = 20.dp)
            Text(text, color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
internal fun AppIconButton(resource: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics {
            contentDescription = description
            role = Role.Button
            if (!enabled) disabled()
        }) {
        Icon(painter = painterResource(resource), contentDescription = null,
            tint = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
    }
}

internal fun ComponentActivity.showMessage(value: String) {
    Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
