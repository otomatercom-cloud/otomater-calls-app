package com.otomater.calls

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** The EduOS Call Center (Next.js) inside this app, plus a small bridge to the phone's dialler and call log. */
class WebActivity : Activity() {
    private lateinit var web: WebView
    private val host get() = Uri.parse(Prefs.portal(this)).host

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { startForegroundService(Intent(this, NoticeService::class.java)) } catch (_: Exception) { }
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = userAgentString + " OtomaterCallsApp/1"
        }
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "OtmApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                return when (u.scheme) {
                    "tel", "sms", "mailto" -> { startActivity(Intent(if (u.scheme == "tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW, u)); true }
                    "http", "https" -> if (u.host == host) false else { startActivity(Intent(Intent.ACTION_VIEW, u)); true }  // WhatsApp etc. open outside
                    else -> true
                }
            }
        }
        val base = Prefs.portal(this)
        web.loadUrl(if (base.startsWith("https://")) "$base/callcenter" else "about:blank")
        if (!base.startsWith("https://")) startActivity(Intent(this, MainActivity::class.java).putExtra("settings", true))
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true }
        return super.onKeyDown(code, e)
    }

    inner class Bridge {
        @JavascriptInterface fun isApp() = true

        /** Opens the chat in the officer's own WhatsApp (or WhatsApp Business) with the text prefilled. */
        @JavascriptInterface fun openWhatsApp(number: String, text: String) {
            var d = number.filter { it.isDigit() }
            if (d.length == 10) d = "91$d"
            if (d.length < 7) return
            val uri = Uri.parse("https://wa.me/$d?text=" + Uri.encode(text))
            runOnUiThread {
                for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
                    try {
                        val i = Intent(Intent.ACTION_VIEW, uri)
                        if (pkg != null) i.setPackage(pkg)
                        startActivity(i)
                        return@runOnUiThread
                    } catch (_: Exception) { /* try next */ }
                }
            }
        }

        @JavascriptInterface fun openSettings() {
            runOnUiThread { startActivity(Intent(this@WebActivity, MainActivity::class.java).putExtra("settings", true)) }
        }

        /** Latest call-log entry for [number] at/after [sinceMs]: {found,type,duration,date}. Read-only, nothing is sent. */
        @JavascriptInterface fun callInfo(number: String, sinceMs: Long): String {
            val o = JSONObject().put("found", false)
            try {
                val tail = number.filter { it.isDigit() }.takeLast(10)
                if (tail.length < 7) return o.toString()
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                    "${CallLog.Calls.DATE} >= ?", arrayOf((sinceMs - 5000).toString()),
                    "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val n = (c.getString(0) ?: "").filter { it.isDigit() }
                        if (n.takeLast(10) == tail) {
                            val t = c.getInt(1)
                            return o.put("found", true).put("duration", c.getInt(2)).put("date", c.getLong(3))
                                .put("type", when (t) { CallLog.Calls.OUTGOING_TYPE -> "outgoing"; CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.INCOMING_TYPE -> "incoming"; else -> "other" })
                                .toString()
                        }
                    }
                }
            } catch (_: SecurityException) { o.put("error", "permission") }
            return o.toString()
        }
    }
}
