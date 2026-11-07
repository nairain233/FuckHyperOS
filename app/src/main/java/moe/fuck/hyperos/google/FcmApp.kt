package moe.fuck.hyperos.google

import java.util.Locale

internal data class FcmApp(val packageName: String, val label: String, val system: Boolean, val installed: Boolean = true)

internal enum class FcmAppFilter(val title: String) {
    ALL("全部"), SELECTED("已选择"), UNSELECTED("未选择");

    fun matches(selected: Boolean): Boolean = when (this) {
        ALL -> true
        SELECTED -> selected
        UNSELECTED -> !selected
    }
}

internal fun visibleFcmApps(apps: List<FcmApp>, selected: Set<String>, query: String, showSystem: Boolean): List<FcmApp> {
    val installed = apps.map { it.packageName }.toSet()
    val entries = apps + (selected - installed).map { FcmApp(it, it, false, installed = false) }
    val search = query.trim()
    return entries.filter {
        (showSystem || !it.system || it.packageName in selected) &&
            (it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true))
    }.sortedWith(compareByDescending<FcmApp> { it.packageName in selected }
        .thenBy { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
}
