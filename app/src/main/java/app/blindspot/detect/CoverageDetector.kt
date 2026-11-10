package app.blindspot.detect

import app.blindspot.data.Camera
import app.blindspot.geo.CameraIndex
import app.blindspot.geo.GeoMath
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

data class DetectorConfig(
    val fovDeg: Double = 110.0,
    val maxDistanceM: Double = 40.0,
    val unknownRadiusM: Double = 40.0,
    /** Extra distance a camera's area is widened by once you are in it (hysteresis). */
    val marginM: Double = 8.0,
    /** Readings with a worse reported accuracy are ignored. */
    val maxAccuracyM: Double = 35.0,
    val enterConfirmations: Int = 2,
    val exitConfirmations: Int = 3,
    /** Faster apparent movement than this is treated as a GPS jump. */
    val maxSpeedMps: Double = 70.0,
    /** Radius for the "cameras nearby" count. */
    val nearbyRadiusM: Double = 150.0,
)

/** A location reading. [timeMs] must be monotonic (elapsed realtime). */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long)

enum class FixQuality { GOOD, INACCURATE, JUMP }
enum class Transition { NONE, ENTERED, EXITED }

data class DetectionResult(
    val quality: FixQuality,
    val transition: Transition,
    /** Confirmed state: inside at least one camera's estimated coverage. */
    val confirmedInside: Boolean,
    /** What the latest accepted reading alone says. */
    val rawInside: Boolean,
    /** Cameras whose estimated coverage you are in (confirmed state). */
    val coveringIds: Set<Long>,
    val nearbyCount: Int,
    /** Distance to the nearest camera's coverage edge within the search radius, or null if none. */
    val nearestZoneM: Double?,
    /** Readings counted so far towards a pending enter/exit. */
    val pendingCount: Int,
)

/**
 * Turns a stream of GPS readings into stable enter/exit transitions.
 *
 * Stability measures:
 *  1. Accuracy gate: readings worse than [DetectorConfig.maxAccuracyM] never count as evidence.
 *  2. Jump rejection: a reading implying impossible speed is dropped, unless several in a row
 *     agree (then the GPS genuinely moved, e.g. after leaving a tunnel).
 *  3. Hysteresis: once inside a camera's area, the area is widened by a margin (plus part of the
 *     reading's uncertainty), so jitter on the boundary does not flip the state.
 *  4. Confirmation: the overall inside/outside state only changes after N consecutive readings
 *     agree (separate counts for entering and leaving).
 *
 * Moving between overlapping cameras keeps the state "inside" and produces no transition.
 * Not thread-safe: call from a single thread.
 */
class CoverageDetector(private var index: CameraIndex, private var config: DetectorConfig) {

    var confirmedInside = false
        private set
    private var pending = 0
    private var rawSet: Set<Long> = emptySet()
    private var confirmedSet: Set<Long> = emptySet()
    private var lastAccepted: Fix? = null
    private var jumpStreak = 0
    private var lastNearby = 0
    private var lastNearest: Double? = null
    private var lastRawInside = false

    fun updateIndex(newIndex: CameraIndex) { index = newIndex }
    fun updateConfig(newConfig: DetectorConfig) { config = newConfig }

    /** GPS lost or provider disabled: forget partial evidence but keep the confirmed state. */
    fun signalLost() {
        pending = 0
        lastAccepted = null
        jumpStreak = 0
    }

    fun process(fix: Fix): DetectionResult {
        val acc = fix.accuracyM?.toDouble()
        if (acc == null || acc > config.maxAccuracyM) return snapshot(FixQuality.INACCURATE, Transition.NONE)

        val last = lastAccepted
        if (last != null) {
            val dt = (fix.timeMs - last.timeMs) / 1000.0
            if (dt > 0 && dt <= JUMP_WINDOW_S) {
                val dist = GeoMath.distanceM(last.lat, last.lon, fix.lat, fix.lon)
                val allowed = config.maxSpeedMps * dt + acc + (last.accuracyM?.toDouble() ?: 0.0)
                if (dist > allowed) {
                    jumpStreak++
                    if (jumpStreak < JUMP_ACCEPT_STREAK) return snapshot(FixQuality.JUMP, Transition.NONE)
                }
            }
        }
        jumpStreak = 0
        lastAccepted = fix

        val newRaw = HashSet<Long>()
        var nearby = 0
        var nearest: Double? = null
        val exitMargin = config.marginM + min(acc, config.maxAccuracyM) * 0.5
        for (cam in index.near(fix.lat, fix.lon, SEARCH_RADIUS_M)) {
            val d = GeoMath.distanceM(cam.lat, cam.lon, fix.lat, fix.lon)
            if (d > SEARCH_RADIUS_M) continue
            if (d <= config.nearbyRadiusM) nearby++
            val zone = if (cam.hasDirection) config.maxDistanceM else config.unknownRadiusM
            val edge = max(0.0, d - zone)
            if (nearest == null || edge < nearest) nearest = edge
            if (d > zone + exitMargin) continue
            val margin = if (cam.id in rawSet) exitMargin else 0.0
            val bearing = GeoMath.bearingDeg(cam.lat, cam.lon, fix.lat, fix.lon)
            if (isCovered(cam, d, bearing, config, margin)) newRaw += cam.id
        }
        rawSet = newRaw
        lastNearby = nearby
        lastNearest = nearest
        lastRawInside = newRaw.isNotEmpty()

        var transition = Transition.NONE
        if (lastRawInside != confirmedInside) {
            pending++
            val needed = if (lastRawInside) config.enterConfirmations else config.exitConfirmations
            if (pending >= needed) {
                confirmedInside = lastRawInside
                pending = 0
                transition = if (confirmedInside) Transition.ENTERED else Transition.EXITED
            }
        } else {
            pending = 0
        }
        confirmedSet = when {
            confirmedInside && lastRawInside -> newRaw
            confirmedInside -> confirmedSet      // exit pending: keep the last known set
            else -> emptySet()
        }
        return snapshot(FixQuality.GOOD, transition)
    }

    private fun snapshot(q: FixQuality, t: Transition) = DetectionResult(
        quality = q,
        transition = t,
        confirmedInside = confirmedInside,
        rawInside = lastRawInside,
        coveringIds = confirmedSet,
        nearbyCount = lastNearby,
        nearestZoneM = lastNearest,
        pendingCount = pending,
    )

    companion object {
        const val SEARCH_RADIUS_M = 2500.0
        const val JUMP_WINDOW_S = 60.0
        const val JUMP_ACCEPT_STREAK = 3
        private const val MAX_ANGLE_MARGIN_DEG = 20.0

        /**
         * Geometry test for one camera. [distanceM] and [bearingFromCameraDeg] describe the
         * user's position relative to the camera. [marginM] widens the area (hysteresis).
         */
        fun isCovered(
            cam: Camera,
            distanceM: Double,
            bearingFromCameraDeg: Double,
            config: DetectorConfig,
            marginM: Double = 0.0,
        ): Boolean {
            if (!cam.hasDirection) return distanceM <= config.unknownRadiusM + marginM
            if (distanceM > config.maxDistanceM + marginM) return false
            val angleMargin = if (marginM > 0) {
                min(MAX_ANGLE_MARGIN_DEG, Math.toDegrees(atan2(marginM, max(distanceM, 1.0))))
            } else 0.0
            val half = config.fovDeg / 2.0 + angleMargin
            return cam.directions.any { GeoMath.angleDiffDeg(bearingFromCameraDeg, it) <= half }
        }
    }
}
