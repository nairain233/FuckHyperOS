package moe.fuck.hyperos

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import moe.fuck.hyperos.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme

@SuppressLint("SetTextI18n")
class AutofillTestActivity : ComponentActivity() {
    private lateinit var username: ObservedEditText
    private lateinit var password: ObservedEditText
    private var result by mutableStateOf("等待从键盘内选择建议。请勿手动输入测试值。")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        username = ObservedEditText(this, "username").apply {
            id = R.id.test_username
            hint = "用户名"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        }
        password = ObservedEditText(this, "password").apply {
            id = R.id.test_password
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        username.onAutofilled = { updateResult() }
        password.onAutofilled = { updateResult() }
        setMiuixContent {
            SimplePage("自动填充测试", ::finish) {
                item {
                    UsageInstructions("先在当前密码管理器中新建一个登录条目，使用以下虚构测试信息：\n\nURI：androidapp://$packageName\n用户名：$TEST_USERNAME\n密码：$TEST_PASSWORD\n\n保存后返回这里，点击下方输入框，再点击小爱键盘内的填充建议。")
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    InfoCard(result)
                    Spacer(Modifier.height(16.dp))
                }
                item {
                    Card(Modifier.fillMaxWidth().testTag("autofill_form")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AutofillField(username)
                            AutofillField(password)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(text = "请求自动填充建议", modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val focused = if (password.hasFocus()) password else username
                            focused.requestFocus()
                            getSystemService(AutofillManager::class.java)?.requestAutofill(focused)
                        })
                    Spacer(Modifier.height(8.dp))
                    TextButton(text = "检查填充结果", modifier = Modifier.fillMaxWidth(), onClick = ::updateResult)
                }
            }
        }
        report("TEST_FORM_READY")
    }

    @Composable
    private fun AutofillField(field: ObservedEditText) {
        var focused by remember(field) { mutableStateOf(field.hasFocus()) }
        val colors = TextFieldDefaults.textFieldColors()
        val foreground = MiuixTheme.colorScheme.onSurfaceContainer.toArgb()
        val fontSize = MiuixTheme.textStyles.main.fontSize.value
        val horizontalPadding = with(LocalDensity.current) { TextFieldDefaults.InsideMargin.width.roundToPx() }
        val verticalPadding = with(LocalDensity.current) { TextFieldDefaults.InsideMargin.height.roundToPx() }
        // 沿用 Miuix TextField 的外观，原生字段保留 autofill() 观测，避免误判手动输入。
        Box(
            modifier = Modifier.fillMaxWidth()
                .squircleBackground(colors.backgroundColor, TextFieldDefaults.CornerRadius)
                .squircleBorder(
                    width = if (focused) 2.dp else 0.dp,
                    color = if (focused) colors.borderColor else colors.backgroundColor,
                    cornerRadius = TextFieldDefaults.CornerRadius,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            AndroidView(
                factory = {
                    field.apply {
                        background = null
                        typeface = Typeface.DEFAULT
                        gravity = Gravity.CENTER_VERTICAL
                        setOnFocusChangeListener { _, hasFocus -> focused = hasFocus }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                update = {
                    it.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                    it.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                    it.setTextColor(foreground)
                    it.setHintTextColor(colors.labelColor.toArgb())
                    it.textCursorDrawable?.mutate()?.setTint(colors.borderColor.toArgb())
                },
            )
        }
    }

    private fun updateResult() {
        val usernameMatches = username.text.toString() == TEST_USERNAME
        val passwordMatches = password.text.toString() == TEST_PASSWORD
        val passed = username.autofilled && password.autofilled && usernameMatches && passwordMatches
        result = if (passed) {
            "测试通过：用户名和密码均已通过系统自动填充，并与测试条目一致。"
        } else {
            "用户名收到系统填充：${username.autofilled}\n密码收到系统填充：${password.autofilled}\n测试值匹配：${usernameMatches && passwordMatches}"
        }
        report("TEST_RESULT usernameAutofilled=${username.autofilled} passwordAutofilled=${password.autofilled} usernameMatches=$usernameMatches passwordMatches=$passwordMatches passed=$passed")
    }

    private class ObservedEditText(activity: Activity, private val role: String) : EditText(activity) {
        var autofilled = false
            private set
        var onAutofilled: (() -> Unit)? = null

        init {
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES
            isSaveEnabled = false
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    autofilled = false
                }
                override fun afterTextChanged(text: Editable?) = Unit
            })
        }

        override fun autofill(value: AutofillValue) {
            super.autofill(value)
            autofilled = value.isText
            report("FIELD_AUTOFILLED role=$role")
            onAutofilled?.invoke()
        }
    }

    companion object {
        private const val TAG = "XiaoAiInlineTest"
        private val report = ModuleLog.reporter(TAG)
        private const val TEST_USERNAME = "xiaoai-inline-test"
        private const val TEST_PASSWORD = "Inline-Test-102!"
    }
}
