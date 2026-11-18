package com.ahmedmunna.callrecorder.service

import android.media.MediaMetadataRetriever
import com.ahmedmunna.callrecorder.data.repo.RecordingRepository
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.storage.StorageManager
import java.io.File

/** Repairs recordings that were still marked RECORDING when the process or service was killed. */
class RecoveryManager(private val repo: RecordingRepository, private val storage: StorageManager) {

    /** @return number of recordings handled */
    suspend fun recover(activeRowId: Long): Int {
        var handled = 0
        repo.inProgress().filter { it.id != activeRowId }.forEach { row ->
            handled++
            val f = File(row.filePath)
            if (row.filePath.isBlank() || !f.exists() || f.length() == 0L) {
                repo.update(row.copy(status = RecordingStatus.FAILED, failureReason = "Interrupted before any audio was saved"))
                return@forEach
            }
            val duration = probeDuration(f) ?: (f.lastModified() - row.startTime).coerceAtLeast(0)
            val name = storage.fileName(row.startTime, row.direction, row.phoneNumber, row.format)
            val placed = runCatching { storage.place(f, name, row.direction.takeIf { it != CallDirection.UNKNOWN } ?: CallDirection.UNKNOWN, false, row.format, null) }
                .getOrDefault(f.absolutePath)
            val size = File(placed).takeIf { it.exists() }?.length() ?: f.length()
            repo.update(
                row.copy(
                    filePath = placed, durationMs = duration, endTime = row.startTime + duration, fileSize = size,
                    status = RecordingStatus.INCOMPLETE,
                    failureReason = "Recovered after the recorder was interrupted. The end of the call may be missing" +
                        if (row.format.name == "M4A" || row.format.name == "THREE_GP") " and the file may not play (MP4 containers need a clean stop)." else ".",
                )
            )
        }
        return handled
    }

    private fun probeDuration(f: File): Long? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            r.release()
        }
    }.getOrNull()
}
