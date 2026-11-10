package app.blindspot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.blindspot.data.Camera
import app.blindspot.geo.GeoMath
import app.blindspot.geo.LatLon
import java.time.LocalDate
import kotlin.math.roundToInt

/** Camera being added or edited on the map. */
data class CameraDraft(
    val original: Camera?,
    val lat: Double,
    val lon: Double,
    val directions: List<Double> = emptyList(),
    val types: List<String> = emptyList(),
    val mount: String? = null,
    val zone: String? = null,
    val operator: String = "",
    /** When true, the next map tap adds a direction instead of replacing them. */
    val addingDirection: Boolean = false,
) {
    val isNew: Boolean get() = original == null

    /** Applies a map tap: point the camera towards the tapped location. */
    fun withTap(p: LatLon): CameraDraft {
        if (GeoMath.distanceM(lat, lon, p.lat, p.lon) < 1.0) return this
        val bearing = GeoMath.bearingDeg(lat, lon, p.lat, p.lon).roundToInt().toDouble() % 360.0
        val dirs = if (addingDirection || directions.isEmpty()) directions + bearing
        else directions.dropLast(1) + bearing
        return copy(directions = dirs.distinct().take(MAX_DIRECTIONS), addingDirection = false)
    }

    fun preview(): Camera = Camera(original?.id ?: 0L, lat, lon, directions, types, null, null, null, null)

    fun toCamera(newId: () -> Long): Camera = Camera(
        id = original?.id ?: newId(),
        lat = lat,
        lon = lon,
        directions = directions,
        type = types,
        operator = operator.trim().ifEmpty { null },
        mount = mount,
        zone = zone,
        checkDate = LocalDate.now().toString(),
        extras = LinkedHashMap(original?.extras ?: emptyMap()).apply { put(Camera.SOURCE_KEY, Camera.SOURCE_USER) },
    )

    companion object {
        const val MAX_DIRECTIONS = 4
        val TYPES = listOf("fixed", "dome", "panning", "ALPR")
        val MOUNTS = listOf("building", "wall", "pole", "street_lamp", "ceiling")
        val ZONES = listOf("street", "traffic", "parking", "entrance", "shop", "town", "building")

        fun from(c: Camera) = CameraDraft(c, c.lat, c.lon, c.directions, c.type, c.mount, c.zone, c.operator ?: "")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddCameraPanel(
    draft: CameraDraft,
    operatorSuggestions: List<String>,
    onChange: (CameraDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (draft.isNew) "New camera" else "Edit camera",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onSave) { Text("Save") }
            }
            Text(
                if (draft.addingDirection) "Tap the map where the extra direction points."
                else "Tap the map to point the camera. Long-press to move it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                Label("Viewing direction")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (draft.directions.isEmpty()) {
                        Text("Unknown (assumed 360°)", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 10.dp))
                    }
                    draft.directions.forEach { d ->
                        InputChip(
                            selected = false,
                            onClick = { onChange(draft.copy(directions = draft.directions - d)) },
                            label = { Text("${formatDeg(d)}° ${compass(d)}") },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", Modifier.size(16.dp)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.directions.isNotEmpty() && draft.directions.size < CameraDraft.MAX_DIRECTIONS) {
                        OutlinedButton(onClick = { onChange(draft.copy(addingDirection = !draft.addingDirection)) }) {
                            Text(if (draft.addingDirection) "Cancel adding" else "Add direction")
                        }
                    }
                    if (draft.directions.isNotEmpty()) {
                        TextButton(onClick = { onChange(draft.copy(directions = emptyList(), addingDirection = false)) }) {
                            Text("Direction unknown")
                        }
                    }
                }

                Label("Camera type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CameraDraft.TYPES.forEach { t ->
                        FilterChip(selected = t in draft.types, label = { Text(t) }, onClick = {
                            onChange(draft.copy(types = if (t in draft.types) draft.types - t else draft.types + t))
                        })
                    }
                }
                Label("Mount location")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CameraDraft.MOUNTS.forEach { m ->
                        FilterChip(selected = draft.mount == m, label = { Text(m) }, onClick = {
                            onChange(draft.copy(mount = if (draft.mount == m) null else m))
                        })
                    }
                }
                Label("Monitored zone")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CameraDraft.ZONES.forEach { z ->
                        FilterChip(selected = draft.zone == z, label = { Text(z) }, onClick = {
                            onChange(draft.copy(zone = if (draft.zone == z) null else z))
                        })
                    }
                }
                Label("Operator")
                OutlinedTextField(
                    value = draft.operator,
                    onValueChange = { onChange(draft.copy(operator = it)) },
                    placeholder = { Text("Unknown") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                val q = draft.operator.trim()
                val matches = operatorSuggestions
                    .filter { it != q && (q.isEmpty() || it.contains(q, ignoreCase = true)) }
                    .take(4)
                if (matches.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        matches.forEach { op ->
                            FilterChip(selected = false, label = { Text(op) }, onClick = { onChange(draft.copy(operator = op)) })
                        }
                    }
                }
                Text(
                    "Saved on this phone together with your imported cameras. Position: " +
                        String.format(java.util.Locale.US, "%.6f, %.6f", draft.lat, draft.lon),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

private fun compass(d: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((d % 360) + 22.5) / 45).toInt() % 8]
}
