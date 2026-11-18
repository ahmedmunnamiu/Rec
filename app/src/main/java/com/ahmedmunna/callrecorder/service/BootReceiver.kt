package com.ahmedmunna.callrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ahmedmunna.callrecorder.work.WorkScheduler

/** After boot or an app update: repair interrupted recordings and re-arm the periodic maintenance job. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WorkScheduler.scheduleMaintenance(context.applicationContext)
            WorkScheduler.runMaintenanceNow(context.applicationContext)
        }
    }
}
