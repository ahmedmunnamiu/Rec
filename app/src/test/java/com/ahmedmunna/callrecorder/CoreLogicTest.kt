package com.ahmedmunna.callrecorder

import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.data.prefs.AppSettings
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.QualityPreset
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.security.PinHasher
import com.ahmedmunna.callrecorder.util.PhoneUtil
import com.ahmedmunna.callrecorder.work.CleanupPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilTest {
    @Test fun `normalize keeps digits and a leading plus`() {
        assertEquals("+15551234567", PhoneUtil.normalize("+1 (555) 123-4567"))
        assertEquals("5551234567", PhoneUtil.normalize("555-123-4567"))
    }

    @Test fun `blank or non numeric input is unknown`() {
        assertEquals("UNKNOWN", PhoneUtil.normalize(null))
        assertEquals("UNKNOWN", PhoneUtil.normalize("  "))
        assertEquals("UNKNOWN", PhoneUtil.normalize("Private"))
    }

    @Test fun `numbers match regardless of country prefix formatting`() {
        assertTrue(PhoneUtil.sameNumber("+15551234567", "5551234567"))
        assertFalse(PhoneUtil.sameNumber("+15551234567", "5551234568"))
        assertFalse(PhoneUtil.sameNumber("UNKNOWN", "UNKNOWN"))
    }
}

class PinHasherTest {
    @Test fun `correct pin verifies and wrong pin does not`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("4821", salt)
        assertTrue(PinHasher.verify("4821", salt, hash))
        assertFalse(PinHasher.verify("4822", salt, hash))
    }

    @Test fun `same pin with different salts gives different hashes`() {
        assertFalse(PinHasher.hash("1234", PinHasher.newSalt()) == PinHasher.hash("1234", PinHasher.newSalt()))
    }
}

class SettingsAndCleanupTest {
    private val day = 86_400_000L
    private fun rec(id: Long, ageDays: Int, size: Long = 1_000_000, fav: Boolean = false, important: Boolean = false) = RecordingEntity(
        id = id, phoneNumber = "555", direction = CallDirection.INCOMING, startTime = 1_000 * day - ageDays * day,
        fileSize = size, filePath = "/x/$id.m4a", format = AudioFormat.M4A, engine = EngineId.MIC, quality = "High",
        captureMode = CaptureMode.MIC_ONLY, status = RecordingStatus.COMPLETED, favorite = fav, important = important,
    )

    private val now = 1_000 * day

    @Test fun `cleanup by age never includes favorites or important recordings`() {
        val all = listOf(rec(1, 100), rec(2, 100, fav = true), rec(3, 100, important = true), rec(4, 5))
        val picked = CleanupPlanner.candidates(all, AppSettings(cleanupDays = 30), now).map { it.id }
        assertEquals(listOf(1L), picked)
    }

    @Test fun `cleanup by storage limit proposes oldest first until under the limit`() {
        val all = listOf(rec(1, 30, 3_000_000), rec(2, 20, 3_000_000), rec(3, 10, 3_000_000))
        val picked = CleanupPlanner.candidates(all, AppSettings(storageLimitMb = 5), now).map { it.id }
        assertEquals(listOf(1L, 2L), picked)
    }

    @Test fun `nothing is proposed when no rule is enabled`() {
        assertTrue(CleanupPlanner.candidates(listOf(rec(1, 500)), AppSettings(), now).isEmpty())
    }

    @Test fun `wav ignores the compact cap while aac honours it`() {
        val aac = AppSettings(format = AudioFormat.M4A, quality = QualityPreset.VERY_HIGH, compression = true)
        assertEquals(16000, aac.sampleRate)
        assertEquals(64_000, aac.bitrate)
        val wav = AppSettings(format = AudioFormat.WAV, quality = QualityPreset.VERY_HIGH, compression = true)
        assertEquals(48000, wav.sampleRate)
    }

    @Test fun `amr formats force their fixed sample rate`() {
        assertEquals(8000, AppSettings(format = AudioFormat.AMR_NB).sampleRate)
        assertEquals(16000, AppSettings(format = AudioFormat.AMR_WB).sampleRate)
    }
}
