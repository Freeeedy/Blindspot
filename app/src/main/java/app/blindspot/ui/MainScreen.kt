package app.blindspot.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.imePadding
import android.view.HapticFeedbackConstants
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.blindspot.data.AppSettings
import app.blindspot.data.Camera
import app.blindspot.geo.GeoMath
import app.blindspot.service.GpsStatus
import app.blindspot.service.MonitorSnapshot
import app.blindspot.service.MonitorState
import app.blindspot.service.MonitoringService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import kotlin.math.roundToInt

@Composable
fun MainScreen(vm: AppViewModel, onImport: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mon by vm.monitor.collectAsStateWithLifecycle()
    val loadMessage by vm.loadMessage.collectAsStateWithLifecycle()
    val visibleIndex by vm.visibleIndex.collectAsStateWithLifecycle()
    val installedMaps by vm.installedMaps.collectAsStateWithLifecycle()
    val startRequest by MainActivity.startRequests.collectAsStateWithLifecycle()
    val centerRequest by MainActivity.centerRequests.collectAsStateWithLifecycle()

    var hasFine by remember { mutableStateOf(Perms.hasFine(ctx)) }
    var hasNotif by remember { mutableStateOf(Perms.hasNotifications(ctx)) }
    var gpsOn by remember { mutableStateOf(Perms.gpsEnabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        hasFine = Perms.hasFine(ctx); hasNotif = Perms.hasNotifications(ctx); gpsOn = Perms.gpsEnabled(ctx)
        onPauseOrDispose { }
    }

    val preview by rememberPreviewLocation(ctx, enabled = !mon.monitoring, hasPermission = hasFine)
    val userFix: UserFix? = if (mon.monitoring) {
        if (mon.lat != null && mon.lon != null) UserFix(mon.lat!!, mon.lon!!, mon.accuracyM) else null
    } else preview

    var selected by remember { mutableStateOf<Camera?>(null) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var centeredOnce by remember { mutableStateOf(false) }
    var showPermDialog by remember { mutableStateOf(false) }
    var showGpsDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current.density
    val cameraOverlay = remember { CameraOverlay(density) }
    val userOverlay = remember { UserLocationOverlay(density) }
    val editOverlay = remember { EditCameraOverlay(density) }
    var draft by remember { mutableStateOf<CameraDraft?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var showSession by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Camera?>(null) }
    val operatorSuggestions = remember(cameras) {
        cameras.mapNotNull { it.operator }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val camerasById = remember(cameras) { cameras.associateBy { it.id } }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    LaunchedEffect(userFix != null, mapRef) {
        val f = userFix
        val m = mapRef
        if (!centeredOnce && f != null && m != null) {
            centeredOnce = true
            m.controller.animateTo(GeoPoint(f.lat, f.lon), 17.0, 800L)
        }
    }

    fun beginMonitoring() {
        if (!Perms.gpsEnabled(ctx)) { showGpsDialog = true; return }
        MonitorState.clearError()
        MonitoringService.start(ctx)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasFine = Perms.hasFine(ctx)
        hasNotif = Perms.hasNotifications(ctx)
        if (hasFine) beginMonitoring() else showPermDialog = true
    }

    fun onStartStop() {
        if (mon.monitoring) {
            MonitoringService.stop(ctx)
        } else if (!Perms.hasFine(ctx)) {
            permLauncher.launch(Perms.startPermissions())
        } else {
            beginMonitoring()
        }
    }

    androidx.activity.compose.BackHandler(enabled = draft != null) { draft = null }

    LaunchedEffect(centerRequest, mapRef) {
        val p = centerRequest ?: return@LaunchedEffect
        val m = mapRef ?: return@LaunchedEffect
        centeredOnce = true
        m.controller.setZoom(16.0)
        m.controller.setCenter(GeoPoint(p.lat, p.lon))
        MainActivity.centerRequests.value = null
    }

    // Started from the Quick Settings tile when the app had to be opened first.
    LaunchedEffect(startRequest) {
        if (startRequest) {
            if (!mon.monitoring) {
                if (Perms.hasFine(ctx) && Perms.gpsEnabled(ctx)) {
                    MonitorState.clearError()
                    MonitoringService.start(ctx)
                    // Back to whatever the user was doing; monitoring continues in the background.
                    delay(500)
                    (ctx as? android.app.Activity)?.moveTaskToBack(true)
                } else {
                    onStartStop()   // permission or GPS dialog
                }
            }
            // Reset last: changing the key earlier would cancel this effect mid-way.
            MainActivity.startRequests.value = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        OsmMap(
            modifier = Modifier.fillMaxSize(),
            tileMode = settings.tileMode,
            vectorMaps = installedMaps,
            initialIndex = index,
            initialCenter = userFix?.let { GeoPoint(it.lat, it.lon) },
            setup = { m ->
                m.overlays.add(cameraOverlay)
                m.overlays.add(userOverlay)
                m.overlays.add(editOverlay)
                mapRef = m
            },
            update = {
                cameraOverlay.index = visibleIndex
                editOverlay.draft = draft?.preview()
                editOverlay.fovDeg = settings.fovDeg.toDouble()
                editOverlay.maxDistanceM = settings.maxDistanceM.toDouble()
                editOverlay.unknownRadiusM = settings.unknownRadiusM.toDouble()
                editOverlay.onLongPress = { p ->
                    mapRef?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    selected = null
                    draft = draft?.copy(lat = p.lat, lon = p.lon) ?: CameraDraft(null, p.lat, p.lon)
                    mapRef?.let { m -> keepVisibleAbovePanel(m, p.lat, p.lon) }
                }
                editOverlay.onDraftTap = { p -> draft = draft?.withTap(p) }
                cameraOverlay.fovDeg = settings.fovDeg.toDouble()
                cameraOverlay.maxDistanceM = settings.maxDistanceM.toDouble()
                cameraOverlay.unknownRadiusM = settings.unknownRadiusM.toDouble()
                cameraOverlay.covering = if (mon.monitoring) mon.coveringIds else emptySet()
                cameraOverlay.selectedId = selected?.id
                cameraOverlay.onTap = { selected = it }
                userOverlay.lat = userFix?.lat
                userOverlay.lon = userFix?.lon
                userOverlay.accuracyM = userFix?.accuracyM
                userOverlay.stale = mon.monitoring && mon.gps == GpsStatus.LOST
            },
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusCard(mon, cameras.size, settings)
            mon.error?.let { Banner(it, "Dismiss") { MonitorState.clearError() } }
            loadMessage?.let { Banner(it, null) {} }
            if (!hasFine) {
                Banner("Precise location permission is needed to compare your position with camera locations. " +
                    "Your location never leaves the phone.", "Grant") { permLauncher.launch(Perms.startPermissions()) }
            } else if (!gpsOn) {
                Banner("Location (GPS) is turned off.", "Turn on") { Perms.openLocationSettings(ctx) }
            }
            if (mon.monitoring && !hasNotif) {
                Banner("Notifications are blocked, so the monitoring notification is hidden. Vibration alerts still work.", "Allow") {
                    Perms.openAppSettings(ctx)
                }
            }
            if (cameras.isEmpty()) {
                Banner("The camera database is empty. Import cameras to start.", "Import", onImport)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(12.dp),
        ) {
            SnackbarHost(snackbar) { Snackbar(it) }
            val d = draft
            if (d != null) {
                Box(Modifier.imePadding()) {
                    AddCameraPanel(
                        draft = d,
                        operatorSuggestions = operatorSuggestions,
                        onChange = { draft = it },
                        onCancel = { draft = null },
                        onSave = {
                            vm.saveCamera(d.toCamera { vm.newUserCameraId() }, d.isNew)
                            draft = null
                        },
                    )
                }
            } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallFloatingActionButton(onClick = { showFilters = true }) {
                    BadgedBox(badge = { if (settings.filtersActive) Badge() }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filter cameras")
                    }
                }
                Spacer(Modifier.width(10.dp))
                SmallFloatingActionButton(onClick = { showSession = true }) {
                    BadgedBox(badge = { if (mon.sessionHits.isNotEmpty()) Badge { Text(mon.sessionHits.size.toString()) } }) {
                        Icon(Icons.Filled.History, contentDescription = "Cameras this session")
                    }
                }
                Spacer(Modifier.weight(1f))
                SmallFloatingActionButton(onClick = {
                    val f = userFix
                    if (f != null) mapRef?.controller?.animateTo(GeoPoint(f.lat, f.lon), 17.0, 600L)
                }) { Icon(Icons.Filled.LocationOn, contentDescription = "Center on my location") }
            }
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = onImport, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = "Import cameras")
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { onStartStop() },
                            modifier = Modifier.weight(1f).height(52.dp),
                            colors = if (mon.monitoring) ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
                            else ButtonDefaults.buttonColors(),
                        ) {
                            Icon(if (mon.monitoring) Icons.Filled.Close else Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (mon.monitoring) "Stop monitoring" else "Start monitoring", fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                    Text(
                        "Estimates only: Blindspot cannot know whether a camera works, records, or has a clear view. " +
                            "Long-press the map to add a camera.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
            }
        }
    }

    if (showFilters) {
        FilterSheet(cameras, settings, onUpdate = { vm.updateSettings(it) }, onDismiss = { showFilters = false })
    }
    if (showSession) {
        SessionSheet(
            hits = mon.sessionHits,
            camerasById = camerasById,
            monitoring = mon.monitoring,
            onSelect = { cam ->
                showSession = false
                selected = cam
                mapRef?.controller?.animateTo(GeoPoint(cam.lat, cam.lon), 18.0, 600L)
            },
            onClear = { MonitorState.clearSession() },
            onDismiss = { showSession = false },
        )
    }
    confirmDelete?.let { cam ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete camera?") },
            text = {
                Text(
                    if (cam.isUserAdded) "This removes the camera you added (ID ${cam.id}) from the database."
                    else "This removes camera ${cam.id}${cam.operator?.let { " ($it)" } ?: ""} from the database. " +
                        "Future imports will skip it, so it won't come back. You can restore deleted cameras in Settings."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.deleteCamera(cam) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    selected?.let { cam ->
        CameraInfoSheet(
            cam = cam,
            userFix = userFix,
            covering = mon.monitoring && cam.id in mon.coveringIds,
            settings = settings,
            onDismiss = { selected = null },
            onEdit = { selected = null; draft = CameraDraft.from(cam); mapRef?.let { keepVisibleAbovePanel(it, cam.lat, cam.lon) } },
            onDelete = { selected = null; confirmDelete = cam },
        )
    }

    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title = { Text("Precise location needed") },
            text = {
                Text(
                    (if (Perms.hasCoarse(ctx)) "Only approximate location was granted. " else "") +
                        "Blindspot needs precise location to tell whether you are near a camera's estimated viewing area. " +
                        "Your location is processed only on this phone and is never sent or stored."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPermDialog = false; Perms.openAppSettings(ctx) }) { Text("Open app settings") }
            },
            dismissButton = { TextButton(onClick = { showPermDialog = false }) { Text("Cancel") } },
        )
    }
    if (showGpsDialog) {
        AlertDialog(
            onDismissRequest = { showGpsDialog = false },
            title = { Text("Turn on location") },
            text = { Text("Location (GPS) is turned off. Turn it on so Blindspot can read your position from the phone's GPS.") },
            confirmButton = {
                TextButton(onClick = { showGpsDialog = false; Perms.openLocationSettings(ctx) }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showGpsDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusCard(mon: MonitorSnapshot, cameraCount: Int, settings: AppSettings) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    val (color, title, subtitle) = statusText(mon)
    Card(
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            if (subtitle != null) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val gps = when {
                    !mon.monitoring -> "Off"
                    mon.gps == GpsStatus.PROVIDER_DISABLED -> "Turned off"
                    mon.accuracyM == null -> "Searching…"
                    else -> {
                        val age = mon.fixWallTimeMs?.let { ((now - it) / 1000).coerceAtLeast(0) }
                        "±${mon.accuracyM.roundToInt()} m" + (age?.let { " · ${it}s ago" } ?: "")
                    }
                }
                InfoChip("GPS", gps)
                InfoChip("Cameras", cameraCount.toString())
                InfoChip("Nearby", if (mon.monitoring) mon.nearbyCount.toString() else "–")
                InfoChip("Monitoring", if (mon.monitoring) "On" else "Off")
            }
        }
    }
}

private data class StatusTriple(val color: Color, val title: String, val subtitle: String?)

private fun statusText(mon: MonitorSnapshot): StatusTriple {
    if (!mon.monitoring) return StatusTriple(StatusColors.off, "Monitoring off", "Start monitoring to get vibration alerts.")
    val n = mon.coveringIds.size
    val coverage = "Estimated coverage of ${if (n == 1) "1 camera" else "$n cameras"}"
    return when (mon.gps) {
        GpsStatus.PROVIDER_DISABLED -> StatusTriple(StatusColors.warning, "GPS is turned off", "Monitoring is paused until location is turned on.")
        GpsStatus.WAITING -> StatusTriple(StatusColors.warning, "Waiting for GPS…", "Go outdoors or near a window for a faster fix.")
        GpsStatus.LOST -> StatusTriple(
            StatusColors.warning, "GPS signal lost",
            if (mon.inside) "Last state: potentially within camera view." else "Holding the last state until GPS returns.",
        )
        GpsStatus.INACCURATE -> if (mon.inside) {
            StatusTriple(StatusColors.inside, "Potentially within camera view", "$coverage · GPS inaccurate, holding state")
        } else {
            StatusTriple(StatusColors.warning, "GPS accuracy too low", "Holding the last state until accuracy improves.")
        }
        GpsStatus.GOOD -> when {
            mon.inside && mon.pendingCount > 0 -> StatusTriple(StatusColors.inside, "Potentially within camera view", "$coverage · confirming exit…")
            mon.inside -> StatusTriple(StatusColors.inside, "Potentially within camera view", coverage)
            mon.pendingCount > 0 -> StatusTriple(StatusColors.outside, "Outside estimated camera coverage", "Approaching a camera's estimated area…")
            else -> StatusTriple(StatusColors.outside, "Outside estimated camera coverage", null)
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer, shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

fun formatDeg(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraInfoSheet(
    cam: Camera, userFix: UserFix?, covering: Boolean, settings: AppSettings,
    onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)
        ) {
            Text("Camera", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Estimated visibility area only. The camera may be off, not recording, obstructed, or pointed elsewhere.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (covering) {
                Surface(color = StatusColors.inside.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        "You are potentially within this camera's estimated view.",
                        color = StatusColors.inside, modifier = Modifier.padding(10.dp), fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    if (cam.isUserAdded) "Added by you on this phone" else "Imported camera",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                if (cam.isUserAdded) {
                    OutlinedButton(onClick = onEdit) { Text("Edit") }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            userFix?.let {
                val d = GeoMath.distanceM(cam.lat, cam.lon, it.lat, it.lon)
                Text(
                    "Distance from you: ${if (d < 1000) "${d.roundToInt()} m" else String.format(java.util.Locale.US, "%.1f km", d / 1000)}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Column {
                    InfoRow("Camera ID", cam.id.toString())
                    InfoRow("Operator", cam.operator)
                    InfoRow("Camera type", cam.type.joinToString(", ").ifEmpty { null })
                    InfoRow("Mount location", cam.mount)
                    InfoRow("Monitored zone", cam.zone)
                    InfoRow("Last check date", cam.checkDate)
                    InfoRow(
                        "Viewing direction",
                        if (cam.directions.isEmpty()) "Unknown (assumed 360° within ${settings.unknownRadiusM} m)"
                        else cam.directions.joinToString(", ") { "${formatDeg(it)}°" } +
                            " (estimated ${settings.fovDeg}° view, up to ${settings.maxDistanceM} m)",
                    )
                    InfoRow("Coordinates", "${cam.lat}, ${cam.lon}")
                    for ((k, v) in cam.extras) {
                        if (k == Camera.SOURCE_KEY) continue
                        if (v is JSONObject) {
                            Text(
                                if (k == "osm_tags") "OpenStreetMap tags" else k,
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                            )
                            val keys = v.keys().asSequence().toList().sorted()
                            for (tk in keys) InfoRow(tk, jsonText(v.opt(tk)))
                        } else {
                            InfoRow(k, jsonText(v))
                        }
                    }
                }
            }
        }
    }
}

private fun jsonText(v: Any?): String? = when (v) {
    null, JSONObject.NULL -> null
    is JSONArray -> (0 until v.length()).joinToString(", ") { v.opt(it).toString() }
    else -> v.toString()
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value == null) {
            Text("Not specified", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        } else {
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Scrolls the map so a point being edited sits in the upper third, above the edit panel. */
private fun keepVisibleAbovePanel(m: MapView, lat: Double, lon: Double) {
    val pt = m.projection.toPixels(GeoPoint(lat, lon), null)
    val targetY = m.height * 0.30
    if (pt.y <= m.height * 0.42 && pt.y >= m.height * 0.18) return
    val center = m.projection.fromPixels(m.width / 2, (m.height / 2 + (pt.y - targetY)).toInt())
    m.controller.animateTo(center)
}
