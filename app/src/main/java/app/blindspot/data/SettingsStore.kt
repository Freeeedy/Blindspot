package app.blindspot.data

import android.content.Context
import app.blindspot.detect.DetectorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TileMode { ONLINE, CACHED_ONLY, OFFLINE_VECTOR, OFF }

/** Key used for cameras without an operator / without a type in the filter sets. */
const val FILTER_UNKNOWN = ""

data class AppSettings(
    /** Maximum distance for cameras with a known viewing direction. */
    val maxDistanceM: Int = 40,
    /** Horizontal field of view for cameras with a known direction. */
    val fovDeg: Int = 110,
    /** Radius assumed for cameras whose direction is unknown (360° coverage). */
    val unknownRadiusM: Int = 40,
    val gpsIntervalSec: Int = 3,
    val vibrationEnabled: Boolean = true,
    val backgroundMonitoring: Boolean = true,
    val batterySaver: Boolean = true,
    // Stability (advanced)
    val maxAccuracyM: Int = 35,
    val enterConfirmations: Int = 2,
    val exitConfirmations: Int = 3,
    val boundaryMarginM: Int = 8,
    // Map
    val tileMode: TileMode = TileMode.ONLINE,
    val wifiOnlyMapDownload: Boolean = true,
    // Filters (operator / type values exactly as in the data; FILTER_UNKNOWN for missing)
    val hiddenOperators: Set<String> = emptySet(),
    val hiddenTypes: Set<String> = emptySet(),
    val filtersAffectAlerts: Boolean = false,
) {
    val filtersActive: Boolean get() = hiddenOperators.isNotEmpty() || hiddenTypes.isNotEmpty()

    /** A camera is shown unless its operator is hidden or every one of its types is hidden. */
    fun isVisible(c: app.blindspot.data.Camera): Boolean {
        if ((c.operator ?: FILTER_UNKNOWN) in hiddenOperators) return false
        if (hiddenTypes.isEmpty()) return true
        return c.type.ifEmpty { listOf(FILTER_UNKNOWN) }.any { it !in hiddenTypes }
    }

    fun sanitized() = copy(
        maxDistanceM = maxDistanceM.coerceIn(5, 300),
        fovDeg = fovDeg.coerceIn(10, 180),
        unknownRadiusM = unknownRadiusM.coerceIn(5, 300),
        gpsIntervalSec = gpsIntervalSec.coerceIn(1, 30),
        maxAccuracyM = maxAccuracyM.coerceIn(5, 200),
        enterConfirmations = enterConfirmations.coerceIn(1, 10),
        exitConfirmations = exitConfirmations.coerceIn(1, 10),
        boundaryMarginM = boundaryMarginM.coerceIn(0, 50),
    )

    fun toDetectorConfig() = DetectorConfig(
        fovDeg = fovDeg.toDouble(),
        maxDistanceM = maxDistanceM.toDouble(),
        unknownRadiusM = unknownRadiusM.toDouble(),
        marginM = boundaryMarginM.toDouble(),
        maxAccuracyM = maxAccuracyM.toDouble(),
        enterConfirmations = enterConfirmations,
        exitConfirmations = exitConfirmations,
    )
}

class SettingsStore private constructor(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val current: AppSettings get() = _flow.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(_flow.value).sanitized()
        write(n)
        _flow.value = n
    }

    fun resetDefaults() = update {
        AppSettings(tileMode = it.tileMode, wifiOnlyMapDownload = it.wifiOnlyMapDownload,
            hiddenOperators = it.hiddenOperators, hiddenTypes = it.hiddenTypes, filtersAffectAlerts = it.filtersAffectAlerts)
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            maxDistanceM = prefs.getInt("max_distance", d.maxDistanceM),
            fovDeg = prefs.getInt("fov", d.fovDeg),
            unknownRadiusM = prefs.getInt("unknown_radius", d.unknownRadiusM),
            gpsIntervalSec = prefs.getInt("gps_interval", d.gpsIntervalSec),
            vibrationEnabled = prefs.getBoolean("vibration", d.vibrationEnabled),
            backgroundMonitoring = prefs.getBoolean("background", d.backgroundMonitoring),
            batterySaver = prefs.getBoolean("battery_saver", d.batterySaver),
            maxAccuracyM = prefs.getInt("max_accuracy", d.maxAccuracyM),
            enterConfirmations = prefs.getInt("enter_conf", d.enterConfirmations),
            exitConfirmations = prefs.getInt("exit_conf", d.exitConfirmations),
            boundaryMarginM = prefs.getInt("margin", d.boundaryMarginM),
            tileMode = runCatching { TileMode.valueOf(prefs.getString("tiles", d.tileMode.name)!!) }
                .getOrDefault(d.tileMode),
            wifiOnlyMapDownload = prefs.getBoolean("wifi_only_maps", d.wifiOnlyMapDownload),
            hiddenOperators = prefs.getStringSet("hidden_operators", emptySet())!!.toSet(),
            hiddenTypes = prefs.getStringSet("hidden_types", emptySet())!!.toSet(),
            filtersAffectAlerts = prefs.getBoolean("filters_alerts", d.filtersAffectAlerts),
        ).sanitized()
    }

    private fun write(s: AppSettings) {
        prefs.edit()
            .putInt("max_distance", s.maxDistanceM)
            .putInt("fov", s.fovDeg)
            .putInt("unknown_radius", s.unknownRadiusM)
            .putInt("gps_interval", s.gpsIntervalSec)
            .putBoolean("vibration", s.vibrationEnabled)
            .putBoolean("background", s.backgroundMonitoring)
            .putBoolean("battery_saver", s.batterySaver)
            .putInt("max_accuracy", s.maxAccuracyM)
            .putInt("enter_conf", s.enterConfirmations)
            .putInt("exit_conf", s.exitConfirmations)
            .putInt("margin", s.boundaryMarginM)
            .putString("tiles", s.tileMode.name)
            .putBoolean("wifi_only_maps", s.wifiOnlyMapDownload)
            .putStringSet("hidden_operators", HashSet(s.hiddenOperators))
            .putStringSet("hidden_types", HashSet(s.hiddenTypes))
            .putBoolean("filters_alerts", s.filtersAffectAlerts)
            .apply()
    }

    companion object {
        @Volatile private var instance: SettingsStore? = null
        fun get(ctx: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(ctx.applicationContext).also { instance = it }
        }
    }
}
