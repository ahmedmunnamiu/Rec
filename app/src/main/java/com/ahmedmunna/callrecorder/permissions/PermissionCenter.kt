package com.ahmedmunna.callrecorder.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

enum class PermStatus(val label: String) {
    GRANTED("Granted"), DENIED("Denied"), RESTRICTED("Restricted"), NOT_SUPPORTED("Not supported")
}

enum class PermAction { REQUEST_RUNTIME, OPEN_APP_SETTINGS, BATTERY, OVERLAY, NONE }

data class PermItem(
    val id: String,
    val title: String,
    val why: String,
    val status: PermStatus,
    val permission: String? = null,
    val action: PermAction = PermAction.NONE,
    val optional: Boolean = false,
)

object PermissionCenter {

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun runtime(ctx: Context, id: String, title: String, why: String, perm: String, optional: Boolean = false) =
        PermItem(id, title, why, if (granted(ctx, perm)) PermStatus.GRANTED else PermStatus.DENIED, perm, PermAction.REQUEST_RUNTIME, optional)

    fun items(ctx: Context): List<PermItem> = buildList {
        add(runtime(ctx, "mic", "Microphone", "Required. Without it nothing can be recorded.", Manifest.permission.RECORD_AUDIO))
        add(runtime(ctx, "phone", "Phone state", "Required. Lets the app know when a call rings, is answered and ends.", Manifest.permission.READ_PHONE_STATE))
        add(runtime(ctx, "calllog", "Call log", "Recommended. Since Android 9 the caller's number is only delivered to apps holding this permission; " +
            "it is also used to learn the number of outgoing calls. Without it numbers show as Unknown.", Manifest.permission.READ_CALL_LOG, optional = true))
        add(runtime(ctx, "contacts", "Contacts", "Optional. Shows contact names and photos next to recordings and enables \"unknown numbers only\".", Manifest.permission.READ_CONTACTS, optional = true))
        if (Build.VERSION.SDK_INT >= 33) {
            add(runtime(ctx, "notif", "Notifications", "Required for the recording notification, failure alerts and low-storage warnings.", Manifest.permission.POST_NOTIFICATIONS))
        } else {
            add(PermItem("notif", "Notifications", "Notifications are allowed by default on this Android version.", PermStatus.GRANTED))
        }
        add(PermItem("fgs", "Foreground service (microphone)", "Declared in the manifest and granted at install time. Keeps recording alive while the app is in the background.", PermStatus.GRANTED))
        val pm = ctx.getSystemService(PowerManager::class.java)
        add(PermItem(
            "battery", "Ignore battery optimisation",
            "Optional but recommended. Stops aggressive battery managers from killing the recorder during a call.",
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) PermStatus.GRANTED else PermStatus.DENIED,
            action = PermAction.BATTERY, optional = true,
        ))
        add(PermItem(
            "overlay", "Display over other apps",
            "Optional. Android lets apps with this permission start the microphone service from the background, which makes auto recording more reliable on Android 12+.",
            if (Settings.canDrawOverlays(ctx)) PermStatus.GRANTED else PermStatus.DENIED,
            action = PermAction.OVERLAY, optional = true,
        ))
        add(PermItem(
            "storage", "Storage access",
            if (Build.VERSION.SDK_INT >= 29) "Not needed. Recordings use app media storage; pick another folder with the system folder picker."
            else "Not needed. Recordings use app-specific media storage.",
            PermStatus.NOT_SUPPORTED,
        ))
        add(PermItem(
            "capture", "Capture audio output (CAPTURE_AUDIO_OUTPUT)",
            "Signature-level permission reserved for the system. Normal apps and ADB cannot get it, so true call-audio sources stay blocked on almost all phones.",
            PermStatus.RESTRICTED,
        ))
        add(PermItem("a11y", "Accessibility service", "Not used. This app has no feature that needs it, so it is not requested.", PermStatus.NOT_SUPPORTED, optional = true))
        add(PermItem("notiflistener", "Notification listener", "Not used. No feature depends on it.", PermStatus.NOT_SUPPORTED, optional = true))
        add(PermItem("dialer", "Default dialer role", "Not used. This app is not a dialer.", PermStatus.NOT_SUPPORTED, optional = true))
    }

    /** 0..1 over the permissions that matter for recording. */
    fun health(ctx: Context): Float {
        val core = items(ctx).filter { it.id in setOf("mic", "phone", "notif", "calllog") }
        return core.count { it.status == PermStatus.GRANTED } / core.size.toFloat()
    }

    fun missingCore(ctx: Context): List<String> =
        items(ctx).filter { it.id in setOf("mic", "phone", "notif") && it.status != PermStatus.GRANTED }.map { it.title }

    fun appSettingsIntent(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryIntent(ctx: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun overlayIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val runtimePermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
}
