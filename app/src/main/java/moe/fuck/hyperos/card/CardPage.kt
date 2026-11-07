package moe.fuck.hyperos.card

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import moe.fuck.hyperos.R
import moe.fuck.hyperos.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.horizontalPagerSwipeOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage

// ImageView 仅作为既有加载器的目标；图片绘制和过渡由 Compose 统一处理。
@SuppressLint("ViewConstructor")
internal class CardPreview(context: Context) : FrameLayout(context) {
    var phase by mutableStateOf(CardPreviewPhase.Empty)
        private set
    var animateReveal by mutableStateOf(true)
        private set
    var content by mutableStateOf<Drawable?>(null)
        private set
    val image = object : ImageView(context) {
        override fun setImageDrawable(drawable: Drawable?) {
            super.setImageDrawable(drawable)
            content = drawable
            if (drawable != null) phase = CardPreviewPhase.Ready
        }
        override fun setImageBitmap(bitmap: Bitmap?) {
            super.setImageBitmap(bitmap)
            content = drawable
            if (bitmap != null) phase = CardPreviewPhase.Ready
        }
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(image, LayoutParams(-1, -1))
    }
    fun beginLoading(hasSource: Boolean) {
        animateReveal = true
        phase = if (hasSource) CardPreviewPhase.Loading else CardPreviewPhase.Empty
    }
    fun showBitmap(bitmap: Bitmap, animate: Boolean) {
        animateReveal = animate
        image.setImageBitmap(bitmap)
    }
    fun unavailable() { phase = CardPreviewPhase.Failed }
}

internal data class CardPageState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val status: String? = null,
    val working: Boolean = false,
    val error: Boolean = false,
    val cards: List<Bundle> = emptyList(),
    val images: List<String> = emptyList(),
)

internal class CardPage(
    private val onBack: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onAdd: () -> Unit,
    private val onHelp: () -> Unit,
    private val onEnabled: (Boolean) -> Unit,
    private val onCard: (Bundle) -> Unit,
    private val onCardMenu: (Bundle) -> Unit,
    private val onImage: (String) -> Unit,
    private val onImageMenu: (String) -> Unit,
    private val loadImage: (ImageView, String?, String?) -> Unit,
) {
    var state by mutableStateOf(CardPageState())
        private set
    var selectedTab by mutableIntStateOf(0)
        private set
    // 显式选页与手势反馈分开，滑动中更新选中项不会反过来重启翻页动画。
    private var tabRequest by mutableStateOf(0 to 0)
    private var previewEpoch by mutableIntStateOf(0)
    private var menuAnchor = IntRect.Zero
    val menuPositionProvider = object : PopupPositionProvider {
        override fun getMargins() = ListPopupDefaults.DropdownPositionProvider.getMargins()
        override fun calculatePosition(anchorBounds: IntRect, windowBounds: IntRect, layoutDirection: LayoutDirection,
            popupContentSize: IntSize, popupMargin: IntRect, alignment: PopupPositionProvider.Align): IntOffset =
            ListPopupDefaults.DropdownPositionProvider.calculatePosition(
                if (menuAnchor == IntRect.Zero) anchorBounds else menuAnchor, windowBounds, layoutDirection,
                popupContentSize, popupMargin, alignment)
    }

    fun retryPreviews() { previewEpoch++ }

    fun setEnabled(available: Boolean, checked: Boolean) {
        // 连接变化只影响可用性，不能把已经失效的操作进度留在页面上。
        val clearProgress = !available && state.working
        state = state.copy(available = available, enabled = checked,
            status = if (clearProgress) null else state.status,
            working = if (clearProgress) false else state.working,
            error = if (clearProgress) false else state.error)
    }
    fun setBusy(value: Boolean) { state = state.copy(busy = value) }
    fun showStatus(value: String, working: Boolean = false, error: Boolean = false) {
        state = state.copy(status = value, working = working, error = error)
    }
    fun render(snapshot: Bundle) {
        state = state.copy(loaded = true, enabled = snapshot.getBoolean("enabled"),
            cards = snapshot.getParcelableArrayList("cards", Bundle::class.java).orEmpty()
                .sortedWith(compareBy<Bundle> { it.getString("image").isNullOrEmpty() }.thenBy { it.getString("title") }),
            images = snapshot.getStringArrayList("images").orEmpty().toList())
    }
    fun selectTab(tab: Int) {
        val target = tab.coerceIn(0, 1)
        updateSelectedTab(target)
        tabRequest = target to (tabRequest.second + 1)
    }
    private fun updateSelectedTab(tab: Int) {
        selectedTab = tab.coerceIn(0, 1)
        if (state.loaded && state.available && !state.busy && !state.error && !state.working) showStatus(hint())
    }
    fun hint(): String = when {
        !state.enabled -> "已停用 · 开启后应用自定义卡面"
        selectedTab == 0 -> "点击卡面更换 · 更多操作在 ⋮ 菜单"
        else -> "点击应用到卡片 · 长按编辑或删除"
    }

    @Composable
    fun Content(overlayOpen: Boolean, overlays: @Composable () -> Unit) {
        val usable = state.available && !state.busy && !overlayOpen
        val pager = rememberPagerState(initialPage = selectedTab, pageCount = { 2 })
        val walletScroll = rememberLazyListState()
        val imageScroll = rememberLazyGridState()
        LaunchedEffect(tabRequest) { pager.springAnimateToPage(tabRequest.first) }
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.collect { updateSelectedTab(it) }
        }
        AppPage("自定义卡面", onBack,
            actions = {
                AppIconButton(R.drawable.card_refresh, "刷新卡片", usable, onRefresh)
                AppIconButton(R.drawable.card_help, "使用说明", !overlayOpen && !state.busy, onHelp)
            },
            bottomBar = {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    TextButton(text = "添加卡面", modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(), enabled = usable,
                        colors = ButtonDefaults.textButtonColorsPrimary(), onClick = onAdd)
                }
            },
        ) { padding, modifier ->
            Box(modifier.padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                HorizontalPager(state = pager, key = { it }, userScrollEnabled = false,
                    pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                    modifier = Modifier.fillMaxSize().testTag("card_pager")
                        .horizontalPagerSwipeOverride(pager, enabled = !overlayOpen),
                ) { tab ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        if (tab == 0) {
                            LazyColumn(Modifier.widthIn(max = 1000.dp).fillMaxSize().testTag("wallet_cards"),
                                state = walletScroll, contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item(key = "header") { Header(usable) }
                                itemsIndexed(state.cards, key = { _, card -> card.getString("aid").orEmpty() }) { index, card ->
                                    CardArrival(index) { WalletCard(card, usable) }
                                }
                                if (state.cards.isEmpty()) {
                                    if (state.working) item(key = "loading") { LoadingWalletCard() }
                                    else item { EmptyState(usable, 0, Modifier.fillParentMaxHeight(0.5f)) }
                                }
                            }
                        } else {
                            LazyVerticalGrid(columns = GridCells.Adaptive(156.dp),
                                modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize().testTag("card_images"), state = imageScroll,
                                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Header(usable) }
                                itemsIndexed(state.images, key = { _, name -> name }) { index, name ->
                                    CardArrival(index) {
                                        Preview(name, null, "自定义卡面，点击应用到卡片", usable, { onImage(name) }, { onImageMenu(name) })
                                    }
                                }
                                if (state.images.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(usable, 1) }
                            }
                        }
                    }
                }
            }
            overlays()
        }
    }

    @Composable
    private fun Header(usable: Boolean) {
        val density = LocalDensity.current
        val compactTabs = with(density) { LocalWindowInfo.current.containerSize.width.toDp().value / fontScale < 300 }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card {
                SwitchPreference(title = "启用自定义卡面", summary = "钱包与超级岛同步使用",
                    checked = state.enabled, enabled = usable, onCheckedChange = onEnabled)
            }
            TabRow(tabs = if (compactTabs) listOf("卡片 · ${state.cards.size}", "卡面 · ${state.images.size}")
                else listOf("钱包卡片 · ${state.cards.size}", "我的卡面 · ${state.images.size}"),
                selectedTabIndex = selectedTab, onTabSelected = ::selectTab)
            state.status?.let { status ->
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.working) CircularProgressIndicator(size = 18.dp)
                    Text(status, style = MiuixTheme.textStyles.footnote1,
                        color = if (state.error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }

    @Composable
    private fun EmptyState(usable: Boolean, tab: Int, modifier: Modifier = Modifier) {
        Box(modifier.fillMaxWidth().heightIn(min = 200.dp).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (tab == 0) "还没有钱包卡片" else "还没有自定义卡面",
                    style = MiuixTheme.textStyles.title4)
                Text(if (tab == 0) "打开小米智能卡后刷新，卡片会显示在这里" else "从相册添加喜欢的图片，再应用到卡片",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextButton(text = if (tab == 0) "刷新卡片" else "从相册添加", enabled = usable,
                    onClick = if (tab == 0) onRefresh else onAdd)
            }
        }
    }

    @Composable
    private fun WalletCard(card: Bundle, usable: Boolean) {
        val title = card.getString("title").orEmpty()
        val image = card.getString("image")
        Card {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(title, style = MiuixTheme.textStyles.headline1)
                    Text(if (image.isNullOrEmpty()) "未设置" else "已替换", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                MoreButton("$title，更多操作", usable) { onCardMenu(card) }
            }
            BoxWithConstraints(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                // 极窄窗口改为纵向对比，避免预览和标签被挤压。
                if (maxWidth < 300.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledPreview("原卡面", null, card.getStringArrayList("urls").orEmpty().firstOrNull { it.startsWith("https://") }, title, usable) { onCard(card) }
                        LabeledPreview("自定义卡面", image, null, title, usable) { onCard(card) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { LabeledPreview("原卡面", null, card.getStringArrayList("urls").orEmpty().firstOrNull { it.startsWith("https://") }, title, usable) { onCard(card) } }
                        Box(Modifier.weight(1f)) { LabeledPreview("自定义卡面", image, null, title, usable) { onCard(card) } }
                    }
                }
            }
        }
    }

    @Composable
    private fun LabeledPreview(label: String, name: String?, url: String?, title: String, enabled: Boolean, onClick: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Preview(name, url, "$title，$label，点击更换", enabled, onClick)
        }
    }

    @Composable
    private fun MoreButton(description: String, enabled: Boolean, onClick: () -> Unit) {
        var bounds by remember { mutableStateOf(IntRect.Zero) }
        Box(Modifier.onGloballyPositioned {
            val rect = it.boundsInWindow()
            bounds = IntRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        }) {
            AppIconButton(R.drawable.card_more, description, enabled) { menuAnchor = bounds; onClick() }
        }
    }

    @Composable
    fun Preview(name: String?, url: String?, description: String, enabled: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
        val context = LocalContext.current
        val preview = remember { CardPreview(context) }
        var bounds by remember { mutableStateOf(IntRect.Zero) }
        val phase = preview.phase
        val reveal = animateFloatAsState(if (phase == CardPreviewPhase.Ready) 1f else 0f,
            if (!preview.animateReveal) snap() else tween(180), label = "卡面渐显")
        LaunchedEffect(name, url, previewEpoch) {
            preview.beginLoading(name != null || url != null)
            loadImage(preview.image, name, url)
        }
        DisposableEffect(preview) { onDispose { preview.image.tag = null } }
        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(70f / 44f)
                .clip(shape).background(MiuixTheme.colorScheme.secondaryContainer)
                .border(1.dp, MiuixTheme.colorScheme.outline, shape)
                .onGloballyPositioned {
                    val rect = it.boundsInWindow()
                    bounds = IntRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
                }
                .semantics { contentDescription = description; stateDescription = phase.description }
                .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick,
                    onLongClick = onLongClick?.let { action -> { menuAnchor = bounds; action() } }),
        ) {
            if (reveal.value < 1f) CardLoadingPreview(phase, Modifier.fillMaxSize())
            preview.content?.let { drawable ->
                CardDrawable(drawable, { reveal.value }, Modifier.fillMaxSize())
            }
        }
    }
}
