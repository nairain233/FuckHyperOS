package moe.fuck.hyperos.google

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import moe.fuck.hyperos.R
import moe.fuck.hyperos.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FcmWakePage(
    apps: List<FcmApp>,
    selected: Set<String>,
    available: Boolean,
    loading: Boolean,
    loadError: Boolean,
    onChange: (Set<String>) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(FcmAppFilter.ALL) }
    val focus = LocalFocusManager.current
    val candidates = remember(apps, selected, query, showSystem) { visibleFcmApps(apps, selected, query, showSystem) }
    val selectedCount = candidates.count { it.packageName in selected }
    val visible = remember(candidates, selected, filter) { candidates.filter { filter.matches(it.packageName in selected) } }
    AppPage("FCM 唤醒白名单", onBack, actions = {
        OverlayIconDropdownMenu(
            entry = DropdownEntry(items = listOf(
                DropdownItem(text = "显示系统应用", selected = showSystem, onClick = { showSystem = !showSystem }),
                DropdownItem(text = "全选当前列表", enabled = available && !loading && !loadError && visible.isNotEmpty(),
                    onClick = { onChange(selected + visible.map { it.packageName }) }),
                DropdownItem(text = "清空名单", enabled = available && selected.isNotEmpty(), onClick = { onChange(emptySet()) }),
            )),
            minWidth = 48.dp,
            minHeight = 48.dp,
            onExpandedChange = { if (it) focus.clearFocus() },
        ) {
            Icon(painter = painterResource(R.drawable.card_more), contentDescription = "白名单更多操作")
        }
    }) { padding, modifier ->
        SettingsList(padding, modifier) {
            item("instructions") {
                UsageInstructions("白名单保存后即时生效。\n\n空名单会放行全部应用；选择应用后，仅为选中项提供豁免。")
            }
            item("filters") {
                Spacer(Modifier.height(12.dp))
                SearchBar(inputField = {
                    InputField(query = query, onQueryChange = { query = it }, onSearch = { focus.clearFocus() },
                        expanded = expanded, onExpandedChange = { expanded = it }, label = "搜索应用名称或包名",
                        modifier = Modifier.testTag("fcm_app_search"))
                }, expanded = expanded, onExpandedChange = { expanded = it }) {}
                Spacer(Modifier.height(4.dp))
                FcmAppFilters(filter, candidates.size, selectedCount) { filter = it }
                if (!available) {
                    InfoCard("需要连接支持 API 102 的框架后保存名单")
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (loading) item("loading") { InfoCard("正在读取已安装应用…", working = true) }
            else if (loadError) item("error") {
                InfoCard("应用列表读取失败，请重试", error = true)
                TextButton(text = "重新读取", onClick = onRetry, modifier = Modifier.fillMaxWidth())
            }
            else {
                if (visible.isEmpty()) item("empty") { InfoCard("没有符合条件的应用") }
                items(visible, key = { "app:${it.packageName}" }) { app ->
                    Card {
                        CheckboxPreference(title = app.label,
                            summary = if (app.installed) app.packageName else "未安装 · ${app.packageName}",
                            checked = app.packageName in selected, enabled = available,
                            checkboxLocation = CheckboxLocation.End,
                            onCheckedChange = { checked -> onChange(if (checked) selected + app.packageName else selected - app.packageName) },
                            modifier = Modifier.testTag("fcm_app:${app.packageName}"))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// 使用 Miuix 按钮组成独立胶囊，避免将参考图的筛选项绘制为连在一起的分段容器。
@Composable
private fun FcmAppFilters(filter: FcmAppFilter, total: Int, selectedCount: Int, onFilterChange: (FcmAppFilter) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val selectedBackground = colorResource(R.color.hint_background)
    val selectedForeground = colorResource(R.color.hint_foreground)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FcmAppFilter.entries.forEach { option ->
            val active = filter == option
            val count = when (option) {
                FcmAppFilter.ALL -> total
                FcmAppFilter.SELECTED -> selectedCount
                FcmAppFilter.UNSELECTED -> total - selectedCount
            }
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                TextButton(text = "${option.title} $count", onClick = { onFilterChange(option) },
                    modifier = Modifier.testTag("fcm_filter:${option.name}").semantics {
                        selected = active
                        role = Role.Tab
                    },
                    cornerRadius = 50.dp, minWidth = 0.dp, minHeight = 32.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    textStyle = MiuixTheme.textStyles.body2,
                    colors = ButtonDefaults.textButtonColors(
                        color = if (active) selectedBackground else colors.secondaryVariant,
                        textColor = if (active) selectedForeground else colors.onSecondaryVariant))
            }
        }
    }
}
