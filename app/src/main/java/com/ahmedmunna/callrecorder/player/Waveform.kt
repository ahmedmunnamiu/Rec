package com.ahmedmunna.callrecorder.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Decodes a recording and reduces it to [bars] normalised peak values for the waveform view. */
object Waveform {
    suspend fun compute(ctx: Context, uri: Uri, bars: Int = 120): FloatArray = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(ctx, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext FloatArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val peaks = ArrayList<Float>()

            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                val buf = java.nio.ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    coroutineContext.ensureActive()
                    buf.clear()
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    buf.limit(n); buf.position(0)
                    peaks += peakOf(buf.asShortBuffer())
                    extractor.advance()
                }
            } else {
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                c.configure(format, null, null, 0)
                c.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        val out = c.getOutputBuffer(o)!!
                        out.position(info.offset); out.limit(info.offset + info.size)
                        if (info.size > 0) peaks += peakOf(out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            reduce(peaks, bars)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun peakOf(sb: java.nio.ShortBuffer): Float {
        var p = 0
        while (sb.hasRemaining()) { val v = abs(sb.get().toInt()); if (v > p) p = v }
        return p / 32768f
    }

    private fun reduce(peaks: List<Float>, bars: Int): FloatArray {
        if (peaks.isEmpty()) return FloatArray(0)
        val out = FloatArray(bars)
        val per = peaks.size / bars.toFloat()
        for (b in 0 until bars) {
            val from = (b * per).toInt().coerceAtMost(peaks.size - 1)
            val to = maxOf(from + 1, ((b + 1) * per).toInt()).coerceAtMost(peaks.size)
            var m = 0f
            for (i in from until to) if (peaks[i] > m) m = peaks[i]
            out[b] = m
        }
        val max = out.max().takeIf { it > 0f } ?: 1f
        for (i in out.indices) out[i] = out[i] / max
        return out
    }
}
