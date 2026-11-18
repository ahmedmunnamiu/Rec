package com.ahmedmunna.callrecorder.engine

import android.media.AudioFormat as Pcm
import android.media.AudioRecord
import android.media.MediaCodecList
import com.ahmedmunna.callrecorder.domain.AudioFormat

/** Checks what this device's encoders really support, so unsupported settings are never applied. */
object EncoderSupport {
    val SAMPLE_RATES = listOf(8000, 16000, 22050, 32000, 44100, 48000)
    val BITRATES = listOf(32_000, 64_000, 96_000, 128_000, 192_000, 256_000, 320_000)

    private const val MIME_AAC = "audio/mp4a-latm"
    private const val MIME_AMR_NB = "audio/3gpp"
    private const val MIME_AMR_WB = "audio/amr-wb"

    private fun encoders(mime: String) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }

    private fun mimeFor(format: AudioFormat): String? = when (format) {
        AudioFormat.M4A, AudioFormat.THREE_GP -> MIME_AAC
        AudioFormat.AMR_NB -> MIME_AMR_NB
        AudioFormat.AMR_WB -> MIME_AMR_WB
        else -> null
    }

    /** Formats that can actually be recorded here. MP3 is never included: Android ships no MP3 encoder. */
    fun availableFormats(): List<AudioFormat> = AudioFormat.entries.filter { f ->
        when {
            !f.recordable -> false
            f == AudioFormat.WAV -> true
            else -> encoders(mimeFor(f)!!).isNotEmpty()
        }
    }

    fun supportedSampleRates(format: AudioFormat): List<Int> {
        format.fixedSampleRate?.let { return listOf(it) }
        if (format == AudioFormat.WAV) {
            return SAMPLE_RATES.filter {
                AudioRecord.getMinBufferSize(it, Pcm.CHANNEL_IN_MONO, Pcm.ENCODING_PCM_16BIT) > 0
            }
        }
        val caps = encoders(MIME_AAC).mapNotNull { runCatching { it.getCapabilitiesForType(MIME_AAC).audioCapabilities }.getOrNull() }
        if (caps.isEmpty()) return emptyList()
        return SAMPLE_RATES.filter { r -> caps.any { it.isSampleRateSupported(r) } }
    }

    fun supportedBitrates(format: AudioFormat, sampleRate: Int): List<Int> {
        if (format == AudioFormat.WAV || format.fixedSampleRate != null) return emptyList()
        val caps = encoders(MIME_AAC).mapNotNull { runCatching { it.getCapabilitiesForType(MIME_AAC).audioCapabilities }.getOrNull() }
        return BITRATES.filter { b -> caps.any { c -> c.bitrateRange.contains(b) && c.isSampleRateSupported(sampleRate) } }
    }

    fun supportsStereo(format: AudioFormat): Boolean {
        if (format.fixedSampleRate != null) return false
        if (format == AudioFormat.WAV) return true
        return encoders(MIME_AAC).any {
            runCatching { it.getCapabilitiesForType(MIME_AAC).audioCapabilities.maxInputChannelCount >= 2 }.getOrDefault(false)
        }
    }

    data class Resolved(val sampleRate: Int, val bitrate: Int, val stereo: Boolean, val notes: List<String>)

    /** Snaps requested values to supported ones and reports every change. */
    fun resolve(format: AudioFormat, sampleRate: Int, bitrate: Int, stereo: Boolean): Resolved {
        val notes = mutableListOf<String>()
        val rates = supportedSampleRates(format)
        val rate = if (rates.isEmpty() || sampleRate in rates) sampleRate else rates.minBy { kotlin.math.abs(it - sampleRate) }
            .also { notes += "Sample rate $sampleRate Hz unsupported, using $it Hz" }
        val bitrates = supportedBitrates(format, rate)
        val br = if (bitrates.isEmpty() || bitrate in bitrates) bitrate else bitrates.minBy { kotlin.math.abs(it - bitrate) }
            .also { notes += "Bitrate ${bitrate / 1000} kbps unsupported, using ${it / 1000} kbps" }
        val st = stereo && supportsStereo(format)
        if (stereo && !st) notes += "Stereo unsupported for ${format.label}, using mono"
        return Resolved(rate, br, st, notes)
    }

    fun noiseSuppressionAvailable() = android.media.audiofx.NoiseSuppressor.isAvailable()
    fun echoCancellerAvailable() = android.media.audiofx.AcousticEchoCanceler.isAvailable()
    fun agcAvailable() = android.media.audiofx.AutomaticGainControl.isAvailable()
}
