package moe.fuck.hyperos

import moe.fuck.hyperos.hyperos.HyperOsSettings

class HyperOsActivity : FeatureSettingsActivity("主题相关") {
    internal override val settings = listOf(
        FeatureSetting(HyperOsSettings.PREFERENCES, HyperOsSettings.THEME_UNLOCK, "主题与大图标解锁", "作用于系统框架、主题壁纸"),
        FeatureSetting(HyperOsSettings.PREFERENCES, HyperOsSettings.WIDGET_UNLOCK, "付费小部件解锁", "作用于主题壁纸、智能助理"),
        FeatureSetting(HyperOsSettings.PREFERENCES, HyperOsSettings.REMOVE_ADS, "主题商店去广告", "作用于主题壁纸"),
    )

    internal override val instructions = "以上功能默认关闭。请在 LSPosed 中勾选对应作用域；修改后重启对应应用，主题授权的系统侧变更需重启设备。"
}
