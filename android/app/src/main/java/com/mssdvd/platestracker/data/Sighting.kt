package com.mssdvd.platestracker.data

import org.json.JSONObject

/**
 * One recorded sighting. Field-for-field mirror of the server wire contract — the reference
 * producer is the seeder's `sighting` struct (server/cmd/seed/main.go); the app is integrated when
 * its records are indistinguishable from the seeder's. `id` is the stable client UUID minted at
 * dedup promotion (the server's idempotency key).
 */
data class Sighting(
    val id: String,
    val plateText: String,
    val readKind: String,
    val confidence: Double,
    val capturedAt: String, // RFC3339 UTC
    val lat: Double,
    val lon: Double,
    val speedKmh: Double?,  // nullable on the wire (omitted when unknown)
    val accuracyM: Double?,
    val country: String,
    val sourceDevice: String,
    // Local-only (not on the wire): raw OCR text for the history screen (equals plateText now that
    // there's no correction step — see PlateRead's class doc — kept for that screen's contract).
    val rawText: String? = null,
    // Buffered-capture-v2 diagnostics (docs/android-app.md component 8), also local-only for now —
    // the wire contract is server-coupled and server-side storage for these is deferred. Was this
    // specific read recovered by the burst re-scan rather than the live pass, and the device's
    // thermal/power state at capture time — none of this survived past logcat before, see
    // buildCaptureStats().
    val bufferedV2: Boolean = false,
    val deviceTempC: Double? = null,
    val batteryPct: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("plate_text", plateText)
        put("read_kind", readKind)
        put("confidence", confidence)
        put("captured_at", capturedAt)
        put("lat", lat)
        put("lon", lon)
        speedKmh?.let { put("speed_kmh", it) }
        accuracyM?.let { put("accuracy_m", it) }
        put("country", country)
        put("source_device", sourceDevice)
    }
}
