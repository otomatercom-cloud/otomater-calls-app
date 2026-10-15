package com.otomater.calls

import android.content.ContentUris
import android.content.Context
import android.provider.CallLog
import android.provider.MediaStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SyncWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        runSync(ctx)
        return Result.success()
    }

    companion object {
        fun schedulePeriodic(c: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            androidx.work.WorkManager.getInstance(c)
                .enqueueUniquePeriodicWork("otm_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun http(base: String, key: String, path: String): HttpURLConnection {
            val con = URL(base + path).openConnection() as HttpURLConnection
            con.connectTimeout = 15000
            con.readTimeout = 60000
            con.setRequestProperty("Authorization", "Bearer $key")
            return con
        }

        fun ping(c: Context): String {
            return try {
                val con = http(Prefs.url(c), Prefs.key(c), "/otm/mobile/ping")
                con.requestMethod = "POST"
                con.doOutput = true
                con.outputStream.use { it.write("{}".toByteArray()) }
                val txt = (if (con.responseCode in 200..299) con.inputStream else con.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (con.responseCode == 200) "Connected as " + JSONObject(txt).optString("name")
                else "Not connected: check the server address and the API key (HTTP ${con.responseCode})"
            } catch (e: Exception) {
                "Not connected: " + (e.message ?: e.javaClass.simpleName)
            }
        }

        @Synchronized
        fun runSync(c: Context): String {
            val base = Prefs.url(c)
            val key = Prefs.key(c)
            if (base.isEmpty() || key.isEmpty()) return "Enter the server address and API key first"
            val msg = try {
                val sent = sendCalls(c, base, key)
                val rec = sendRecordings(c, base, key)
                "$sent call(s) sent, $rec recording(s) uploaded"
            } catch (e: SecurityException) {
                "Permission missing: allow Call log and Music/audio access"
            } catch (e: Exception) {
                "Will retry: " + (e.message ?: e.javaClass.simpleName)
            }
            val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
            val full = "$stamp - $msg"
            Prefs.setStatus(c, full)
            return full
        }

        // ── calls ─────────────────────────────────────────────────────────────
        private fun sendCalls(c: Context, base: String, key: String): Int {
            val since = Prefs.lastDate(c)
            val calls = JSONArray()
            var newest = since
            val cols = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION)
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, "${CallLog.Calls.DATE} > ?",
                arrayOf(since.toString()), "${CallLog.Calls.DATE} ASC")?.use { cur ->
                while (cur.moveToNext() && calls.length() < 200) {
                    val number = cur.getString(1) ?: continue
                    val dir = when (cur.getInt(2)) {
                        CallLog.Calls.OUTGOING_TYPE -> "out"
                        CallLog.Calls.INCOMING_TYPE -> "in"
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "missed"
                        else -> continue
                    }
                    val date = cur.getLong(3)
                    calls.put(JSONObject().put("id", "${date}_${cur.getLong(0)}").put("number", number)
                        .put("direction", dir).put("time", date).put("duration", cur.getLong(4)))
                    if (date > newest) newest = date
                }
            }
            if (calls.length() == 0) return 0
            val con = http(base, key, "/otm/mobile/calls")
            con.requestMethod = "POST"
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(JSONObject().put("calls", calls).toString().toByteArray()) }
            if (con.responseCode != 200) error("server answered ${con.responseCode}")
            val res = JSONObject(con.inputStream.bufferedReader().readText()).getJSONArray("results")
            val pending = JSONArray(Prefs.pending(c))
            var matched = 0
            for (i in 0 until res.length()) {
                val r = res.getJSONObject(i)
                if (!r.optBoolean("matched")) continue
                matched++
                if (r.optBoolean("need_recording")) {
                    val id = r.getString("id")
                    for (j in 0 until calls.length()) {
                        val cj = calls.getJSONObject(j)
                        if (cj.getString("id") == id) {
                            pending.put(JSONObject().put("id", id).put("time", cj.getLong("time"))
                                .put("duration", cj.getLong("duration")))
                        }
                    }
                }
            }
            Prefs.setPending(c, pending.toString())
            Prefs.setLastDate(c, newest)
            return matched
        }

        // ── recordings ────────────────────────────────────────────────────────
        private fun sendRecordings(c: Context, base: String, key: String): Int {
            val pending = JSONArray(Prefs.pending(c))
            val keep = JSONArray()
            var done = 0
            val now = System.currentTimeMillis()
            for (i in 0 until pending.length()) {
                val p = pending.getJSONObject(i)
                val time = p.getLong("time")
                if (now - time > 24L * 3600 * 1000) continue          // give up after a day
                if (now - time < 20_000) { keep.put(p); continue }
                val uri = findRecording(c, time, p.getLong("duration"))
                if (uri == null) { keep.put(p); continue }
                val ok = try { upload(c, base, key, p.getString("id"), uri) } catch (e: Exception) { false }
                if (ok) done++ else keep.put(p)
            }
            Prefs.setPending(c, keep.toString())
            return done
        }

        /** The audio file the phone's own call recorder saved for this call (matched by time and folder/name). */
        private fun findRecording(c: Context, time: Long, durSec: Long): android.net.Uri? {
            val from = time / 1000 - 30
            val to = time / 1000 + durSec + 900
            val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH)
            var best: android.net.Uri? = null
            var bestScore = Long.MAX_VALUE
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Audio.Media.DATE_ADDED} BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val hint = ((cur.getString(2) ?: "") + " " + (cur.getString(3) ?: "")).lowercase()
                    if (!(hint.contains("call") || hint.contains("record"))) continue
                    val score = Math.abs(cur.getLong(1) - (time / 1000 + durSec))
                    if (score < bestScore) {
                        bestScore = score
                        best = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cur.getLong(0))
                    }
                }
            }
            return best
        }

        private fun upload(c: Context, base: String, key: String, id: String, uri: android.net.Uri): Boolean {
            val boundary = "----otm" + System.currentTimeMillis()
            val con = http(base, key, "/otm/mobile/recording")
            con.requestMethod = "POST"
            con.doOutput = true
            con.setChunkedStreamingMode(64 * 1024)
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(con.outputStream).use { out ->
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\n$id\r\n")
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"call_$id.m4a\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n")
                c.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            return con.responseCode == 200
        }
    }
}
