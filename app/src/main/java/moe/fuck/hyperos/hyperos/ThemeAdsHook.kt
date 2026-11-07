package moe.fuck.hyperos.hyperos

import android.view.View
import android.widget.FrameLayout

internal object ThemeAdsHook {
    fun install(support: HookSupport) = with(support) {
        booleanMethods("miui.drm.DrmManager", "isSupportAd", false)
        booleanMethods("miui.drm.DrmManager", "setSupportAd", false, allowVoid = true)
        booleanMethods("com.android.thememanager.basemodule.ad.model.AdInfoResponse", "isAdValid", false, paramCount = 1)
        listOf("SelfFontItemAdViewHolder", "SelfRingtoneItemAdViewHolder").forEach { name ->
            attempt("ad-card-$name") {
                val constructors = load("com.android.thememanager.recommend.view.listview.viewholder.$name")
                    .declaredConstructors.filter {
                        it.parameterCount == 2 && View::class.java.isAssignableFrom(it.parameterTypes[0])
                    }
                check(constructors.size == 1) { "Ad constructor matches=${constructors.size}" }
                hooks.intercept(constructors.single()) { chain ->
                    val result = chain.proceed()
                    if (active()) attempt("ad-card-hide-$name", reportSuccess = false) {
                        (chain.args[0] as? View)?.let { view ->
                            view.layoutParams = FrameLayout.LayoutParams(0, 0)
                            view.visibility = View.GONE
                        }
                    }
                    result
                }
            }
        }
    }
}
