package app.blindspot.data

/**
 * One camera entry, matching the cameras.json structure.
 *
 * String values are kept exactly as they appear in the source JSON (no translation or
 * normalisation), so the UI can show them verbatim.
 *
 * [extras] holds any additional JSON fields (for example `osm_tags` from an OpenStreetMap
 * import, or unknown fields from a custom file) so they survive save/load round trips.
 * Values are org.json types (String, Number, Boolean, JSONObject, JSONArray, JSONObject.NULL).
 */
data class Camera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val directions: List<Double>,
    val type: List<String>,
    val operator: String?,
    val mount: String?,
    val zone: String?,
    val checkDate: String?,
    val extras: Map<String, Any?> = emptyMap(),
) {
    val hasDirection: Boolean get() = directions.isNotEmpty()

    /** Added by the user on the map (stored with `"source": "user"` and a negative id). */
    val isUserAdded: Boolean get() = extras[SOURCE_KEY] == SOURCE_USER

    companion object {
        const val SOURCE_KEY = "source"
        const val SOURCE_USER = "user"
    }
}
