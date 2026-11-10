package app.blindspot

import app.blindspot.data.AppSettings
import app.blindspot.data.Camera
import app.blindspot.data.FILTER_UNKNOWN
import app.blindspot.geo.GeoMath
import app.blindspot.geo.LatLon
import app.blindspot.maps.MapEntry
import app.blindspot.ui.CameraDraft
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NewFeaturesTest {
    private fun cam(op: String?, vararg types: String) =
        Camera(1, 50.0, 15.0, emptyList(), types.toList(), op, null, null, null)

    @Test fun filters() {
        val s = AppSettings(hiddenOperators = setOf("Shop", FILTER_UNKNOWN), hiddenTypes = setOf("dome"))
        assertFalse(s.isVisible(cam("Shop", "fixed")))
        assertFalse(s.isVisible(cam(null, "fixed")))          // unknown operator hidden
        assertFalse(s.isVisible(cam("Police", "dome")))       // only type hidden
        assertTrue(s.isVisible(cam("Police", "dome", "fixed"))) // has a visible type
        assertTrue(s.isVisible(cam("Police")))                // unknown type not hidden
        assertFalse(AppSettings(hiddenTypes = setOf(FILTER_UNKNOWN)).isVisible(cam("Police")))
    }

    @Test fun draftTapSetsDirection() {
        val d = CameraDraft(null, 50.0, 15.0)
        val east = GeoMath.destination(50.0, 15.0, 90.0, 20.0)
        val d1 = d.withTap(LatLon(east.lat, east.lon))
        assertEquals(listOf(90.0), d1.directions)
        val south = GeoMath.destination(50.0, 15.0, 180.0, 20.0)
        assertEquals(listOf(180.0), d1.withTap(LatLon(south.lat, south.lon)).directions)  // replaces
        val d2 = d1.copy(addingDirection = true).withTap(LatLon(south.lat, south.lon))
        assertEquals(listOf(90.0, 180.0), d2.directions)                                    // adds
        val c = d2.copy(operator = "  Me ").toCamera { -5L }
        assertEquals(-5L, c.id)
        assertEquals("Me", c.operator)
        assertTrue(c.isUserAdded)
    }

    @Test fun catalogPicksCzechForKutnaHora() {
        val f = listOf(File("src/main/assets/map_catalog.json"), File("app/src/main/assets/map_catalog.json")).first { it.exists() }
        val arr = JSONArray(f.readText())
        val entries = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MapEntry(o.getString("path"), o.getLong("size"), o.getDouble("s"), o.getDouble("w"), o.getDouble("n"), o.getDouble("e"))
        }
        val best = entries.filter { it.contains(49.9486, 15.2650) }.minBy { it.areaDeg }
        assertEquals("europe/czech-republic.map", best.path)
        assertEquals("Czech Republic", best.displayName)
        assertEquals("Bayern, Germany", entries.first { it.path == "europe/germany/bayern.map" }.displayName)
    }
}
