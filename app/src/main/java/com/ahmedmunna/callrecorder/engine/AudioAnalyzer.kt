package com.ahmedmunna.callrecorder.engine

import kotlin.math.abs
import kotlin.math.sqrt

/** Result of analysing a block of PCM16 audio. Levels are normalised to 0..1. */
data class AudioStats(
    val peak: Float,
    val rms: Float,
    val clippingPct: Float,
    val silencePct: Float,
    val samples: Long,
) {
    val activeRatio: Float get() = 1f - silencePct / 100f
    val isSilent: Boolean get() = samples == 0L || peak < 0.01f || activeRatio < 0.02f || rms < 0.002f
    val isLowVolume: Boolean get() = !isSilent && rms < 0.02f
    val isClipping: Boolean get() = clippingPct > 0.5f
    val isDistorted: Boolean get() = clippingPct > 2f

    fun qualityNotes(): List<String> = buildList {
        if (isSilent) add("silence")
        if (isLowVolume) add("low volume")
        if (isClipping) add("clipping")
        if (isDistorted) add("likely distortion")
    }

    companion object {
        val EMPTY = AudioStats(0f, 0f, 0f, 100f, 0)
    }
}

/** Streaming analyser: feed PCM16 buffers, then read [result]. */
class AudioAnalyzer(sampleRate: Int) {
    private val windowSize = (sampleRate / 50).coerceAtLeast(1) // 20 ms
    private var peak = 0
    private var sumSq = 0.0
    private var count = 0L
    private var clipped = 0L
    private var winSum = 0.0
    private var winCount = 0
    private var silentWindows = 0L
    private var totalWindows = 0L

    fun feed(buf: ShortArray, len: Int) {
        for (i in 0 until len) {
            val v = buf[i].toInt()
            val a = abs(v)
            if (a > peak) peak = a
            if (a >= CLIP_LEVEL) clipped++
            val sq = v.toDouble() * v
            sumSq += sq
            count++
            winSum += sq
            winCount++
            if (winCount >= windowSize) {
                if (sqrt(winSum / winCount) < SILENCE_RMS) silentWindows++
                totalWindows++
                winSum = 0.0
                winCount = 0
            }
        }
    }

    fun result(): AudioStats {
        if (count == 0L) return AudioStats.EMPTY
        return AudioStats(
            peak = peak / 32768f,
            rms = (sqrt(sumSq / count) / 32768.0).toFloat(),
            clippingPct = clipped * 100f / count,
            silencePct = if (totalWindows == 0L) 100f else silentWindows * 100f / totalWindows,
            samples = count,
        )
    }

    private companion object {
        const val CLIP_LEVEL = 32000
        const val SILENCE_RMS = 250.0 // about -42 dBFS
    }
}
