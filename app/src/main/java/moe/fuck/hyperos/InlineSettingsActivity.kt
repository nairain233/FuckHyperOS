package moe.fuck.hyperos

import android.content.Intent
import androidx.compose.foundation.lazy.LazyListScope
import moe.fuck.hyperos.ui.SectionTitle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

class InlineSettingsActivity : FeatureSettingsActivity("键盘内联填充") {
    internal override val settings = listOf(
        FeatureSetting(ModuleEntry.PREFERENCES, ModuleEntry.ENABLED, "启用键盘内联填充",
            "在小爱键盘中显示兼容密码管理器的填充建议", ModuleEntry.DEFAULT_ENABLED),
    )
    internal override val instructions = "为超级小爱输入法接入系统键盘内联自动填充。请在 LSPosed 中勾选超级小爱输入法，修改后重启输入法。"

    internal override fun extraContent(scope: LazyListScope) = with(scope) {
        item {
            SectionTitle("测试")
            Card {
                ArrowPreference(title = "自动填充测试", summary = "检查系统填充是否成功送达用户名和密码输入框",
                    onClick = { startActivity(Intent(this@InlineSettingsActivity, AutofillTestActivity::class.java)) })
            }
        }
    }
}
