package app.blindspot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.blindspot.maps.MapCatalog
import app.blindspot.maps.MapDownloadState
import app.blindspot.maps.MapEntry
import app.blindspot.maps.OfflineMaps
import app.blindspot.service.MonitorState
import java.io.File

/** Settings block for downloading and managing offline vector maps. */
@Composable
fun OfflineMapsSection(vm: AppViewModel) {
    val ctx = LocalContext.current
    val installed by vm.installedMaps.collectAsStateWithLifecycle()
    val download by vm.mapDownload.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var deleteFile by remember { mutableStateOf<File?>(null) }

    Text(
        "Download a vector map of your country so the map works fully offline. " +
            "Your country is found from GPS on the phone; your location is not sent anywhere. " +
            "Maps come from download.mapsforge.org (© OpenStreetMap contributors).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (val d = download) {
        is MapDownloadState.Running -> Column(Modifier.padding(vertical = 8.dp)) {
            Text("Downloading ${d.name}", fontWeight = FontWeight.SemiBold)
            if (d.total > 0) LinearProgressIndicator(progress = { (d.downloaded.toFloat() / d.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Text(
                "${OfflineMaps.formatSize(d.downloaded)} of ${if (d.total > 0) OfflineMaps.formatSize(d.total) else "?"}" +
                    (d.waitingReason?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = vm::cancelMapDownload) { Text("Cancel download") }
        }
        is MapDownloadState.Completed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${d.name} installed. The map now uses it.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = vm::dismissMapDownloadResult) { Text("OK") }
        }
        is MapDownloadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${d.name}: ${d.message}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::dismissMapDownloadResult) { Text("OK") }
        }
        MapDownloadState.Idle -> Unit
    }

    if (installed.isNotEmpty()) {
        Text("Installed maps", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        installed.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(prettyFileName(ctx, f), style = MaterialTheme.typography.bodyMedium)
                    Text(OfflineMaps.formatSize(f.length()), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { deleteFile = f }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
        vm.updateSettings { it.copy(wifiOnlyMapDownload = !it.wifiOnlyMapDownload) }
    }) {
        Checkbox(checked = settings.wifiOnlyMapDownload, onCheckedChange = { v -> vm.updateSettings { it.copy(wifiOnlyMapDownload = v) } })
        Text("Download only over Wi-Fi", style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedButton(
        onClick = { picker = true },
        enabled = download !is MapDownloadState.Running,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Download offline map…") }
    Text("Free space: ${OfflineMaps.formatSize(vm.offlineMaps.freeBytes())}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (picker) {
        MapPickerDialog(
            onDismiss = { picker = false },
            onPick = { picker = false; vm.startMapDownload(it) },
        )
    }
    deleteFile?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteFile = null },
            text = { Text("Delete the offline map “${prettyFileName(ctx, f)}” (${OfflineMaps.formatSize(f.length())})?") },
            confirmButton = { TextButton(onClick = { deleteFile = null; vm.deleteMap(f) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("Cancel") } },
        )
    }
}

private fun prettyFileName(ctx: Context, f: File): String =
    MapCatalog.all(ctx).firstOrNull { it.fileName == f.name }?.displayName ?: f.nameWithoutExtension

@SuppressLint("MissingPermission")
private fun currentPosition(ctx: Context): Pair<Double, Double>? {
    val s = MonitorState.flow.value
    if (s.lat != null && s.lon != null) return s.lat to s.lon
    if (!Perms.hasFine(ctx) && !Perms.hasCoarse(ctx)) return null
    return try {
        ctx.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?.let { it.latitude to it.longitude }
    } catch (_: Exception) { null }
}

@Composable
private fun MapPickerDialog(onDismiss: () -> Unit, onPick: (MapEntry) -> Unit) {
    val ctx = LocalContext.current
    val pos = remember { currentPosition(ctx) }
    val suggested = remember(pos) { pos?.let { MapCatalog.candidates(ctx, it.first, it.second).take(6) } ?: emptyList() }
    var query by remember { mutableStateOf("") }
    val results = remember(query) { MapCatalog.search(ctx, query).take(40) }
    var confirm by remember { mutableStateOf<MapEntry?>(null) }

    confirm?.let { e ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Download ${e.displayName}?") },
            text = { Text("Size: ${OfflineMaps.formatSize(e.sizeBytes)}. The download continues in the background and can be cancelled in Settings.") },
            confirmButton = { Button(onClick = { onPick(e) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline map") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    if (pos == null) {
                        Text("No recent GPS position. Start monitoring or open the map until your position shows, or search below.",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (suggested.isNotEmpty()) {
                        Text("For your location", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Text("No map covers your location. Search below.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(suggested, key = { "s:" + it.path }) { e -> MapRow(e, highlight = e == suggested.first()) { confirm = e } }
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Search country or region") },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                }
                items(results, key = { "r:" + it.path }) { e -> MapRow(e, highlight = false) { confirm = e } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MapRow(e: MapEntry, highlight: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(e.displayName, style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal)
        Text(OfflineMaps.formatSize(e.sizeBytes) + if (highlight) " · recommended" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
