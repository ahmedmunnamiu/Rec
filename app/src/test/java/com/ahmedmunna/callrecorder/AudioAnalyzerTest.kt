package com.ahmedmunna.callrecorder

import com.ahmedmunna.callrecorder.engine.AudioAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioAnalyzerTest {
    private val rate = 16000

    private fun sine(amplitude: Int, seconds: Int = 1): ShortArray =
        ShortArray(rate * seconds) { (amplitude * sin(2 * PI * 440 * it / rate)).toInt().toShort() }

    private fun analyze(buf: ShortArray) = AudioAnalyzer(rate).apply { feed(buf, buf.size) }.result()

    @Test fun `digital silence is detected`() {
        val s = analyze(ShortArray(rate))
        assertTrue(s.isSilent)
        assertEquals(100f, s.silencePct, 0.01f)
    }

    @Test fun `no samples counts as silence`() {
        assertTrue(AudioAnalyzer(rate).result().isSilent)
    }

    @Test fun `normal speech level is healthy`() {
        val s = analyze(sine(10_000))
        assertFalse(s.isSilent)
        assertFalse(s.isLowVolume)
        assertFalse(s.isClipping)
        assertEquals(0.305f, s.peak, 0.01f)
        assertEquals(0.216f, s.rms, 0.01f)
    }

    @Test fun `quiet signal is flagged as low volume`() {
        val s = analyze(sine(400))
        assertFalse(s.isSilent)
        assertTrue(s.isLowVolume)
    }

    @Test fun `full scale signal is flagged as clipping and distortion`() {
        val s = analyze(ShortArray(rate) { 32767 })
        assertTrue(s.isClipping)
        assertTrue(s.isDistorted)
        assertTrue("clipping" in s.qualityNotes())
    }

    @Test fun `analysis works across several buffers`() {
        val a = AudioAnalyzer(rate)
        val chunk = sine(8000)
        repeat(4) { a.feed(chunk, chunk.size) }
        assertEquals(rate * 4L, a.result().samples)
    }
}
