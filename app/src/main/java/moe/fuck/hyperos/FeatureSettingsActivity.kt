package moe.fuck.hyperos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import io.github.libxposed.service.XposedService
import moe.fuck.hyperos.ui.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

internal data class FeatureSetting(
    val preferences: String,
    val key: String,
    val label: String,
    val summary: String,
    val default: Boolean = false,
)

abstract class FeatureSettingsActivity(private val pageTitle: String) : ComponentActivity(), App.ServiceStateListener {
    internal abstract val settings: List<FeatureSetting>
    internal abstract val instructions: String
    internal open fun extraContent(scope: LazyListScope) = Unit
    private var available by mutableStateOf(false)
    private var values by mutableStateOf<Map<String, Boolean>>(emptyMap())

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setMiuixContent {
            SimplePage(pageTitle, ::finish) {
                item(key = "instructions") { UsageInstructions(instructions) }
                item {
                    SectionTitle("功能设置")
                    Card {
                        settings.forEach { setting ->
                            SwitchPreference(
                                title = setting.label,
                                summary = setting.summary,
                                checked = values[setting.key] ?: setting.default,
                                enabled = available,
                                onCheckedChange = { save(setting, it) },
                            )
                        }
                    }
                }
                extraContent(this)
            }
        }
    }

    private fun save(setting: FeatureSetting, checked: Boolean) {
        if (!available) return
        try {
            val service = checkNotNull(App.mService)
            check(service.apiVersion >= 102)
            check(service.getRemotePreferences(setting.preferences).edit().putBoolean(setting.key, checked).commit())
            values = values + (setting.key to checked)
        } catch (_: Exception) { showMessage("设置保存失败") }
    }

    final override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }

    final override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }

    final override fun onServiceStateChanged(service: XposedService?) = runOnUiThread {
        try {
            val connected = service != null && service.apiVersion >= 102
            if (connected) {
                values = settings.associate { setting ->
                    setting.key to checkNotNull(service).getRemotePreferences(setting.preferences)
                        .getBoolean(setting.key, setting.default)
                }
            }
            available = connected
        } catch (_: Exception) {
            available = false
        }
    }
}
