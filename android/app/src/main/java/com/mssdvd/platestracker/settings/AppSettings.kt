package com.mssdvd.platestracker.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Server config for sync: base URL + bearer token, entered in-app (DataStore-backed). */
object AppSettings {

    data class Config(
        val serverUrl: String,
        val apiToken: String,
        val wifiOnly: Boolean,
        val captureV2: Boolean, // buffered capture: RAM video ring + burst re-scan
    ) {
        val isComplete: Boolean get() = serverUrl.isNotBlank() && apiToken.isNotBlank()
    }

    private val KEY_URL = stringPreferencesKey("server_url")
    private val KEY_TOKEN = stringPreferencesKey("api_token")
    private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only")
    private val KEY_CAPTURE_V2 = booleanPreferencesKey("capture_v2")
    private val KEY_SYNC_STATUS = stringPreferencesKey("last_sync_status")
    private val KEY_CAPTURE_STATS = stringPreferencesKey("capture_stats_journal")
    // Entries are separated by a marker that can't appear in buildCaptureStats()'s own text, and
    // each entry starts with "<sessionId> " so a session's periodic saves keep replacing its own
    // entry instead of appending duplicates.
    private const val ENTRY_SEP = "\n§§§\n"
    private const val MAX_JOURNAL_ENTRIES = 10

    fun flow(context: Context): Flow<Config> = context.dataStore.data.map { p ->
        Config(
            serverUrl = (p[KEY_URL] ?: "").trimEnd('/'),
            apiToken = p[KEY_TOKEN] ?: "",
            wifiOnly = p[KEY_WIFI_ONLY] ?: false,
            captureV2 = p[KEY_CAPTURE_V2] ?: false,
        )
    }

    suspend fun read(context: Context): Config = flow(context).first()

    suspend fun save(
        context: Context,
        serverUrl: String,
        apiToken: String,
        wifiOnly: Boolean,
        captureV2: Boolean,
    ) {
        context.dataStore.edit { p ->
            p[KEY_URL] = serverUrl.trim()
            p[KEY_TOKEN] = apiToken.trim()
            p[KEY_WIFI_ONLY] = wifiOnly
            p[KEY_CAPTURE_V2] = captureV2
        }
    }

    /** Outcome of the last sync attempt, written by SyncWorker, shown in the HUD. */
    suspend fun readSyncStatus(context: Context): String =
        context.dataStore.data.map { it[KEY_SYNC_STATUS] ?: "" }.first()

    suspend fun saveSyncStatus(context: Context, status: String) {
        context.dataStore.edit { it[KEY_SYNC_STATUS] = status }
    }

    /**
     * Per-session capture diagnostics, written by [CaptureService][com.mssdvd.platestracker.capture.CaptureService]
     * (periodically and on stop) so a v1/v2 fallback or thermal throttling that happened on a drive
     * is still visible afterward, not just in logcat's small ring buffer.
     *
     * A rolling journal, not a single last-writer-wins string (pre-2026-07-10 behavior): that let
     * a second same-day session silently destroy the first session's stats, which on the
     * 2026-07-10 evening drive halved the only surviving telemetry from that dump. Each entry is
     * keyed by session id so the in-session periodic save keeps overwriting *that session's* entry
     * only; the journal keeps the last [MAX_JOURNAL_ENTRIES] sessions. The HUD shows just the
     * newest entry ([readCaptureStats]); [readCaptureJournal] exposes the full history.
     */
    suspend fun saveCaptureStats(context: Context, sessionId: String, entry: String) {
        context.dataStore.edit { p ->
            val kept = (p[KEY_CAPTURE_STATS] ?: "").split(ENTRY_SEP)
                .filter { it.isNotBlank() && !it.startsWith("$sessionId ") }
            p[KEY_CAPTURE_STATS] = (kept + entry).takeLast(MAX_JOURNAL_ENTRIES).joinToString(ENTRY_SEP)
        }
    }

    /** The newest journal entry — what the HUD shows, during a session or after it stops. */
    suspend fun readCaptureStats(context: Context): String = readCaptureJournal(context).lastOrNull() ?: ""

    /** The full rolling journal, oldest first, capped at [MAX_JOURNAL_ENTRIES] sessions. */
    suspend fun readCaptureJournal(context: Context): List<String> =
        context.dataStore.data.map { p ->
            (p[KEY_CAPTURE_STATS] ?: "").split(ENTRY_SEP).filter { it.isNotBlank() }
        }.first()
}
