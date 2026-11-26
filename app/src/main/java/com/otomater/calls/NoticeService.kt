package com.otomater.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Keeps a light connection to Odoo (every 30 s) so new-lead / follow-up alerts reach the phone even when the app is closed. */
class NoticeService : Service() {
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("svc", "Call Center running", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("lead", "New leads & follow-ups", NotificationManager.IMPORTANCE_HIGH))
        val fg = Notification.Builder(this, "svc").setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Otomater Calls").setContentText("Listening for new leads").build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, fg)
        if (!running) { running = true; thread { loop(nm) } }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun loop(nm: NotificationManager) {
        val sp = getSharedPreferences("otm_calls", Context.MODE_PRIVATE)
        while (running) {
            try {
                val base = Prefs.url(this); val key = Prefs.key(this)
                if (base.isNotEmpty() && key.isNotEmpty()) {
                    val after = sp.getLong("notice_after", -1L)
                    val con = URL("$base/otm/mobile/notifications?after=${maxOf(after, 0)}").openConnection() as HttpURLConnection
                    con.setRequestProperty("Authorization", "Bearer $key"); con.connectTimeout = 15000; con.readTimeout = 15000
                    if (con.responseCode == 200) {
                        val items = JSONObject(con.inputStream.bufferedReader().readText()).getJSONArray("items")
                        var max = after
                        for (i in 0 until items.length()) {
                            val n = items.getJSONObject(i); val id = n.getLong("id"); if (id > max) max = id
                            if (after >= 0) show(nm, id.toInt(), n.getString("title"), n.optString("message"))   // first run only records the position
                        }
                        if (max != after) sp.edit().putLong("notice_after", max).apply()
                        else if (after < 0) sp.edit().putLong("notice_after", 0).apply()
                    }
                    con.disconnect()
                }
            } catch (_: Exception) { /* offline: try again */ }
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { return }
        }
    }

    private fun show(nm: NotificationManager, id: Int, title: String, msg: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(1000 + id, Notification.Builder(this, "lead").setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(title).setContentText(msg).setAutoCancel(true).setContentIntent(open).build())
    }
}
