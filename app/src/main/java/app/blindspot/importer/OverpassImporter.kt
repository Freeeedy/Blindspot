package app.blindspot.importer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.blindspot.BuildConfig
import app.blindspot.data.CameraJson
import app.blindspot.data.DirectionParser
import app.blindspot.data.ValidationReport
import app.blindspot.geo.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class ImportException(message: String, val retryable: Boolean = false) : Exception(message)

data class ImportProgress(val stage: String, val detail: String? = null)

/**
 * Downloads CCTV cameras inside a user-selected polygon from the Overpass API (read-only
 * OpenStreetMap query service) and converts them to Blindspot's camera format.
 *
 * Usage-policy notes: one request per import, an identifying User-Agent, a server-side timeout,
 * a cap on the selectable area (enforced by the UI), and a fallback server only when the
 * primary one is busy or unreachable. Only the selected polygon is sent, never the user's location.
 */
class OverpassImporter(private val context: Context) {

    suspend fun fetchCameras(polygon: List<LatLon>, onProgress: (ImportProgress) -> Unit): ValidationReport {
        if (polygon.size < 3) throw ImportException("Select an area with at least 3 points.")
        if (!isOnline()) {
            throw ImportException(
                "No internet connection. Importing from OpenStreetMap needs internet; monitoring itself works offline."
            )
        }
        val query = buildQuery(polygon)
        val body = download(query, onProgress)
        onProgress(ImportProgress("Processing data…"))
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.Default) { CameraJson.validate(convert(body)) }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun download(query: String, onProgress: (ImportProgress) -> Unit): String =
        withContext(Dispatchers.IO) {
            var lastError: ImportException? = null
            for ((i, server) in SERVERS.withIndex()) {
                ensureActive()
                val host = URL(server).host
                onProgress(ImportProgress("Contacting $host…", if (i > 0) "Primary server unavailable, trying a fallback" else null))
                try {
                    return@withContext request(server, query) { bytes ->
                        ensureActive()
                        onProgress(ImportProgress("Downloading camera data…", "${formatBytes(bytes)} received from $host"))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImportException) {
                    lastError = e
                    if (!e.retryable) throw e
                } catch (e: SocketTimeoutException) {
                    lastError = ImportException("The server did not respond in time.", retryable = true)
                } catch (e: IOException) {
                    lastError = ImportException("Network error: ${e.message ?: e.javaClass.simpleName}", retryable = true)
                }
            }
            throw lastError ?: ImportException("No OpenStreetMap server available.")
        }

    private fun request(server: String, query: String, onBytes: (Long) -> Unit): String {
        val conn = (URL(server).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 150_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            when {
                code == 429 -> throw ImportException("The OpenStreetMap server is busy (rate limited). Try again in a minute.", true)
                code == 504 || code == 503 -> throw ImportException("The OpenStreetMap server is overloaded (HTTP $code). Try again later or select a smaller area.", true)
                code == 400 -> throw ImportException("The server rejected the request (HTTP 400). Try a simpler area shape.")
                code !in 200..299 -> throw ImportException("Unexpected server response (HTTP $code).", code >= 500)
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                var lastReport = -1L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total > MAX_RESPONSE_BYTES) throw ImportException("Too much data. Select a smaller area.")
                    if (total - lastReport >= 32 * 1024) { onBytes(total); lastReport = total }
                }
                onBytes(total)
            }
            return out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
        val USER_AGENT = "Blindspot/${BuildConfig.VERSION_NAME} (Android; offline CCTV awareness app)"
        private const val MAX_RESPONSE_BYTES = 64L * 1024 * 1024

        fun buildQuery(polygon: List<LatLon>): String {
            val poly = polygon.joinToString(" ") {
                String.format(Locale.US, "%.6f %.6f", it.lat, it.lon)
            }
            return """
                [out:json][timeout:120];
                (
                  node["man_made"="surveillance"]["surveillance:type"~"^(camera|ALPR)$",i](poly:"$poly");
                  node["man_made"="surveillance"][!"surveillance:type"](poly:"$poly");
                );
                out body qt;
            """.trimIndent()
        }

        /** Converts an Overpass JSON response to an array in Blindspot's camera format. */
        fun convert(body: String): JSONArray {
            val root = try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw ImportException("The server returned data that could not be read.", true)
            }
            val elements = root.optJSONArray("elements") ?: JSONArray()
            val remark = root.optString("remark", "")
            if (elements.length() == 0 && remark.contains("error", ignoreCase = true)) {
                throw ImportException("The server could not complete the query: $remark", true)
            }
            val out = JSONArray()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                if (el.optString("type") != "node" || !el.has("lat") || !el.has("lon")) continue
                val tags = el.optJSONObject("tags") ?: JSONObject()
                out.put(toCamera(el, tags))
            }
            return out
        }

        private fun toCamera(el: JSONObject, tags: JSONObject): JSONObject {
            fun tag(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { k -> tags.optString(k, "").takeIf { it.isNotBlank() } }

            val dirText = tag("camera:direction", "direction", "surveillance:direction")
            val directions = JSONArray()
            dirText?.let { DirectionParser.parse(it).directions.forEach { d -> directions.put(d) } }
            val types = JSONArray()
            tag("camera:type")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { types.put(it) }

            return JSONObject().apply {
                put("id", el.getLong("id"))
                put("lat", el.getDouble("lat"))
                put("lon", el.getDouble("lon"))
                put("directions", directions)
                put("type", types)
                put("operator", tag("operator") ?: JSONObject.NULL)
                put("mount", tag("camera:mount") ?: JSONObject.NULL)
                put("zone", tag("surveillance:zone") ?: JSONObject.NULL)
                put("check_date", tag("check_date", "survey:date") ?: JSONObject.NULL)
                // Keep every original OpenStreetMap tag so no metadata is lost.
                put("osm_tags", tags)
            }
        }

        fun formatBytes(b: Long): String = when {
            b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024.0)
            b >= 1024 -> "${b / 1024} KB"
            else -> "$b B"
        }
    }
}
