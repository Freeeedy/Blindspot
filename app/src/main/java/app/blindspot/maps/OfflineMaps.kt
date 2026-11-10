package app.blindspot.maps

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import app.blindspot.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.util.Locale

/** One downloadable Mapsforge vector map (country or region). */
data class MapEntry(
    val path: String,
    val sizeBytes: Long,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val url: String get() = MapCatalog.BASE_URL + path
    val fileName: String get() = path.substringAfterLast('/')
    val displayName: String
        get() {
            val parts = path.removeSuffix(".map").split('/')
            val name = pretty(parts.last())
            return if (parts.size > 2) "$name, ${pretty(parts[parts.size - 2])}" else name
        }

    val areaDeg: Double get() = (north - south) * (east - west)
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east

    private fun pretty(s: String) = s.split('-', '_').joinToString(" ") { w ->
        w.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}

/**
 * Catalog of Mapsforge maps from download.mapsforge.org, bundled with the app (built from each
 * file's header). The country for the user's position is chosen locally from the bounding boxes,
 * so the location is never sent anywhere.
 */
object MapCatalog {
    const val BASE_URL = "https://download.mapsforge.org/maps/v5/"
    @Volatile private var cache: List<MapEntry>? = null

    fun all(ctx: Context): List<MapEntry> = cache ?: synchronized(this) {
        cache ?: run {
            val text = ctx.assets.open("map_catalog.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MapEntry(o.getString("path"), o.getLong("size"), o.getDouble("s"), o.getDouble("w"), o.getDouble("n"), o.getDouble("e"))
            }.sortedBy { it.displayName }
        }.also { cache = it }
    }

    /** Maps covering the position, smallest (most specific) first. */
    fun candidates(ctx: Context, lat: Double, lon: Double): List<MapEntry> =
        all(ctx).filter { it.contains(lat, lon) }.sortedBy { it.areaDeg }

    fun search(ctx: Context, query: String): List<MapEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        return all(ctx).filter { it.displayName.lowercase(Locale.ROOT).contains(q) }
    }
}

sealed interface MapDownloadState {
    data object Idle : MapDownloadState
    data class Running(val name: String, val downloaded: Long, val total: Long, val waitingReason: String?) : MapDownloadState
    data class Failed(val name: String, val message: String) : MapDownloadState
    data class Completed(val name: String) : MapDownloadState
}

/**
 * Manages installed offline maps in app-specific storage (removed with the app) and downloads
 * them with Android's DownloadManager, which resumes after network interruptions.
 */
class OfflineMaps private constructor(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)
    private val dm = ctx.getSystemService(DownloadManager::class.java)

    val dir: File? get() = ctx.getExternalFilesDir("maps")?.also { it.mkdirs() }

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<List<File>> = _installed.asStateFlow()

    private val _download = MutableStateFlow<MapDownloadState>(MapDownloadState.Idle)
    val download: StateFlow<MapDownloadState> = _download.asStateFlow()

    private fun scanInstalled(): List<File> =
        dir?.listFiles { f -> f.isFile && f.name.endsWith(".map") }?.sortedBy { it.name } ?: emptyList()

    fun refreshInstalled() { _installed.value = scanInstalled() }

    fun freeBytes(): Long = dir?.let { StatFs(it.path).availableBytes } ?: 0L

    fun start(entry: MapEntry, wifiOnly: Boolean): String? {
        val d = dir ?: return "Storage is not available."
        if (prefs.getLong(KEY_ID, -1L) >= 0) return "Another map is already downloading."
        if (freeBytes() < entry.sizeBytes + 50L * 1024 * 1024) {
            return "Not enough free space (${formatSize(entry.sizeBytes)} needed, ${formatSize(freeBytes())} free)."
        }
        File(d, entry.fileName + PART).delete()
        val req = DownloadManager.Request(Uri.parse(entry.url))
            .setTitle("Blindspot offline map")
            .setDescription(entry.displayName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, "maps", entry.fileName + PART)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
            .addRequestHeader("User-Agent", "Blindspot/${BuildConfig.VERSION_NAME} (Android)")
        val id = dm.enqueue(req)
        prefs.edit().putLong(KEY_ID, id).putString(KEY_FILE, entry.fileName).putString(KEY_NAME, entry.displayName)
            .putBoolean(KEY_WIFI, wifiOnly).apply()
        _download.value = MapDownloadState.Running(entry.displayName, 0, entry.sizeBytes, null)
        return null
    }

    fun cancel() {
        val id = prefs.getLong(KEY_ID, -1L)
        if (id >= 0) dm.remove(id)
        prefs.getString(KEY_FILE, null)?.let { f -> dir?.let { File(it, f + PART).delete() } }
        clearPrefs()
        _download.value = MapDownloadState.Idle
    }

    fun dismissResult() {
        if (_download.value is MapDownloadState.Completed || _download.value is MapDownloadState.Failed) {
            _download.value = MapDownloadState.Idle
        }
    }

    /** Polls DownloadManager and installs a finished download. Safe to call often. */
    fun poll() {
        val id = prefs.getLong(KEY_ID, -1L)
        if (id < 0) return
        val name = prefs.getString(KEY_NAME, "Map") ?: "Map"
        val file = prefs.getString(KEY_FILE, null)
        val c: Cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return
        c.use {
            if (!it.moveToFirst()) {
                clearPrefs()
                _download.value = MapDownloadState.Failed(name, "The download was cancelled.")
                return
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> finish(name, file)
                DownloadManager.STATUS_FAILED -> {
                    dm.remove(id)
                    file?.let { f -> dir?.let { d -> File(d, f + PART).delete() } }
                    clearPrefs()
                    _download.value = MapDownloadState.Failed(name, "Download failed (code $reason). Check the connection and try again.")
                }
                DownloadManager.STATUS_PAUSED -> _download.value = MapDownloadState.Running(name, done, total, pausedReason(reason))
                else -> _download.value = MapDownloadState.Running(name, done, total,
                    if (status == DownloadManager.STATUS_PENDING) pendingReason() else null)
            }
        }
    }

    private fun finish(name: String, file: String?) {
        val d = dir
        clearPrefs()
        if (d == null || file == null) {
            _download.value = MapDownloadState.Failed(name, "Storage is not available."); return
        }
        val part = File(d, file + PART)
        val target = File(d, file)
        if (!isMapsforgeFile(part)) {
            part.delete()
            _download.value = MapDownloadState.Failed(name, "The downloaded file is not a valid map.")
            return
        }
        target.delete()
        if (!part.renameTo(target)) {
            _download.value = MapDownloadState.Failed(name, "Could not install the map file."); return
        }
        refreshInstalled()
        _download.value = MapDownloadState.Completed(name)
    }

    fun delete(f: File) {
        f.delete()
        refreshInstalled()
    }

    private fun clearPrefs() = prefs.edit().remove(KEY_ID).remove(KEY_FILE).remove(KEY_NAME).remove(KEY_WIFI).apply()

    private fun pendingReason(): String {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null -> "Waiting for a network connection…"
            prefs.getBoolean(KEY_WIFI, false) &&
                !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> "Waiting for Wi-Fi…"
            else -> "Starting…"
        }
    }

    private fun pausedReason(r: Int) = when (r) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi…"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a network connection…"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection interrupted, retrying…"
        else -> "Paused"
    }

    companion object {
        private const val KEY_ID = "download_id"
        private const val KEY_FILE = "download_file"
        private const val KEY_NAME = "download_name"
        private const val KEY_WIFI = "download_wifi_only"
        private const val PART = ".part"

        @Volatile private var instance: OfflineMaps? = null
        fun get(ctx: Context): OfflineMaps = instance ?: synchronized(this) {
            instance ?: OfflineMaps(ctx.applicationContext).also { instance = it }
        }

        fun isMapsforgeFile(f: File): Boolean = try {
            f.inputStream().use { s ->
                val b = ByteArray(20)
                s.read(b) == 20 && String(b, Charsets.US_ASCII) == "mapsforge binary OSM"
            }
        } catch (_: Exception) { false }

        fun formatSize(b: Long): String = when {
            b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", b / 1073741824.0)
            b >= 1024 * 1024 -> String.format(Locale.US, "%.0f MB", b / 1048576.0)
            b >= 1024 -> "${b / 1024} KB"
            else -> "$b B"
        }
    }
}
