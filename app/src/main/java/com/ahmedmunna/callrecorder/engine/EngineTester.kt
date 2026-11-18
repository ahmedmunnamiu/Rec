package com.ahmedmunna.callrecorder.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.ahmedmunna.callrecorder.data.db.EngineTestDao
import com.ahmedmunna.callrecorder.data.db.EngineTestEntity
import com.ahmedmunna.callrecorder.data.prefs.SettingsRepository
import com.ahmedmunna.callrecorder.domain.CaptureMode
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState
import com.ahmedmunna.callrecorder.root.RootTinycapSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

data class TestProgress(val instruction: String, val secondsLeft: Int, val level: Float)

@Singleton
class EngineTester @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: EngineTestDao,
    private val settingsRepo: SettingsRepository,
    private val registry: EngineRegistry,
) {
    val testDir: File get() = File(ctx.cacheDir, "engine-tests").apply { mkdirs() }

    fun testFile(id: EngineId) = File(testDir, "${id.name}.wav")

    fun isCallActive(): Boolean = try {
        ctx.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE
    } catch (e: SecurityException) {
        false
    }

    /**
     * Records real audio from [engine], analyses it and stores the verdict.
     * When [guided] and a call is active, runs the two-phase test that can prove two-way capture.
     */
    suspend fun test(engine: RecordingEngine, guided: Boolean, onProgress: (TestProgress) -> Unit): EngineTestEntity =
        withContext(Dispatchers.IO) {
            val settings = settingsRepo.current()
            val id = engine.id
            suspend fun finish(
                state: EngineState, mode: CaptureMode, verdict: String, error: String? = null,
                stats: AudioStats? = null, file: String? = null,
            ): EngineTestEntity {
                val warn = if (id != EngineId.BLUETOOTH && id.category != com.ahmedmunna.callrecorder.domain.EngineCategory.SETUP)
                    BluetoothEngine.routingWarning(ctx) else null
                return EngineTestEntity(
                    engine = id, state = state, captureMode = mode, testedAt = System.currentTimeMillis(),
                    error = error,
                    peak = stats?.peak ?: 0f, rms = stats?.rms ?: 0f,
                    clippingPct = stats?.clippingPct ?: 0f, silencePct = stats?.silencePct ?: 100f,
                    verdict = listOfNotNull(verdict, warn).joinToString(" "), testFilePath = file,
                ).also { dao.upsert(it) }
            }

            if (id == EngineId.ADB) {
                val on = (engine as AdbEngine).isAdbEnabled(ctx)
                return@withContext if (on) finish(EngineState.SUPPORTED, CaptureMode.NONE, "USB/Wireless debugging is on. Run the scripts in the adb folder to grant permissions.")
                else finish(EngineState.RESTRICTED, CaptureMode.NONE, "Debugging is off. Enable Developer options, then USB or Wireless debugging.")
            }

            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@withContext finish(EngineState.RESTRICTED, CaptureMode.NONE, "Microphone permission is not granted.", "RECORD_AUDIO denied")
            }

            val pre = engine.precheck(ctx, settings)
            if (pre.blocksAttempt) {
                return@withContext finish(pre.state ?: EngineState.UNSUPPORTED, CaptureMode.NONE, pre.note.orEmpty(), pre.note)
            }

            if (id == EngineId.ROOT) return@withContext testRoot { s, m, v, e, st, f -> finish(s, m, v, e, st, f) }

            val callActive = isCallActive()
            val twoPhase = guided && callActive
            val instructions = if (twoPhase) listOf(
                "Phase 1: let the OTHER party talk. Stay silent.",
                "Phase 2: YOU talk. Ask the other party to stay silent.",
            ) else listOf(if (callActive) "Talk normally with the other party." else "Speak into the microphone now.")

            val rate = engine.testSampleRate
            val source = checkNotNull(id.audioSource)
            val handle = runCatching { engine.prepare(ctx, settings) }.getOrNull()
            val capture = PcmCapture(source, rate, 1, engine.preferredDevice(ctx))
            val file = testFile(id)
            var writer: WavWriter? = null
            try {
                capture.open()
                writer = WavWriter(file, rate, 1)
                capture.start()
                val buf = ShortArray(rate / 10)
                val stats = mutableListOf<AudioStats>()
                for (instruction in instructions) {
                    val analyzer = AudioAnalyzer(rate)
                    val end = System.currentTimeMillis() + PHASE_MS
                    var lastUi = 0L
                    while (System.currentTimeMillis() < end) {
                        coroutineContext.ensureActive()
                        val n = capture.read(buf)
                        check(n >= 0) { "AudioRecord read error $n" }
                        if (n == 0) { delay(5); continue }
                        analyzer.feed(buf, n)
                        writer.write(buf, n)
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 150) {
                            lastUi = now
                            var p = 0
                            for (i in 0 until n) p = maxOf(p, abs(buf[i].toInt()))
                            onProgress(TestProgress(instruction, ((end - now) / 1000 + 1).toInt(), p / 32768f))
                        }
                    }
                    stats += analyzer.result()
                }
                val v = EngineVerdict.classify(id, callActive, stats)
                val merged = stats.maxByOrNull { it.rms } ?: AudioStats.EMPTY
                finish(v.state, v.mode, v.message, null, merged, file.absolutePath)
            } catch (e: SecurityException) {
                finish(EngineState.RESTRICTED, CaptureMode.NONE, "Android denied access to this audio source.", e.message)
            } catch (e: IllegalStateException) {
                finish(EngineState.UNSUPPORTED, CaptureMode.NONE, "This audio source cannot be opened on this device.", e.message)
            } catch (e: IllegalArgumentException) {
                finish(EngineState.UNSUPPORTED, CaptureMode.NONE, "This audio source or configuration is not supported.", e.message)
            } catch (e: UnsupportedOperationException) {
                finish(EngineState.UNSUPPORTED, CaptureMode.NONE, "This audio source is not supported.", e.message)
            } finally {
                capture.close()
                writer?.close()
                runCatching { handle?.close() }
            }
        }

    private suspend fun testRoot(finish: suspend (EngineState, CaptureMode, String, String?, AudioStats?, String?) -> EngineTestEntity): EngineTestEntity {
        val file = testFile(EngineId.ROOT)
        val session = RootTinycapSession(file)
        return try {
            session.start()
            delay(PHASE_MS)
            if (!session.stop()) finish(EngineState.UNSUPPORTED, CaptureMode.NONE, "tinycap produced no audio.", null, null, null)
            else {
                val stats = analyzeWav(file, 48000)
                val v = EngineVerdict.classify(EngineId.ROOT, isCallActive(), listOf(stats))
                finish(v.state, v.mode, v.message, null, stats, file.absolutePath)
            }
        } catch (e: Exception) {
            finish(EngineState.UNSUPPORTED, CaptureMode.NONE, "Root backend failed.", e.message, null, null)
        }
    }

    private fun analyzeWav(file: File, rate: Int): AudioStats {
        val analyzer = AudioAnalyzer(rate)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(44)
            val bytes = ByteArray(8192)
            val shorts = ShortArray(4096)
            while (true) {
                val n = raf.read(bytes)
                if (n <= 1) break
                val count = n / 2
                for (i in 0 until count) shorts[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                analyzer.feed(shorts, count)
            }
        }
        return analyzer.result()
    }

    suspend fun testAllMicrophoneEngines(onEach: (EngineId) -> Unit = {}) {
        registry.all.filter { it.id.category == com.ahmedmunna.callrecorder.domain.EngineCategory.MICROPHONE }.forEach {
            onEach(it.id); test(it, guided = false) { }
        }
    }

    data class QualityResult(
        val file: File?, val bytes: Long, val peak: Float, val loudShare: Float,
        val resolvedFormat: String, val adjustments: List<String>, val verdict: String, val error: String? = null,
    )

    /**
     * Records 5 s through the REAL recording path (same format, rate, bitrate and effects as a call recording)
     * and reports the resulting file size and measured level.
     */
    suspend fun qualityTest(engineId: EngineId, onProgress: (TestProgress) -> Unit): QualityResult = withContext(Dispatchers.IO) {
        val s = settingsRepo.current()
        val engine = registry[engineId]
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext QualityResult(null, 0, 0f, 0f, "-", emptyList(), "Microphone permission is not granted.", "RECORD_AUDIO denied")
        }
        if (!engineId.isRecordable || engineId == EngineId.ADB) {
            return@withContext QualityResult(null, 0, 0f, 0f, "-", emptyList(), "${engineId.label} cannot record.", "not recordable")
        }
        val format = if (engineId == EngineId.ROOT) com.ahmedmunna.callrecorder.domain.AudioFormat.WAV else s.format
        val r = EncoderSupport.resolve(format, s.sampleRate, s.bitrate, s.stereo)
        val cfg = EngineConfig(format, r.sampleRate, r.bitrate, r.stereo, s.noiseSuppression, s.echoCancellation, s.autoGain, s.micGainDb)
        val label = "${format.label} · ${r.sampleRate} Hz" +
            (if (format == com.ahmedmunna.callrecorder.domain.AudioFormat.WAV || format.fixedSampleRate != null) "" else " · ${r.bitrate / 1000} kbps") +
            if (r.stereo) " · stereo" else " · mono"
        val pre = engine.precheck(ctx, s)
        if (pre.blocksAttempt) return@withContext QualityResult(null, 0, 0f, 0f, label, r.notes, pre.note.orEmpty(), pre.note)

        val file = File(testDir, "quality.${format.extension}").also { it.delete() }
        val handle = runCatching { engine.prepare(ctx, s) }.getOrNull()
        val session = try {
            engine.createSession(ctx, file, cfg).also { it.start() }
        } catch (t: Throwable) {
            runCatching { handle?.close() }
            return@withContext QualityResult(null, 0, 0f, 0f, label, r.notes, "The recorder could not start with these settings.", t.message ?: t.javaClass.simpleName)
        }
        var peak = 0; var loud = 0; var ticks = 0
        var ok = false
        try {
            repeat(50) { i ->
                delay(100)
                coroutineContext.ensureActive()
                val p = session.peak()
                if (p > peak) peak = p
                if (p > 600) loud++
                ticks++
                onProgress(TestProgress("Say something for 5 seconds…", (50 - i) / 10 + 1, p / 32768f))
            }
        } finally {
            ok = runCatching { session.stop() }.getOrDefault(false)
            runCatching { handle?.close() }
        }
        if (!ok || !file.exists()) {
            return@withContext QualityResult(null, 0, peak / 32768f, 0f, label, r.notes, "No audio file was produced.", "empty output")
        }
        val level = peak / 32768f
        val share = if (ticks == 0) 0f else loud.toFloat() / ticks
        val notes = buildList {
            if (peak < 300) add("silence (nothing was captured)") else if (level < 0.1f) add("low volume")
            if (level > 0.97f) add("possible clipping")
        }
        QualityResult(file, file.length(), level, share, label, r.notes, if (notes.isEmpty()) "Recording path works; level looks healthy." else "Recording path works. Flags: ${notes.joinToString()}.")
    }

    companion object { const val PHASE_MS = 5000L }
}
