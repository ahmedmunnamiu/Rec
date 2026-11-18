package com.ahmedmunna.callrecorder.domain

import android.media.MediaRecorder

enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

enum class RecordingStatus { RECORDING, COMPLETED, FAILED, INCOMPLETE }

enum class EngineState { NOT_TESTED, SUPPORTED, UNSUPPORTED, RESTRICTED }

/** What a test actually proved about the captured audio. */
enum class CaptureMode(val label: String) {
    NONE("Nothing captured"),
    MIC_ONLY("Microphone only"),
    UPLINK_ONLY("Your voice only (uplink)"),
    DOWNLINK_ONLY("Remote party only (downlink)"),
    CALL_AUDIO_UNVERIFIED("Call audio present, sides not verified"),
    TWO_WAY_VERIFIED("Two-way verified")
}

enum class AudioFormat(
    val label: String,
    val extension: String,
    val mime: String,
) {
    M4A("M4A / AAC", "m4a", "audio/mp4"),
    WAV("WAV (PCM 16-bit)", "wav", "audio/x-wav"),
    THREE_GP("3GP (AAC)", "3gp", "audio/3gpp"),
    AMR_NB("AMR-NB", "amr", "audio/amr"),
    AMR_WB("AMR-WB", "awb", "audio/amr-wb"),
    MP3("MP3", "mp3", "audio/mpeg");

    /** Android ships no MP3 encoder, so MP3 can never be offered for recording. */
    val recordable: Boolean get() = this != MP3
    val fixedSampleRate: Int?
        get() = when (this) {
            AMR_NB -> 8000
            AMR_WB -> 16000
            else -> null
        }
}

enum class QualityPreset(val label: String, val sampleRate: Int, val bitrate: Int) {
    LOW("Low", 16000, 32_000),
    MEDIUM("Medium", 32000, 64_000),
    HIGH("High", 44100, 128_000),
    VERY_HIGH("Very High", 48000, 192_000),
    CUSTOM("Custom", 44100, 128_000)
}

enum class EngineCategory { CALL_AUDIO, MICROPHONE, ROUTING, SETUP }

enum class EngineId(
    val label: String,
    val category: EngineCategory,
    val audioSource: Int?,
    val summary: String,
) {
    VOICE_CALL("A · VOICE_CALL", EngineCategory.CALL_AUDIO, MediaRecorder.AudioSource.VOICE_CALL,
        "Both sides of a call. Blocked for normal apps on nearly all devices."),
    VOICE_UPLINK("B · VOICE_UPLINK", EngineCategory.CALL_AUDIO, MediaRecorder.AudioSource.VOICE_UPLINK,
        "Outgoing (your voice) side of a call. Normally privileged."),
    VOICE_DOWNLINK("C · VOICE_DOWNLINK", EngineCategory.CALL_AUDIO, MediaRecorder.AudioSource.VOICE_DOWNLINK,
        "Incoming (remote party) side of a call. Normally privileged."),
    MIC("D · MIC", EngineCategory.MICROPHONE, MediaRecorder.AudioSource.MIC,
        "Standard microphone. Captures the remote side only via speakerphone."),
    VOICE_RECOGNITION("E · VOICE_RECOGNITION", EngineCategory.MICROPHONE, MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "Microphone tuned for speech recognition."),
    VOICE_COMMUNICATION("F · VOICE_COMMUNICATION", EngineCategory.MICROPHONE, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "Microphone with echo cancellation / noise suppression where available."),
    DEFAULT("G · DEFAULT", EngineCategory.MICROPHONE, MediaRecorder.AudioSource.DEFAULT,
        "System default input. Used as the last-resort fallback."),
    CAMCORDER("H · CAMCORDER", EngineCategory.MICROPHONE, MediaRecorder.AudioSource.CAMCORDER,
        "Microphone oriented for video capture."),
    UNPROCESSED("I · UNPROCESSED", EngineCategory.MICROPHONE, MediaRecorder.AudioSource.UNPROCESSED,
        "Raw microphone without gain control or noise processing, if supported."),
    REMOTE_SUBMIX("J · REMOTE_SUBMIX", EngineCategory.CALL_AUDIO, MediaRecorder.AudioSource.REMOTE_SUBMIX,
        "System audio mix. Requires a privileged permission; detection only."),
    SPEAKERPHONE("K · SPEAKERPHONE", EngineCategory.ROUTING, MediaRecorder.AudioSource.MIC,
        "Microphone recording while speakerphone is on (with your consent)."),
    BLUETOOTH("L · BLUETOOTH", EngineCategory.ROUTING, MediaRecorder.AudioSource.MIC,
        "Records from a Bluetooth SCO input when one is connected."),
    ADB("M · ADB", EngineCategory.SETUP, null,
        "Setup module: grants ordinary permissions through ADB. Not a capture path."),
    ROOT("N · ROOT (optional)", EngineCategory.SETUP, null,
        "Optional isolated backend for rooted devices; experimental.");

    val isRecordable: Boolean get() = category != EngineCategory.SETUP || this == ROOT
}
