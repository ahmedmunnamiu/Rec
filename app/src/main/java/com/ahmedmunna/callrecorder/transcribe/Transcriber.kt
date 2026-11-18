package com.ahmedmunna.callrecorder.transcribe

import android.content.Context
import android.content.Intent
import android.media.AudioFormat as Pcm
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Optional transcription using Android's ON-DEVICE speech recogniser only (never a network service).
 * Needs Android 13+ (file input for SpeechRecognizer) and an installed on-device recognition model.
 * Disabled by default; the user switches it on in Settings.
 */
@Singleton
class Transcriber @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val storage: StorageManager,
) {
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    private data class Pcm16(val file: File, val rate: Int, val channels: Int)

    suspend fun transcribe(r: RecordingEntity): Result<String> {
        if (Build.VERSION.SDK_INT < 33) return Result.failure(IllegalStateException("Needs Android 13 or newer"))
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            return Result.failure(IllegalStateException("No on-device speech recogniser is installed on this phone"))
        }
        val pcm = withContext(Dispatchers.IO) { runCatching { decode(r) } }.getOrElse { return Result.failure(it) }
        return try {
            val text = withTimeout(10 * 60_000L) { recognize(pcm) }
            if (text.isBlank()) Result.failure(IllegalStateException("No speech was recognised")) else Result.success(text)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            pcm.file.delete()
        }
    }

    private suspend fun recognize(pcm: Pcm16): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String> { cont ->
            val pfd = ParcelFileDescriptor.open(pcm.file, ParcelFileDescriptor.MODE_READ_ONLY)
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(result: Result<String>) {
                if (!done.compareAndSet(false, true)) return
                runCatching { recognizer.destroy() }
                runCatching { pfd.close() }
                if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWith(Result.failure(it)) })
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    finish(Result.success(list?.firstOrNull().orEmpty()))
                }
                override fun onError(error: Int) = finish(Result.failure(IllegalStateException("Recogniser error code $error")))
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            cont.invokeOnCancellation { runCatching { recognizer.cancel(); recognizer.destroy() }; runCatching { pfd.close() } }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, Pcm.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.rate)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcm.channels)
            recognizer.startListening(intent)
        }
    }

    /** Decodes any supported recording to raw PCM16 in the cache directory. */
    private fun decode(r: RecordingEntity): Pcm16 {
        val out = File(ctx.cacheDir, "transcribe_${System.nanoTime()}.pcm")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ctx, storage.uriFor(r), null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            FileOutputStream(out).use { fos ->
                if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                    val buf = java.nio.ByteBuffer.allocate(32 * 1024)
                    val tmp = ByteArray(32 * 1024)
                    while (true) {
                        buf.clear()
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) break
                        buf.position(0); buf.get(tmp, 0, n); fos.write(tmp, 0, n)
                        extractor.advance()
                    }
                } else {
                    val c = MediaCodec.createDecoderByType(mime).also { codec = it }
                    c.configure(format, null, null, 0); c.start()
                    val info = MediaCodec.BufferInfo()
                    var inDone = false; var outDone = false
                    val tmp = ByteArray(64 * 1024)
                    while (!outDone) {
                        if (!inDone) {
                            val i = c.dequeueInputBuffer(10_000)
                            if (i >= 0) {
                                val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                                if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                                else { c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                            }
                        }
                        when (val o = c.dequeueOutputBuffer(info, 10_000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f = c.outputFormat
                                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            else -> if (o >= 0) {
                                val b = c.getOutputBuffer(o)!!
                                b.position(info.offset); b.limit(info.offset + info.size)
                                while (b.hasRemaining()) { val n = minOf(b.remaining(), tmp.size); b.get(tmp, 0, n); fos.write(tmp, 0, n) }
                                c.releaseOutputBuffer(o, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                            }
                        }
                    }
                }
            }
            return Pcm16(out, rate, channels)
        } catch (t: Throwable) {
            out.delete(); throw t
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }
}
