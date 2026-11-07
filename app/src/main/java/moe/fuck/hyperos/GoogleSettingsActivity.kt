package moe.fuck.hyperos

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.fuck.hyperos.hyperos.HyperOsSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

class GoogleSettingsActivity : FeatureSettingsActivity("Google 相关") {
    override val settings = listOf(
        FeatureSetting(HyperOsSettings.PREFERENCES, HyperOsSettings.PASSKEY, "恢复通行密钥", "HyperPasskey：恢复凭据选择、第三方凭据提供方与扫码登录支持"),
        FeatureSetting(HyperOsSettings.PREFERENCES, HyperOsSettings.FCM_LIVE, "FCM 推送保活", "HyperOS FCM Live：解除 Google 推送的冻结、联网与广播限制"),
    )

    override val instructions = "以上功能默认关闭，需要已安装并启用 Google Play 服务。\n\n通行密钥：请在 LSPosed 中勾选系统框架、设置、手机管家、扫一扫。\nFCM 推送保活：请勾选系统框架、电量和性能。\n\n修改后请重启设备，使系统侧配置生效；关闭后也需重启以恢复已修改的运行状态。"

    override fun extraContent(scope: LazyListScope) = with(scope) {
        item("fcm_wake") {
            Spacer(Modifier.height(12.dp))
            Card {
                ArrowPreference(title = "FCM 唤醒白名单", summary = "选择可被额外唤醒的应用，空名单放行全部",
                    onClick = { startActivity(Intent(this@GoogleSettingsActivity, FcmWakeSettingsActivity::class.java)) })
            }
        }
    }
}
