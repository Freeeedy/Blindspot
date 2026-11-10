package app.blindspot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.blindspot.data.ValidationReport
import app.blindspot.geo.GeoMath
import app.blindspot.geo.LatLon
import org.osmdroid.views.MapView
import java.util.Locale

private const val MAX_AREA_KM2 = 5000.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.importState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import cameras") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("OpenStreetMap") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("JSON file") })
            }
            when (tab) {
                0 -> OsmImportPane(vm)
                else -> FileImportPane(vm)
            }
        }
    }
    ImportDialogs(state, vm, onFinished = onBack)
}

@Composable
private fun OsmImportPane(vm: AppViewModel) {
    val index by vm.index.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installedMaps by vm.installedMaps.collectAsStateWithLifecycle()
    var polygonMode by rememberSaveable { mutableStateOf(false) }
    var points by remember { mutableStateOf(listOf<LatLon>()) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    val density = LocalDensity.current.density
    val cameraOverlay = remember { CameraOverlay(density).apply { showCoverage = false } }
    val selection = remember { SelectionOverlay(density) }

    val areaKm2 = GeoMath.polygonAreaM2(points) / 1_000_000.0
    val tooLarge = areaKm2 > MAX_AREA_KM2

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            OsmMap(
                modifier = Modifier.fillMaxSize(),
                tileMode = settings.tileMode,
                vectorMaps = installedMaps,
                initialIndex = index,
                initialCenter = null,
                setup = { m ->
                    m.overlays.add(cameraOverlay)
                    m.overlays.add(selection)
                    mapRef = m
                },
                update = {
                    cameraOverlay.index = index
                    selection.points = points
                    selection.showFrame = !polygonMode && points.isEmpty()
                    selection.tapToAdd = if (polygonMode) { p -> points = points + p } else null
                },
            )
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) { Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !polygonMode, onClick = { polygonMode = false; points = emptyList() }, label = { Text("Visible area") })
                FilterChip(selected = polygonMode, onClick = { polygonMode = true; points = emptyList() }, label = { Text("Draw perimeter") })
            }
            Text(
                when {
                    polygonMode && points.size < 3 -> "Tap the map to place the corners of your area (at least 3). Pan and zoom as needed."
                    polygonMode -> "Keep tapping to add corners, or download when the perimeter looks right."
                    points.isEmpty() -> "Move and zoom the map so the area you want is inside the dashed frame, then tap “Use this area”."
                    else -> "Selected area is shown in blue. Tap “Change area” to choose again."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (polygonMode) {
                    OutlinedButton(onClick = { points = points.dropLast(1) }, enabled = points.isNotEmpty()) { Text("Undo point") }
                    OutlinedButton(onClick = { points = emptyList() }, enabled = points.isNotEmpty()) { Text("Clear") }
                } else if (points.isEmpty()) {
                    OutlinedButton(onClick = {
                        val m = mapRef ?: return@OutlinedButton
                        val i = selection.frameInsetPx.toInt()
                        val pj = m.projection
                        val nw = pj.fromPixels(i, i)
                        val se = pj.fromPixels(m.width - i, m.height - i)
                        points = listOf(
                            LatLon(nw.latitude, nw.longitude), LatLon(nw.latitude, se.longitude),
                            LatLon(se.latitude, se.longitude), LatLon(se.latitude, nw.longitude),
                        )
                    }) { Text("Use this area") }
                } else {
                    OutlinedButton(onClick = { points = emptyList() }) { Text("Change area") }
                }
            }
            if (points.size >= 3) {
                Text(
                    "Selected area: ${formatArea(areaKm2)}" + if (tooLarge) " (too large, the maximum is ${MAX_AREA_KM2.toInt()} km²)" else "",
                    color = if (tooLarge) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = { vm.importFromOsm(points) },
                enabled = points.size >= 3 && !tooLarge,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download cameras in this area") }
            Text(
                "Requires internet. Only the selected area is sent to the Overpass API (an OpenStreetMap data service), never your location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } }
    }
}

private fun formatArea(km2: Double): String =
    if (km2 < 1) String.format(Locale.US, "%.2f km²", km2) else String.format(Locale.US, "%.1f km²", km2)

@Composable
private fun FileImportPane(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFromFile(uri)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import your own camera list", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Choose a .json file from your phone's storage. It is validated first; you then see how many cameras are new, " +
                "duplicate or invalid, and can merge it into your database or replace the database entirely.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Choose JSON file") }
        Text("Expected format: a JSON array of objects like this:", style = MaterialTheme.typography.bodyMedium)
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                """
                [
                  {
                    "id": 2718303450,
                    "lat": 49.9486249,
                    "lon": 15.2650147,
                    "directions": [25.0, 230.0],
                    "type": ["dome"],
                    "operator": "Example operator",
                    "mount": "building",
                    "zone": "street",
                    "check_date": "2025-02-06"
                  }
                ]
                """.trimIndent(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        Text(
            "Required: id (whole number), lat, lon. Optional: directions (degrees; empty means unknown, assumed 360°), " +
                "type, operator, mount, zone, check_date. Other fields are kept as additional data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportDialogs(state: ImportUiState, vm: AppViewModel, onFinished: () -> Unit) {
    when (state) {
        ImportUiState.Idle -> Unit
        is ImportUiState.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Importing") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.stage)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {},
            dismissButton = {
                if (state.cancellable) TextButton(onClick = vm::cancelImport) { Text("Cancel") }
            },
        )
        is ImportUiState.Review -> ReviewDialog(state, vm)
        is ImportUiState.Error -> AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text("Import failed") },
            text = { Text(state.message + "\n\nYour existing camera database is unchanged.") },
            confirmButton = { TextButton(onClick = vm::dismissImport) { Text("OK") } },
        )
        is ImportUiState.Done -> AlertDialog(
            onDismissRequest = { vm.dismissImport(); onFinished() },
            title = { Text("Import finished") },
            text = { Text(state.message + " All cameras are stored on this phone and work offline.") },
            confirmButton = { TextButton(onClick = { vm.dismissImport(); onFinished() }) { Text("Show map") } },
            dismissButton = { TextButton(onClick = vm::dismissImport) { Text("Import more") } },
        )
    }
}

@Composable
private fun ReviewDialog(state: ImportUiState.Review, vm: AppViewModel) {
    val r = state.report
    val p = state.preview
    var confirmReplace by remember { mutableStateOf(false) }
    val valid = r.cameras.size - p.deletedSkipped

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace database?") },
            text = {
                Text("Your current ${p.currentTotal} cameras will be replaced by the $valid imported cameras. " +
                    "The previous version is kept and can be restored in Settings.")
            },
            confirmButton = { TextButton(onClick = { confirmReplace = false; vm.commitImport(replace = true) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = vm::dismissImport,
        title = { Text(if (valid > 0) "Review import" else "Nothing to import") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                ReportBody(state.source, r, p)
            }
        },
        confirmButton = {
            if (valid > 0) {
                Row {
                    TextButton(onClick = { confirmReplace = true }) { Text("Replace") }
                    TextButton(onClick = { vm.commitImport(replace = false) }) { Text("Merge") }
                }
            } else {
                TextButton(onClick = vm::dismissImport) { Text("Close") }
            }
        },
        dismissButton = { if (valid > 0) TextButton(onClick = vm::dismissImport) { Text("Cancel") } },
    )
}

@Composable
private fun ReportBody(source: String, r: ValidationReport, p: app.blindspot.data.MergePreview) {
    @Composable
    fun line(label: String, value: String, strong: Boolean = false) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
        }
    }
    @Composable
    fun section(title: String) {
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }

    Text(source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    section("Summary")
    line("Entries found", r.totalEntries.toString())
    line("Valid cameras", r.cameras.size.toString(), strong = true)
    line("New cameras", p.newCount.toString())
    line("Already in database", "${p.updatedCount + p.unchangedCount}" + if (p.updatedCount > 0) " (${p.updatedCount} with changes)" else "")
    line("Duplicate IDs in source (skipped)", r.duplicateCount.toString())
    if (p.deletedSkipped > 0) line("Deleted by you (skipped)", p.deletedSkipped.toString())
    line("Invalid entries (skipped)", r.invalidCount.toString())

    if (r.invalidReasons.isNotEmpty()) {
        section("Invalid entries")
        r.invalidReasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        if (r.invalidCount > r.invalidReasons.size) Text("…and ${r.invalidCount - r.invalidReasons.size} more", style = MaterialTheme.typography.bodySmall)
    }
    if (r.duplicateIds.isNotEmpty()) {
        section("Duplicate IDs")
        Text(r.duplicateIds.joinToString(", ") + if (r.duplicateCount > r.duplicateIds.size) ", …" else "",
            style = MaterialTheme.typography.bodySmall)
    }
    if (r.missingFields.isNotEmpty()) {
        section("Missing or empty fields")
        r.missingFields.forEach { (k, n) -> line(k, "$n ${if (n == 1) "entry" else "entries"}") }
        Text("Missing details are shown as “Not specified”. Missing directions are treated as unknown (360°).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (r.unsupportedFields.isNotEmpty()) {
        section("Unsupported fields (kept as additional data)")
        r.unsupportedFields.forEach { (k, n) -> line(k, "$n ${if (n == 1) "entry" else "entries"}") }
    }
    if (r.warnings.isNotEmpty()) {
        section("Warnings")
        r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        if (r.warningCount > r.warnings.size) Text("…and ${r.warningCount - r.warnings.size} more", style = MaterialTheme.typography.bodySmall)
    }
    if (r.cameras.isNotEmpty()) {
        section("What happens next")
        Text("Merge: adds new cameras and updates cameras with the same ID (existing details are kept where the import has none). Total: ${p.mergedTotal} cameras.",
            style = MaterialTheme.typography.bodySmall)
        Text("Replace: the database will contain only these ${r.cameras.size - p.deletedSkipped} cameras plus the ones you added yourself (currently ${p.currentTotal}).",
            style = MaterialTheme.typography.bodySmall)
    }
}
