package com.ahmedmunna.callrecorder.data.repo

import com.ahmedmunna.callrecorder.data.db.EngineTestDao
import com.ahmedmunna.callrecorder.data.db.RecordingDao
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.storage.StorageManager
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class IntegrityReport(val checked: Int, val missingFiles: Int, val emptyFiles: Int, val orphansImported: Int, val sizesFixed: Int)

@Singleton
class RecordingRepository @Inject constructor(
    private val dao: RecordingDao,
    private val testDao: EngineTestDao,
    private val storage: StorageManager,
) {
    fun observeAll(): Flow<List<RecordingEntity>> = dao.observeAll()
    fun observe(id: Long): Flow<RecordingEntity?> = dao.observe(id)
    fun observeEngineTests() = testDao.observeAll()
    suspend fun engineTests() = testDao.getAll()
    suspend fun get(id: Long) = dao.getById(id)
    suspend fun all() = dao.getAll()
    suspend fun insert(e: RecordingEntity) = dao.insert(e)
    suspend fun update(e: RecordingEntity) = dao.update(e)
    suspend fun inProgress() = dao.getInProgress()
    suspend fun setFavorite(id: Long, fav: Boolean) = dao.setFavorite(id, fav)
    suspend fun setPlayPosition(id: Long, pos: Long) = dao.setPlayPosition(id, pos)
    suspend fun findDuplicate(number: String, start: Long) = dao.findDuplicate(number, start, 30_000)
    suspend fun insertAll(list: List<RecordingEntity>) = dao.insertAll(list)

    /** Deletes files and rows. Callers must have obtained explicit user confirmation first. */
    suspend fun deleteConfirmed(items: List<RecordingEntity>) {
        items.forEach { storage.delete(it) }
        dao.deleteByIds(items.map { it.id })
    }

    suspend fun rename(e: RecordingEntity, newName: String): Boolean {
        val path = storage.rename(e, newName) ?: return false
        dao.update(e.copy(filePath = path))
        return true
    }

    /**
     * Reconciles the database with the files on disk: fixes sizes, flags missing/empty files and imports
     * recordings found in the default folder that the database does not know about. Never deletes anything.
     */
    suspend fun checkIntegrity(): IntegrityReport {
        val rows = dao.getAll()
        var missing = 0; var empty = 0; var fixed = 0
        rows.forEach { r ->
            if (r.status == RecordingStatus.RECORDING) return@forEach
            if (!storage.exists(r)) {
                missing++
                if (r.status != RecordingStatus.FAILED) dao.update(r.copy(status = RecordingStatus.FAILED, failureReason = "File is missing"))
            } else {
                val size = storage.size(r)
                if (size == 0L) {
                    empty++
                    if (r.status != RecordingStatus.FAILED) dao.update(r.copy(status = RecordingStatus.FAILED, failureReason = "File is empty"))
                } else if (size != r.fileSize) { fixed++; dao.update(r.copy(fileSize = size)) }
            }
        }
        val known = rows.map { it.filePath }.toSet()
        var orphans = 0
        storage.rootDir.walkTopDown()
            .filter { it.isFile && !it.path.contains("/.staging/") && AudioFormat.entries.any { f -> f.extension == it.extension.lowercase() } && it.absolutePath !in known && it.length() > 0 }
            .forEach { f ->
                dao.insert(orphanRow(f)); orphans++
            }
        return IntegrityReport(rows.size, missing, empty, orphans, fixed)
    }

    private fun orphanRow(f: File): RecordingEntity {
        val fmt = AudioFormat.entries.first { it.extension == f.extension.lowercase() }
        val m = Regex("""^(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_(INCOMING|OUTGOING|UNKNOWN)_(.*)\.\w+$""").find(f.name)
        val dir = m?.groupValues?.get(2)?.let { runCatching { CallDirection.valueOf(it) }.getOrNull() } ?: CallDirection.UNKNOWN
        val ts = m?.groupValues?.get(1)?.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).parse(it)?.time }.getOrNull() }
        return RecordingEntity(
            phoneNumber = m?.groupValues?.get(3)?.ifBlank { null } ?: "UNKNOWN",
            direction = dir, startTime = ts ?: f.lastModified(), fileSize = f.length(), filePath = f.absolutePath,
            format = fmt, engine = EngineId.DEFAULT, quality = "Imported", captureMode = CaptureMode.NONE,
            status = RecordingStatus.COMPLETED, isPrivate = f.parentFile?.name == "Private",
            notes = "Found on disk and added by the integrity check.",
        )
    }
}
