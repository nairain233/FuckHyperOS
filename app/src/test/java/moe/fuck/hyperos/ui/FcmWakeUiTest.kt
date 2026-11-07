package moe.fuck.hyperos.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import moe.fuck.hyperos.MainActivity
import moe.fuck.hyperos.google.FcmApp
import moe.fuck.hyperos.google.FcmWakePage
import org.junit.Assert.*
import org.junit.Test
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class FcmWakeUiTest : ActivityUiTest<MainActivity>(MainActivity::class.java) {
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private var retries = 0
    private val apps = listOf(FcmApp("app.alpha", "Alpha", false), FcmApp("app.beta", "Beta", false),
        FcmApp("app.system", "系统应用", true))

    private fun show(available: Boolean = true, loadError: Boolean = false, dark: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.setContent {
                XiaoAiTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    FcmWakePage(apps, selected, available, false, loadError, { selected = it }, { retries++ }, {})
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun choosingAndClearingReflectWakeAllSemantics() {
        show()
        compose.onNodeWithText("显示系统应用").assertDoesNotExist()
        compose.onNodeWithText("全选当前列表").assertDoesNotExist()
        compose.onNodeWithText("清空名单").assertDoesNotExist()
        compose.onNodeWithText("全部 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已选择 0").assertIsDisplayed()
        compose.onNodeWithTag("fcm_app:app.beta").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("app.beta"), selected) }
        compose.onNodeWithText("已选择 1").performScrollTo().assertIsDisplayed()
        screenshot("fcm-wake-light")
        compose.onNodeWithContentDescription("白名单更多操作").performClick()
        screenshot("fcm-wake-menu-light")
        compose.onNodeWithText("清空名单").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
        compose.onNodeWithText("已选择 0").assertIsDisplayed()
    }

    @Test fun searchAndSelectAllPreserveSelectionsOutsideCurrentFilter() {
        selected = setOf("app.system")
        show()
        compose.onNodeWithTag("fcm_app_search").performScrollTo().performTextInput("BETA")
        compose.onNodeWithContentDescription("白名单更多操作").performClick()
        compose.onNodeWithText("全选当前列表").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(setOf("app.system", "app.beta"), selected) }
        compose.onNodeWithTag("fcm_app:app.beta").performScrollTo().assertExists()
        compose.onNodeWithTag("fcm_app:app.alpha").assertDoesNotExist()
    }

    @Test fun systemFilterAndDarkPageRender() {
        show(dark = true)
        compose.onNodeWithContentDescription("白名单更多操作").performClick()
        compose.onNodeWithText("显示系统应用").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("fcm_app:app.system").performScrollTo().assertIsDisplayed()
        screenshot("fcm-wake-dark")
        compose.onNodeWithContentDescription("白名单更多操作").performClick()
        screenshot("fcm-wake-menu-dark")
    }

    @Test fun disconnectedPageDoesNotAllowSavingAndLoadErrorsCanRetry() {
        show(available = false, loadError = true)
        compose.onNodeWithContentDescription("白名单更多操作").performClick()
        compose.onNodeWithText("全选当前列表").assertIsNotEnabled()
        compose.onNodeWithText("清空名单").assertIsNotEnabled()
        compose.onNodeWithText("显示系统应用").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("重新读取").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertTrue(selected.isEmpty()) }
        screenshot("fcm-wake-error")
    }

    @Test fun selectedAndUnselectedFiltersUpdateImmediatelyAfterChangingSelection() {
        selected = setOf("app.beta")
        show(dark = true)
        compose.onNodeWithText("全部 2").performScrollTo().assertIsSelected()
        compose.onNodeWithText("已选择 1").performClick().assertIsSelected()
        compose.onNodeWithTag("fcm_app:app.beta").assertIsDisplayed()
        compose.onNodeWithTag("fcm_app:app.alpha").assertDoesNotExist()
        screenshot("fcm-wake-selected-dark")
        compose.onNodeWithTag("fcm_app:app.beta").performClick()
        compose.onNodeWithText("已选择 0").assertIsSelected()
        compose.onNodeWithText("没有符合条件的应用").assertIsDisplayed()
        compose.onNodeWithText("未选择 2").performClick().assertIsSelected()
        compose.onNodeWithTag("fcm_app:app.alpha").assertIsDisplayed()
        compose.onNodeWithTag("fcm_app:app.beta").assertIsDisplayed()
        compose.onNodeWithTag("fcm_app:app.alpha").performClick()
        compose.onNodeWithText("未选择 1").assertIsSelected()
        compose.onNodeWithTag("fcm_app:app.alpha").assertDoesNotExist()
        compose.onNodeWithText("全部 2").performClick()
        compose.onNodeWithTag("fcm_app:app.alpha").assertIsDisplayed()
    }

    @Test fun selectionFilterCombinesWithSearchAndSelectAllOnlyChangesCurrentResults() {
        selected = setOf("app.beta")
        show()
        compose.onNodeWithText("已选择 1").performScrollTo().performClick()
        compose.onNodeWithTag("fcm_app_search").performTextInput("ALPHA")
        compose.onNodeWithText("全部 1").assertExists()
        compose.onNodeWithText("已选择 0").assertIsSelected()
        compose.onNodeWithTag("fcm_app:app.alpha").assertDoesNotExist()
        compose.onNodeWithText("未选择 1").performClick()
        compose.onNodeWithTag("fcm_app:app.alpha").assertIsDisplayed()
        compose.onNodeWithContentDescription("白名单更多操作").performClick()
        compose.onNodeWithText("全选当前列表").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(setOf("app.alpha", "app.beta"), selected) }
        compose.onNodeWithText("未选择 0").assertIsSelected()
        compose.onNodeWithText("没有符合条件的应用").assertIsDisplayed()
    }
}
