package com.ahmedmunna.callrecorder.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.data.repo.RecordingRepository
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Trims a recording into a NEW M4A file. The original is never modified or deleted. */
@OptIn(UnstableApi::class)
@Singleton
class AudioTrimmer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val storage: StorageManager,
    private val repo: RecordingRepository,
) {
    suspend fun trim(src: RecordingEntity, startMs: Long, endMs: Long): Result<Long> {
        if (endMs - startMs < 500) return Result.failure(IllegalArgumentException("Selection is too short"))
        val tmp = File(storage.stagingDir, "trim_${System.nanoTime()}.m4a")
        val error: String? = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
                val item = MediaItem.Builder().setUri(storage.uriFor(src)).setClippingConfiguration(clip).build()
                val edited = EditedMediaItem.Builder(item).setRemoveVideo(true).build()
                val transformer = Transformer.Builder(ctx)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(null) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resume(exportException.message ?: "Export failed")
                        }
                    }).build()
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(edited, tmp.absolutePath)
            }
        }
        if (error != null || !tmp.exists() || tmp.length() == 0L) {
            tmp.delete()
            return Result.failure(IllegalStateException(error ?: "Nothing was exported"))
        }
        val baseName = (if (src.isUri) "trim" else File(src.filePath).nameWithoutExtension) + "_trim"
        val placed = storage.place(tmp, "$baseName.m4a", src.direction, src.isPrivate, AudioFormat.M4A, null)
        val id = repo.insert(
            src.copy(
                id = 0, filePath = placed, format = AudioFormat.M4A, durationMs = endMs - startMs,
                fileSize = File(placed).length(), favorite = false, lastPlayPositionMs = 0,
                notes = (src.notes + "\nTrimmed copy of the recording from ${src.startTime}").trim(),
            )
        )
        return Result.success(id)
    }
}
