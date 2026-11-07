package moe.fuck.hyperos.card

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CardOverlays(
    page: CardPage,
    overlay: CardOverlay?,
    visible: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onChooseImage: (Bundle) -> Unit,
    onChooseCard: (String) -> Unit,
    onImport: (String?) -> Unit,
    onReset: (String) -> Unit,
    onBind: (String, String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onConfirmDelete: (String) -> Unit,
    onSaveEditor: (CardImageEditor) -> Unit,
    onDiagnostics: (String?) -> Unit,
    previewError: (String) -> String?,
    onCopy: (String) -> Unit,
) {
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.65f }
    when (overlay) {
        is CardOverlay.CardMenu -> {
            val card = overlay.card
            val entries = buildList<Pair<String, () -> Unit>> {
                card.getString("image")?.let(previewError)?.let { error -> add("查看预览错误" to { onDiagnostics(error) }) }
                add("更换卡面" to { onChooseImage(card) })
                add("从相册导入" to { onImport(card.getString("aid")) })
                if (!card.getString("image").isNullOrEmpty()) add("恢复原卡面" to { onReset(card.getString("aid").orEmpty()) })
            }
            CardActionMenu(page, visible, enabled, entries, onDismiss, onDismissFinished)
        }
        is CardOverlay.ImageMenu -> {
            val entries = buildList<Pair<String, () -> Unit>> {
                previewError(overlay.name)?.let { error -> add("查看预览错误" to { onDiagnostics(error) }) }
                add("应用到卡片" to { onChooseCard(overlay.name) })
                add("编辑为新卡面" to { onEdit(overlay.name) })
                add("删除卡面" to { onDelete(overlay.name) })
            }
            CardActionMenu(page, visible, enabled, entries, onDismiss, onDismissFinished)
        }
        is CardOverlay.ChooseCard -> OverlayBottomSheet(
            show = visible, title = "应用到哪张卡片？", onDismissRequest = onDismiss, onDismissFinished = onDismissFinished,
        ) {
            LazyColumn(Modifier.heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(page.state.cards, key = { it.getString("aid").orEmpty() }) { card ->
                    ArrowPreference(title = card.getString("title").orEmpty(), enabled = enabled,
                        onClick = { onBind(card.getString("aid").orEmpty(), overlay.image) })
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
        is CardOverlay.ChooseImage -> OverlayBottomSheet(
            show = visible, title = overlay.card.getString("title"), onDismissRequest = onDismiss, onDismissFinished = onDismissFinished,
        ) {
            Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("点击图片立即应用", style = MiuixTheme.textStyles.body2)
                LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), modifier = Modifier.heightIn(max = maxHeight),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(page.state.images, key = { it }) { name ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            page.Preview(name, null, if (name == overlay.card.getString("image")) "当前卡面，已选中" else "选择此卡面", enabled,
                                { onBind(overlay.card.getString("aid").orEmpty(), name) })
                            if (name == overlay.card.getString("image")) Text("当前卡面", style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = "从相册添加", enabled = enabled, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(), onClick = { onImport(overlay.card.getString("aid")) })
                    if (!overlay.card.getString("image").isNullOrEmpty()) {
                        TextButton(text = "恢复原卡面", enabled = enabled, modifier = Modifier.fillMaxWidth(),
                            onClick = { onReset(overlay.card.getString("aid").orEmpty()) })
                    }
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is CardOverlay.Editor -> {
            var zoom by remember(overlay.view) { mutableFloatStateOf(1f) }
            OverlayBottomSheet(show = visible, title = "编辑卡面", enableNestedScroll = false,
                onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
                Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AndroidView(factory = { overlay.view }, modifier = Modifier.fillMaxWidth().aspectRatio(70f / 44f).clipToBounds()
                        .semantics { contentDescription = "卡面裁剪预览，可拖动调整位置" })
                    Text("拖动调整位置，滑动缩放", style = MiuixTheme.textStyles.body2)
                    Slider(value = zoom, onValueChange = { zoom = it; overlay.view.setZoom(it) },
                        valueRange = 1f..3f, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "卡面缩放" })
                    TextButton(text = "旋转 90°", enabled = enabled, onClick = { overlay.view.rotate() }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                DialogActions("保存", enabled, onDismiss) { onSaveEditor(overlay.view) }
            }
        }
        is CardOverlay.DeleteImage -> OverlayDialog(show = visible, title = "删除卡面？",
            summary = "使用这张图片的卡片会恢复原卡面。", onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
            DialogActions("删除", enabled, onDismiss) { onConfirmDelete(overlay.name) }
        }
        is CardOverlay.Diagnostics -> OverlayDialog(show = visible, title = "卡面预览诊断",
            onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
            Text(overlay.text, modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), style = MiuixTheme.textStyles.body2)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "关闭", modifier = Modifier.weight(1f), onClick = onDismiss)
                TextButton(text = "复制", modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { onCopy(overlay.text); onDismiss() })
            }
        }
        CardOverlay.Help -> OverlayDialog(show = visible, title = "使用说明",
            onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
            Text("1. 在 LSPosed 中勾选“小米智能卡”，更新模块后重新打开钱包。\n\n2. 开启自定义卡面，点击卡片预览选择图片。\n\n3. 在“我的卡面”中点击图片可应用到卡片，更多菜单中可编辑或删除。\n\n修改后重新打开钱包页面查看，超级岛使用同一张卡面。",
                modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), style = MiuixTheme.textStyles.body2)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "预览诊断", onClick = { onDiagnostics(null) }, modifier = Modifier.fillMaxWidth())
                TextButton(text = "知道了", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
        null -> Unit
    }
}

@Composable
private fun DialogActions(confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
        TextButton(text = confirm, enabled = enabled, onClick = onConfirm, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary())
    }
}

@Composable
private fun CardActionMenu(page: CardPage, visible: Boolean, enabled: Boolean, entries: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit, onDismissFinished: () -> Unit) {
    OverlayListPopup(show = visible, popupPositionProvider = page.menuPositionProvider,
        alignment = PopupPositionProvider.Align.End, onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
        ListPopupColumn {
            entries.forEachIndexed { index, (title, action) ->
                DropdownImpl(text = title, optionSize = entries.size, isSelected = false, index = index,
                    enabled = enabled, onSelectedIndexChange = { action() })
            }
        }
    }
}
