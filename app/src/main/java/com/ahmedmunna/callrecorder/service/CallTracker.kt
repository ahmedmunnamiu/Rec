package com.ahmedmunna.callrecorder.service

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.ahmedmunna.callrecorder.data.prefs.SettingsRepository
import com.ahmedmunna.callrecorder.util.Notifier
import com.ahmedmunna.callrecorder.util.PhoneUtil
import com.ahmedmunna.callrecorder.domain.CallDirection

/**
 * Turns the raw PHONE_STATE broadcasts into call events. State is kept in SharedPreferences because the
 * process can be killed between broadcasts.
 *
 *  IDLE -> RINGING -> OFFHOOK : incoming call answered
 *  IDLE -> OFFHOOK            : outgoing call (placed / connecting)
 *  RINGING -> IDLE            : missed (or rejected) call
 *  OFFHOOK -> IDLE            : call ended
 */
object CallTracker {
    private const val PREFS = "call_tracker"
    private const val K_LAST = "last"
    private const val K_NUMBER = "number"
    private const val K_MISSED = "missed"

    fun missedCount(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(K_MISSED, 0)
    fun clearMissed(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(K_MISSED, 0).apply()

    /** Called from a coroutine/goAsync context. */
    suspend fun onState(ctx: Context, settingsRepo: SettingsRepository, state: String, rawNumber: String?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getString(K_LAST, TelephonyManager.EXTRA_STATE_IDLE)
        val number = PhoneUtil.normalize(rawNumber)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val e = p.edit().putString(K_LAST, state)
                if (last != TelephonyManager.EXTRA_STATE_RINGING) e.putString(K_NUMBER, "UNKNOWN")
                if (number != "UNKNOWN") e.putString(K_NUMBER, number)
                e.apply()
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (last == TelephonyManager.EXTRA_STATE_OFFHOOK) return
                val incoming = last == TelephonyManager.EXTRA_STATE_RINGING
                val num = if (incoming) p.getString(K_NUMBER, "UNKNOWN") ?: "UNKNOWN" else "UNKNOWN"
                p.edit().putString(K_LAST, state).apply()
                val direction = if (incoming) CallDirection.INCOMING else CallDirection.OUTGOING
                val decision = RecordPolicy.decide(ctx, settingsRepo.current(), num, direction)
                if (!decision.record) return
                val intent = Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_DIRECTION, direction.name)
                    .putExtra(RecordingService.EXTRA_NUMBER, num)
                    .putExtra(RecordingService.EXTRA_START_TIME, System.currentTimeMillis())
                try {
                    ContextCompat.startForegroundService(ctx, intent)
                } catch (t: Throwable) {
                    // Android 12+ blocks starting a microphone foreground service from the background unless an exemption applies.
                    Notifier.alert(
                        ctx, Notifier.ID_FAILURE, "Recording could not start",
                        "Android blocked starting the recorder from the background (${t.javaClass.simpleName}). " +
                            "Grant the optional 'Display over other apps' exemption in the Permission Center and keep the app out of battery restrictions."
                    )
                    RecordingService.logStartFailure(ctx, direction, num, t)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                p.edit().putString(K_LAST, state).apply()
                when (last) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        val who = p.getString(K_NUMBER, "UNKNOWN") ?: "UNKNOWN"
                        p.edit().putInt(K_MISSED, p.getInt(K_MISSED, 0) + 1).apply()
                        Notifier.alert(ctx, Notifier.ID_MISSED, "Missed call", "Unanswered call from ${if (who == "UNKNOWN") "an unknown number" else who}. Nothing was recorded.")
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        // If the recorder never started there is nothing to stop; Android may refuse a background startService.
                        runCatching { ctx.startService(Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) }
                    }
                }
            }
        }
    }
}
