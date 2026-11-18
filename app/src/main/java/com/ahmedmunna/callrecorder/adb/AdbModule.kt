package com.ahmedmunna.callrecorder.adb

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Generates ADB commands for this app's real package name and reports what the device currently allows. */
object AdbModule {

    data class Row(val label: String, val granted: Boolean, val detail: String)

    private val adbGrantable = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )

    fun grantCommands(pkg: String): List<String> = buildList {
        adbGrantable.forEach { add("adb shell pm grant $pkg $it") }
        if (Build.VERSION.SDK_INT >= 33) add("adb shell pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        add("adb shell appops set $pkg RECORD_AUDIO allow")
        add("adb shell appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        add("adb shell dumpsys deviceidle whitelist +$pkg")
    }

    fun inspectCommands(pkg: String): List<String> = listOf(
        "adb devices -l",
        "adb shell pm list packages | grep $pkg",
        "adb shell dumpsys package $pkg | grep -A40 \"requested permissions\"",
        "adb shell appops get $pkg",
    )

    fun resetCommands(pkg: String): List<String> = buildList {
        adbGrantable.forEach { add("adb shell pm revoke $pkg $it") }
        add("adb shell appops reset $pkg")
        add("adb shell dumpsys deviceidle whitelist -$pkg")
    }

    val wirelessSteps = listOf(
        "Settings → About phone → tap Build number 7 times to enable Developer options.",
        "Developer options → turn on Wireless debugging (Android 11+).",
        "Wireless debugging → Pair device with pairing code. Note the IP:port and the code.",
        "On the computer run: adb pair <ip>:<pairing-port>, enter the code, then adb connect <ip>:<port>.",
        "Run adb devices -l and confirm the phone shows as \"device\".",
    )

    val usbSteps = listOf(
        "Enable Developer options (tap Build number 7 times).",
        "Developer options → turn on USB debugging.",
        "Connect the cable and accept the \"Allow USB debugging?\" prompt on the phone.",
        "Run adb devices -l and confirm the phone shows as \"device\".",
    )

    const val LIMITATION =
        "ADB can grant ordinary runtime permissions and set AppOps that the shell is allowed to change. " +
            "It cannot grant signature or privileged permissions such as CAPTURE_AUDIO_OUTPUT, so ADB does not unlock VOICE_CALL / uplink / downlink capture."

    fun inspect(ctx: Context): List<Row> {
        val rows = mutableListOf<Row>()
        adbGrantable.forEach {
            val g = ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
            rows += Row(it.substringAfterLast('.'), g, if (g) "granted" else "not granted")
        }
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), ctx.packageName)
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        rows += Row("AppOp RECORD_AUDIO", mode == AppOpsManager.MODE_ALLOWED, if (mode == AppOpsManager.MODE_ALLOWED) "allowed" else "mode=$mode")
        val pm = ctx.getSystemService(PowerManager::class.java)
        val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        rows += Row("Battery optimisation exempt", ignoring, if (ignoring) "exempt" else "optimised (may be killed)")
        val captureGranted = ContextCompat.checkSelfPermission(ctx, "android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED
        rows += Row("CAPTURE_AUDIO_OUTPUT (privileged)", captureGranted, if (captureGranted) "granted" else "not grantable by ADB")
        return rows
    }
}
