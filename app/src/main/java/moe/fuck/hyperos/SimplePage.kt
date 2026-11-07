package moe.fuck.hyperos

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import moe.fuck.hyperos.ui.AppPage
import moe.fuck.hyperos.ui.SettingsList

@Composable
internal fun SimplePage(title: String, onBack: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    AppPage(title, onBack) { padding, modifier -> SettingsList(padding, modifier, content) }
}
