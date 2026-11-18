package com.ahmedmunna.callrecorder.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ahmedmunna.callrecorder.data.db.RecordingEntity
import com.ahmedmunna.callrecorder.data.prefs.SettingsRepository
import com.ahmedmunna.callrecorder.data.repo.RecordingRepository
import com.ahmedmunna.callrecorder.domain.AudioFormat
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineCategory
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState
import com.ahmedmunna.callrecorder.domain.RecordingStatus
import com.ahmedmunna.callrecorder.engine.EncoderSupport
import com.ahmedmunna.callrecorder.engine.EngineConfig
import com.ahmedmunna.callrecorder.engine.EngineRegistry
import com.ahmedmunna.callrecorder.engine.EngineSelector
import com.ahmedmunna.callrecorder.engine.RecorderSession
import com.ahmedmunna.callrecorder.engine.RecordingEngine
import com.ahmedmunna.callrecorder.storage.StorageManager
import com.ahmedmunna.callrecorder.util.CallLogResolver
import com.ahmedmunna.callrecorder.util.ContactResolver
import com.ahmedmunna.callrecorder.util.Notifications
import com.ahmedmunna.callrecorder.util.Notifier
import com.ahmedmunna.callrecorder.util.PhoneUtil
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecorderEntryPoint {
    fun repo(): RecordingRepository
}

private class ActiveRecording(
    val rowId: Long,
    val session: RecorderSession,
    val engine: RecordingEngine,
    val staging: File,
    val startMs: Long,
    var number: String,
    val direction: CallDirection,
    val format: AudioFormat,
    val handle: AutoCloseable?,
    val captureMode: CaptureMode,
    val quality: String,
    val deferredPolicy: Boolean,
    val remainingChain: List<RecordingEngine>,
) {
    @Volatile var lastLoudMs: Long = startMs
}

@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var repo: RecordingRepository
    @Inject lateinit var storage: StorageManager
    @Inject lateinit var registry: EngineRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var active: ActiveRecording? = null
    private var monitor: Job? = null
    @Volatile private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!enterForeground("Starting…")) { stopSelf(); return START_NOT_STICKY }
                stopRequested = false
                scope.launch { mutex.withLock { begin(intent) } }
            }
            ACTION_STOP -> {
                stopRequested = true
                scope.launch { mutex.withLock { finishActive("Call ended") ; stopIfIdle() } }
            }
            else -> {
                // Restarted after the process was killed: repair whatever was in progress, then stop.
                if (!enterForeground("Recovering recordings…")) { stopSelf(); return START_NOT_STICKY }
                scope.launch {
                    RecoveryManager(repo, storage).recover(activeRowId)
                    stopIfIdle()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopPending() = PendingIntent.getService(
        this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun enterForeground(text: String): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, Notifications.ID_RECORDING, Notifier.recording(this, text, stopPending()), type)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground refused", t)
        Notifier.alert(
            this, Notifier.ID_FAILURE, "Recording could not start",
            "Android refused the microphone foreground service (${t.javaClass.simpleName}). Check microphone permission and background restrictions."
        )
        false
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(Notifications.ID_RECORDING, Notifier.recording(this, text, stopPending()))
    }

    // ---------------------------------------------------------------- start

    private suspend fun begin(intent: Intent) {
        if (active != null) { Log.i(TAG, "Duplicate start ignored"); return }
        val s = settingsRepo.current()
        val direction = runCatching { CallDirection.valueOf(intent.getStringExtra(EXTRA_DIRECTION) ?: "") }.getOrDefault(CallDirection.UNKNOWN)
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: "UNKNOWN"
        val start = intent.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis())

        if (number != "UNKNOWN" && repo.findDuplicate(number, start) != null) {
            Log.i(TAG, "A recording for this call already exists; skipping"); stopIfIdle(); return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failStart(direction, number, start, "Microphone permission is not granted", s.notifyOnFailure); return
        }
        if (storage.freeBytes() < MIN_FREE_BYTES) {
            failStart(direction, number, start, "Not enough free storage (< 20 MB)", s.notifyOnFailure); return
        }

        val tests = repo.engineTests()
        val first = s.manualEngine?.takeIf { it.isRecordable }
            ?: EngineSelector.recommend(tests, s.speakerphoneConsent).engine
        val chain = buildChain(registry[first], tests.filter { it.state == EngineState.SUPPORTED }.map { it.engine }, s.autoRetry)

        var lastError = "No engine could start"
        for ((i, engine) in chain.withIndex()) {
            try {
                startWith(engine, chain.drop(i + 1), direction, number, start, s.deferred(direction, number))
                return
            } catch (t: Throwable) {
                lastError = "${engine.id.label}: ${t.message ?: t.javaClass.simpleName}"
                Log.w(TAG, "Engine ${engine.id} failed to start", t)
            }
        }
        failStart(direction, number, start, lastError, s.notifyOnFailure)
    }

    private fun com.ahmedmunna.callrecorder.data.prefs.AppSettings.deferred(d: CallDirection, n: String) =
        RecordPolicy.decide(this@RecordingService, this, n, d).deferUntilNumberKnown

    private fun buildChain(first: RecordingEngine, workingIds: List<EngineId>, retry: Boolean): List<RecordingEngine> {
        if (!retry) return listOf(first)
        val fallbacks = (workingIds + EngineId.MIC + EngineId.DEFAULT)
            .distinct()
            .filter { it != first.id && registry[it].id.category == EngineCategory.MICROPHONE }
            .map { registry[it] }
        return listOf(first) + fallbacks
    }

    private suspend fun startWith(
        engine: RecordingEngine, rest: List<RecordingEngine>, direction: CallDirection,
        number: String, start: Long, deferred: Boolean,
    ) {
        val s = settingsRepo.current()
        val format = if (engine.id == EngineId.ROOT) AudioFormat.WAV else s.format
        val r = EncoderSupport.resolve(format, s.sampleRate, s.bitrate, s.stereo)
        val cfg = EngineConfig(
            format = format, sampleRate = r.sampleRate, bitrate = r.bitrate, stereo = r.stereo,
            noiseSuppression = s.noiseSuppression, echoCancellation = s.echoCancellation, autoGain = s.autoGain,
            gainDb = s.micGainDb,
        )
        val pre = engine.precheck(this, s)
        if (pre.blocksAttempt) error(pre.note ?: "Engine unavailable")

        val file = storage.stagingFile(System.currentTimeMillis(), format)
        val handle = engine.prepare(this, s)
        val session = try {
            engine.createSession(this, file, cfg).also { it.start() }
        } catch (t: Throwable) {
            runCatching { handle?.close() }; file.delete(); throw t
        }

        val tested = repo.engineTests().firstOrNull { it.engine == engine.id }
        val mode = tested?.captureMode
            ?: if (engine.id.category == EngineCategory.CALL_AUDIO) CaptureMode.CALL_AUDIO_UNVERIFIED else CaptureMode.MIC_ONLY
        val qualityLabel = "${s.quality.label} · ${r.sampleRate / 1000.0} kHz" +
            (if (format == AudioFormat.WAV || format.fixedSampleRate != null) "" else " · ${r.bitrate / 1000} kbps")

        val rowId = repo.insert(
            RecordingEntity(
                phoneNumber = number, direction = direction, startTime = start, filePath = file.absolutePath,
                format = format, engine = engine.id, quality = qualityLabel, captureMode = mode,
                status = RecordingStatus.RECORDING,
                notes = if (r.notes.isEmpty()) "" else r.notes.joinToString("; "),
            )
        )
        val rec = ActiveRecording(rowId, session, engine, file, start, number, direction, format, handle, mode, qualityLabel, deferred, rest)
        active = rec
        activeRowId = rowId
        updateNotification("${engine.id.label} · ${mode.label}")
        monitor = scope.launch { watch(rec, s.maxDurationMin, s.silenceStopSec) }
        if (stopRequested) finishActive("Call ended")
    }

    // -------------------------------------------------------------- monitor

    private suspend fun watch(rec: ActiveRecording, maxMin: Int, silenceSec: Int) {
        while (scope.isActive && active === rec) {
            delay(1000)
            val now = System.currentTimeMillis()
            val elapsed = (now - rec.startMs) / 1000
            if (rec.session.peak() > LOUD_PEAK) rec.lastLoudMs = now
            updateNotification("%02d:%02d · %s".format(elapsed / 60, elapsed % 60, rec.engine.id.label))

            val err = rec.session.error()
            // The finishing work runs in its own coroutine: finishActive() cancels this monitor job,
            // and finalisation must not be cancelled half way.
            when {
                err != null -> { fire { if (active === rec) onCaptureError(rec, err) }; return }
                maxMin > 0 && elapsed >= maxMin * 60L -> { fire { if (active === rec) { finishActive("Maximum duration reached"); stopIfIdle() } }; return }
                silenceSec > 0 && now - rec.lastLoudMs >= silenceSec * 1000L -> { fire { if (active === rec) { finishActive("Stopped after $silenceSec s of silence"); stopIfIdle() } }; return }
                storage.freeBytes() < MIN_FREE_BYTES -> {
                    fire { if (active === rec) { finishActive("Stopped: storage almost full"); stopIfIdle() } }
                    Notifier.alert(this, Notifier.ID_LOW_STORAGE, "Storage almost full", "Recording stopped because less than 20 MB is free.")
                    return
                }
            }
        }
    }

    private fun fire(block: suspend () -> Unit) {
        scope.launch { mutex.withLock { block() } }
    }

    private suspend fun onCaptureError(rec: ActiveRecording, err: String) {
        Log.w(TAG, "Capture error: $err")
        finishActive("Capture error: $err")
        val s = settingsRepo.current()
        if (s.autoRetry && rec.remainingChain.isNotEmpty() && !stopRequested) {
            for ((i, engine) in rec.remainingChain.withIndex()) {
                try {
                    startWith(engine, rec.remainingChain.drop(i + 1), rec.direction, rec.number, System.currentTimeMillis(), rec.deferredPolicy)
                    return
                } catch (t: Throwable) { Log.w(TAG, "Retry with ${engine.id} failed", t) }
            }
        }
        if (s.notifyOnFailure) Notifier.alert(this, Notifier.ID_FAILURE, "Recording interrupted", "The recorder stopped: $err")
        stopIfIdle()
    }

    // --------------------------------------------------------------- finish

    private suspend fun finishActive(reason: String) {
        val rec = active ?: return
        active = null
        activeRowId = -1
        monitor?.cancel()
        val ok = runCatching { rec.session.stop() }.getOrDefault(false)
        runCatching { rec.handle?.close() }
        val s = settingsRepo.current()
        val end = System.currentTimeMillis()
        val duration = end - rec.startMs
        val row = repo.get(rec.rowId) ?: return

        if (!ok || !rec.staging.exists() || rec.staging.length() == 0L) {
            rec.staging.delete()
            repo.update(row.copy(status = RecordingStatus.FAILED, endTime = end, durationMs = duration, failureReason = "No audio data was produced ($reason)"))
            if (s.notifyOnFailure) Notifier.alert(this, Notifier.ID_FAILURE, "Recording failed", "No audio data was produced for the last call. The engine may be blocked on this device.")
            return
        }

        var number = rec.number
        if (number == "UNKNOWN" && s.callLogIntegration) {
            CallLogResolver.latestNumber(this, rec.startMs)?.let { number = PhoneUtil.normalize(it) }
        }
        if (rec.deferredPolicy) {
            val d = RecordPolicy.decide(this, s, number, rec.direction)
            if (!d.record) {
                // The user's own exclusion/selection rule says this call must not be kept.
                rec.staging.delete(); repo.deleteConfirmed(listOf(row)); return
            }
        }
        if (s.minDurationSec > 0 && duration < s.minDurationSec * 1000L) {
            rec.staging.delete(); repo.deleteConfirmed(listOf(row)); return
        }

        val contact = if (s.contactsIntegration) ContactResolver.lookup(this, number) else null
        val name = storage.fileName(rec.startMs, rec.direction, number, rec.format)
        val placed = storage.place(rec.staging, name, rec.direction, false, rec.format, s.storageTreeUri)
        val updated = row.copy(
            phoneNumber = number, contactName = contact?.name, endTime = end, durationMs = duration,
            filePath = placed, status = RecordingStatus.COMPLETED,
            favorite = s.autoFavoriteMinSec > 0 && duration >= s.autoFavoriteMinSec * 1000L,
            failureReason = null,
        )
        repo.update(updated)
        repo.update(repo.get(rec.rowId)!!.let { it.copy(fileSize = storage.size(it)) })
        checkStorageWarnings(s)
    }

    private suspend fun checkStorageWarnings(s: com.ahmedmunna.callrecorder.data.prefs.AppSettings) {
        if (storage.isLowStorage(s.lowStorageMb)) {
            Notifier.alert(this, Notifier.ID_LOW_STORAGE, "Storage is running low", "Less than ${s.lowStorageMb} MB is free on this device.")
        }
        if (s.storageLimitMb > 0) {
            val used = repo.all().sumOf { it.fileSize }
            if (used > s.storageLimitMb * 1024L * 1024L) {
                Notifier.alert(this, Notifier.ID_STORAGE_LIMIT, "Recording storage limit reached", "Recordings use ${used / (1024 * 1024)} MB, above your ${s.storageLimitMb} MB limit. Review them in Settings → Storage.")
            }
        }
    }

    private suspend fun failStart(direction: CallDirection, number: String, start: Long, reason: String, notify: Boolean) {
        repo.insert(
            RecordingEntity(
                phoneNumber = number, direction = direction, startTime = start, endTime = System.currentTimeMillis(),
                filePath = "", format = AudioFormat.M4A, engine = EngineId.DEFAULT, quality = "-",
                captureMode = CaptureMode.NONE, status = RecordingStatus.FAILED, failureReason = reason,
            )
        )
        if (notify) Notifier.alert(this, Notifier.ID_FAILURE, "Recording failed", reason)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (active == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // If the system destroys the service mid-call, stop the encoder cleanly so the file stays usable.
        active?.let { runCatching { it.session.stop() }; runCatching { it.handle?.close() } }
        activeRowId = -1
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ahmedmunna.callrecorder.START"
        const val ACTION_STOP = "com.ahmedmunna.callrecorder.STOP"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_START_TIME = "start"
        private const val TAG = "RecordingService"
        private const val MIN_FREE_BYTES = 20L * 1024 * 1024
        private const val LOUD_PEAK = 600

        /** Row currently being recorded, so recovery does not touch it. */
        @Volatile var activeRowId: Long = -1

        fun logStartFailure(ctx: Context, direction: CallDirection, number: String, t: Throwable) {
            val repo = EntryPointAccessors.fromApplication(ctx.applicationContext, RecorderEntryPoint::class.java).repo()
            CoroutineScope(Dispatchers.IO).launch {
                repo.insert(
                    RecordingEntity(
                        phoneNumber = number, direction = direction, startTime = System.currentTimeMillis(),
                        filePath = "", format = AudioFormat.M4A, engine = EngineId.DEFAULT, quality = "-",
                        captureMode = CaptureMode.NONE, status = RecordingStatus.FAILED,
                        failureReason = "Background start blocked by Android: ${t.javaClass.simpleName}",
                    )
                )
            }
        }
    }
}
