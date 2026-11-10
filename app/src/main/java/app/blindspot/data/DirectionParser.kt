package app.blindspot.data

/**
 * Parses camera viewing directions as they appear in JSON files and OpenStreetMap tags:
 * plain degrees ("230"), several values ("25;230"), compass points ("NE"), and ranges
 * ("90-180", interpreted as the middle of the range).
 */
object DirectionParser {

    private val COMPASS = mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5,
        "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
        "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
        "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
    )

    data class Result(val directions: List<Double>, val invalidParts: List<String>)

    fun parse(text: String): Result {
        val out = ArrayList<Double>()
        val bad = ArrayList<String>()
        for (raw in text.split(';', ',')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val v = parseSingle(part)
            if (v == null) bad += part else out += v
        }
        return Result(out, bad)
    }

    fun parseSingle(part: String): Double? {
        val p = part.trim().removeSuffix("°").trim()
        p.toDoubleOrNull()?.let { return if (it.isFinite()) normalize(it) else null }
        COMPASS[p.uppercase()]?.let { return it }
        // Range such as "90-180" (a leading minus sign is a negative number, not a range).
        val dash = p.indexOf('-', startIndex = 1)
        if (dash > 0) {
            val a = p.substring(0, dash).trim().toDoubleOrNull()
            val b = p.substring(dash + 1).trim().toDoubleOrNull()
            if (a != null && b != null && a.isFinite() && b.isFinite()) {
                val na = normalize(a)
                var nb = normalize(b)
                if (nb < na) nb += 360.0
                return normalize((na + nb) / 2.0)
            }
        }
        return null
    }

    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        val n = if (r < 0) r + 360.0 else r
        return if (n >= 360.0) 0.0 else n
    }
}
