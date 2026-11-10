package app.blindspot

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.blindspot.data.CameraRepository
import app.blindspot.data.SettingsStore
import app.blindspot.service.MonitorState
import app.blindspot.service.MonitoringService
import app.blindspot.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class BlindspotApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid()
        // Vector (Mapsforge) offline map renderer.
        org.osmdroid.mapsforge.MapsForgeTileSource.createInstance(this)
        Notifications.createChannels(this)
        appScope.launch { CameraRepository.get(this@BlindspotApp).ensureLoaded() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(BackgroundPolicy(this))
    }

    private fun configureOsmdroid() {
        val cfg = Configuration.getInstance()
        cfg.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        // OpenStreetMap's tile policy requires an identifying User-Agent.
        cfg.userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
        // Tiles are cached in app-private storage so previously viewed areas work offline.
        val base = File(filesDir, "osmdroid")
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles")
        cfg.tileFileSystemCacheMaxBytes = 400L * 1024 * 1024
        cfg.tileFileSystemCacheTrimBytes = 320L * 1024 * 1024
    }
}

/**
 * Implements the "Background monitoring" setting: when it is off, monitoring pauses while the
 * app is not visible and resumes when the user returns.
 */
private class BackgroundPolicy(private val ctx: Context) : DefaultLifecycleObserver {
    private var pausedByPolicy = false

    override fun onStop(owner: LifecycleOwner) {
        if (MonitorState.flow.value.monitoring && !SettingsStore.get(ctx).current.backgroundMonitoring) {
            pausedByPolicy = true
            android.util.Log.i("Blindspot", "background monitoring disabled: pausing while app is hidden")
            MonitoringService.stop(ctx)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (pausedByPolicy) {
            pausedByPolicy = false
            if (MonitoringService.hasFineLocation(ctx)) MonitoringService.start(ctx)
        }
    }
}
