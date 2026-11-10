package app.blindspot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.blindspot.data.TileMode
import app.blindspot.service.Haptics
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val deletedCameras by vm.deletedCameras.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var batteryOk by remember { mutableStateOf(Perms.ignoringBatteryOptimizations(ctx)) }
    var hasPrevious by remember { mutableStateOf(vm.repo.hasPreviousVersion()) }
    LifecycleResumeEffect(Unit) {
        batteryOk = Perms.ignoringBatteryOptimizations(ctx)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect {
            hasPrevious = vm.repo.hasPreviousVersion()
            snackbar.showSnackbar(it)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportDatabase(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Section("Estimated camera coverage")
            SliderSetting("Maximum detection distance", s.maxDistanceM, 10..150, 5, "m",
                "How far a camera with a known direction is assumed to see.") { v -> vm.updateSettings { it.copy(maxDistanceM = v) } }
            SliderSetting("Field of view", s.fovDeg, 30..180, 5, "°",
                "Assumed horizontal viewing angle of cameras with a known direction.") { v -> vm.updateSettings { it.copy(fovDeg = v) } }
            SliderSetting("Unknown-direction radius", s.unknownRadiusM, 10..150, 5, "m",
                "Cameras without a known direction are assumed to see all around, up to this distance.") { v ->
                vm.updateSettings { it.copy(unknownRadiusM = v) }
            }

            Section("GPS")
            Text("Update frequency", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                for (sec in listOf(1, 2, 3, 5, 10)) {
                    FilterChip(selected = s.gpsIntervalSec == sec, onClick = { vm.updateSettings { it.copy(gpsIntervalSec = sec) } },
                        label = { Text("${sec}s") })
                }
            }
            Hint("More frequent updates react faster but use more battery. 3 s suits walking.")
            SwitchSetting("Battery saver", s.batterySaver,
                "Use fewer GPS updates while you are far from every camera.") { v -> vm.updateSettings { it.copy(batterySaver = v) } }

            Section("Alerts")
            SwitchSetting("Vibration alerts", s.vibrationEnabled,
                "One long vibration when entering estimated coverage, two short ones when leaving it.") { v ->
                vm.updateSettings { it.copy(vibrationEnabled = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Haptics.enter(ctx) }) { Text("Test “enter”") }
                OutlinedButton(onClick = { Haptics.exit(ctx) }) { Text("Test “leave”") }
            }

            Section("Quick Settings tile")
            Hint("Start and stop monitoring from the notification shade. Starting briefly opens Blindspot, " +
                "because Android only gives location access to monitoring started from the app.")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(onClick = {
                    val sbm = ctx.getSystemService(android.app.StatusBarManager::class.java)
                    sbm.requestAddTileService(
                        android.content.ComponentName(ctx, app.blindspot.service.MonitoringTileService::class.java),
                        "Blindspot",
                        android.graphics.drawable.Icon.createWithResource(ctx, app.blindspot.R.drawable.ic_stat_blindspot),
                        ctx.mainExecutor,
                    ) { }
                }) { Text("Add tile to Quick Settings") }
            } else {
                Hint("Edit your Quick Settings panel and drag the Blindspot tile into it.")
            }

            Section("Background")
            SwitchSetting("Background monitoring", s.backgroundMonitoring,
                "Keep monitoring when the app is minimised or the screen is locked. When off, monitoring pauses while Blindspot is not visible.") { v ->
                vm.updateSettings { it.copy(backgroundMonitoring = v) }
            }
            if (!batteryOk) {
                Hint("Android battery optimisation may stop background monitoring on some phones.")
                OutlinedButton(onClick = { Perms.requestIgnoreBatteryOptimizations(ctx) }) { Text("Allow unrestricted background use") }
            } else {
                Hint("Battery optimisation is disabled for Blindspot, so background monitoring is not restricted.")
            }

            Section("Map")
            TileOption("Online map (viewed areas are cached for offline use)", s.tileMode == TileMode.ONLINE) {
                vm.updateSettings { it.copy(tileMode = TileMode.ONLINE) }
            }
            TileOption("Cached map only (no network use)", s.tileMode == TileMode.CACHED_ONLY) {
                vm.updateSettings { it.copy(tileMode = TileMode.CACHED_ONLY) }
            }
            val installedMaps by vm.installedMaps.collectAsStateWithLifecycle()
            if (installedMaps.isNotEmpty()) {
                TileOption("Offline vector map (installed below)", s.tileMode == TileMode.OFFLINE_VECTOR) {
                    vm.updateSettings { it.copy(tileMode = TileMode.OFFLINE_VECTOR) }
                }
            }
            TileOption("No map tiles (cameras on a plain background)", s.tileMode == TileMode.OFF) {
                vm.updateSettings { it.copy(tileMode = TileMode.OFF) }
            }
            Hint("Monitoring never depends on map tiles.")

            Section("Offline vector map")
            OfflineMapsSection(vm)

            Section("Detection stability")
            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced options" else "Show advanced options") }
            if (showAdvanced) {
                SliderSetting("Required GPS accuracy", s.maxAccuracyM, 10..100, 5, "m",
                    "Readings less accurate than this are ignored and never trigger alerts.") { v -> vm.updateSettings { it.copy(maxAccuracyM = v) } }
                SliderSetting("Readings to confirm entering", s.enterConfirmations, 1..6, 1, "",
                    "Consecutive readings inside an estimated area before the long vibration.") { v -> vm.updateSettings { it.copy(enterConfirmations = v) } }
                SliderSetting("Readings to confirm leaving", s.exitConfirmations, 1..8, 1, "",
                    "Consecutive readings outside all areas before the two short vibrations.") { v -> vm.updateSettings { it.copy(exitConfirmations = v) } }
                SliderSetting("Boundary margin", s.boundaryMarginM, 0..30, 1, "m",
                    "Once inside, an area is widened by this much so GPS jitter at its edge does not cause repeated alerts.") { v ->
                    vm.updateSettings { it.copy(boundaryMarginM = v) }
                }
                TextButton(onClick = { confirm = "Reset all settings to their defaults?" to { vm.resetSettings() } }) { Text("Reset settings to defaults") }
            }

            Section("Camera database")
            val withDir = cameras.count { it.hasDirection }
            val operators = cameras.mapNotNull { it.operator }.distinct().size
            val newest = cameras.mapNotNull { it.checkDate }.maxOrNull()
            Stat("Cameras", cameras.size.toString())
            Stat("With known direction", withDir.toString())
            Stat("Unknown direction (360°)", (cameras.size - withDir).toString())
            Stat("Operators", operators.toString())
            Stat("Most recent check date", newest ?: "–")
            Stat("Added by you", cameras.count { it.isUserAdded }.toString())
            Stat("Deleted by you (skipped on import)", deletedCameras.size.toString())
            Stat("Database size", formatSize(vm.repo.databaseSizeBytes()))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { exporter.launch("cameras.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export database…") }
            if (hasPrevious) {
                OutlinedButton(
                    onClick = { confirm = "Restore the previous version of the database? The current version becomes the previous one." to { vm.restorePrevious() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore previous version") }
            }
            if (deletedCameras.isNotEmpty()) {
                OutlinedButton(
                    onClick = { confirm = "Put the ${deletedCameras.size} cameras you deleted back into the database? Imports will include them again." to { vm.restoreDeleted() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore deleted cameras (${deletedCameras.size})") }
            }
            OutlinedButton(
                onClick = { confirm = "Replace the current database with the camera list bundled with the app? This also forgets which cameras you deleted." to { vm.restoreBundled() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset to bundled database") }
            OutlinedButton(
                onClick = { confirm = "Delete all ${cameras.size} cameras from the database? A copy is kept as the previous version." to { vm.clearDatabase() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete all cameras", color = MaterialTheme.colorScheme.error) }

            Section("About and privacy")
            Text(
                "Blindspot estimates where cameras might see, using their registered position, direction and the distances above. " +
                    "It cannot know whether a camera works, records, has a clear view, or is pointed as registered. " +
                    "Being inside an estimated area does not mean you are being recorded.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your location is processed only on this phone. It is never sent anywhere, and no location history is stored. " +
                    "There is no account, analytics or telemetry. The network is used only for map tiles and when you import from OpenStreetMap. " +
                    "Camera data © OpenStreetMap contributors (ODbL).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun formatSize(b: Long) = when {
    b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SliderSetting(title: String, value: Int, range: IntRange, step: Int, unit: String, desc: String?, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${local.roundToInt()}${if (unit.isEmpty()) "" else " $unit"}", fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            onValueChangeFinished = { onChange(local.roundToInt()) },
        )
        desc?.let { Hint(it) }
    }
}

@Composable
private fun SwitchSetting(title: String, checked: Boolean, desc: String?, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TileOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
