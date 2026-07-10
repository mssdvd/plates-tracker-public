package com.mssdvd.platestracker.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mssdvd.platestracker.AppLog
import com.mssdvd.platestracker.data.Sighting
import com.mssdvd.platestracker.data.SightingStore
import com.mssdvd.platestracker.settings.AppSettings
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Drains the sightings queue to the server in batches (docs/android-app.md component 8):
 * `POST {server}/records`. Retries are safe: a batch is only marked synced after a 2xx, and the
 * server dedups on the client-minted id (INSERT OR IGNORE), so a retry after a half-applied batch
 * is a server-side no-op.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cfg = AppSettings.read(applicationContext)
        if (!cfg.isComplete) {
            AppLog.i(TAG, "server not configured; leaving queue as-is")
            AppSettings.saveSyncStatus(applicationContext, "server not configured")
            return Result.success()
        }
        val store = SightingStore.get(applicationContext)

        when (
            val outcome = drainQueue("${cfg.serverUrl}/records", cfg.apiToken, store::unsynced, store::markSynced)
        ) {
            is DrainOutcome.Done -> {
                val uploaded = outcome.uploaded
                AppSettings.saveSyncStatus(
                    applicationContext, "ok ${clock()}" + if (uploaded > 0) " (+$uploaded)" else ""
                )
                return Result.success()
            }
            is DrainOutcome.Stopped -> {
                AppSettings.saveSyncStatus(
                    applicationContext,
                    outcome.statusMessage + if (outcome.uploaded > 0) " (+${outcome.uploaded})" else "",
                )
                return outcome.result
            }
        }
    }

    private sealed class DrainOutcome {
        data class Done(val uploaded: Int) : DrainOutcome()

        /** [statusMessage] is the full message except the trailing "(+N)" — doWork adds that. */
        data class Stopped(val result: Result, val uploaded: Int, val statusMessage: String) : DrainOutcome()
    }

    /** Drains one queue until empty or a hard-failure/retry condition stops it early. */
    private suspend fun drainQueue(
        url: String,
        token: String,
        fetch: (Int) -> List<Sighting>,
        mark: (List<String>) -> Unit,
    ): DrainOutcome {
        var uploaded = 0
        while (true) {
            val batch = withContext(Dispatchers.IO) { fetch(BATCH_SIZE) }
            if (batch.isEmpty()) return DrainOutcome.Done(uploaded)

            val body = JSONArray().also { arr -> batch.forEach { arr.put(it.toJson()) } }.toString()
            val status = withContext(Dispatchers.IO) { post(url, token, body) }
            when {
                status in 200..299 -> {
                    withContext(Dispatchers.IO) { mark(batch.map { it.id }) }
                    uploaded += batch.size
                }
                status == 401 || status == 400 -> {
                    // Retrying won't fix a bad token or a rejected payload; keep rows queued and
                    // surface it (the HUD shows the unsynced count not going down).
                    AppLog.w(TAG, "upload to $url rejected with HTTP $status; check server URL/token")
                    val msg = "rejected HTTP $status at ${clock()} — check URL/token"
                    return DrainOutcome.Stopped(Result.failure(), uploaded, msg)
                }
                else -> { // network trouble / 5xx: back off and try again
                    return DrainOutcome.Stopped(Result.retry(), uploaded, "unreachable at ${clock()} — retrying")
                }
            }
        }
    }

    private fun clock(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun post(url: String, token: String, body: String): Int = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "upload failed: $e")
        -1
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val BATCH_SIZE = 100
        private const val WORK_NAME = "sync"

        /** Enqueue a drain of the queue; safe to call on every promotion. */
        fun enqueue(context: Context, wifiOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
