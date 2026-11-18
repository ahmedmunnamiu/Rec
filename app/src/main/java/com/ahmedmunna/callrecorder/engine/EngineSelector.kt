package com.ahmedmunna.callrecorder.engine

import com.ahmedmunna.callrecorder.data.db.EngineTestEntity
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState
import com.ahmedmunna.callrecorder.root.RootEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineRegistry @Inject constructor() {
    val all: List<RecordingEngine> = listOf(
        VoiceCallEngine(), VoiceUplinkEngine(), VoiceDownlinkEngine(), MicEngine(),
        VoiceRecognitionEngine(), VoiceCommunicationEngine(), DefaultEngine(), CamcorderEngine(),
        UnprocessedEngine(), RemoteSubmixEngine(), SpeakerphoneEngine(), BluetoothEngine(),
        AdbEngine(), RootEngine(),
    )
    private val byId = all.associateBy { it.id }
    operator fun get(id: EngineId): RecordingEngine = byId.getValue(id)
}

data class Recommendation(val engine: EngineId, val expectedMode: CaptureMode, val reason: String)

/**
 * Picks an engine from measured results only. Order:
 * 1. verified two-way, 2. other call-audio sources that really delivered audio,
 * 3. speakerphone-assisted mic (only with consent), 4. best-scoring microphone engine, 5. DEFAULT.
 */
object EngineSelector {

    private val micOrder = listOf(
        EngineId.MIC, EngineId.VOICE_RECOGNITION, EngineId.UNPROCESSED,
        EngineId.CAMCORDER, EngineId.VOICE_COMMUNICATION, EngineId.DEFAULT,
    )

    fun recommend(tests: List<EngineTestEntity>, speakerphoneConsent: Boolean): Recommendation {
        val byEngine = tests.associateBy { it.engine }
        fun ok(id: EngineId) = byEngine[id]?.takeIf { it.state == EngineState.SUPPORTED }

        byEngine.values.filter { it.state == EngineState.SUPPORTED && it.captureMode == CaptureMode.TWO_WAY_VERIFIED }
            .minByOrNull { if (it.engine == EngineId.VOICE_CALL) 0 else 1 }
            ?.let { return Recommendation(it.engine, CaptureMode.TWO_WAY_VERIFIED, "Verified two-way capture in a real call test.") }

        listOf(EngineId.VOICE_CALL, EngineId.VOICE_DOWNLINK, EngineId.VOICE_UPLINK).forEach { id ->
            val t = ok(id)
            if (t != null && t.captureMode in setOf(CaptureMode.CALL_AUDIO_UNVERIFIED, CaptureMode.DOWNLINK_ONLY, CaptureMode.UPLINK_ONLY)) {
                return Recommendation(id, t.captureMode, "Call-audio source delivered audio, but only: ${t.captureMode.label.lowercase()}.")
            }
        }

        if (speakerphoneConsent) {
            ok(EngineId.SPEAKERPHONE)?.takeIf { it.captureMode == CaptureMode.MIC_ONLY }?.let {
                return Recommendation(EngineId.SPEAKERPHONE, CaptureMode.MIC_ONLY, "Speakerphone-assisted microphone capture tested OK and you enabled it.")
            }
        }

        val best = micOrder.mapNotNull { id -> ok(id)?.takeIf { it.captureMode == CaptureMode.MIC_ONLY } }
            .maxByOrNull { score(it) }
        if (best != null) {
            return Recommendation(best.engine, CaptureMode.MIC_ONLY, "Best-scoring microphone source from your tests. Records your side only.")
        }
        return Recommendation(EngineId.DEFAULT, CaptureMode.MIC_ONLY, "No engine has a passing test yet; falling back to the default microphone. Run the Engine Test Center.")
    }

    internal fun score(t: EngineTestEntity): Float =
        minOf(t.rms, 0.2f) * 5f - t.clippingPct * 0.5f - t.silencePct * 0.01f
}
