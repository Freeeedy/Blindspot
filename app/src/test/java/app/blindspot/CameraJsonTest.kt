package app.blindspot

import app.blindspot.data.CameraJson
import app.blindspot.data.DirectionParser
import app.blindspot.data.ParseOutcome
import app.blindspot.importer.OverpassImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraJsonTest {
    private fun ok(text: String) = (CameraJson.parse(text) as ParseOutcome.Ok).report

    @Test fun parsesBundledDatabase() {
        val f = listOf(File("src/main/assets/cameras.json"), File("app/src/main/assets/cameras.json")).first { it.exists() }
        val r = ok(f.readText())
        assertEquals(62, r.cameras.size)
        assertEquals(0, r.invalidCount)
        val c = r.cameras.first { it.id == 2718303839L }
        assertEquals(listOf(25.0, 230.0), c.directions)
        assertEquals(listOf("dome", "fixed"), c.type)
        assertEquals("Městská policie Kutná Hora", c.operator)   // kept verbatim
    }

    @Test fun invalidAndDuplicateEntries() {
        val r = ok("""[
            {"id": 1, "lat": 50.0, "lon": 15.0},
            {"id": 1, "lat": 50.1, "lon": 15.1},
            {"id": "x", "lat": 50.0, "lon": 15.0},
            {"id": 3, "lat": 95.0, "lon": 15.0},
            {"id": 4, "lon": 15.0},
            "garbage",
            {"id": 5, "lat": 50.0, "lon": 15.0, "directions": [90, "NE", "bad"], "foo": 1}
        ]""")
        assertEquals(7, r.totalEntries)
        assertEquals(2, r.cameras.size)
        assertEquals(1, r.duplicateCount)
        assertEquals(4, r.invalidCount)
        assertEquals(listOf(90.0, 45.0), r.cameras[1].directions)
        assertEquals(1, r.unsupportedFields["foo"])
        assertTrue(r.warningCount >= 1)
        assertEquals(2, r.missingFields["operator"])
    }

    @Test fun notJsonFails() {
        assertTrue(CameraJson.parse("hello") is ParseOutcome.Failed)
        assertTrue(CameraJson.parse("") is ParseOutcome.Failed)
        assertTrue(CameraJson.parse("{\"a\":1}") is ParseOutcome.Failed)
    }

    @Test fun roundTrip() {
        val src = """[{"id": 7, "lat": 49.1, "lon": 15.2, "directions": [10.5], "type": ["dome"], "operator": "Žluťoučký",
            "mount": null, "zone": "street", "check_date": "2025-01-01", "osm_tags": {"man_made": "surveillance"}}]"""
        val a = ok(src).cameras
        val b = ok(CameraJson.serialize(a)).cameras
        assertEquals(CameraJson.contentKey(a[0]), CameraJson.contentKey(b[0]))
        assertNull(b[0].mount)
        assertEquals("Žluťoučký", b[0].operator)
    }

    @Test fun directionParser() {
        assertEquals(listOf(25.0, 230.0), DirectionParser.parse("25;230").directions)
        assertEquals(listOf(315.0), DirectionParser.parse("NW").directions)
        assertEquals(listOf(135.0), DirectionParser.parse("90-180").directions)
        assertEquals(listOf(0.0), DirectionParser.parse("350-10").directions)
        assertEquals(listOf(350.0), DirectionParser.parse("-10").directions)
    }

    @Test fun overpassConversion() {
        val body = """{"elements":[
            {"type":"node","id":123,"lat":49.9,"lon":15.2,"tags":{"man_made":"surveillance","surveillance:type":"camera",
             "camera:direction":"45;225","camera:type":"fixed","camera:mount":"pole","surveillance:zone":"traffic",
             "operator":"Město","check_date":"2024-05-01"}},
            {"type":"node","id":124,"lat":49.91,"lon":15.21,"tags":{"man_made":"surveillance"}}
        ]}"""
        val r = CameraJson.validate(OverpassImporter.convert(body))
        assertEquals(2, r.cameras.size)
        val c = r.cameras[0]
        assertEquals(listOf(45.0, 225.0), c.directions)
        assertEquals(listOf("fixed"), c.type)
        assertEquals("pole", c.mount)
        assertEquals("traffic", c.zone)
        assertEquals("Město", c.operator)
        assertTrue(r.unsupportedFields.isEmpty())
        assertTrue(r.cameras[1].directions.isEmpty())
    }
}

class MergeTest {
    @org.junit.Test fun mergeKeepsExistingDetails() {
        val old = app.blindspot.data.Camera(1, 50.0, 15.0, listOf(90.0), listOf("dome"), "Op", "pole", "street", "2025-01-01")
        val new = app.blindspot.data.Camera(1, 50.0001, 15.0, emptyList(), emptyList(), null, "wall", null, null,
            mapOf("osm_tags" to "x"))
        val m = app.blindspot.data.CameraRepository.mergeCamera(old, new)
        org.junit.Assert.assertEquals(50.0001, m.lat, 0.0)
        org.junit.Assert.assertEquals(listOf(90.0), m.directions)
        org.junit.Assert.assertEquals("Op", m.operator)
        org.junit.Assert.assertEquals("wall", m.mount)
        org.junit.Assert.assertEquals("2025-01-01", m.checkDate)
        org.junit.Assert.assertEquals("x", m.extras["osm_tags"])
    }
}
