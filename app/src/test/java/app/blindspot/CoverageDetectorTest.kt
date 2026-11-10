package app.blindspot

import app.blindspot.data.Camera
import app.blindspot.detect.CoverageDetector
import app.blindspot.detect.DetectorConfig
import app.blindspot.detect.Fix
import app.blindspot.detect.FixQuality
import app.blindspot.detect.Transition
import app.blindspot.geo.CameraIndex
import app.blindspot.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageDetectorTest {
    private val lat0 = 49.9486
    private val lon0 = 15.2650
    private fun cam(id: Long, lat: Double = lat0, lon: Double = lon0, dirs: List<Double> = emptyList()) =
        Camera(id, lat, lon, dirs, emptyList(), null, null, null, null)

    private val cfg = DetectorConfig(fovDeg = 110.0, maxDistanceM = 40.0, unknownRadiusM = 40.0, marginM = 8.0,
        maxAccuracyM = 35.0, enterConfirmations = 2, exitConfirmations = 3)

    private var t = 0L
    /** A fix at [dist] metres from (lat0, lon0) on [bearing]. */
    private fun at(bearing: Double, dist: Double, acc: Float = 5f): Fix {
        val p = GeoMath.destination(lat0, lon0, bearing, dist)
        t += 3000
        return Fix(p.lat, p.lon, acc, t)
    }

    @Test fun coneGeometry() {
        val c = cam(1, dirs = listOf(90.0))
        assertTrue(CoverageDetector.isCovered(c, 30.0, 90.0, cfg))
        assertTrue(CoverageDetector.isCovered(c, 30.0, 90.0 + 54.0, cfg))
        assertFalse(CoverageDetector.isCovered(c, 30.0, 90.0 + 56.0, cfg))
        assertFalse(CoverageDetector.isCovered(c, 30.0, 270.0, cfg))   // right behind the camera
        assertFalse(CoverageDetector.isCovered(c, 45.0, 90.0, cfg))    // too far
        assertFalse(CoverageDetector.isCovered(c, 3.0, 270.0, cfg))    // close, but behind: not auto-covered
    }

    @Test fun multipleDirections() {
        val c = cam(1, dirs = listOf(25.0, 230.0))
        assertTrue(CoverageDetector.isCovered(c, 20.0, 230.0, cfg))
        assertTrue(CoverageDetector.isCovered(c, 20.0, 25.0, cfg))
        assertFalse(CoverageDetector.isCovered(c, 20.0, 130.0, cfg))
    }

    @Test fun unknownDirectionIsCircle() {
        val c = cam(1)
        assertTrue(CoverageDetector.isCovered(c, 39.0, 123.0, cfg))
        assertFalse(CoverageDetector.isCovered(c, 41.0, 123.0, cfg))
    }

    @Test fun enterNeedsConfirmationAndFiresOnce() {
        val d = CoverageDetector(CameraIndex(listOf(cam(1))), cfg)
        assertEquals(Transition.NONE, d.process(at(0.0, 100.0)).transition)
        assertEquals(Transition.NONE, d.process(at(0.0, 30.0)).transition)      // 1st inside reading
        assertEquals(Transition.ENTERED, d.process(at(0.0, 28.0)).transition)   // confirmed
        repeat(10) { assertEquals(Transition.NONE, d.process(at(0.0, 20.0 + it)).transition) }
        assertTrue(d.confirmedInside)
    }

    @Test fun singleOutlierDoesNotEnter() {
        val d = CoverageDetector(CameraIndex(listOf(cam(1))), cfg)
        d.process(at(0.0, 60.0))
        d.process(at(0.0, 35.0))   // one reading inside
        d.process(at(0.0, 60.0))   // back out, resets
        d.process(at(0.0, 35.0))
        assertFalse(d.confirmedInside)
    }

    @Test fun boundaryJitterDoesNotFlap() {
        val d = CoverageDetector(CameraIndex(listOf(cam(1))), cfg)
        d.process(at(0.0, 35.0)); d.process(at(0.0, 35.0))
        assertTrue(d.confirmedInside)
        // Jitter around the 40 m edge, within the hysteresis margin
        var events = 0
        for (r in listOf(42.0, 38.0, 45.0, 41.0, 46.0, 39.0, 44.0, 47.0, 43.0)) {
            if (d.process(at(0.0, r)).transition != Transition.NONE) events++
        }
        assertEquals(0, events)
        assertTrue(d.confirmedInside)
    }

    @Test fun exitNeedsThreeReadings() {
        val d = CoverageDetector(CameraIndex(listOf(cam(1))), cfg)
        d.process(at(0.0, 10.0)); d.process(at(0.0, 10.0))
        assertEquals(Transition.NONE, d.process(at(0.0, 80.0)).transition)
        assertEquals(Transition.NONE, d.process(at(0.0, 85.0)).transition)
        assertEquals(Transition.EXITED, d.process(at(0.0, 90.0)).transition)
        assertFalse(d.confirmedInside)
    }

    @Test fun inaccurateReadingsIgnored() {
        val d = CoverageDetector(CameraIndex(listOf(cam(1))), cfg)
        d.process(at(0.0, 100.0))
        val r1 = d.process(at(0.0, 10.0, acc = 80f))
        val r2 = d.process(at(0.0, 10.0, acc = 80f))
        assertEquals(FixQuality.INACCURATE, r1.quality)
        assertEquals(FixQuality.INACCURATE, r2.quality)
        assertFalse(d.confirmedInside)
    }

    @Test fun gpsJumpRejectedThenAcceptedIfPersistent() {
        val d = CoverageDetector(CameraIndex(listOf(cam(1))), cfg)
        d.process(at(0.0, 2000.0))
        // Sudden 2 km jump in 3 s (667 m/s) onto the camera
        val j1 = d.process(at(0.0, 5.0))
        assertEquals(FixQuality.JUMP, j1.quality)
        assertEquals(FixQuality.JUMP, d.process(at(0.0, 5.0)).quality)
        // Third consistent reading at the new place: accepted
        assertEquals(FixQuality.GOOD, d.process(at(0.0, 5.0)).quality)
    }

    @Test fun overlappingCamerasNoExtraEvents() {
        // Two unknown-direction cameras 50 m apart along north; walk from one into the other.
        val c2 = GeoMath.destination(lat0, lon0, 0.0, 50.0)
        val d = CoverageDetector(CameraIndex(listOf(cam(1), cam(2, c2.lat, c2.lon))), cfg)
        val events = ArrayList<Transition>()
        var dist = -80.0
        while (dist <= 130.0) {
            val f = if (dist >= 0) at(0.0, dist) else at(180.0, -dist)
            val r = d.process(f)
            if (r.transition != Transition.NONE) events += r.transition
            dist += 4.0
        }
        assertEquals(listOf(Transition.ENTERED, Transition.EXITED), events)
    }

    @Test fun coveringSetTracksBothCameras() {
        val c2 = GeoMath.destination(lat0, lon0, 0.0, 30.0)
        val d = CoverageDetector(CameraIndex(listOf(cam(1), cam(2, c2.lat, c2.lon))), cfg)
        d.process(at(0.0, 15.0))
        val r = d.process(at(0.0, 15.0))
        assertEquals(setOf(1L, 2L), r.coveringIds)
    }
}
