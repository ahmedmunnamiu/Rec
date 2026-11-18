package com.ahmedmunna.callrecorder.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.CallDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageManager @Inject constructor(@ApplicationContext private val ctx: Context) {

    /** Android/media/<package>/CallRecordings, falling back to app-specific storage. */
    val rootDir: File
        get() {
            @Suppress("DEPRECATION")
            val media = ctx.externalMediaDirs.firstOrNull { it != null }
            val base = media ?: ctx.getExternalFilesDir(null) ?: ctx.filesDir
            return File(base, "CallRecordings").apply { mkdirs() }
        }

    /** Where in-progress recordings live until they are finalised. */
    val stagingDir: File get() = File(rootDir, ".staging").apply { mkdirs() }

    fun folderFor(direction: CallDirection, private: Boolean): File {
        val name = when {
            private -> "Private"
            direction == CallDirection.INCOMING -> "Incoming Calls"
            direction == CallDirection.OUTGOING -> "Outgoing Calls"
            else -> "Other Calls"
        }
        return File(rootDir, name).apply { mkdirs() }
    }

    fun stagingFile(startTime: Long, format: AudioFormat) = File(stagingDir, "rec_$startTime.${format.extension}")

    /** YYYY-MM-DD_HH-MM-SS_INCOMING_PHONE_NUMBER.ext */
    fun fileName(startTime: Long, direction: CallDirection, number: String, format: AudioFormat): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startTime))
        val safe = number.filter { it.isDigit() || it == '+' }.ifEmpty { "UNKNOWN" }
        return "${ts}_${direction.name}_$safe.${format.extension}"
    }

    /** Moves a finished staging file to its final place. Returns the path or content URI to store. */
    fun place(staging: File, name: String, direction: CallDirection, private: Boolean, format: AudioFormat, treeUri: String?): String {
        if (!treeUri.isNullOrBlank()) {
            val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))
            if (tree != null && tree.canWrite()) {
                val sub = if (private) "Private" else if (direction == CallDirection.INCOMING) "Incoming Calls" else "Outgoing Calls"
                val dir = tree.findFile(sub)?.takeIf { it.isDirectory } ?: tree.createDirectory(sub)
                val doc = dir?.createFile(format.mime, name.substringBeforeLast('.'))
                if (doc != null) {
                    val ok = ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                        staging.inputStream().use { it.copyTo(out) }
                        true
                    } ?: false
                    if (ok) { staging.delete(); return doc.uri.toString() }
                    doc.delete()
                }
            }
            // Chosen folder not writable any more: fall through to default location rather than lose the recording.
        }
        var target = File(folderFor(direction, private), name)
        var n = 1
        while (target.exists()) { target = File(target.parentFile, name.substringBeforeLast('.') + "_$n." + name.substringAfterLast('.')); n++ }
        if (!staging.renameTo(target)) { staging.copyTo(target); staging.delete() }
        return target.absolutePath
    }

    fun open(entity: RecordingEntity): InputStream? =
        if (entity.isUri) ctx.contentResolver.openInputStream(Uri.parse(entity.filePath))
        else File(entity.filePath).takeIf { it.exists() }?.inputStream()

    fun uriFor(entity: RecordingEntity): Uri =
        if (entity.isUri) Uri.parse(entity.filePath) else Uri.fromFile(File(entity.filePath))

    fun exists(entity: RecordingEntity): Boolean =
        if (entity.isUri) DocumentFile.fromSingleUri(ctx, Uri.parse(entity.filePath))?.exists() == true
        else File(entity.filePath).exists()

    fun size(entity: RecordingEntity): Long =
        if (entity.isUri) DocumentFile.fromSingleUri(ctx, Uri.parse(entity.filePath))?.length() ?: 0L
        else File(entity.filePath).length()

    fun delete(entity: RecordingEntity): Boolean =
        if (entity.isUri) DocumentFile.fromSingleUri(ctx, Uri.parse(entity.filePath))?.delete() == true
        else File(entity.filePath).let { !it.exists() || it.delete() }

    /** Returns the new path/URI, or null when renaming failed. */
    fun rename(entity: RecordingEntity, newBaseName: String): String? {
        val clean = newBaseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (clean.isEmpty()) return null
        return if (entity.isUri) {
            val doc = DocumentFile.fromSingleUri(ctx, Uri.parse(entity.filePath)) ?: return null
            val ext = entity.format.extension
            if (doc.renameTo("$clean.$ext")) doc.uri.toString() else null
        } else {
            val f = File(entity.filePath)
            val target = File(f.parentFile, "$clean.${f.extension}")
            if (target.exists()) null else if (f.renameTo(target)) target.absolutePath else null
        }
    }

    fun freeBytes(): Long = runCatching { StatFs(rootDir.absolutePath).availableBytes }.getOrDefault(0L)
    fun totalBytes(): Long = runCatching { StatFs(rootDir.absolutePath).totalBytes }.getOrDefault(0L)

    fun isLowStorage(thresholdMb: Int): Boolean = thresholdMb > 0 && freeBytes() < thresholdMb * 1024L * 1024L

    fun isExternalMounted() = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
}
