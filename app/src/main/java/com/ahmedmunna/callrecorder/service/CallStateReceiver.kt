package com.ahmedmunna.callrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.ahmedmunna.callrecorder.data.prefs.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CallStateReceiver : BroadcastReceiver() {
    @Inject lateinit var settingsRepo: SettingsRepository

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                CallTracker.onState(context.applicationContext, settingsRepo, state, number)
            } finally {
                pending.finish()
            }
        }
    }
}
