package moe.fuck.hyperos

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import io.github.libxposed.service.XposedService
import moe.fuck.hyperos.google.*
import moe.fuck.hyperos.ui.setMiuixContent
import moe.fuck.hyperos.ui.showMessage
import java.util.concurrent.Executors

class FcmWakeSettingsActivity : ComponentActivity(), App.ServiceStateListener {
    private var apps by mutableStateOf<List<FcmApp>>(emptyList())
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private var available by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var loadError by mutableStateOf(false)
    private var preferences: SharedPreferences? = null
    private val loader = Executors.newSingleThreadExecutor()
    private val subscription = FcmWakePreferences { packages ->
        runOnUiThread {
            if (!isDestroyed) {
                available = packages != null
                if (packages != null) selected = packages
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setMiuixContent { FcmWakePage(apps, selected, available, loading, loadError, ::save, ::loadApps, ::finish) }
        loadApps()
    }

    private fun loadApps() {
        if (loader.isShutdown) return
        loading = true
        loadError = false
        loader.execute {
            val result = runCatching {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)).map { app ->
                    FcmApp(app.packageName, app.loadLabel(packageManager).toString(),
                        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    apps = result.getOrDefault(emptyList())
                    loadError = result.isFailure
                    loading = false
                }
            }
        }
    }

    private fun save(packages: Set<String>) {
        if (!available) return
        val current = preferences ?: return
        try {
            FcmWakeSettings.save(current, packages)
            selected = packages.toSet()
        } catch (_: Exception) {
            showMessage("白名单保存失败，已保留原选择")
        }
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }

    override fun onStop() {
        App.removeServiceStateListener(this)
        subscription.close()
        preferences = null
        super.onStop()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun onServiceStateChanged(service: XposedService?) = runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        try {
            preferences = if (service != null && service.apiVersion >= 102) service.getRemotePreferences(FcmWakeSettings.PREFERENCES) else null
            subscription.bind(preferences)
        } catch (_: Exception) {
            preferences = null
            subscription.close()
        }
    }
}
