package moe.fuck.hyperos.hyperos

internal object WidgetHook {
    fun install(support: HookSupport, packageName: String) = with(support) {
        booleanMethods("com.miui.maml.widget.edit.MamlutilKt", "themeManagerSupportPaidWidget", false)
        if (packageName == HyperOsSettings.ASSISTANT) {
            val detail = "com.miui.personalassistant.picker.business.detail."
            booleanMethods("${detail}PickerDetailViewModel", "isCanDirectAddMaMl", true)
            booleanMethods("${detail}utils.PickerDetailDownloadManager\$Companion", "isCanDownload", true)
            booleanMethods("${detail}utils.PickerDetailUtil", "isCanAutoDownloadMaMl", true)
            booleanMethods("${detail}bean.PickerDetailResponse", "isPay", false)
            booleanMethods("${detail}bean.PickerDetailResponse", "isBought", true)
            booleanMethods("${detail}bean.PickerDetailResponseWrapper", "isPay", false)
            booleanMethods("${detail}bean.PickerDetailResponseWrapper", "isBought", true)
            booleanMethods("${detail}PickerDetailViewModel", "shouldCheckMamlBoughtState", false)
            booleanMethods("${detail}PickerDetailViewModel", "isTargetPositionMamlPayAndDownloading", false)
            booleanMethods("${detail}PickerDetailViewModel", "checkIsIndependentProcessWidgetForPosition", true)
            booleanMethods("com.miui.personalassistant.picker.bean.cards.RegularWidgetEntity", "isPay", false)
            booleanMethods("com.miui.personalassistant.picker.bean.cards.SuitEntity", "isShowPayLogo", false)
        }
    }
}
