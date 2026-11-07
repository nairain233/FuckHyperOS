package moe.fuck.hyperos

import moe.fuck.hyperos.hyperos.HyperOsSettings

class TelemetryActivity : FeatureSettingsActivity("遥测相关") {
    internal override val settings = listOf(
        FeatureSetting(HyperOsSettings.PREFERENCES, HyperOsSettings.BLOCK_ANALYTICS, "阻止 Analytics 上传", "阻止使用情况采集与遥测上传"),
        FeatureSetting(HyperOsSettings.PREFERENCES, HyperOsSettings.DISABLE_GUARD_ROOT_CHECK, "禁用环境检查", "禁止系统安全组件 Root 检查"),
        FeatureSetting(HyperOsSettings.PREFERENCES, HyperOsSettings.BLOCK_GUARD_APP_LIST_UPLOAD, "阻止自动上传已安装应用列表", "阻止自动上传已安装应用列表"),
    )

    internal override val instructions = "以上功能默认关闭。请在 LSPosed 中勾选对应的作用域；修改后重启对应应用。"
}
