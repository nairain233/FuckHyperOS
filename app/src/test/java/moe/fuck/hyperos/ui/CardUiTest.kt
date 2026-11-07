package moe.fuck.hyperos.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import moe.fuck.hyperos.MainActivity
import moe.fuck.hyperos.card.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class CardUiTest : ActivityUiTest<MainActivity>(MainActivity::class.java) {
    private var active by mutableStateOf<CardOverlay?>(null)
    private var visible by mutableStateOf(false)
    private var addCount = 0
    private var bindCount = 0
    private lateinit var page: CardPage

    private val pendingPreviews = mutableListOf<ImageView>()

    private fun show(populated: Boolean = true, mode: ColorSchemeMode = ColorSchemeMode.Light, deferPreviews: Boolean = false) {
        compose.runOnIdle {
            page = CardPage({}, {}, { addCount++ }, { open(CardOverlay.Help) }, {},
                { open(CardOverlay.ChooseImage(it)) }, { open(CardOverlay.CardMenu(it)) },
                { open(CardOverlay.ChooseCard(it)) }, { open(CardOverlay.ImageMenu(it)) },
                { view, name, _ ->
                    if (deferPreviews) pendingPreviews.add(view)
                    else view.setImageDrawable(ColorDrawable(if (name == null) 0xFF657C96.toInt() else 0xFFBA8B5E.toInt()))
                })
            page.setEnabled(true, true)
            if (populated) page.render(Bundle().apply {
                putBoolean("enabled", true)
                putParcelableArrayList("cards", arrayListOf(Bundle().apply {
                    putString("aid", "demo-card")
                    putString("title", "演示交通卡")
                    putString("image", "demo.png")
                    putStringArrayList("urls", arrayListOf("https://example.invalid/original.png"))
                }))
                putStringArrayList("images", arrayListOf("demo.png", "second.png"))
            })
            page.showStatus(page.hint())
            compose.activity.setContent {
                XiaoAiTheme(mode) {
                    page.Content(active != null) {
                        CardOverlays(page, active, visible, !page.state.busy && page.state.available,
                            onDismiss = { visible = false }, onDismissFinished = {
                                (active as? CardOverlay.Editor)?.view?.release()
                                active = null
                            },
                            onChooseImage = { open(CardOverlay.ChooseImage(it)) },
                            onChooseCard = { open(CardOverlay.ChooseCard(it)) },
                            onImport = { addCount++ }, onReset = {}, onBind = { _, _ -> bindCount++ },
                            onEdit = {}, onDelete = { open(CardOverlay.DeleteImage(it)) }, onConfirmDelete = {},
                            onSaveEditor = {}, onDiagnostics = { open(CardOverlay.Diagnostics(it ?: "无预览错误")) },
                            previewError = { null }, onCopy = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun open(value: CardOverlay) { active = value; visible = true }

    @Test fun cardAndImageTabsKeepTheirActions() {
        show()
        compose.onNodeWithText("演示交通卡").assertIsDisplayed()
        compose.onNodeWithContentDescription("演示交通卡，更多操作").assertExists()
        screenshot("cards-light")
        compose.onNodeWithText("我的卡面 · 2").performClick()
        compose.onAllNodesWithContentDescription("卡面更多操作").assertCountEquals(0)
        screenshot("images-light")
        compose.onAllNodesWithContentDescription("自定义卡面，点击应用到卡片")[0].performTouchInput { longClick() }
        compose.onNodeWithText("编辑为新卡面").assertIsDisplayed()
        compose.onNodeWithText("删除卡面").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        compose.onNodeWithText("添加卡面").performClick()
        assertEquals(1, addCount)
    }

    @Test fun busyAndDisconnectedStatesBlockActions() {
        show()
        compose.runOnIdle { page.setBusy(true); page.showStatus("正在同步图片…", working = true) }
        compose.onNodeWithText("添加卡面").assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).assertAll(isNotEnabled())
        compose.onNodeWithText("正在同步图片…").assertIsDisplayed()
        screenshot("cards-loading")
        compose.runOnIdle { page.setBusy(false); page.setEnabled(false, true) }
        compose.onNodeWithText("正在同步图片…").assertDoesNotExist()
        compose.onNodeWithText("框架服务暂不可用").assertDoesNotExist()
        compose.onNodeWithContentDescription("刷新卡片").assertIsNotEnabled()
        screenshot("cards-disconnected")
    }

    @Test fun disconnectedPageKeepsOperationErrors() {
        show()
        compose.runOnIdle {
            page.showStatus("图片导入失败，请重试", error = true)
            page.setEnabled(false, true)
        }
        compose.onNodeWithText("图片导入失败，请重试").assertIsDisplayed()
        compose.onNodeWithText("添加卡面").assertIsNotEnabled()
    }

    @Test fun emptyStateAndDarkThemeRender() {
        show(populated = false, mode = ColorSchemeMode.Dark)
        compose.onNodeWithText("还没有钱包卡片").assertIsDisplayed()
        screenshot("cards-empty-dark")
        compose.onNodeWithText("我的卡面 · 0").performClick()
        screenshot("images-empty-dark")
    }

    @Test fun darkPopulatedPageAndDialogRender() {
        show(mode = ColorSchemeMode.Dark)
        screenshot("cards-dark")
        compose.runOnIdle { open(CardOverlay.DeleteImage("demo.png")) }
        screenshot("delete-card-image-dark")
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle { assertEquals(null, active) }
    }

    @Test fun selectorsMenusAndConfirmationsRenderAndDismiss() {
        show()
        val card = page.state.cards.single()
        val editor = CardImageEditor(compose.activity, Bitmap.createBitmap(700, 440, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })
        val examples = listOf(
            "card-menu" to CardOverlay.CardMenu(card),
            "image-menu" to CardOverlay.ImageMenu("demo.png"),
            "choose-card" to CardOverlay.ChooseCard("demo.png"),
            "choose-image" to CardOverlay.ChooseImage(card),
            "card-editor" to CardOverlay.Editor(editor),
            "delete-card-image" to CardOverlay.DeleteImage("demo.png"),
            "card-help" to CardOverlay.Help,
            "card-diagnostics" to CardOverlay.Diagnostics("预览缓存可用\n无预览错误"),
        )
        examples.forEach { (name, value) ->
            compose.runOnIdle { open(value) }
            compose.mainClock.advanceTimeBy(1500)
            screenshot(name)
            if (value is CardOverlay.ChooseImage) {
                assertButtonsSeparated("从相册添加", "恢复原卡面", "取消")
            } else if (value == CardOverlay.Help) {
                assertButtonsSeparated("预览诊断", "知道了")
            }
            compose.runOnIdle { visible = false }
            compose.mainClock.advanceTimeBy(1500)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(null, active) }
        }
    }

    private fun assertButtonsSeparated(vararg labels: String) {
        labels.toList().zipWithNext().forEach { (previous, next) ->
            val previousBounds = compose.onNode(hasText(previous) and hasClickAction()).fetchSemanticsNode().boundsInRoot
            val nextBounds = compose.onNode(hasText(next) and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("$previous 和 $next 应有间距", nextBounds.top > previousBounds.bottom)
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h360dp-land")
    fun landscapeImagePickerKeepsAllActionsReachable() {
        show()
        compose.runOnIdle { open(CardOverlay.ChooseImage(page.state.cards.single())) }
        compose.onNodeWithText("从相册添加").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("恢复原卡面").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").performScrollTo().assertIsDisplayed()
        screenshot("choose-image-landscape-actions")
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle { assertEquals(null, active) }
    }

    @Test fun choosingAnImageEmitsOneBind() {
        show()
        compose.runOnIdle { open(CardOverlay.ChooseImage(page.state.cards.single())) }
        compose.onNodeWithContentDescription("当前卡面，已选中").performClick()
        assertEquals(1, bindCount)
    }

    @Test fun swipingBothDirectionsUpdatesTheTabWithoutOpeningCardActions() {
        show()
        compose.onNodeWithTag("card_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("我的卡面 · 2").assertIsSelected()
        compose.runOnIdle { assertEquals(1, page.selectedTab); assertEquals(null, active) }
        compose.onNodeWithTag("card_pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("钱包卡片 · 1").assertIsSelected()
        compose.runOnIdle { assertEquals(0, page.selectedTab); assertEquals(null, active) }
    }

    @Test fun changingTabsKeepsBothListsScrollPositions() {
        show()
        compose.runOnIdle {
            val template = page.state.cards.single()
            page.render(Bundle().apply {
                putBoolean("enabled", true)
                putParcelableArrayList("cards", ArrayList((0..14).map { index -> Bundle(template).apply {
                    putString("aid", "card-$index")
                    putString("title", "交通卡 ${index.toString().padStart(2, '0')}")
                } }))
                putStringArrayList("images", ArrayList((0..29).map { "image-$it.png" }))
            })
        }
        compose.onNodeWithTag("wallet_cards").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, page.selectedTab) }
        assertTrue(compose.onNodeWithTag("wallet_cards").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        compose.onNodeWithTag("wallet_cards").performScrollToIndex(5)
        compose.onNodeWithText("交通卡 04").assertIsDisplayed()
        compose.onNodeWithTag("card_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("card_images").performScrollToIndex(12)
        val before = compose.onNodeWithTag("card_images").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("card_pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("交通卡 04").assertIsDisplayed()
        compose.runOnIdle { page.selectTab(1) }
        val after = compose.onNodeWithTag("card_images").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 0.001f)
    }

    @Test fun walletLoadShowsPlaceholdersAndThenTheCards() {
        show(populated = false)
        compose.runOnIdle { page.setBusy(true); page.showStatus("正在读取卡片…", working = true) }
        compose.onAllNodesWithContentDescription("正在读取钱包卡片").assertCountEquals(1)
        screenshot("wallet-card-skeletons")
        compose.runOnIdle {
            page.render(Bundle().apply {
                putBoolean("enabled", true)
                putParcelableArrayList("cards", arrayListOf(Bundle().apply {
                    putString("aid", "loaded-card"); putString("title", "加载后的卡片")
                }))
            })
            page.setBusy(false)
            page.showStatus(page.hint())
        }
        compose.onNodeWithText("加载后的卡片").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("正在读取钱包卡片").assertCountEquals(0)
        screenshot("wallet-card-arrival")
    }

    @Test fun openOverlayBlocksBackgroundPaging() {
        show()
        compose.runOnIdle { open(CardOverlay.Help) }
        compose.onNodeWithText("知道了").assertIsDisplayed()
        compose.onNodeWithTag("card_pager").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, page.selectedTab) }
    }

    @Test fun previewLoadingFailureAndFadeInKeepTheCardActionAvailable() {
        show(deferPreviews = true)
        compose.onNodeWithContentDescription("演示交通卡，原卡面，点击更换")
            .assert(hasStateDescription(CardPreviewPhase.Loading.description))
        screenshot("card-preview-skeleton")
        compose.runOnIdle { (pendingPreviews.first().parent as CardPreview).unavailable() }
        compose.onNodeWithContentDescription("演示交通卡，原卡面，点击更换")
            .assert(hasStateDescription(CardPreviewPhase.Failed.description))
        screenshot("card-preview-failed")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            pendingPreviews.first().setImageBitmap(Bitmap.createBitmap(700, 440, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.RED) })
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        screenshot("card-preview-fade-start")
        fun greenAtCorner(): Int {
            val bitmap = compose.onNodeWithContentDescription("演示交通卡，原卡面，点击更换")
                .captureToImage().asAndroidBitmap()
            return Color.green(bitmap.getPixel(bitmap.width / 4, bitmap.height / 4))
        }
        val startGreen = greenAtCorner()
        compose.mainClock.advanceTimeBy(80)
        screenshot("card-preview-fade-middle")
        val middleGreen = greenAtCorner()
        compose.mainClock.advanceTimeBy(300)
        screenshot("card-preview-fade-finished")
        assertTrue("应有实际渐显中间帧：$startGreen -> $middleGreen", middleGreen in 1 until startGreen)
        assertEquals(0, greenAtCorner())
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("演示交通卡，原卡面，点击更换")
            .assert(hasStateDescription(CardPreviewPhase.Ready.description)).performClick()
        compose.onNodeWithText("点击图片立即应用").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-port")
    fun smallScreenAndLargeFontKeepActionsReachable() {
        RuntimeEnvironment.setFontScale(1.5f)
        show()
        compose.onNodeWithText("添加卡面").assertIsDisplayed()
        screenshot("cards-small-large-font")
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-night")
    fun landscapeDarkPageRenders() {
        show(mode = ColorSchemeMode.Dark)
        compose.onNodeWithText("添加卡面").assertIsDisplayed()
        screenshot("cards-landscape-dark")
    }
}
