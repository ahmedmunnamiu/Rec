package com.ahmedmunna.callrecorder.root

import android.content.Context
import com.ahmedmunna.callrecorder.data.prefs.AppSettings
import com.ahmedmunna.callrecorder.domain.EngineId
import com.ahmedmunna.callrecorder.domain.EngineState
import com.ahmedmunna.callrecorder.engine.EngineConfig
import com.ahmedmunna.callrecorder.engine.Precheck
import com.ahmedmunna.callrecorder.engine.RecorderSession
import com.ahmedmunna.callrecorder.engine.RecordingEngine
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Everything root-related lives in this package. Nothing here runs unless the user opens the Root
 * Status screen or explicitly selects the Root engine. The rest of the app never depends on root.
 */
object RootDetector {
    private val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su", "/vendor/bin/su", "/su/bin/su")

    data class Status(val suBinaryFound: Boolean, val accessGranted: Boolean, val detail: String)

    /** Blocking; call from a background dispatcher. */
    fun detect(): Status {
        val found = suPaths.any { File(it).exists() }
        if (!found) return Status(false, false, "No su binary found. The app works normally without root.")
        return try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = p.waitFor(5, TimeUnit.SECONDS)
            val out = p.inputStream.bufferedReader().readText()
            if (!finished) { p.destroy(); Status(true, false, "su did not answer (permission prompt pending or denied).") }
            else if (out.contains("uid=0")) Status(true, true, "Root access granted.")
            else Status(true, false, "su exists but access was denied.")
        } catch (e: Exception) {
            Status(true, false, "su could not be started: ${e.message}")
        }
    }

    fun binaryExists(name: String): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "which $name").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS) && p.inputStream.bufferedReader().readText().trim().isNotEmpty()
    }.getOrDefault(false)
}

/** Experimental: records the call PCM stream with the `tinycap` utility if the ROM ships it. Output is WAV. */
class RootEngine : RecordingEngine(EngineId.ROOT) {
    override fun precheck(ctx: Context, settings: AppSettings): Precheck {
        val st = RootDetector.detect()
        return when {
            !st.suBinaryFound -> Precheck(EngineState.UNSUPPORTED, st.detail, blocksAttempt = true)
            !st.accessGranted -> Precheck(EngineState.RESTRICTED, st.detail, blocksAttempt = true)
            !RootDetector.binaryExists("tinycap") ->
                Precheck(EngineState.UNSUPPORTED, "Root works, but this ROM has no tinycap binary; the experimental backend cannot run.", blocksAttempt = true)
            else -> Precheck.OK
        }
    }

    override fun createSession(ctx: Context, file: File, cfg: EngineConfig): RecorderSession = RootTinycapSession(file)
}

class RootTinycapSession(private val file: File) : RecorderSession {
    private var process: Process? = null

    override fun start() {
        process = ProcessBuilder("su", "-c", "tinycap ${file.absolutePath} -D 0 -d 0 -c 2 -r 48000 -b 16")
            .redirectErrorStream(true).start()
    }

    override fun peak(): Int = 0 // level metering is not available through tinycap

    override fun stop(): Boolean {
        runCatching { ProcessBuilder("su", "-c", "pkill -INT tinycap").start().waitFor(3, TimeUnit.SECONDS) }
        runCatching { process?.destroy() }
        runCatching { ProcessBuilder("su", "-c", "chmod 666 ${file.absolutePath}").start().waitFor(3, TimeUnit.SECONDS) }
        process = null
        return file.exists() && file.length() > 44
    }

    override fun release() { stop() }
}
