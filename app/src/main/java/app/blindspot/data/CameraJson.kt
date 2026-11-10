package app.blindspot.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs

/** Detailed result of validating a camera JSON document. */
class ValidationReport {
    var totalEntries = 0
    val cameras = ArrayList<Camera>()
    var invalidCount = 0
    val invalidReasons = ArrayList<String>()
    var duplicateCount = 0
    val duplicateIds = ArrayList<Long>()
    /** Optional fields that were absent or null, with the number of entries affected. */
    val missingFields = sortedMapOf<String, Int>()
    /** Fields that are not part of the camera format (kept as additional data). */
    val unsupportedFields = sortedMapOf<String, Int>()
    var warningCount = 0
    val warnings = ArrayList<String>()

    internal fun invalid(reason: String) {
        invalidCount++
        if (invalidReasons.size < MAX_DETAILS) invalidReasons += reason
    }

    internal fun warn(msg: String) {
        warningCount++
        if (warnings.size < MAX_DETAILS) warnings += msg
    }

    internal fun missing(field: String) {
        missingFields[field] = (missingFields[field] ?: 0) + 1
    }

    internal fun unsupported(field: String) {
        unsupportedFields[field] = (unsupportedFields[field] ?: 0) + 1
    }

    companion object {
        const val MAX_DETAILS = 50
    }
}

sealed class ParseOutcome {
    data class Ok(val report: ValidationReport) : ParseOutcome()
    data class Failed(val message: String) : ParseOutcome()
}

/** Reading, validating and writing the cameras.json format. */
object CameraJson {

    private const val BOM = "\uFEFF"

    val CORE_FIELDS = listOf("id", "lat", "lon", "directions", "type", "operator", "mount", "zone", "check_date")

    /** Extra fields Blindspot itself writes; recognised, so they are not reported as unsupported. */
    val KNOWN_EXTRA_FIELDS = setOf("osm_tags", Camera.SOURCE_KEY)

    fun parse(text: String): ParseOutcome {
        val cleaned = text.removePrefix(BOM).trim()
        if (cleaned.isEmpty()) return ParseOutcome.Failed("The file is empty.")
        val root = try {
            JSONTokener(cleaned).nextValue()
        } catch (e: JSONException) {
            return ParseOutcome.Failed("The file is not valid JSON (${e.message}).")
        }
        val array: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("cameras")
                ?: if (root.has("lat") && root.has("lon")) JSONArray().put(root)
                else return ParseOutcome.Failed("Expected a JSON array of camera objects.")
            else -> return ParseOutcome.Failed("Expected a JSON array of camera objects.")
        }
        return ParseOutcome.Ok(validate(array))
    }

    fun validate(array: JSONArray): ValidationReport {
        val report = ValidationReport()
        val seen = HashSet<Long>()
        for (i in 0 until array.length()) {
            report.totalEntries++
            val obj = array.opt(i) as? JSONObject
            if (obj == null) {
                report.invalid("Entry #${i + 1}: not a JSON object")
                continue
            }
            // Duplicates are detected before parsing so their fields don't skew the statistics.
            val peekId = toLong(obj.opt("id"))
            if (peekId != null && peekId in seen) {
                report.duplicateCount++
                if (report.duplicateIds.size < ValidationReport.MAX_DETAILS) report.duplicateIds += peekId
                continue
            }
            val cam = parseEntry(obj, i, report) ?: continue
            seen += cam.id
            report.cameras += cam
        }
        return report
    }

    private fun parseEntry(o: JSONObject, index: Int, r: ValidationReport): Camera? {
        val entry = "Entry #${index + 1}"
        val id = toLong(o.opt("id"))
        if (id == null) {
            r.invalid("$entry: missing or invalid \"id\" (must be a whole number)")
            return null
        }
        val label = "$entry (id $id)"
        val lat = toDouble(o.opt("lat"))
        if (lat == null || lat < -90.0 || lat > 90.0) {
            r.invalid("$label: missing or invalid \"lat\" (must be a number between -90 and 90)")
            return null
        }
        val lon = toDouble(o.opt("lon"))
        if (lon == null || lon < -180.0 || lon > 180.0) {
            r.invalid("$label: missing or invalid \"lon\" (must be a number between -180 and 180)")
            return null
        }

        val directions = if (!o.has("directions") || o.isNull("directions")) {
            r.missing("directions"); emptyList()
        } else parseDirections(o.get("directions"), label, r)

        val type = if (!o.has("type") || o.isNull("type")) {
            r.missing("type"); emptyList()
        } else parseStringList(o.get("type"), label, r)
        if (o.has("type") && !o.isNull("type") && type.isEmpty()) r.missing("type")

        val operator = optionalString(o, "operator", label, r)
        val mount = optionalString(o, "mount", label, r)
        val zone = optionalString(o, "zone", label, r)
        val checkDate = optionalString(o, "check_date", label, r)

        val extras = LinkedHashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in CORE_FIELDS) continue
            if (k !in KNOWN_EXTRA_FIELDS) r.unsupported(k)
            extras[k] = o.opt(k)
        }

        return Camera(id, lat, lon, directions, type, operator, mount, zone, checkDate, extras)
    }

    private fun parseDirections(v: Any, label: String, r: ValidationReport): List<Double> {
        val out = ArrayList<Double>()
        val bad = ArrayList<String>()
        fun add(x: Any?) {
            when (x) {
                null, JSONObject.NULL -> Unit
                is Number -> {
                    val d = x.toDouble()
                    if (d.isFinite()) out += DirectionParser.normalize(d) else bad += x.toString()
                }
                is String -> {
                    val res = DirectionParser.parse(x)
                    out += res.directions
                    bad += res.invalidParts
                }
                else -> bad += x.toString()
            }
        }
        if (v is JSONArray) for (i in 0 until v.length()) add(v.opt(i)) else add(v)
        if (bad.isNotEmpty()) r.warn("$label: ignored invalid direction value(s): ${bad.joinToString(", ")}")
        return out.distinct()
    }

    private fun parseStringList(v: Any, label: String, r: ValidationReport): List<String> = when (v) {
        is String -> v.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            when (val x = v.opt(i)) {
                null, JSONObject.NULL -> null
                is String -> x
                else -> x.toString()
            }
        }
        else -> {
            r.warn("$label: \"type\" should be a list of text values; converted ${v}")
            listOf(v.toString())
        }
    }

    private fun optionalString(o: JSONObject, key: String, label: String, r: ValidationReport): String? {
        if (!o.has(key) || o.isNull(key)) {
            r.missing(key); return null
        }
        return when (val v = o.get(key)) {
            is String -> v
            is Number, is Boolean -> {
                r.warn("$label: \"$key\" should be text; converted $v")
                v.toString()
            }
            else -> {
                r.warn("$label: \"$key\" has an unsupported value and was ignored")
                null
            }
        }
    }

    private fun toLong(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite() && d % 1.0 == 0.0 && abs(d) < 9.0e15) d.toLong() else null
        }
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    private fun toDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    fun toJson(c: Camera): JSONObject {
        val o = JSONObject()
        o.put("id", c.id)
        o.put("lat", c.lat)
        o.put("lon", c.lon)
        o.put("directions", JSONArray().apply { c.directions.forEach { put(it) } })
        o.put("type", JSONArray().apply { c.type.forEach { put(it) } })
        o.put("operator", c.operator ?: JSONObject.NULL)
        o.put("mount", c.mount ?: JSONObject.NULL)
        o.put("zone", c.zone ?: JSONObject.NULL)
        o.put("check_date", c.checkDate ?: JSONObject.NULL)
        for ((k, v) in c.extras) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    fun serialize(cameras: List<Camera>): String {
        val arr = JSONArray()
        cameras.forEach { arr.put(toJson(it)) }
        return arr.toString(2)
    }

    /** Stable textual form used to decide whether two entries with the same id differ. */
    fun contentKey(c: Camera): String = toJson(c).toString()
}
