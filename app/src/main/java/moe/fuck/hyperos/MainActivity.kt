package moe.fuck.hyperos

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.fuck.hyperos.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import moe.fuck.hyperos.card.CardActivity

class MainActivity : ComponentActivity(), App.ServiceStateListener {
    private var frameworkStatus by mutableStateOf<FrameworkStatus>(FrameworkStatus.Disconnected)
    private var reloadStatus by mutableStateOf("")
    private var reloadInProgress by mutableStateOf(false)
    private var serviceAvailable by mutableStateOf(false)
    private var detailedLogging by mutableStateOf(false)
    private var hideLauncher by mutableStateOf(false)
    private var boundService: XposedService? = null
    private var reloadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideLauncher = packageManager.getComponentEnabledSetting(launcher()) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        setMiuixContent {
            SimplePage(getString(R.string.app_name)) {
                item { FrameworkStatusCard(frameworkStatus) { onServiceStateChanged(App.mService) } }
                item {
                    Spacer(Modifier.height(12.dp))
                    Card {
                        ArrowPreference(title = "键盘内联填充", summary = "启用填充建议与自动填充测试",
                            onClick = { startActivity(Intent(this@MainActivity, InlineSettingsActivity::class.java)) })
                        ArrowPreference(title = "自定义卡面", summary = "管理钱包卡片与超级岛卡面",
                            onClick = { startActivity(Intent(this@MainActivity, CardActivity::class.java)) })
                        ArrowPreference(title = "主题相关", summary = "主题与大图标、小部件、主题商店去广告",
                            onClick = { startActivity(Intent(this@MainActivity, HyperOsActivity::class.java)) })
                        ArrowPreference(title = "遥测相关", summary = "Analytics 上传、系统安全组件环境检查与应用列表上传",
                            onClick = { startActivity(Intent(this@MainActivity, TelemetryActivity::class.java)) })
                        ArrowPreference(title = "Google 相关", summary = "HyperPasskey 通行密钥与 FCM 推送保活",
                            onClick = { startActivity(Intent(this@MainActivity, GoogleSettingsActivity::class.java)) })
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Card {
                        SwitchPreference(title = "隐藏桌面图标", summary = "隐藏后可从 LSPosed 模块设置进入",
                            checked = hideLauncher, onCheckedChange = ::setLauncherHidden)
                        SwitchPreference(title = "启用详细日志", summary = if (serviceAvailable) "记录调试信息，便于排查问题" else "需要连接支持 API 102 的框架",
                            checked = detailedLogging, enabled = serviceAvailable, onCheckedChange = ::setLogging)
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(text = if (reloadInProgress) "正在热重载…" else "热重载已更新的模块", onClick = ::hotReload,
                        enabled = serviceAvailable && !reloadInProgress, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                    if (reloadStatus.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        InfoCard(reloadStatus, working = reloadInProgress)
                    }
                }
            }
        }
    }

    private fun launcher() = ComponentName(this, "$packageName.Launcher")

    private fun setLauncherHidden(hidden: Boolean) {
        try {
            packageManager.setComponentEnabledSetting(launcher(),
                if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP)
            hideLauncher = hidden
        } catch (_: Exception) { showMessage("桌面图标设置失败") }
    }

    private fun setLogging(checked: Boolean) {
        if (!serviceAvailable) return
        try {
            val service = checkNotNull(App.mService)
            check(service.apiVersion >= 102)
            setDetailedLogging(service.getRemotePreferences(ModuleLog.PREFERENCES), checked)
            detailedLogging = checked
        } catch (_: Exception) { showMessage("日志设置保存失败") }
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }

    override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            if (boundService !== service) {
                boundService = service
                reloadGeneration++
                if (reloadInProgress) reloadStatus = "框架连接已变化，请刷新进程状态"
                reloadInProgress = false
            }
            try {
                serviceAvailable = service != null && service.apiVersion >= 102
                detailedLogging = if (serviceAvailable) {
                    checkNotNull(service).getRemotePreferences(ModuleLog.PREFERENCES)
                        .getBoolean(ModuleLog.DETAILED, false)
                } else false
                frameworkStatus = when {
                    service == null -> FrameworkStatus.Disconnected
                    !serviceAvailable -> FrameworkStatus.ReadError(getString(R.string.framework_api_required))
                    else -> FrameworkStatus.Connected(service.frameworkName, service.apiVersion, service.frameworkVersionCode)
                }
            } catch (_: Exception) {
                serviceAvailable = false
                frameworkStatus = FrameworkStatus.ReadError()
            }
        }
    }

    private fun hotReload() {
        if (!serviceAvailable || reloadInProgress) return
        val service = App.mService ?: return
        try {
            if (service.apiVersion < 102) return
            val targets = service.runningTargets.filter {
                it.state == HookedTarget.State.STALE || it.state == HookedTarget.State.FAILED
            }
            if (targets.isEmpty()) {
                reloadStatus = "没有需要重载的进程"
                onServiceStateChanged(service)
                return
            }
            reloadInProgress = true
            val generation = reloadGeneration
            reloadStatus = "正在热重载 ${targets.size} 个进程…"
            var pending = targets.size
            val messages = mutableListOf<String>()
            fun completed(process: String, message: String) {
                runOnUiThread {
                    if (isDestroyed || generation != reloadGeneration) return@runOnUiThread
                    messages.add("$process：$message")
                    pending--
                    reloadStatus = messages.joinToString("\n")
                    if (pending == 0) {
                        reloadInProgress = false
                        onServiceStateChanged(App.mService)
                    }
                }
            }
            targets.forEach { target ->
                try {
                    service.hotReloadModule(target, null) { reloaded, result ->
                        val message = when (result.status()) {
                            HotReloadResult.Status.SUCCEEDED -> "重载成功"
                            HotReloadResult.Status.FAILED -> result.message()
                                ?: "重载失败"
                            HotReloadResult.Status.UNSUPPORTED -> "框架不支持此进程热重载"
                            HotReloadResult.Status.IN_PROGRESS -> "框架正在重载"
                            HotReloadResult.Status.PROCESS_DIED -> "进程已退出"
                        }
                        completed(reloaded.processName, message)
                    }
                } catch (_: Exception) {
                    completed(target.processName, "重载请求提交失败")
                }
            }
        } catch (_: Exception) {
            reloadInProgress = false
            reloadStatus = "读取进程状态失败"
        }
    }

}
