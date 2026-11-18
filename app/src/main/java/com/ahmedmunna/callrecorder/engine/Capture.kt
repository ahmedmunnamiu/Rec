package com.ahmedmunna.callrecorder.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat as Pcm
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.ahmedmunna.callrecorder.domain.AudioFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

data class EngineConfig(
    val format: AudioFormat,
    val sampleRate: Int,
    val bitrate: Int,
    val stereo: Boolean,
    val noiseSuppression: Boolean,
    val echoCancellation: Boolean,
    val autoGain: Boolean,
    val gainDb: Int,
    val preferredDevice: AudioDeviceInfo? = null,
)

interface RecorderSession {
    fun start()

    /** Stops recording. Returns true when a non-empty file was produced. */
    fun stop(): Boolean

    /** Peak amplitude (0..32767) seen since the previous call. */
    fun peak(): Int

    /** Non-null when the capture thread died unexpectedly. */
    fun error(): String? = null

    fun release()
}

/** Low-level PCM16 capture through [AudioRecord]. Also used by the engine tester. */
class PcmCapture(
    private val source: Int,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val preferred: AudioDeviceInfo? = null,
    private val noiseSuppression: Boolean = false,
    private val echoCancellation: Boolean = false,
    private val autoGain: Boolean = false,
) {
    private var record: AudioRecord? = null
    private val effects = mutableListOf<AudioEffect>()
    val effectsApplied = mutableListOf<String>()

    @SuppressLint("MissingPermission")
    fun open() {
        val mask = if (channels == 2) Pcm.CHANNEL_IN_STEREO else Pcm.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(sampleRate, mask, Pcm.ENCODING_PCM_16BIT)
        check(min > 0) { "Sample rate $sampleRate Hz / $channels ch is not supported by this device (code $min)" }
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                Pcm.Builder()
                    .setEncoding(Pcm.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(min * 4)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord failed to initialise for audio source $source")
        }
        preferred?.let { rec.setPreferredDevice(it) }
        val sid = rec.audioSessionId
        if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sid)?.let { it.setEnabled(true); effects += it; effectsApplied += "noise suppression" }
        }
        if (echoCancellation && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sid)?.let { it.setEnabled(true); effects += it; effectsApplied += "echo cancellation" }
        }
        if (autoGain && AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sid)?.let { it.setEnabled(true); effects += it; effectsApplied += "auto gain" }
        }
        record = rec
    }

    fun start() {
        val r = checkNotNull(record) { "open() not called" }
        r.startRecording()
        check(r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android refused to start recording (source $source)" }
    }

    fun read(buf: ShortArray): Int = record?.read(buf, 0, buf.size) ?: -1

    fun close() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        record?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            runCatching { it.release() }
        }
        record = null
    }
}

/** Minimal WAV writer that refreshes its header every second so a killed process leaves a playable file. */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var lastHeaderAt = 0L

    init {
        raf.setLength(0)
        writeHeader()
    }

    fun write(buf: ShortArray, len: Int) {
        val bytes = ByteArray(len * 2)
        var j = 0
        for (i in 0 until len) {
            val v = buf[i].toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
        if (dataBytes - lastHeaderAt >= sampleRate * channels * 2L) {
            writeHeader()
            lastHeaderAt = dataBytes
        }
    }

    fun close() {
        runCatching { writeHeader() }
        runCatching { raf.close() }
    }

    private fun writeHeader() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt())
        h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray()); h.putInt(16)
        h.putShort(1); h.putShort(channels.toShort()); h.putInt(sampleRate)
        h.putInt(sampleRate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.seek(44 + dataBytes)
    }
}

/** WAV recording through AudioRecord. Supports gain, noise suppression, echo cancellation and AGC. */
class PcmWavSession(
    private val capture: PcmCapture,
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val gainDb: Int,
) : RecorderSession {
    @Volatile private var running = false
    @Volatile private var peakValue = 0
    @Volatile private var failure: String? = null
    private var thread: Thread? = null
    private var writer: WavWriter? = null

    override fun start() {
        try {
            capture.open()
            writer = WavWriter(file, sampleRate, channels)
            capture.start()
        } catch (t: Throwable) {
            release()
            throw t
        }
        running = true
        thread = Thread({ loop() }, "pcm-wav-writer").also { it.start() }
    }

    private fun loop() {
        val buf = ShortArray(2048)
        val gain = 10.0.pow(gainDb / 20.0).toFloat()
        try {
            while (running) {
                val n = capture.read(buf)
                if (n < 0) { failure = "AudioRecord read error $n"; break }
                if (n == 0) continue
                var p = 0
                for (i in 0 until n) {
                    var v = buf[i].toInt()
                    if (gainDb != 0) v = (v * gain).toInt().coerceIn(-32768, 32767)
                    buf[i] = v.toShort()
                    val a = if (v < 0) -v else v
                    if (a > p) p = a
                }
                if (p > peakValue) peakValue = p
                writer?.write(buf, n)
            }
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
        }
    }

    override fun stop(): Boolean {
        running = false
        runCatching { thread?.join(2000) }
        release()
        return file.exists() && file.length() > 44
    }

    override fun peak(): Int { val p = peakValue; peakValue = 0; return p }
    override fun error(): String? = failure

    override fun release() {
        running = false
        writer?.close(); writer = null
        capture.close()
    }
}

/** M4A / 3GP / AMR through [MediaRecorder]. */
class MediaRecorderSession(
    private val ctx: Context,
    private val source: Int,
    private val file: File,
    private val cfg: EngineConfig,
) : RecorderSession {
    private var recorder: MediaRecorder? = null
    private var started = false

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    override fun start() {
        val r = newRecorder()
        recorder = r
        try {
            r.setAudioSource(source)
            val (container, encoder) = when (cfg.format) {
                AudioFormat.M4A -> MediaRecorder.OutputFormat.MPEG_4 to MediaRecorder.AudioEncoder.AAC
                AudioFormat.THREE_GP -> MediaRecorder.OutputFormat.THREE_GPP to MediaRecorder.AudioEncoder.AAC
                AudioFormat.AMR_NB -> MediaRecorder.OutputFormat.AMR_NB to MediaRecorder.AudioEncoder.AMR_NB
                AudioFormat.AMR_WB -> MediaRecorder.OutputFormat.AMR_WB to MediaRecorder.AudioEncoder.AMR_WB
                else -> throw IllegalArgumentException("${cfg.format} cannot be recorded with MediaRecorder")
            }
            r.setOutputFormat(container)
            r.setAudioEncoder(encoder)
            if (Build.VERSION.SDK_INT >= 28) cfg.preferredDevice?.let { r.setPreferredDevice(it) }
            if (cfg.format != AudioFormat.AMR_NB && cfg.format != AudioFormat.AMR_WB) {
                r.setAudioChannels(if (cfg.stereo) 2 else 1)
                r.setAudioSamplingRate(cfg.sampleRate)
                r.setAudioEncodingBitRate(cfg.bitrate)
            }
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            started = true
        } catch (t: Throwable) {
            release()
            throw t
        }
    }

    override fun peak(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    override fun stop(): Boolean {
        val r = recorder ?: return false
        var ok = started
        try {
            if (started) r.stop()
        } catch (e: RuntimeException) {
            ok = false // stop() throws when no valid audio data was received
        }
        release()
        return ok && file.exists() && file.length() > 0
    }

    override fun release() {
        recorder?.let { runCatching { it.reset() }; runCatching { it.release() } }
        recorder = null
        started = false
    }
}
