package com.ahmedmunna.callrecorder.service

import android.content.Context
import com.ahmedmunna.callrecorder.data.prefs.AppSettings
import com.ahmedmunna.callrecorder.data.prefs.RecordScope
import com.ahmedmunna.callrecorder.domain.CallDirection
import com.ahmedmunna.callrecorder.util.ContactResolver
import com.ahmedmunna.callrecorder.util.PhoneUtil

/** Decides whether a call should be recorded according to the user's smart-recording rules. */
object RecordPolicy {

    data class Decision(val record: Boolean, val reason: String, val deferUntilNumberKnown: Boolean = false)

    fun decide(ctx: Context, s: AppSettings, number: String, direction: CallDirection): Decision {
        if (!s.autoRecord) return Decision(false, "Auto recording is off")
        val unknown = number == "UNKNOWN"
        // Outgoing numbers are not announced by Android; they are read from the call log afterwards.
        if (unknown && direction == CallDirection.OUTGOING && (s.scope != RecordScope.ALL || s.excludedNumbers.isNotEmpty())) {
            return Decision(true, "Number not yet known", deferUntilNumberKnown = true)
        }
        if (!unknown && s.excludedNumbers.any { PhoneUtil.sameNumber(it, number) }) return Decision(false, "Number is excluded")
        return when (s.scope) {
            RecordScope.ALL -> if (unknown && !s.recordUnknown) Decision(false, "Unknown numbers are not recorded") else Decision(true, "All calls")
            RecordScope.SELECTED_ONLY ->
                if (!unknown && s.selectedNumbers.any { PhoneUtil.sameNumber(it, number) }) Decision(true, "Selected number")
                else Decision(false, "Number is not in the selected list")
            RecordScope.UNKNOWN_ONLY ->
                if (unknown || !ContactResolver.isContact(ctx, number)) Decision(true, "Unknown number")
                else Decision(false, "Number is a saved contact")
        }
    }
}
