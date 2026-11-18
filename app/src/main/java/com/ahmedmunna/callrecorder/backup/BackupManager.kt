package com.ahmedmunna.callrecorder.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.data.repo.RecordingRepository
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupResult(val count: Int, val skipped: Int, val message: String)

/**
 * Backup, restore, export and import. Everything stays on the device or in the folder the user picks.
 * Backup ZIPs are NOT encrypted, so keep them somewhere private.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: RecordingRepository,
    private val storage: StorageManager,
) {
    private companion object {
        const val META = "metadata.json"
        const val DIR = "recordings/"
    }

    /** Writes metadata first, then audio files, so an import can read the metadata before the audio. */
    suspend fun exportZip(target: Uri, items: List<RecordingEntity>): BackupResult = withContext(Dispatchers.IO) {
        val usable = items.filter { it.status != RecordingStatus.RECORDING && storage.exists(it) }
        val skipped = items.size - usable.size
        val out = ctx.contentResolver.openOutputStream(target, "w") ?: return@withContext BackupResult(0, items.size, "Could not open the destination")
        val names = HashSet<String>()
        ZipOutputStream(out.buffered()).use { zip ->
            val arr = JSONArray()
            val entryNames = usable.associateWith { e ->
                var base = displayName(e); var n = 1
                while (!names.add(base)) base = "${n++}_${displayName(e)}"
                base
            }
            usable.forEach { arr.put(toJson(it).put("entryName", entryNames.getValue(it))) }
            zip.putNextEntry(ZipEntry(META)); zip.write(arr.toString(2).toByteArray()); zip.closeEntry()
            usable.forEach { e ->
                zip.putNextEntry(ZipEntry(DIR + entryNames.getValue(e)))
                storage.open(e)?.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        BackupResult(usable.size, skipped, "Exported ${usable.size} recording(s)" + if (skipped > 0) ", skipped $skipped with missing files" else "")
    }

    suspend fun backupToTree(treeUri: String): BackupResult = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))
        if (tree == null || !tree.canWrite()) return@withContext BackupResult(0, 0, "Backup folder is not writable. Choose it again in Settings → Backup.")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val doc = tree.createFile("application/zip", "AdvancedCallRecorder-backup-$stamp.zip")
            ?: return@withContext BackupResult(0, 0, "Could not create the backup file")
        exportZip(doc.uri, repo.all())
    }

    suspend fun importZip(source: Uri): BackupResult = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(source) ?: return@withContext BackupResult(0, 0, "Could not open the file")
        var imported = 0; var skipped = 0
        val meta = HashMap<String, JSONObject>()
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == META -> JSONArray(zip.readBytes().toString(Charsets.UTF_8)).let { a ->
                        for (i in 0 until a.length()) a.getJSONObject(i).let { meta[it.optString("entryName")] = it }
                    }
                    entry.name.startsWith(DIR) && !entry.isDirectory -> {
                        val name = entry.name.removePrefix(DIR)
                        val m = meta[name]
                        val ok = importOne(zip, name, m)
                        if (ok) imported++ else skipped++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        BackupResult(imported, skipped, "Restored $imported recording(s)" + if (skipped > 0) ", skipped $skipped duplicate/invalid" else "")
    }

    private suspend fun importOne(zip: ZipInputStream, name: String, m: JSONObject?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        val format = AudioFormat.entries.firstOrNull { it.extension == ext } ?: return false
        val direction = m?.optString("direction")?.let { runCatching { CallDirection.valueOf(it) }.getOrNull() } ?: CallDirection.UNKNOWN
        val start = m?.optLong("startTime", 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val number = m?.optString("phoneNumber")?.ifBlank { null } ?: "UNKNOWN"
        if (m != null && repo.findDuplicate(number, start) != null) return false
        val tmp = File(storage.stagingDir, "import_${System.nanoTime()}.$ext")
        tmp.outputStream().use { zip.copyTo(it) }
        if (tmp.length() == 0L) { tmp.delete(); return false }
        val placed = storage.place(tmp, File(name).name, direction, m?.optBoolean("isPrivate") == true, format, null)
        repo.insert(fromJson(m, placed, format, direction, start, number, File(placed).length()))
        return true
    }

    /** Copies one recording to a location the user picked (Storage Access Framework). */
    suspend fun exportAudio(e: RecordingEntity, target: Uri): Boolean = withContext(Dispatchers.IO) {
        val input = storage.open(e) ?: return@withContext false
        val out = ctx.contentResolver.openOutputStream(target, "w") ?: return@withContext false
        input.use { i -> out.use { o -> i.copyTo(o) } }
        true
    }

    private fun displayName(e: RecordingEntity): String =
        if (e.isUri) storage.fileName(e.startTime, e.direction, e.phoneNumber, e.format) else File(e.filePath).name

    private fun toJson(e: RecordingEntity) = JSONObject().apply {
        put("phoneNumber", e.phoneNumber); put("contactName", e.contactName ?: JSONObject.NULL)
        put("direction", e.direction.name); put("startTime", e.startTime); put("endTime", e.endTime ?: JSONObject.NULL)
        put("durationMs", e.durationMs); put("format", e.format.name); put("engine", e.engine.name)
        put("quality", e.quality); put("captureMode", e.captureMode.name); put("status", e.status.name)
        put("favorite", e.favorite); put("important", e.important); put("isPrivate", e.isPrivate)
        put("notes", e.notes); put("tags", e.tags); put("transcript", e.transcript ?: JSONObject.NULL)
    }

    private fun fromJson(m: JSONObject?, path: String, format: AudioFormat, direction: CallDirection, start: Long, number: String, size: Long) =
        RecordingEntity(
            phoneNumber = number, contactName = m?.optString("contactName")?.takeIf { it != "null" && it.isNotBlank() },
            direction = direction, startTime = start, endTime = m?.optLong("endTime", 0L)?.takeIf { it > 0 },
            durationMs = m?.optLong("durationMs", 0L) ?: 0L, fileSize = size, filePath = path, format = format,
            engine = m?.optString("engine")?.let { runCatching { EngineId.valueOf(it) }.getOrNull() } ?: EngineId.DEFAULT,
            quality = m?.optString("quality")?.ifBlank { null } ?: "Imported",
            captureMode = m?.optString("captureMode")?.let { runCatching { CaptureMode.valueOf(it) }.getOrNull() } ?: CaptureMode.NONE,
            status = RecordingStatus.COMPLETED,
            favorite = m?.optBoolean("favorite") ?: false, important = m?.optBoolean("important") ?: false,
            isPrivate = m?.optBoolean("isPrivate") ?: false, notes = m?.optString("notes").orEmpty(),
            tags = m?.optString("tags").orEmpty(),
            transcript = m?.optString("transcript")?.takeIf { it != "null" && it.isNotBlank() },
        )
}
