package app.blindspot.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.blindspot.R
import app.blindspot.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile that starts and stops monitoring. */
class MonitoringTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch { MonitorState.flow.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        android.util.Log.i("Blindspot", "tile click, monitoring=${MonitorState.flow.value.monitoring}")
        if (MonitorState.flow.value.monitoring) {
            MonitoringService.stop(this)
            return
        }
        // Android only grants location to a location service started while the app is in the
        // foreground, so the tile briefly opens Blindspot, which starts monitoring and steps back.
        if (isLocked) unlockAndRun { openApp(startMonitoring = true) } else openApp(startMonitoring = true)
    }

    private fun openApp(startMonitoring: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (startMonitoring) intent.action = MainActivity.ACTION_START_MONITORING
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(s: MonitorSnapshot) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_blindspot)
        tile.label = "Blindspot"
        tile.state = if (s.monitoring) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val sub = when {
            !s.monitoring -> "Off"
            s.inside -> "In estimated view"
            s.gps == GpsStatus.GOOD -> "Monitoring"
            else -> "Waiting for GPS"
        }
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.contentDescription = "Blindspot monitoring: $sub"
        tile.updateTile()
    }
}
