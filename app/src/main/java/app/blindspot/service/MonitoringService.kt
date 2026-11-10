package app.blindspot.service

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.service.quicksettings.TileService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.blindspot.data.AppSettings
import app.blindspot.data.Camera
import app.blindspot.data.CameraRepository
import app.blindspot.data.SettingsStore
import app.blindspot.detect.CoverageDetector
import app.blindspot.detect.DetectionResult
import app.blindspot.detect.Fix
import app.blindspot.detect.FixQuality
import app.blindspot.detect.Transition
import app.blindspot.geo.CameraIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Foreground service that owns GPS monitoring. It receives location updates from the phone's
 * own GPS (LocationManager.GPS_PROVIDER, no Google services, nothing leaves the device), runs
 * the [CoverageDetector], vibrates on transitions and keeps the persistent notification current.
 *
 * All detector work happens on a dedicated handler thread.
 */
class MonitoringService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // State below is only touched on the handler thread.
    private var detector: CoverageDetector? = null
    private var settings = AppSettings()
    private var requestedIntervalMs = 0L
    private var lastFixElapsed = 0L
    private var gpsStatus = GpsStatus.WAITING
    private var lastResult: DetectionResult? = null
    private var lastLocation: Location? = null
    private var lastNotification: Pair<String, String>? = null
    private var allCameras: List<Camera> = emptyList()
    private var indexKey: Any? = null
    private val sessionHits = LinkedHashMap<Long, SessionHit>()
    private var prevCovering: Set<Long> = emptySet()

    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        thread = HandlerThread("blindspot-gps").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            android.util.Log.i("Blindspot", "stop requested from notification")
            shutdown()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_MONITOR,
                Notifications.monitoring(this, "Blindspot · monitoring active", "Starting…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // E.g. restarted by the system while location access is "only while in use".
            fail("Android did not allow monitoring to run in the background. Open Blindspot to restart it.")
            return START_NOT_STICKY
        }
        if (!hasFineLocation(this)) {
            fail("Precise location permission is missing. Open Blindspot and grant it to monitor.")
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            Notifications.cancelStopped(this)
            begin()
        }
        return START_STICKY
    }

    private fun begin() {
        MonitorState.update { MonitorSnapshot(monitoring = true, gps = GpsStatus.WAITING) }
        val repo = CameraRepository.get(this)
        val store = SettingsStore.get(this)
        handler.post {
            settings = store.current
            detector = CoverageDetector(CameraIndex(emptyList()), settings.toDetectorConfig())
            sessionHits.clear()
            prevCovering = emptySet()
        }
        refreshTile(this)
        scope.launch {
            repo.ensureLoaded()
            launch {
                repo.cameras.collect { list ->
                    handler.post {
                        allCameras = list
                        indexKey = null
                        rebuildIndex()
                    }
                }
            }
            launch {
                store.flow.collect { s -> handler.post { applySettings(s) } }
            }
        }
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Rebuilds the detection index; hidden cameras are left out only if the user asked for that. */
    private fun rebuildIndex() {
        val filter = settings.filtersAffectAlerts && settings.filtersActive
        val key = if (filter) Triple(allCameras, settings.hiddenOperators, settings.hiddenTypes) else allCameras
        if (key == indexKey) return
        indexKey = key
        val list = if (filter) allCameras.filter { settings.isVisible(it) } else allCameras
        detector?.updateIndex(CameraIndex(list))
    }

    private fun applySettings(s: AppSettings) {
        settings = s
        detector?.updateConfig(s.toDetectorConfig())
        rebuildIndex()
        updateLocationRequest()
        publish()
    }

    /** Picks the GPS interval; with battery saver, slows down when far from every camera. */
    private fun desiredIntervalMs(): Long {
        val base = settings.gpsIntervalSec * 1000L
        if (!settings.batterySaver) return base
        val r = lastResult ?: return base
        if (gpsStatus != GpsStatus.GOOD || r.confirmedInside || r.pendingCount > 0) return base
        val nearest = r.nearestZoneM
        return when {
            nearest == null || nearest > 2000 -> max(base, 30_000L)
            nearest > 600 -> max(base, 12_000L)
            else -> base
        }
    }

    private fun updateLocationRequest() {
        val desired = desiredIntervalMs()
        if (desired == requestedIntervalMs) return
        try {
            locationManager.removeUpdates(listener)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, desired, 0f, listener, thread.looper,
            )
            requestedIntervalMs = desired
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsStatus = GpsStatus.PROVIDER_DISABLED
            }
        } catch (e: SecurityException) {
            scope.launch { fail("Location permission was revoked. Monitoring stopped.") }
        } catch (e: IllegalArgumentException) {
            scope.launch { fail("This device has no GPS location provider.") }
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)

        // All four methods are overridden because they are abstract on Android 8–10.
        override fun onProviderEnabled(provider: String) {
            gpsStatus = GpsStatus.WAITING
            publish()
        }

        override fun onProviderDisabled(provider: String) {
            gpsStatus = GpsStatus.PROVIDER_DISABLED
            detector?.signalLost()
            publish()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private fun onFix(loc: Location) {
        val det = detector ?: return
        lastFixElapsed = SystemClock.elapsedRealtime()
        lastLocation = loc
        val fix = Fix(
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            timeMs = loc.elapsedRealtimeNanos / 1_000_000L,
        )
        val r = det.process(fix)
        lastResult = r
        recordSession(r)
        gpsStatus = if (r.quality == FixQuality.GOOD) GpsStatus.GOOD else GpsStatus.INACCURATE
        if (settings.vibrationEnabled) {
            when (r.transition) {
                Transition.ENTERED -> Haptics.enter(this)
                Transition.EXITED -> Haptics.exit(this)
                Transition.NONE -> Unit
            }
        }
        publish(fresh = true)
        updateLocationRequest()
    }

    private fun recordSession(r: DetectionResult) {
        val now = System.currentTimeMillis()
        val covering = if (r.confirmedInside) r.coveringIds else emptySet()
        for (id in covering) {
            val old = sessionHits[id]
            val newEntry = id !in prevCovering
            sessionHits[id] = old?.copy(lastMs = now, entries = old.entries + if (newEntry) 1 else 0)
                ?: SessionHit(id, now, now, 1)
        }
        prevCovering = covering
    }

    /** Detects missing GPS updates (tunnels, indoors) without ever inventing a transition. */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val limit = max(STALE_MIN_MS, requestedIntervalMs * 4)
            if (lastFixElapsed > 0 && now - lastFixElapsed > limit &&
                gpsStatus != GpsStatus.LOST && gpsStatus != GpsStatus.PROVIDER_DISABLED
            ) {
                gpsStatus = GpsStatus.LOST
                detector?.signalLost()
                lastResult = lastResult?.copy(pendingCount = 0)
                publish()
                updateLocationRequest()
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun publish(fresh: Boolean = false) {
        val r = lastResult
        val loc = lastLocation
        MonitorState.update { s ->
            s.copy(
                monitoring = true,
                gps = gpsStatus,
                lat = loc?.latitude,
                lon = loc?.longitude,
                accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                fixWallTimeMs = if (fresh) System.currentTimeMillis() else s.fixWallTimeMs,
                inside = r?.confirmedInside ?: false,
                coveringIds = r?.coveringIds ?: emptySet(),
                nearbyCount = r?.nearbyCount ?: 0,
                pendingCount = r?.pendingCount ?: 0,
                intervalSec = (requestedIntervalMs / 1000).toInt(),
                error = null,
                sessionHits = sessionHits.values.sortedByDescending { it.lastMs },
            )
        }
        updateNotification()
    }

    private fun notificationText(): Pair<String, String> {
        val r = lastResult
        val inside = r?.confirmedInside == true
        val nearby = r?.nearbyCount ?: 0
        return when (gpsStatus) {
            GpsStatus.PROVIDER_DISABLED -> "Blindspot · GPS is turned off" to
                "Monitoring is paused until location is turned on."
            GpsStatus.WAITING -> "Blindspot · monitoring active" to "Waiting for a GPS fix…"
            GpsStatus.LOST -> "Blindspot · GPS signal lost" to
                (if (inside) "Last state: potentially within estimated camera view." else "Holding the last state until GPS returns.")
            GpsStatus.INACCURATE, GpsStatus.GOOD -> if (inside) {
                val n = r?.coveringIds?.size ?: 0
                "Potentially within camera view" to
                    "Estimated coverage of ${plural(n, "camera")} · ${plural(nearby, "camera")} nearby"
            } else {
                "Blindspot · monitoring active" to
                    (if (gpsStatus == GpsStatus.INACCURATE) "GPS accuracy is low, holding the last state."
                    else "Outside estimated camera coverage · ${plural(nearby, "camera")} nearby")
            }
        }
    }

    private fun updateNotification() {
        val text = notificationText()
        if (text == lastNotification) return
        lastNotification = text
        try {
            NotificationManagerCompat.from(this)
                .notify(Notifications.ID_MONITOR, Notifications.monitoring(this, text.first, text.second))
        } catch (_: SecurityException) {
            // Notifications not permitted; monitoring itself keeps working.
        }
    }

    private fun fail(reason: String) {
        android.util.Log.w("Blindspot", "monitoring failed: $reason")
        MonitorState.update { MonitorSnapshot(monitoring = false, error = reason, sessionHits = it.sessionHits) }
        Notifications.postStopped(this, reason)
        shutdown(keepError = true)
    }

    private fun shutdown(keepError: Boolean = false) {
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        if (!keepError) MonitorState.update { MonitorSnapshot(monitoring = false, sessionHits = it.sessionHits) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        android.util.Log.i("Blindspot", "monitoring service destroyed")
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        scope.cancel()
        MonitorState.update { it.copy(monitoring = false, inside = false, coveringIds = emptySet(), pendingCount = 0) }
        refreshTile(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.blindspot.START"
        const val ACTION_STOP = "app.blindspot.STOP"
        private const val WATCHDOG_MS = 5_000L
        private const val STALE_MIN_MS = 30_000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, MonitoringService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitoringService::class.java))
        }

        fun hasFineLocation(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun refreshTile(ctx: Context) {
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, MonitoringTileService::class.java))
            } catch (_: Exception) {
            }
        }

        private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
    }
}
