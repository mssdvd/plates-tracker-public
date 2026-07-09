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
    private val KEY_CAPTURE_STATS = stringPreferencesKey("last_capture_stats")

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
     * is still visible afterward, not just in logcat's small ring buffer. Shown in the HUD.
     */
    suspend fun readCaptureStats(context: Context): String =
        context.dataStore.data.map { it[KEY_CAPTURE_STATS] ?: "" }.first()

    suspend fun saveCaptureStats(context: Context, stats: String) {
        context.dataStore.edit { it[KEY_CAPTURE_STATS] = stats }
    }
}
