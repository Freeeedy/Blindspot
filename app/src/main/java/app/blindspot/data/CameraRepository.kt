package app.blindspot.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class MergePreview(
    val newCount: Int, val updatedCount: Int, val unchangedCount: Int, val currentTotal: Int,
    /** Imported cameras skipped because the user deleted them earlier. */
    val deletedSkipped: Int = 0,
) {
    val mergedTotal: Int get() = currentTotal + newCount
}

data class MergeResult(val added: Int, val updated: Int, val unchanged: Int, val total: Int, val replaced: Boolean)

/**
 * Owns the local camera database (`files/cameras.json`).
 *
 * Every write goes to a temporary file which is fsync'd and then atomically renamed over the
 * database, so a failed import or crash can never leave a half-written database. The previous
 * version is kept as `cameras.previous.json` and can be restored from Settings.
 */
class CameraRepository private constructor(private val app: Context) {

    private val dbFile = File(app.filesDir, "cameras.json")
    private val previousFile = File(app.filesDir, "cameras.previous.json")
    /** Imported cameras the user deleted; imports skip these ids, and they can be restored. */
    private val deletedFile = File(app.filesDir, "cameras.deleted.json")
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras: StateFlow<List<Camera>> = _cameras.asStateFlow()

    private val _deleted = MutableStateFlow<List<Camera>>(emptyList())
    val deleted: StateFlow<List<Camera>> = _deleted.asStateFlow()

    private val _loadMessage = MutableStateFlow<String?>(null)
    /** Non-null if the stored database had to be recovered at startup. */
    val loadMessage: StateFlow<String?> = _loadMessage.asStateFlow()

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (loaded) return@withLock
                _cameras.value = loadInternal()
                _deleted.value = readFile(deletedFile) ?: emptyList()
                loaded = true
            }
        }
    }

    private fun loadInternal(): List<Camera> {
        if (dbFile.exists()) {
            readFile(dbFile)?.let { return it }
            // Keep the unreadable file for inspection instead of overwriting it.
            dbFile.renameTo(File(app.filesDir, "cameras.corrupt-${System.currentTimeMillis()}.json"))
            readFile(previousFile)?.let {
                _loadMessage.value = "The local camera database was unreadable. The previous version was restored."
                writeAtomic(it, keepPrevious = false)
                return it
            }
            _loadMessage.value = "The local camera database was unreadable. The bundled database was restored."
        }
        val bundled = readBundled()
        writeAtomic(bundled, keepPrevious = false)
        return bundled
    }

    private fun readFile(f: File): List<Camera>? {
        if (!f.exists()) return null
        return try {
            when (val o = CameraJson.parse(f.readText())) {
                is ParseOutcome.Ok -> o.report.cameras
                is ParseOutcome.Failed -> null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun readBundled(): List<Camera> = try {
        val text = app.assets.open("cameras.json").bufferedReader().use { it.readText() }
        (CameraJson.parse(text) as? ParseOutcome.Ok)?.report?.cameras ?: emptyList()
    } catch (e: IOException) {
        emptyList()
    }

    @Throws(IOException::class)
    private fun writeAtomic(list: List<Camera>, keepPrevious: Boolean, target: File = dbFile) {
        val bytes = CameraJson.serialize(list).toByteArray(Charsets.UTF_8)
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (keepPrevious && target == dbFile && dbFile.exists()) dbFile.copyTo(previousFile, overwrite = true)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not replace the database file")
        }
    }

    private fun saveDeleted(list: List<Camera>) {
        writeAtomic(list, keepPrevious = false, target = deletedFile)
        _deleted.value = list
    }

    private suspend fun commit(list: List<Camera>) {
        writeAtomic(list, keepPrevious = true)
        _cameras.value = list
    }

    fun previewMerge(importedAll: List<Camera>): MergePreview {
        val existing = _cameras.value.associateBy { it.id }
        val deletedIds = _deleted.value.mapTo(HashSet()) { it.id }
        val imported = importedAll.filter { it.id !in deletedIds }
        var new = 0; var updated = 0; var same = 0
        for (c in imported) {
            val old = existing[c.id]
            when {
                old == null -> new++
                CameraJson.contentKey(old) == CameraJson.contentKey(mergeCamera(old, c)) -> same++
                else -> updated++
            }
        }
        return MergePreview(new, updated, same, existing.size, importedAll.size - imported.size)
    }

    /** Merges (or replaces with) [imported]. Throws on I/O failure; the database is then unchanged. */
    suspend fun applyImport(importedAll: List<Camera>, replace: Boolean): MergeResult = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val preview = previewMerge(importedAll)
            val deletedIds = _deleted.value.mapTo(HashSet()) { it.id }
            val imported = importedAll.filter { it.id !in deletedIds }
            val result: List<Camera> = if (replace) {
                // Cameras the user added on the map survive a replace.
                val importedIds = imported.mapTo(HashSet()) { it.id }
                imported.distinctBy { it.id } + _cameras.value.filter { it.isUserAdded && it.id !in importedIds }
            } else {
                val map = LinkedHashMap<Long, Camera>()
                _cameras.value.forEach { map[it.id] = it }
                imported.forEach { c -> map[c.id] = map[c.id]?.let { mergeCamera(it, c) } ?: c }
                map.values.toList()
            }
            commit(result)
            MergeResult(preview.newCount, preview.updatedCount, preview.unchangedCount, result.size, replace)
        }
    }

    /** Adds a new camera or replaces the camera with the same id. */
    suspend fun upsert(camera: Camera) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val list = _cameras.value
            val idx = list.indexOfFirst { it.id == camera.id }
            commit(if (idx >= 0) list.toMutableList().also { it[idx] = camera } else list + camera)
        }
    }

    /**
     * Deletes a camera. An imported camera is also remembered as deleted so later imports don't
     * bring it back; a camera the user added is simply removed.
     */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val cam = _cameras.value.firstOrNull { it.id == id } ?: return@withLock
            if (!cam.isUserAdded) saveDeleted(_deleted.value.filter { it.id != id } + cam)
            commit(_cameras.value.filter { it.id != id })
        }
    }

    /** Puts all deleted imported cameras back into the database. Returns how many were restored. */
    suspend fun restoreDeleted(): Int = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val back = _deleted.value
            if (back.isEmpty()) return@withLock 0
            val ids = _cameras.value.mapTo(HashSet()) { it.id }
            commit(_cameras.value + back.filter { it.id !in ids })
            saveDeleted(emptyList())
            back.size
        }
    }

    /** New id for a user-added camera. Negative, like new objects in OpenStreetMap, so imports never collide. */
    fun newUserCameraId(): Long {
        val used = _cameras.value.mapTo(HashSet()) { it.id }
        var id = -System.currentTimeMillis()
        while (id in used) id--
        return id
    }

    suspend fun restoreBundled(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            saveDeleted(emptyList())   // a full reset also forgets deletions
            readBundled().also { commit(it) }.size
        }
    }

    fun hasPreviousVersion(): Boolean = previousFile.exists()

    /** Swaps the current database with the previous version. Returns the restored camera count. */
    suspend fun restorePrevious(): Int? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prev = readFile(previousFile) ?: return@withLock null
            commit(prev)
            prev.size
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock { commit(emptyList()) }
    }

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val text = CameraJson.serialize(_cameras.value)
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Could not open the selected file for writing")
    }

    fun databaseSizeBytes(): Long = if (dbFile.exists()) dbFile.length() else 0L

    companion object {
        /**
         * Updates [old] with [new] without losing information: imported values win, but a field
         * the import leaves empty keeps the existing value. Extra fields are combined.
         */
        fun mergeCamera(old: Camera, new: Camera): Camera = new.copy(
            directions = new.directions.ifEmpty { old.directions },
            type = new.type.ifEmpty { old.type },
            operator = new.operator ?: old.operator,
            mount = new.mount ?: old.mount,
            zone = new.zone ?: old.zone,
            checkDate = new.checkDate ?: old.checkDate,
            extras = LinkedHashMap(old.extras).apply { putAll(new.extras) },
        )

        @Volatile private var instance: CameraRepository? = null
        fun get(ctx: Context): CameraRepository = instance ?: synchronized(this) {
            instance ?: CameraRepository(ctx.applicationContext).also { instance = it }
        }
    }
}
