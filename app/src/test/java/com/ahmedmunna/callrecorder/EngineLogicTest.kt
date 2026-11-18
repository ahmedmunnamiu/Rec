package com.ahmedmunna.callrecorder

import com.ahmedmunna.callrecorder.data.db.EngineTestEntity
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState
import com.ahmedmunna.callrecorder.engine.AudioStats
import com.ahmedmunna.callrecorder.engine.EngineSelector
import com.ahmedmunna.callrecorder.engine.EngineVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EngineVerdictTest {
    private val active = AudioStats(peak = 0.3f, rms = 0.2f, clippingPct = 0f, silencePct = 10f, samples = 1000)
    private val silent = AudioStats.EMPTY

    @Test fun `two-way is only reported when both phases have audio on a call source`() {
        val v = EngineVerdict.classify(EngineId.VOICE_CALL, true, listOf(active, active))
        assertEquals(EngineState.SUPPORTED, v.state)
        assertEquals(CaptureMode.TWO_WAY_VERIFIED, v.mode)
    }

    @Test fun `call source with only the local phase is not two-way`() {
        val v = EngineVerdict.classify(EngineId.VOICE_CALL, true, listOf(silent, active))
        assertEquals(CaptureMode.UPLINK_ONLY, v.mode)
    }

    @Test fun `call source with only the remote phase is downlink only`() {
        val v = EngineVerdict.classify(EngineId.VOICE_CALL, true, listOf(active, silent))
        assertEquals(CaptureMode.DOWNLINK_ONLY, v.mode)
    }

    @Test fun `microphone engine is never reported as two-way even with both phases audible`() {
        val v = EngineVerdict.classify(EngineId.MIC, true, listOf(active, active))
        assertEquals(CaptureMode.MIC_ONLY, v.mode)
        assertNotEquals(CaptureMode.TWO_WAY_VERIFIED, v.mode)
    }

    @Test fun `silence during a call on a call source is restricted`() {
        val v = EngineVerdict.classify(EngineId.VOICE_CALL, true, listOf(silent))
        assertEquals(EngineState.RESTRICTED, v.state)
        assertEquals(CaptureMode.NONE, v.mode)
    }

    @Test fun `silence on a call source outside a call is not a verdict`() {
        val v = EngineVerdict.classify(EngineId.VOICE_CALL, false, listOf(silent))
        assertEquals(EngineState.NOT_TESTED, v.state)
    }

    @Test fun `single phase call audio stays unverified`() {
        val v = EngineVerdict.classify(EngineId.VOICE_CALL, true, listOf(active))
        assertEquals(CaptureMode.CALL_AUDIO_UNVERIFIED, v.mode)
    }

    @Test fun `uplink with only the local voice is uplink only`() {
        val v = EngineVerdict.classify(EngineId.VOICE_UPLINK, true, listOf(silent, active))
        assertEquals(CaptureMode.UPLINK_ONLY, v.mode)
    }

    @Test fun `microphone audio outside a call is mic only`() {
        val v = EngineVerdict.classify(EngineId.VOICE_RECOGNITION, false, listOf(active))
        assertEquals(EngineState.SUPPORTED, v.state)
        assertEquals(CaptureMode.MIC_ONLY, v.mode)
    }
}

class EngineSelectorTest {
    private fun test(id: EngineId, mode: CaptureMode, rms: Float = 0.1f, state: EngineState = EngineState.SUPPORTED) =
        EngineTestEntity(engine = id, state = state, captureMode = mode, testedAt = 0, rms = rms)

    @Test fun `no tests falls back to default microphone`() {
        val r = EngineSelector.recommend(emptyList(), false)
        assertEquals(EngineId.DEFAULT, r.engine)
        assertEquals(CaptureMode.MIC_ONLY, r.expectedMode)
    }

    @Test fun `verified two-way wins over everything`() {
        val r = EngineSelector.recommend(
            listOf(test(EngineId.MIC, CaptureMode.MIC_ONLY), test(EngineId.VOICE_CALL, CaptureMode.TWO_WAY_VERIFIED)), true,
        )
        assertEquals(EngineId.VOICE_CALL, r.engine)
        assertEquals(CaptureMode.TWO_WAY_VERIFIED, r.expectedMode)
    }

    @Test fun `a call source that delivered one side beats the microphone`() {
        val r = EngineSelector.recommend(
            listOf(test(EngineId.MIC, CaptureMode.MIC_ONLY), test(EngineId.VOICE_DOWNLINK, CaptureMode.DOWNLINK_ONLY)), false,
        )
        assertEquals(EngineId.VOICE_DOWNLINK, r.engine)
    }

    @Test fun `restricted engines are never recommended`() {
        val r = EngineSelector.recommend(
            listOf(test(EngineId.VOICE_CALL, CaptureMode.NONE, state = EngineState.RESTRICTED), test(EngineId.MIC, CaptureMode.MIC_ONLY)), false,
        )
        assertEquals(EngineId.MIC, r.engine)
    }

    @Test fun `speakerphone is used only with consent`() {
        val tests = listOf(test(EngineId.MIC, CaptureMode.MIC_ONLY), test(EngineId.SPEAKERPHONE, CaptureMode.MIC_ONLY))
        assertEquals(EngineId.SPEAKERPHONE, EngineSelector.recommend(tests, true).engine)
        assertEquals(EngineId.MIC, EngineSelector.recommend(tests, false).engine)
    }

    @Test fun `best scoring microphone source is chosen`() {
        val r = EngineSelector.recommend(
            listOf(test(EngineId.MIC, CaptureMode.MIC_ONLY, rms = 0.05f), test(EngineId.VOICE_RECOGNITION, CaptureMode.MIC_ONLY, rms = 0.10f)), false,
        )
        assertEquals(EngineId.VOICE_RECOGNITION, r.engine)
    }
}
