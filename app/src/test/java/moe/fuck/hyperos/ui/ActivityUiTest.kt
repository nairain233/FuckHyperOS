package moe.fuck.hyperos.ui

import android.app.Application
import android.graphics.Bitmap
import android.view.autofill.AutofillValue
import android.widget.EditText
import android.graphics.Insets
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import moe.fuck.hyperos.*
import moe.fuck.hyperos.R
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ActivityUiTest<A : ComponentActivity>(activityClass: Class<A>) {
    @get:Rule val compose = createAndroidComposeRule(activityClass)

    protected fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/reports/ui-screenshots/$name.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected fun assertInstructionsAtTop() {
        val instructions = compose.onNodeWithTag("usage_instructions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithText("功能设置").fetchSemanticsNode().boundsInRoot
        assertTrue("使用说明应位于功能设置之前", instructions.bottom <= settings.top)
    }
}

class MainActivityUiTest : ActivityUiTest<MainActivity>(MainActivity::class.java) {
    @Test fun disconnectedFrameworkKeepsFeaturesAccessible() {
        compose.onNodeWithText("模块未激活").assertIsDisplayed()
        compose.onNodeWithText("LSPosed Service 未连接，请检查模块启用与框架状态").assertIsDisplayed()
        compose.onNodeWithText("模块未激活").performClick()
        compose.onNodeWithText("框架状态").assertDoesNotExist()
        compose.onNodeWithText("刷新连接状态").assertDoesNotExist()
        compose.onNodeWithText("键盘内联填充").assertHasClickAction()
        compose.onNodeWithText("自定义卡面").assertHasClickAction()
        compose.onNodeWithText("主题相关").assertHasClickAction()
        compose.onNodeWithText("遥测相关").assertHasClickAction()
        compose.onNodeWithText("Google 相关").assertHasClickAction()
        compose.onNodeWithText("HyperOS 功能").assertDoesNotExist()
        screenshot("home-light")
    }

    @Test @Config(qualifiers = "+night")
    fun darkThemeRenders() { screenshot("home-dark") }
}

class InlineSettingsUiTest : ActivityUiTest<InlineSettingsActivity>(InlineSettingsActivity::class.java) {
    @Test @Config(qualifiers = "+night")
    fun darkSettingsRender() { assertInstructionsAtTop(); screenshot("inline-dark") }

    @Test fun disconnectedSwitchCannotChangeStoredValue() {
        assertInstructionsAtTop()
        compose.onNodeWithText("框架状态").assertDoesNotExist()
        compose.onNodeWithText("尚未连接 LSPosed").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertAll(isNotEnabled()).assertAll(isOff())
        compose.onNodeWithText("自动填充测试", useUnmergedTree = true).assertExists()
        screenshot("inline-light")
    }
}

class HyperOsUiTest : ActivityUiTest<HyperOsActivity>(HyperOsActivity::class.java) {
    @Test @Config(qualifiers = "+night")
    fun darkSettingsRender() { assertInstructionsAtTop(); screenshot("hyperos-dark") }

    @Test fun disconnectedSwitchesAreDisabled() {
        assertInstructionsAtTop()
        compose.onNodeWithText("框架状态").assertDoesNotExist()
        compose.onNodeWithText("尚未连接 LSPosed").assertDoesNotExist()
        compose.onAllNodesWithText("主题相关").onFirst().assertExists()
        compose.onNodeWithText("阻止 Analytics 上传").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(3).assertAll(isNotEnabled())
        screenshot("hyperos-light")
    }
}

class TelemetryUiTest : ActivityUiTest<TelemetryActivity>(TelemetryActivity::class.java) {
    @Test @Config(qualifiers = "+night")
    fun darkSettingsRender() { assertInstructionsAtTop(); screenshot("telemetry-dark") }

    @Test fun disconnectedTelemetrySwitchIsDisabled() {
        assertInstructionsAtTop()
        compose.onAllNodesWithText("遥测相关").onFirst().assertExists()
        compose.onNodeWithText("阻止 Analytics 上传").assertExists()
        compose.onNodeWithText("禁用环境检查").assertIsDisplayed()
        // 该项标题与摘要使用相同文案，明确检查标题节点。
        compose.onAllNodesWithText("阻止自动上传已安装应用列表").onFirst().assertIsDisplayed()
        compose.onNodeWithText("主题与大图标解锁").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(3).assertAll(isNotEnabled())
        screenshot("telemetry-light")
    }
}

class GoogleSettingsUiTest : ActivityUiTest<GoogleSettingsActivity>(GoogleSettingsActivity::class.java) {
    @Test fun disconnectedGoogleSwitchesRemainOffAndDisabled() {
        assertInstructionsAtTop()
        compose.onNodeWithText("恢复通行密钥").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("FCM 推送保活").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("FCM 唤醒白名单").performScrollTo().assertHasClickAction()
        compose.onAllNodes(isToggleable()).assertCountEquals(2).assertAll(isNotEnabled()).assertAll(isOff())
        screenshot("google-light")
    }

    @Test @Config(qualifiers = "+night")
    fun darkGoogleSettingsRender() {
        compose.onNodeWithText("FCM 推送保活").performScrollTo().assertIsDisplayed()
        screenshot("google-dark")
    }
}

class AutofillUiTest : ActivityUiTest<AutofillTestActivity>(AutofillTestActivity::class.java) {
    @Test @Config(qualifiers = "+night")
    fun darkFormRenders() {
        screenshot("autofill-dark")
        compose.onNodeWithTag("autofill_form").performScrollTo()
        compose.runOnIdle {
            compose.activity.findViewById<EditText>(R.id.test_username).requestFocus()
        }
        screenshot("autofill-username-focused-dark")
        compose.runOnIdle {
            compose.activity.findViewById<EditText>(R.id.test_password).requestFocus()
        }
        screenshot("autofill-password-focused-dark")
    }

    @Test fun keyboardInsetsKeepFormActionsReachable() {
        compose.runOnIdle {
            compose.activity.window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 280))
                .setVisible(WindowInsets.Type.ime(), true).build())
        }
        compose.onNodeWithText("检查填充结果").performScrollTo().assertIsDisplayed()
        screenshot("autofill-ime-insets")
    }

    @Test fun formIsAvailableWithoutFrameworkConnection() {
        screenshot("autofill-light")
        compose.onNodeWithTag("autofill_form").performScrollTo().assertIsDisplayed()
    }

    @Test fun manualEntryDoesNotPassButSystemAutofillDoes() {
        compose.onNodeWithTag("autofill_form").performScrollTo()
        compose.runOnIdle {
            compose.activity.findViewById<EditText>(R.id.test_username).setText("xiaoai-inline-test")
            compose.activity.findViewById<EditText>(R.id.test_password).setText("Inline-Test-102!")
        }
        compose.onNodeWithText("检查填充结果").performScrollTo().performClick()
        compose.onNodeWithText("用户名收到系统填充：false", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("autofill_form").performScrollTo()
        compose.runOnIdle {
            compose.activity.findViewById<EditText>(R.id.test_username).autofill(AutofillValue.forText("xiaoai-inline-test"))
            compose.activity.findViewById<EditText>(R.id.test_password).autofill(AutofillValue.forText("Inline-Test-102!"))
        }
        compose.onNodeWithText("测试通过：", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("autofill-result")
    }
}
