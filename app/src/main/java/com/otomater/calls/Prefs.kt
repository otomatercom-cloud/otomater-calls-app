package com.otomater.calls

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("otm_calls", Context.MODE_PRIVATE)

    fun url(c: Context): String = sp(c).getString("url", "") ?: ""
    fun key(c: Context): String = sp(c).getString("key", "") ?: ""
    fun lastDate(c: Context): Long = sp(c).getLong("last_date", 0L)
    fun status(c: Context): String = sp(c).getString("status", "Not synced yet") ?: ""
    fun pending(c: Context): String = sp(c).getString("pending", "[]") ?: "[]"

    fun saveLogin(c: Context, url: String, key: String) {
        val e = sp(c).edit()
        e.putString("url", url.trim().trimEnd('/')).putString("key", key.trim())
        // only calls made from now on are sent (older personal history is never uploaded)
        if (sp(c).getLong("last_date", 0L) == 0L) e.putLong("last_date", System.currentTimeMillis())
        e.apply()
    }

    fun setLastDate(c: Context, v: Long) = sp(c).edit().putLong("last_date", v).apply()
    fun setStatus(c: Context, s: String) = sp(c).edit().putString("status", s).apply()
    fun setPending(c: Context, s: String) = sp(c).edit().putString("pending", s).apply()
}
