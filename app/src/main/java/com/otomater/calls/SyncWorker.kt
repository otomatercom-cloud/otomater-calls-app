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
        val r = runSyncResult(ctx)
        // A caught failure (network/permission/server) must trigger WorkManager's own
        // exponential backoff, not just wait for the next 15-minute periodic tick.
        return if (r.failed) Result.retry() else Result.success()
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

        /** UI-facing: just the status text (kept for MainActivity's buttons). */
        fun runSync(c: Context): String = runSyncResult(c).message

        data class SyncOutcome(val message: String, val failed: Boolean)

        @Synchronized
        fun runSyncResult(c: Context): SyncOutcome {
            val base = Prefs.url(c)
            val key = Prefs.key(c)
            if (base.isEmpty() || key.isEmpty()) {
                return SyncOutcome("Enter the server address and API key first", false)
            }
            var failed = false
            val msg = try {
                val sent = sendCalls(c, base, key)
                val rec = sendRecordings(c, base, key)
                "$sent call(s) sent, $rec recording(s) uploaded" + waitingInfo(c)
            } catch (e: SecurityException) {
                failed = true
                "Permission missing: allow Call log and Music/audio access"
            } catch (e: Exception) {
                failed = true
                "Will retry: " + (e.message ?: e.javaClass.simpleName)
            }
            val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
            val full = "$stamp - $msg"
            Prefs.setStatus(c, full)
            return SyncOutcome(full, failed)
        }

        // ── calls ─────────────────────────────────────────────────────────────
        private fun sendCalls(c: Context, base: String, key: String): Int {
            val since = minOf(Prefs.lastDate(c), System.currentTimeMillis() - 6L * 3600 * 1000)
            val calls = JSONArray()
            var newest = Prefs.lastDate(c)
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
                    var dup = false
                    for (k in 0 until pending.length()) if (pending.getJSONObject(k).getString("id") == id) dup = true
                    if (dup) continue
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

        /** Shows how many recordings are still waiting and what audio files the phone has near the oldest one. */
        private fun waitingInfo(c: Context): String {
            val pending = JSONArray(Prefs.pending(c))
            if (pending.length() == 0) return ""
            val p = pending.getJSONObject(0)
            val t = p.getLong("time") / 1000
            val names = ArrayList<String>()
            try {
                c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH),
                    "${MediaStore.Audio.Media.DATE_ADDED} BETWEEN ? AND ?",
                    arrayOf((t - 60).toString(), (t + p.getLong("duration") + 900).toString()), null)?.use { cur ->
                    while (cur.moveToNext() && names.size < 3) names.add((cur.getString(1) ?: "") + (cur.getString(0) ?: ""))
                }
            } catch (e: Exception) { }
            val af = if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) " [All files access OFF - press 1]" else ""
            return af + "\n${pending.length()} recording(s) waiting. Audio near call: " + (if (names.isEmpty()) "none found" else names.joinToString(" | "))
        }

        // ── recordings ────────────────────────────────────────────────────────
        private fun sendRecordings(c: Context, base: String, key: String): Int {
            val pending = JSONArray(Prefs.pending(c))
            val keep = JSONArray()
            var done = 0
            val now = System.currentTimeMillis()
            // Tracks MediaStore row ids already picked for another call IN THIS PASS, so
            // two calls close together can never both be matched to the same recording file.
            val claimed = HashSet<Long>()
            for (i in 0 until pending.length()) {
                val p = pending.getJSONObject(i)
                val time = p.getLong("time")
                if (now - time > 24L * 3600 * 1000) {
                    // Never disappear silently: tell the server why, so it's visible on the
                    // lead instead of just vanishing from this local queue with no trace.
                    try { reportUnavailable(c, base, key, p.getString("id"), "not_found_within_24h") } catch (e: Exception) { /* best effort */ }
                    continue
                }
                if (now - time < 20_000) { keep.put(p); continue }
                val found = findRecording(c, time, p.getLong("duration"), claimed)
                    ?: findRecordingFile(time, p.getLong("duration"), claimed)
                if (found == null) { keep.put(p); continue }
                claimed.add(found.second)
                val ok = try { upload(c, base, key, p.getString("id"), found.first) } catch (e: Exception) { false }
                if (ok) done++ else keep.put(p)
            }
            Prefs.setPending(c, keep.toString())
            return done
        }

        private val RECORDER_DIRS = listOf(
            "MIUI/sound_recorder/call_rec", "MIUI/sound_recorder", "Recordings/Call", "Recordings/Call recordings",
            "Sounds/CallRecordings", "Call", "CallRecordings", "Music/Recordings/Call Recordings", "Recordings")

        /** Direct folder read (needs "All files access"): for phones whose recorder files are not in the media index. */
        private fun findRecordingFile(time: Long, durSec: Long, claimed: Set<Long>): Pair<android.net.Uri, Long>? {
            val root = android.os.Environment.getExternalStorageDirectory()
            val endMs = time + durSec * 1000
            var best: java.io.File? = null
            var bestScore = Long.MAX_VALUE
            for (d in RECORDER_DIRS) {
                val files = java.io.File(root, d).listFiles() ?: continue
                for (f in files) {
                    if (!f.isFile || f.length() < 2000) continue
                    val n = f.name.lowercase()
                    if (!(n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".amr") || n.endsWith(".wav") || n.endsWith(".3gp") || n.endsWith(".aac") || n.endsWith(".ogg"))) continue
                    val m = f.lastModified()
                    if (m < time - 30_000 || m > endMs + 900_000) continue
                    if (f.absolutePath.hashCode().toLong() in claimed) continue
                    val score = Math.abs(m - endMs)
                    if (score < bestScore) { bestScore = score; best = f }
                }
            }
            val f = best ?: return null
            return Pair(android.net.Uri.fromFile(f), f.absolutePath.hashCode().toLong())
        }

        /** The audio file the phone's own call recorder saved for this call (matched by time and
         * folder/name), excluding any MediaStore row already claimed by another call this pass.
         * Returns the content Uri plus the MediaStore row id (so the caller can claim it). */
        private fun findRecording(c: Context, time: Long, durSec: Long, claimed: Set<Long>): Pair<android.net.Uri, Long>? {
            val from = time / 1000 - 30
            val to = time / 1000 + durSec + 900
            val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH)
            var bestId: Long? = null
            var bestScore = Long.MAX_VALUE
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Audio.Media.DATE_ADDED} BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    if (id in claimed) continue
                    val hint = ((cur.getString(2) ?: "") + " " + (cur.getString(3) ?: "")).lowercase()
                    val hinted = hint.contains("call") || hint.contains("record") || hint.contains("sound")
                    val tight = cur.getLong(1) <= time / 1000 + durSec + 180
                    if (!hinted && !tight) continue
                    val score = Math.abs(cur.getLong(1) - (time / 1000 + durSec)) + (if (hinted) 0 else 100000)
                    if (score < bestScore) {
                        bestScore = score
                        bestId = id
                    }
                }
            }
            val id = bestId ?: return null
            return Pair(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), id)
        }

        /** Tells the backend a recording was never found for this call, with a short reason,
         * instead of letting it disappear from the local queue with no trace (Gap #1). */
        private fun reportUnavailable(c: Context, base: String, key: String, id: String, reason: String) {
            val con = http(base, key, "/otm/mobile/recording_failed")
            con.requestMethod = "POST"
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(JSONObject().put("id", id).put("reason", reason).toString().toByteArray()) }
            con.responseCode // drain so the connection completes; errors are caught by the caller
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
