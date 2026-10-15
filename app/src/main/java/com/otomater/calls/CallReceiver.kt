package com.otomater.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** After every call ends (and after a reboot) sync a little later, once Android has written the call log. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val idle = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_IDLE
        if (!idle || Prefs.key(context).isEmpty()) return
        SyncWorker.schedulePeriodic(context)
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(25, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("otm_after_call", ExistingWorkPolicy.REPLACE, req)
    }
}
