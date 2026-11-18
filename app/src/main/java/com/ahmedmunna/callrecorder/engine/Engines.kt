package com.ahmedmunna.callrecorder.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ahmedmunna.callrecorder.data.prefs.AppSettings
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState
import java.io.File

/** Static, no-audio check of whether an engine can plausibly work. */
data class Precheck(
    val state: EngineState? = null,
    val note: String? = null,
    /** True when the engine must not even be attempted (e.g. no user consent). */
    val blocksAttempt: Boolean = false,
) {
    companion object { val OK = Precheck() }
}

const val PERM_CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"

abstract class RecordingEngine(val id: EngineId) {

    open val requiredPermissions: List<String>
        get() = listOf(Manifest.permission.RECORD_AUDIO)

    /** Sample rate used when testing the engine (call sources are narrow-band). */
    open val testSampleRate: Int get() = 16000

    open fun precheck(ctx: Context, settings: AppSettings): Precheck = Precheck.OK

    open fun preferredDevice(ctx: Context): AudioDeviceInfo? = null

    /** Hook run before recording starts. The returned handle is closed when recording ends. */
    open fun prepare(ctx: Context, settings: AppSettings): AutoCloseable? = null

    open fun createSession(ctx: Context, file: File, cfg: EngineConfig): RecorderSession {
        val source = checkNotNull(id.audioSource) { "${id.label} has no audio source" }
        val cfgWithDevice = cfg.copy(preferredDevice = cfg.preferredDevice ?: preferredDevice(ctx))
        return if (cfg.format == AudioFormat.WAV) {
            val rate = cfg.sampleRate
            PcmWavSession(
                PcmCapture(
                    source, rate, if (cfg.stereo) 2 else 1, cfgWithDevice.preferredDevice,
                    cfg.noiseSuppression, cfg.echoCancellation, cfg.autoGain,
                ),
                file, rate, if (cfg.stereo) 2 else 1, cfg.gainDb,
            )
        } else {
            MediaRecorderSession(ctx, source, file, cfgWithDevice)
        }
    }

    protected fun hasPermission(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}

/** Call-audio sources (A, B, C) need CAPTURE_AUDIO_OUTPUT, a signature permission normal apps cannot hold. */
abstract class CallSourceEngine(id: EngineId, private val what: String) : RecordingEngine(id) {
    override val requiredPermissions: List<String>
        get() = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE, PERM_CAPTURE_AUDIO_OUTPUT)

    override val testSampleRate: Int get() = 8000

    override fun precheck(ctx: Context, settings: AppSettings): Precheck =
        if (hasPermission(ctx, PERM_CAPTURE_AUDIO_OUTPUT)) Precheck.OK
        else Precheck(
            EngineState.RESTRICTED,
            "$what needs the signature-level CAPTURE_AUDIO_OUTPUT permission, which Android does not give to " +
                "ordinary apps. The engine will still be attempted so the result is measured, not assumed.",
        )
}

class VoiceCallEngine : CallSourceEngine(EngineId.VOICE_CALL, "VOICE_CALL (both sides)")
class VoiceUplinkEngine : CallSourceEngine(EngineId.VOICE_UPLINK, "VOICE_UPLINK (outgoing side)")
class VoiceDownlinkEngine : CallSourceEngine(EngineId.VOICE_DOWNLINK, "VOICE_DOWNLINK (remote side)")

class MicEngine : RecordingEngine(EngineId.MIC) {
    override val testSampleRate: Int get() = 44100
}

class VoiceRecognitionEngine : RecordingEngine(EngineId.VOICE_RECOGNITION) {
    override val testSampleRate: Int get() = 44100
}

class VoiceCommunicationEngine : RecordingEngine(EngineId.VOICE_COMMUNICATION) {
    override val testSampleRate: Int get() = 16000
}

class DefaultEngine : RecordingEngine(EngineId.DEFAULT) {
    override val testSampleRate: Int get() = 44100
}

class CamcorderEngine : RecordingEngine(EngineId.CAMCORDER) {
    override val testSampleRate: Int get() = 44100
}

class UnprocessedEngine : RecordingEngine(EngineId.UNPROCESSED) {
    override val testSampleRate: Int get() = 44100
    override fun precheck(ctx: Context, settings: AppSettings): Precheck {
        val am = ctx.getSystemService(AudioManager::class.java)
        return if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") Precheck.OK
        else Precheck(EngineState.UNSUPPORTED, "This device does not advertise unprocessed audio source support.", blocksAttempt = true)
    }
}

/** Capability detection only. Never assumes third-party access to the system mix. */
class RemoteSubmixEngine : RecordingEngine(EngineId.REMOTE_SUBMIX) {
    override val requiredPermissions: List<String>
        get() = listOf(Manifest.permission.RECORD_AUDIO, PERM_CAPTURE_AUDIO_OUTPUT)

    override fun precheck(ctx: Context, settings: AppSettings): Precheck =
        if (hasPermission(ctx, PERM_CAPTURE_AUDIO_OUTPUT)) Precheck.OK
        else Precheck(
            EngineState.RESTRICTED,
            "REMOTE_SUBMIX requires CAPTURE_AUDIO_OUTPUT (privileged). It is only attempted if the device genuinely grants it.",
            blocksAttempt = true,
        )
}

/** Microphone recording while the speakerphone is on, only with the user's explicit consent. */
class SpeakerphoneEngine : RecordingEngine(EngineId.SPEAKERPHONE) {
    override val testSampleRate: Int get() = 44100

    override fun precheck(ctx: Context, settings: AppSettings): Precheck =
        if (settings.speakerphoneConsent) Precheck.OK
        else Precheck(
            EngineState.RESTRICTED,
            "Turn on \"Speakerphone-assisted recording\" in Settings. It changes call audio routing, so it needs your consent.",
            blocksAttempt = true,
        )

    @Suppress("DEPRECATION")
    override fun prepare(ctx: Context, settings: AppSettings): AutoCloseable? {
        if (!settings.speakerphoneConsent) return null
        val am = ctx.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            val speaker = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return null
            val previous = am.communicationDevice
            if (!am.setCommunicationDevice(speaker)) return null
            return AutoCloseable {
                if (previous != null) am.setCommunicationDevice(previous) else am.clearCommunicationDevice()
            }
        }
        val previous = am.isSpeakerphoneOn
        am.isSpeakerphoneOn = true
        return AutoCloseable { am.isSpeakerphoneOn = previous }
    }
}

class BluetoothEngine : RecordingEngine(EngineId.BLUETOOTH) {
    override val testSampleRate: Int get() = 16000

    override fun preferredDevice(ctx: Context): AudioDeviceInfo? = findInput(ctx)

    override fun precheck(ctx: Context, settings: AppSettings): Precheck =
        if (findInput(ctx) != null) Precheck.OK
        else Precheck(EngineState.UNSUPPORTED, "No Bluetooth (SCO/LE) microphone input is connected.", blocksAttempt = true)

    companion object {
        private fun isBtType(t: Int) =
            t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)

        fun findInput(ctx: Context): AudioDeviceInfo? =
            ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }

        fun connectedDevices(ctx: Context): List<String> =
            ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { isBtType(it.type) }
                .map { it.productName?.toString().orEmpty().ifBlank { "Bluetooth audio device" } }
                .distinct()

        /** Warning shown when Bluetooth routing may stop the phone microphone from carrying call audio. */
        fun routingWarning(ctx: Context): String? {
            val devices = connectedDevices(ctx)
            return if (devices.isEmpty()) null
            else "Bluetooth audio is connected (${devices.joinToString()}). While call audio is routed to it, the phone " +
                "microphone usually cannot capture the call reliably; recordings may be silent or one-sided."
        }
    }
}

/** ADB is a setup path, not a capture path: it only grants ordinary permissions and inspects AppOps. */
class AdbEngine : RecordingEngine(EngineId.ADB) {
    override fun createSession(ctx: Context, file: File, cfg: EngineConfig): RecorderSession =
        throw UnsupportedOperationException("ADB is a setup module and cannot record by itself")

    fun isAdbEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
}
