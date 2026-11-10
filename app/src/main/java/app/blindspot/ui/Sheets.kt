package app.blindspot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.blindspot.data.AppSettings
import app.blindspot.data.Camera
import app.blindspot.data.FILTER_UNKNOWN
import app.blindspot.service.SessionHit
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    cameras: List<Camera>,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val operators = remember(cameras) {
        cameras.groupingBy { it.operator ?: FILTER_UNKNOWN }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    }
    val types = remember(cameras) {
        cameras.flatMap { it.type.ifEmpty { listOf(FILTER_UNKNOWN) } }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    }
    val visible = remember(cameras, settings) { cameras.count { settings.isVisible(it) } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            item {
                Text("Filter cameras", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Showing $visible of ${cameras.size} cameras on the map.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Hidden cameras don't trigger alerts", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.filtersAffectAlerts) "Monitoring ignores cameras hidden here."
                            else "Off: hidden cameras are only removed from the map and still trigger alerts.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.filtersAffectAlerts, onCheckedChange = { v -> onUpdate { it.copy(filtersAffectAlerts = v) } })
                }
                if (settings.filtersActive) {
                    OutlinedButton(onClick = { onUpdate { it.copy(hiddenOperators = emptySet(), hiddenTypes = emptySet()) } },
                        modifier = Modifier.padding(top = 6.dp)) { Text("Show all cameras") }
                }
                SectionTitle("Camera type")
            }
            items(types, key = { "t:" + it.key }) { (t, n) ->
                FilterRow(if (t == FILTER_UNKNOWN) null else t, "Unknown type", n, t !in settings.hiddenTypes) { show ->
                    onUpdate { it.copy(hiddenTypes = if (show) it.hiddenTypes - t else it.hiddenTypes + t) }
                }
            }
            item { SectionTitle("Operator") }
            items(operators, key = { "o:" + it.key }) { (op, n) ->
                FilterRow(if (op == FILTER_UNKNOWN) null else op, "Unknown operator", n, op !in settings.hiddenOperators) { show ->
                    onUpdate { it.copy(hiddenOperators = if (show) it.hiddenOperators - op else it.hiddenOperators + op) }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    HorizontalDivider()
}

@Composable
private fun FilterRow(label: String?, unknownLabel: String, count: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(
            label ?: unknownLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (label == null) FontStyle.Italic else FontStyle.Normal,
        )
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    hits: List<SessionHit>,
    camerasById: Map<Long, Camera>,
    monitoring: Boolean,
    onSelect: (Camera) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            item {
                Text("Cameras this session", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Cameras whose estimated coverage included you since monitoring " +
                        (if (monitoring) "started" else "last ran") +
                        ". Kept in memory only, never saved, and cleared when monitoring starts again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hits.isEmpty()) {
                    Text("None yet.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            items(hits, key = { it.cameraId }) { h ->
                val cam = camerasById[h.cameraId]
                Column(
                    Modifier.fillMaxWidth().clickable(enabled = cam != null) { cam?.let(onSelect) }.padding(vertical = 10.dp)
                ) {
                    Text(cam?.operator ?: if (cam == null) "Camera no longer in database" else "Unknown operator",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    val type = cam?.type?.joinToString(", ")?.ifEmpty { null }
                    Text(listOfNotNull(type, "ID ${h.cameraId}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val first = fmt.format(Date(h.firstMs))
                    val last = fmt.format(Date(h.lastMs))
                    Text(
                        (if (first == last) first else "$first – $last") +
                            if (h.entries > 1) " · entered ${h.entries} times" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                    if (hits.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear list") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
