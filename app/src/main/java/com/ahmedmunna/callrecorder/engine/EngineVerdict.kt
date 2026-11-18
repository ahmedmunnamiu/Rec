package com.ahmedmunna.callrecorder.engine

import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState

data class Verdict(val state: EngineState, val mode: CaptureMode, val message: String)

/**
 * Turns measured audio into a verdict. Initialising a recorder is never treated as proof of capture:
 * two-way recording is only reported when BOTH the remote-only and the local-only phases of a call
 * test contained real audio on the call-audio source.
 */
object EngineVerdict {

    private val callSources = setOf(EngineId.VOICE_CALL, EngineId.VOICE_UPLINK, EngineId.VOICE_DOWNLINK, EngineId.REMOTE_SUBMIX)

    private fun AudioStats.active() = !isSilent

    /**
     * @param phases one entry for a simple test; two entries for the guided call test
     * (index 0 = remote speaks / you are silent, index 1 = you speak / remote silent).
     */
    fun classify(engine: EngineId, callActive: Boolean, phases: List<AudioStats>): Verdict {
        require(phases.isNotEmpty()) { "no audio phases" }
        val notes = phases.flatMap { it.qualityNotes() }.filter { it != "silence" }.distinct()
        val suffix = if (notes.isEmpty()) "" else " Quality flags: ${notes.joinToString()}."
        val isCallSource = engine in callSources

        val core = if (phases.size == 1) single(engine, isCallSource, callActive, phases[0])
        else guided(engine, isCallSource, phases[0].active(), phases[1].active())
        return core.copy(message = core.message + suffix)
    }

    private fun single(engine: EngineId, isCallSource: Boolean, callActive: Boolean, s: AudioStats): Verdict = when {
        s.isSilent && isCallSource && !callActive -> Verdict(
            EngineState.NOT_TESTED, CaptureMode.NONE,
            "The source opened but delivered only silence. Call sources are silent outside a call; run the test during a call."
        )
        s.isSilent && isCallSource -> Verdict(
            EngineState.RESTRICTED, CaptureMode.NONE,
            "Android delivered silence during a call. Call-audio capture is blocked on this device."
        )
        s.isSilent -> Verdict(
            EngineState.RESTRICTED, CaptureMode.NONE,
            "Only silence was recorded. Either nothing was said, or Android blocks microphone capture (common during calls and in the background)."
        )
        isCallSource && callActive -> Verdict(
            EngineState.SUPPORTED, CaptureMode.CALL_AUDIO_UNVERIFIED,
            "Audio was captured during a call, but a single phase cannot tell which sides are present. Run the guided call test."
        )
        isCallSource -> Verdict(
            EngineState.SUPPORTED, CaptureMode.MIC_ONLY,
            "Sound was captured outside a call; it is treated as microphone-like input, not call audio."
        )
        else -> Verdict(EngineState.SUPPORTED, CaptureMode.MIC_ONLY, "Microphone audio captured.")
    }

    private fun guided(engine: EngineId, isCallSource: Boolean, remote: Boolean, local: Boolean): Verdict {
        if (!remote && !local) return Verdict(
            EngineState.RESTRICTED, CaptureMode.NONE,
            "No audio in either phase of the call test. Capture is blocked or both parties stayed silent."
        )
        if (!isCallSource) {
            return if (local) Verdict(
                EngineState.SUPPORTED, CaptureMode.MIC_ONLY,
                if (remote) "Your voice and the remote party were both heard, but the remote side came acoustically through the speaker, not as true call audio."
                else "Only your own voice was captured. The remote party is not recorded."
            ) else Verdict(
                EngineState.RESTRICTED, CaptureMode.NONE,
                "The microphone was silent while you spoke during the call. Android is blocking it."
            )
        }
        return when (engine) {
            EngineId.VOICE_UPLINK ->
                if (local && !remote) Verdict(EngineState.SUPPORTED, CaptureMode.UPLINK_ONLY, "Your voice was captured; the remote party was not.")
                else Verdict(EngineState.SUPPORTED, CaptureMode.CALL_AUDIO_UNVERIFIED, "Audio present, but the sides do not match the expected uplink-only pattern.")
            EngineId.VOICE_DOWNLINK ->
                if (remote && !local) Verdict(EngineState.SUPPORTED, CaptureMode.DOWNLINK_ONLY, "The remote party was captured; your voice was not.")
                else Verdict(EngineState.SUPPORTED, CaptureMode.CALL_AUDIO_UNVERIFIED, "Audio present, but the sides do not match the expected downlink-only pattern.")
            else -> when {
                remote && local -> Verdict(EngineState.SUPPORTED, CaptureMode.TWO_WAY_VERIFIED, "Verified: the remote party and your voice were both captured on the call-audio source.")
                local -> Verdict(EngineState.SUPPORTED, CaptureMode.UPLINK_ONLY, "Only your voice was captured.")
                else -> Verdict(EngineState.SUPPORTED, CaptureMode.DOWNLINK_ONLY, "Only the remote party was captured.")
            }
        }
    }
}
