package app.blindspot.geo

import app.blindspot.data.Camera
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * Uniform lat/lon grid index (cells of ~0.005°, roughly 550 m × 350 m in central Europe)
 * so each GPS update only looks at cameras in the surrounding cells.
 */
class CameraIndex(cameras: List<Camera>) {
    val all: List<Camera> = cameras
    val size: Int get() = all.size
    private val cells = HashMap<Long, MutableList<Camera>>()

    init {
        for (c in cameras) cells.getOrPut(key(cell(c.lat), cell(c.lon))) { ArrayList(4) }.add(c)
    }

    /** Candidates in the bounding box around (lat, lon) with the given radius (not distance-filtered). */
    fun near(lat: Double, lon: Double, radiusM: Double): List<Camera> {
        val dLat = radiusM / 110_540.0
        val dLon = radiusM / (111_320.0 * max(0.01, cos(Math.toRadians(lat))))
        return inBounds(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
    }

    fun inBounds(south: Double, west: Double, north: Double, east: Double): List<Camera> {
        if (all.isEmpty()) return emptyList()
        val la0 = cell(south); val la1 = cell(north)
        val lo0 = cell(west); val lo1 = cell(east)
        val cellCount = (la1 - la0 + 1).toLong() * (lo1 - lo0 + 1).toLong()
        if (cellCount <= 0) return emptyList()
        if (cellCount > cells.size) {
            return all.filter { it.lat in south..north && it.lon in west..east }
        }
        val out = ArrayList<Camera>()
        for (a in la0..la1) for (b in lo0..lo1) {
            val list = cells[key(a, b)] ?: continue
            for (c in list) if (c.lat in south..north && c.lon in west..east) out += c
        }
        return out
    }

    /** [south, west, north, east] of all cameras, or null if empty. */
    fun bounds(): DoubleArray? {
        if (all.isEmpty()) return null
        var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
        for (c in all) {
            if (c.lat < s) s = c.lat; if (c.lat > n) n = c.lat
            if (c.lon < w) w = c.lon; if (c.lon > e) e = c.lon
        }
        return doubleArrayOf(s, w, n, e)
    }

    private fun cell(deg: Double): Int = floor(deg / CELL_DEG).toInt()
    private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    companion object {
        const val CELL_DEG = 0.005
    }
}
