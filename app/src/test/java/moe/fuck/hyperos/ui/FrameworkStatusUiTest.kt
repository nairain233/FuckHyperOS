package moe.fuck.hyperos.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import moe.fuck.hyperos.MainActivity
import moe.fuck.hyperos.SimplePage
import moe.fuck.hyperos.card.CardActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class FrameworkStatusUiTest : ActivityUiTest<MainActivity>(MainActivity::class.java) {
    private var status by mutableStateOf<FrameworkStatus>(FrameworkStatus.Disconnected)
    private var refreshCount = 0

    private fun render(mode: ColorSchemeMode) {
        compose.runOnIdle {
            compose.activity.setContent {
                XiaoAiTheme(mode) {
                    SimplePage("FuckHyper") {
                        item { FrameworkStatusCard(status) { refreshCount++ } }
                    }
                }
            }
        }
    }

    @Test fun allStatesRenderAndRefreshInBothThemes() {
        val states = listOf(
            Triple("disconnected", FrameworkStatus.Disconnected, "模块未激活"),
            Triple("working", FrameworkStatus.Connected("LSPosed", 102, 7386L), "模块已激活"),
            Triple("error", FrameworkStatus.ReadError(), "模块未激活"),
            Triple("unsupported-api", FrameworkStatus.ReadError("需要支持 API 102 的框架"), "模块未激活"),
        )
        listOf("light" to ColorSchemeMode.Light, "dark" to ColorSchemeMode.Dark).forEach { (theme, mode) ->
            render(mode)
            states.forEach { (name, value, title) ->
                compose.runOnIdle { status = value }
                compose.onNodeWithText(title).assertIsDisplayed().performClick()
                screenshot("framework-$name-$theme")
            }
        }
        assertEquals(8, refreshCount)
    }

    @Test fun connectionChangesUpdateAccessibleState() {
        render(ColorSchemeMode.Light)
        compose.runOnIdle { status = FrameworkStatus.Connected("LSPosed") }
        compose.onNodeWithText("模块未激活").assertDoesNotExist()
        compose.onNodeWithTag("framework_status_card").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "模块已激活。Framework: LSPosed"))
        compose.runOnIdle { status = FrameworkStatus.ReadError() }
        compose.onNodeWithText("模块已激活").assertDoesNotExist()
        compose.onNodeWithText("无法读取框架信息").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-port")
    fun smallScreenAndLargeFontKeepStatusReadable() {
        RuntimeEnvironment.setFontScale(1.5f)
        render(ColorSchemeMode.Light)
        compose.runOnIdle { status = FrameworkStatus.Connected("LSPosed", 102, 7386L) }
        compose.onNodeWithText("模块已激活").assertIsDisplayed()
        screenshot("framework-small-large-font")
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-night")
    fun landscapeStatusRenders() {
        render(ColorSchemeMode.Dark)
        compose.runOnIdle { status = FrameworkStatus.Connected("LSPosed") }
        compose.onNodeWithText("模块已激活").assertIsDisplayed()
        screenshot("framework-landscape-dark")
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-port")
    fun longFrameworkDescriptionWrapsAtDoubleFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        render(ColorSchemeMode.Light)
        compose.runOnIdle { status = FrameworkStatus.Connected("LSPosed 长框架名称与版本信息", 102, 1234567890L) }
        compose.onNodeWithText("模块已激活").assertIsDisplayed()
        compose.onNodeWithText("Version: 1234567890", substring = true).assertIsDisplayed()
        screenshot("framework-long-name-double-font")
    }
}

class CardActivityUiTest : ActivityUiTest<CardActivity>(CardActivity::class.java) {
    @Test fun frameworkUnavailableDoesNotAddStatusText() {
        listOf("框架状态", "正在连接框架…", "尚未连接 LSPosed", "框架服务暂不可用", "需要支持 API 102 的框架").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("添加卡面").assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).assertCountEquals(1).assertAll(isNotEnabled())
        screenshot("cards-no-framework-status")
    }
}
