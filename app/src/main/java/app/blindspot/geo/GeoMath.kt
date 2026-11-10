package app.blindspot.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Spherical-earth geodesy. Accurate to well under 0.5 % at camera-detection distances. */
object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north, in [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDeg(Math.toDegrees(atan2(y, x)))
    }

    /** Destination reached from (lat, lon) travelling [distanceM] along [bearingDeg]. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), normalizeLon(Math.toDegrees(l2)))
    }

    fun normalizeDeg(d: Double): Double {
        val r = d % 360.0
        return if (r < 0) r + 360.0 else r
    }

    private fun normalizeLon(lon: Double): Double {
        var l = (lon + 540.0) % 360.0 - 180.0
        if (l < -180.0) l += 360.0
        return l
    }

    /** Smallest absolute angle between two bearings, in [0, 180]. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = abs(normalizeDeg(a) - normalizeDeg(b))
        return if (d > 180.0) 360.0 - d else d
    }

    /** Approximate area of a small polygon in square metres (local equirectangular projection). */
    fun polygonAreaM2(points: List<LatLon>): Double {
        if (points.size < 3) return 0.0
        val lat0 = points.sumOf { it.lat } / points.size
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_540.0
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += (a.lon * kx) * (b.lat * ky) - (b.lon * kx) * (a.lat * ky)
        }
        return abs(sum) / 2.0
    }
}
