package app.blindspot

import app.blindspot.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {
    @Test fun distanceKnownValue() {
        // ~111.2 km per degree of latitude
        assertEquals(111_195.0, GeoMath.distanceM(50.0, 15.0, 51.0, 15.0), 50.0)
    }
    @Test fun bearingCardinal() {
        assertEquals(0.0, GeoMath.bearingDeg(50.0, 15.0, 50.001, 15.0), 0.01)
        assertEquals(90.0, GeoMath.bearingDeg(50.0, 15.0, 50.0, 15.001), 0.01)
        assertEquals(180.0, GeoMath.bearingDeg(50.0, 15.0, 49.999, 15.0), 0.01)
        assertEquals(270.0, GeoMath.bearingDeg(50.0, 15.0, 50.0, 14.999), 0.01)
    }
    @Test fun destinationRoundTrip() {
        val p = GeoMath.destination(49.9486, 15.2650, 230.0, 40.0)
        assertEquals(40.0, GeoMath.distanceM(49.9486, 15.2650, p.lat, p.lon), 0.01)
        assertEquals(230.0, GeoMath.bearingDeg(49.9486, 15.2650, p.lat, p.lon), 0.01)
    }
    @Test fun angleDiffWraps() {
        assertEquals(20.0, GeoMath.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, GeoMath.angleDiffDeg(0.0, 180.0), 1e-9)
    }
}
