package com.mssdvd.platestracker.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local queue of sightings awaiting upload (docs/android-app.md component 7). Plain SQLite rather
 * than Room: one table and four queries don't justify an annotation processor on this build setup
 * (AGP 9 built-in Kotlin, no KSP). Rows carry a `synced` flag; SyncWorker drains unsynced rows.
 *
 * 2026-07-09: the `unconfirmed_reads` twin table (v3, 2026-07-06 — reads that passed plate-format
 * validation but never cleared `DedupEngine`'s frame/confidence gate) is retired along with the
 * format-regex gate itself (see `PlateRead`'s class doc, docs/model-specs.md) — v5 drops it.
 */
private const val SIGHTINGS_COLUMNS = """
    id            TEXT PRIMARY KEY,
    plate_text    TEXT NOT NULL,
    read_kind     TEXT NOT NULL,
    confidence    REAL NOT NULL,
    captured_at   TEXT NOT NULL,
    lat           REAL NOT NULL,
    lon           REAL NOT NULL,
    speed_kmh     REAL,
    accuracy_m    REAL,
    country       TEXT NOT NULL,
    source_device TEXT NOT NULL,
    synced        INTEGER NOT NULL DEFAULT 0,
    raw_text      TEXT,
    buffered_v2   INTEGER NOT NULL DEFAULT 0,
    device_temp_c REAL,
    battery_pct   INTEGER
"""

class SightingStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "sightings.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sightings ($SIGHTINGS_COLUMNS)")
        db.execSQL("CREATE INDEX idx_sightings_synced ON sightings(synced)")
        db.execSQL("CREATE INDEX idx_sightings_captured ON sightings(captured_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: pre-correction OCR text for the history screen (local-only, never uploaded).
        if (oldVersion < 2) db.execSQL("ALTER TABLE sightings ADD COLUMN raw_text TEXT")
        // v3: valid-but-unpromoted reads, uploaded to a separate server table (retired in v5).
        if (oldVersion < 3) {
            db.execSQL(
                "CREATE TABLE unconfirmed_reads (" +
                    "id TEXT PRIMARY KEY, plate_text TEXT NOT NULL, plate_type TEXT NOT NULL, " +
                    "read_kind TEXT NOT NULL, confidence REAL NOT NULL, captured_at TEXT NOT NULL, " +
                    "lat REAL NOT NULL, lon REAL NOT NULL, speed_kmh REAL, accuracy_m REAL, " +
                    "country TEXT NOT NULL, source_device TEXT NOT NULL, " +
                    "synced INTEGER NOT NULL DEFAULT 0, raw_text TEXT)"
            )
        }
        // v4: burst-vs-live provenance + device thermal/power state at capture time.
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE sightings ADD COLUMN buffered_v2 INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sightings ADD COLUMN device_temp_c REAL")
            db.execSQL("ALTER TABLE sightings ADD COLUMN battery_pct INTEGER")
        }
        // v5: format-regex gate retired (see class doc) — drop plate_type and unconfirmed_reads.
        // Rebuild rather than ALTER TABLE DROP COLUMN: that needs SQLite 3.35+, not guaranteed at
        // this app's minSdk 26 — a rebuild works on every SQLite version Android has shipped.
        if (oldVersion < 5) {
            db.execSQL("DROP TABLE IF EXISTS unconfirmed_reads")
            db.execSQL("CREATE TABLE sightings_new ($SIGHTINGS_COLUMNS)")
            db.execSQL(
                "INSERT INTO sightings_new (id, plate_text, read_kind, confidence, captured_at, " +
                    "lat, lon, speed_kmh, accuracy_m, country, source_device, synced, raw_text, " +
                    "buffered_v2, device_temp_c, battery_pct) " +
                    "SELECT id, plate_text, read_kind, confidence, captured_at, lat, lon, speed_kmh, " +
                    "accuracy_m, country, source_device, synced, raw_text, buffered_v2, device_temp_c, " +
                    "battery_pct FROM sightings"
            )
            db.execSQL("DROP TABLE sightings")
            db.execSQL("ALTER TABLE sightings_new RENAME TO sightings")
            db.execSQL("CREATE INDEX idx_sightings_synced ON sightings(synced)")
            db.execSQL("CREATE INDEX idx_sightings_captured ON sightings(captured_at)")
        }
    }

    fun insert(s: Sighting) {
        writableDatabase.insertWithOnConflict(
            "sightings", null,
            ContentValues().apply {
                put("id", s.id)
                put("plate_text", s.plateText)
                put("read_kind", s.readKind)
                put("confidence", s.confidence)
                put("captured_at", s.capturedAt)
                put("lat", s.lat)
                put("lon", s.lon)
                if (s.speedKmh != null) put("speed_kmh", s.speedKmh) else putNull("speed_kmh")
                if (s.accuracyM != null) put("accuracy_m", s.accuracyM) else putNull("accuracy_m")
                put("country", s.country)
                put("source_device", s.sourceDevice)
                if (s.rawText != null) put("raw_text", s.rawText) else putNull("raw_text")
                put("buffered_v2", s.bufferedV2)
                if (s.deviceTempC != null) put("device_temp_c", s.deviceTempC) else putNull("device_temp_c")
                if (s.batteryPct != null) put("battery_pct", s.batteryPct) else putNull("battery_pct")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun unsynced(limit: Int): List<Sighting> =
        readableDatabase.query(
            "sightings", null, "synced = 0", null, null, null, "captured_at ASC", limit.toString()
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.toSighting()) }
        }

    fun markSynced(ids: List<String>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE sightings SET synced = 1 WHERE id IN ($placeholders)", ids.toTypedArray()
        )
    }

    /** Sightings captured since the given RFC3339 UTC instant (e.g. local midnight). */
    fun countSince(sinceUtc: String): Int = intQuery(
        "SELECT COUNT(*) FROM sightings WHERE captured_at >= ?", arrayOf(sinceUtc)
    )

    fun unsyncedCount(): Int = intQuery("SELECT COUNT(*) FROM sightings WHERE synced = 0", null)

    /** One row per plate for the on-device history screen. */
    data class PlateSummary(
        val plate: String,
        val country: String,
        val count: Int,
        val firstSeen: String, // RFC3339 UTC
        val lastSeen: String,
    )

    /** Distinct plates, most recently seen first. Synced rows stay in the table, so this is the
     *  full local history, not just the upload queue. */
    fun plateSummaries(): List<PlateSummary> =
        readableDatabase.rawQuery(
            """
            SELECT plate_text, country, COUNT(*), MIN(captured_at), MAX(captured_at) AS last_seen
            FROM sightings GROUP BY plate_text ORDER BY last_seen DESC
            """.trimIndent(),
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(PlateSummary(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4)))
                }
            }
        }

    /** Every sighting of one plate, newest first. */
    fun history(plate: String): List<Sighting> =
        readableDatabase.query(
            "sightings", null, "plate_text = ?", arrayOf(plate), null, null, "captured_at DESC"
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.toSighting()) }
        }

    private fun intQuery(sql: String, args: Array<String>?): Int =
        readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun Cursor.toSighting() = Sighting(
        id = getString(getColumnIndexOrThrow("id")),
        plateText = getString(getColumnIndexOrThrow("plate_text")),
        readKind = getString(getColumnIndexOrThrow("read_kind")),
        confidence = getDouble(getColumnIndexOrThrow("confidence")),
        capturedAt = getString(getColumnIndexOrThrow("captured_at")),
        lat = getDouble(getColumnIndexOrThrow("lat")),
        lon = getDouble(getColumnIndexOrThrow("lon")),
        speedKmh = getColumnIndexOrThrow("speed_kmh").let { if (isNull(it)) null else getDouble(it) },
        accuracyM = getColumnIndexOrThrow("accuracy_m").let { if (isNull(it)) null else getDouble(it) },
        country = getString(getColumnIndexOrThrow("country")),
        sourceDevice = getString(getColumnIndexOrThrow("source_device")),
        rawText = getColumnIndexOrThrow("raw_text").let { if (isNull(it)) null else getString(it) },
        bufferedV2 = getInt(getColumnIndexOrThrow("buffered_v2")) != 0,
        deviceTempC = getColumnIndexOrThrow("device_temp_c").let { if (isNull(it)) null else getDouble(it) },
        batteryPct = getColumnIndexOrThrow("battery_pct").let { if (isNull(it)) null else getInt(it) },
    )

    companion object {
        @Volatile private var instance: SightingStore? = null

        /** One helper instance per process — SQLite serializes writers per connection. */
        fun get(context: Context): SightingStore =
            instance ?: synchronized(this) {
                instance ?: SightingStore(context).also { instance = it }
            }
    }
}
