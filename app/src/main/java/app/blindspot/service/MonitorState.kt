package app.blindspot.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A camera whose estimated coverage included the user during this monitoring session (memory only). */
data class SessionHit(val cameraId: Long, val firstMs: Long, val lastMs: Long, val entries: Int)

enum class GpsStatus { WAITING, GOOD, INACCURATE, LOST, PROVIDER_DISABLED }

/** Live monitoring state published by [MonitoringService] for the UI. Kept in memory only. */
data class MonitorSnapshot(
    val monitoring: Boolean = false,
    val gps: GpsStatus = GpsStatus.WAITING,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val fixWallTimeMs: Long? = null,
    val inside: Boolean = false,
    val coveringIds: Set<Long> = emptySet(),
    val nearbyCount: Int = 0,
    val pendingCount: Int = 0,
    val intervalSec: Int = 0,
    val error: String? = null,
    /** Cameras that covered you this session, most recent first. Never written to storage. */
    val sessionHits: List<SessionHit> = emptyList(),
)

object MonitorState {
    private val _flow = MutableStateFlow(MonitorSnapshot())
    val flow: StateFlow<MonitorSnapshot> = _flow.asStateFlow()

    fun update(f: (MonitorSnapshot) -> MonitorSnapshot) = _flow.update(f)
    fun clearError() = _flow.update { it.copy(error = null) }
    fun clearSession() = _flow.update { it.copy(sessionHits = emptyList()) }
}
