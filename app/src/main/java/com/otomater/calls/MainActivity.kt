package com.otomater.calls

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }
        fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        box.addView(TextView(this).apply {
            text = "Otomater Calls"
            textSize = 22f
        })
        box.addView(TextView(this).apply {
            text = "Calls you make or receive on this phone with a lead's number are added to that lead in Odoo, " +
                "with the call recording. Calls with other numbers are never sent."
        })
        box.addView(label("Odoo address (example: https://hrms.logiceducation.org)"))
        url = EditText(this).apply { setText(Prefs.url(this@MainActivity)); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        box.addView(url)
        box.addView(label("API key (Odoo > My Profile > Account Security > New API Key)"))
        key = EditText(this).apply {
            setText(Prefs.key(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(key)
        box.addView(button("1. Allow access (call log, recordings)") { askPermissions() })
        box.addView(button("2. Save and test connection") { saveAndTest() })
        box.addView(button("3. Sync now") { syncNow() })
        box.addView(button("4. Keep running in background (battery)") { batterySettings() })
        status = TextView(this).apply { text = Prefs.status(this@MainActivity); setPadding(0, pad, 0, 0) }
        box.addView(status)

        setContentView(ScrollView(this).apply {
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (Prefs.key(this).isNotEmpty()) SyncWorker.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        status.text = Prefs.status(this)
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)
        p += if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        requestPermissions(p.toTypedArray(), 1)
    }

    private fun saveAndTest() {
        Prefs.saveLogin(this, url.text.toString(), key.text.toString())
        status.text = "Testing..."
        thread {
            val r = SyncWorker.ping(this)
            runOnUiThread {
                status.text = r
                if (r.startsWith("Connected")) SyncWorker.schedulePeriodic(this)
            }
        }
    }

    private fun syncNow() {
        Prefs.saveLogin(this, url.text.toString(), key.text.toString())
        status.text = "Syncing..."
        thread {
            val r = SyncWorker.runSync(this)
            runOnUiThread { status.text = r }
        }
    }

    private fun batterySettings() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
