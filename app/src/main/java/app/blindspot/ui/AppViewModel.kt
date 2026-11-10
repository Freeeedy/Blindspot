package app.blindspot.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.blindspot.data.AppSettings
import app.blindspot.data.Camera
import app.blindspot.data.TileMode
import app.blindspot.maps.MapDownloadState
import app.blindspot.maps.MapEntry
import app.blindspot.maps.OfflineMaps
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import app.blindspot.data.CameraRepository
import app.blindspot.data.MergePreview
import app.blindspot.data.ParseOutcome
import app.blindspot.data.CameraJson
import app.blindspot.data.SettingsStore
import app.blindspot.data.ValidationReport
import app.blindspot.geo.CameraIndex
import app.blindspot.geo.LatLon
import app.blindspot.importer.ImportException
import app.blindspot.importer.OverpassImporter
import app.blindspot.service.MonitorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Working(val stage: String, val detail: String? = null, val cancellable: Boolean = true) : ImportUiState
    data class Review(val source: String, val report: ValidationReport, val preview: MergePreview) : ImportUiState
    data class Error(val message: String) : ImportUiState
    data class Done(val message: String) : ImportUiState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = CameraRepository.get(app)
    private val settingsStore = SettingsStore.get(app)

    val cameras = repo.cameras
    val settings: StateFlow<AppSettings> = settingsStore.flow
    val monitor = MonitorState.flow
    val loadMessage = repo.loadMessage

    val index: StateFlow<CameraIndex> = cameras
        .map { CameraIndex(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraIndex(emptyList()))

    /** Cameras that pass the operator/type filters; what the map shows. */
    val visibleIndex: StateFlow<CameraIndex> = combine(
        cameras,
        settings.map { Triple(it.hiddenOperators, it.hiddenTypes, it) }.distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second },
    ) { list, (_, _, s) -> CameraIndex(if (s.filtersActive) list.filter { s.isVisible(it) } else list) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CameraIndex(emptyList()))

    val offlineMaps = OfflineMaps.get(app)
    val installedMaps = offlineMaps.installed
    val mapDownload: StateFlow<MapDownloadState> = offlineMaps.download

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()
    private var importJob: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch { repo.ensureLoaded() }
        offlineMaps.refreshInstalled()
        viewModelScope.launch {
            while (true) {
                offlineMaps.poll()
                delay(1000)
            }
        }
        viewModelScope.launch {
            mapDownload.collect { st ->
                // Switch to the offline map as soon as one is installed.
                if (st is MapDownloadState.Completed) updateSettings { it.copy(tileMode = TileMode.OFFLINE_VECTOR) }
            }
        }
    }

    // ---------- User-added cameras ----------

    fun newUserCameraId(): Long = repo.newUserCameraId()

    fun saveCamera(camera: Camera, isNew: Boolean) = dbAction {
        repo.upsert(camera)
        if (isNew) "Camera added. It is stored on this phone with your other cameras." else "Camera updated."
    }

    val deletedCameras = repo.deleted

    fun deleteCamera(camera: Camera) = dbAction {
        repo.delete(camera.id)
        if (camera.isUserAdded) "Camera deleted."
        else "Camera deleted. Future imports will skip it; you can restore it in Settings."
    }

    fun restoreDeleted() = dbAction {
        val n = repo.restoreDeleted()
        if (n == 0) "There are no deleted cameras to restore." else "Restored $n deleted ${if (n == 1) "camera" else "cameras"}."
    }

    // ---------- Offline maps ----------

    fun startMapDownload(entry: MapEntry) {
        val err = offlineMaps.start(entry, settings.value.wifiOnlyMapDownload)
        viewModelScope.launch {
            _messages.emit(err ?: "Downloading ${entry.displayName}. You can keep using the app.")
        }
    }

    fun cancelMapDownload() = offlineMaps.cancel()
    fun dismissMapDownloadResult() = offlineMaps.dismissResult()

    fun deleteMap(f: java.io.File) {
        offlineMaps.delete(f)
        if (offlineMaps.installed.value.isEmpty() && settings.value.tileMode == TileMode.OFFLINE_VECTOR) {
            updateSettings { it.copy(tileMode = TileMode.ONLINE) }
        }
    }

    fun updateSettings(f: (AppSettings) -> AppSettings) = settingsStore.update(f)
    fun resetSettings() = settingsStore.resetDefaults()

    // ---------- Import ----------

    fun importFromOsm(polygon: List<LatLon>) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importState.value = ImportUiState.Working("Preparing request…")
            try {
                val report = OverpassImporter(getApplication()).fetchCameras(polygon) { p ->
                    _importState.value = ImportUiState.Working(p.stage, p.detail)
                }
                repo.ensureLoaded()
                _importState.value = ImportUiState.Review(
                    "OpenStreetMap (selected area)", report, repo.previewMerge(report.cameras),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                _importState.value = ImportUiState.Error(e.message ?: "Import failed.")
            } catch (e: Exception) {
                _importState.value = ImportUiState.Error("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun importFromFile(uri: Uri) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _importState.value = ImportUiState.Working("Reading file…")
            try {
                val app = getApplication<Application>()
                val (name, text) = withContext(Dispatchers.IO) {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "selected file"
                    val stream = app.contentResolver.openInputStream(uri)
                        ?: throw ImportException("The file could not be opened.")
                    val bytes = stream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_FILE_BYTES) throw ImportException("The file is larger than 50 MB.")
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                    name to String(bytes, Charsets.UTF_8)
                }
                _importState.value = ImportUiState.Working("Validating cameras…")
                val outcome = withContext(Dispatchers.Default) { CameraJson.parse(text) }
                when (outcome) {
                    is ParseOutcome.Failed -> _importState.value =
                        ImportUiState.Error("“$name” could not be imported. ${outcome.message} Your existing database was not changed.")
                    is ParseOutcome.Ok -> {
                        repo.ensureLoaded()
                        _importState.value = ImportUiState.Review(
                            "File “$name”", outcome.report, repo.previewMerge(outcome.report.cameras),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportException) {
                _importState.value = ImportUiState.Error(e.message ?: "Import failed.")
            } catch (e: Exception) {
                _importState.value = ImportUiState.Error("The file could not be read: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun commitImport(replace: Boolean) {
        val review = _importState.value as? ImportUiState.Review ?: return
        importJob = viewModelScope.launch {
            _importState.value = ImportUiState.Working("Saving database…", cancellable = false)
            try {
                val r = repo.applyImport(review.report.cameras, replace)
                _importState.value = ImportUiState.Done(
                    if (r.replaced) "Database replaced. It now contains ${r.total} cameras."
                    else "Import complete: ${r.added} new, ${r.updated} updated, ${r.unchanged} unchanged. " +
                        "The database now contains ${r.total} cameras."
                )
            } catch (e: Exception) {
                _importState.value = ImportUiState.Error(
                    "Saving failed, so your existing database was not changed. (${e.message ?: e.javaClass.simpleName})"
                )
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        _importState.value = ImportUiState.Idle
    }

    fun dismissImport() {
        _importState.value = ImportUiState.Idle
    }

    // ---------- Database management ----------

    fun restoreBundled() = dbAction { "Bundled database restored (${repo.restoreBundled()} cameras)." }

    fun restorePrevious() = dbAction {
        repo.restorePrevious()?.let { "Previous database version restored ($it cameras)." }
            ?: "No previous version is available."
    }

    fun clearDatabase() = dbAction { repo.clearAll(); "All cameras were removed from the database." }

    fun exportDatabase(uri: Uri) = dbAction { repo.exportTo(uri); "Database exported (${cameras.value.size} cameras)." }

    private fun dbAction(block: suspend () -> String) {
        viewModelScope.launch {
            val msg = try {
                repo.ensureLoaded(); block()
            } catch (e: Exception) {
                "Operation failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _messages.emit(msg)
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }
}
