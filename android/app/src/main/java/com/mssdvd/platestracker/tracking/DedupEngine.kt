package com.mssdvd.platestracker.tracking

import com.mssdvd.platestracker.alpr.PlateRead
import java.util.UUID
import kotlin.math.min

/**
 * Multi-frame voting / dedup (docs/android-app.md component 5). Gate A showed per-frame OCR slips
 * turn one physical car into several strings (e.g. edit-distance-1 OCR variants of the same plate), so:
 *
 *  - reads within [windowMs] and <= [maxEditDistance] edits collapse into one candidate cluster;
 *  - a cluster becomes a sighting after >= [minFrames] reads AND a best read >= [minConfidence];
 *  - an *exact* best read >= [fastConfidence] promotes already at [fastFrames] reads: the 2026-07-03
 *    drive showed an oncoming car at 70-90 km/h spends well under three processed frames in read
 *    range;
 *  - 2026-07-09: a single read >= [instantConfidence] promotes immediately ([instantFrames] = 1).
 *    The 2026-07-09 drive's field data showed 83% of clusters were single-frame — a car only ever
 *    caught in one processed frame structurally can't clear either path above, no matter how
 *    confident that one read was. Accepted tradeoff: confidence alone doesn't reliably separate a
 *    genuine read from confident garble on this OCR model (see docs/model-specs.md's re-verified
 *    invalid-read-confidence numbers) — [instantConfidence] is set high enough to bound, not
 *    eliminate, that risk;
 *  - the promotion mints the sighting's stable UUID, the server-side idempotency key. Later reads of
 *    the same cluster are absorbed silently, so one pass = one record.
 *
 * Defaults come from the Gate-A gate that validated 187 raw rows -> 18 distinct cars. Pure Kotlin,
 * no Android deps; the caller supplies timestamps so tests control the clock.
 */
class DedupEngine(
    private val minFrames: Int = 3,
    private val minConfidence: Float = 0.70f,
    private val windowMs: Long = 10_000L,
    private val maxEditDistance: Int = 2,
    private val fastFrames: Int = 2,
    private val fastConfidence: Float = 0.75f,
    private val instantFrames: Int = 1,
    private val instantConfidence: Float = 0.90f,
) {

    /** A promoted sighting — everything the wire contract needs except location. */
    data class Promotion(
        val id: String,         // stable client UUID (idempotency key), minted exactly once
        val plateText: String,
        val rawText: String,    // the canonical read's raw OCR text (local-only)
        val readKind: String,
        val confidence: Float,
        val country: String,
        val firstSeenMs: Long,
        val frames: Int,
    )

    private class Cluster(var best: PlateRead, val firstMs: Long) {
        var lastMs: Long = firstMs
        var frames: Int = 1
        var promoted: Boolean = false
    }

    private val clusters = mutableListOf<Cluster>()

    /**
     * Feed one read. Returns a [Promotion] at the moment its cluster clears a gate, or null
     * (noise, already promoted, or not yet over the bar). A cluster is checked against the gates
     * on every frame including its first — [instantFrames] = 1 by default, so a single
     * sufficiently-confident read can promote on the same call that starts its cluster.
     */
    fun observe(read: PlateRead, nowMs: Long): Promotion? {
        clusters.removeAll { nowMs - it.lastMs > windowMs }

        val existing = clusters.firstOrNull {
            editDistance(it.best.text, read.text, maxEditDistance) <= maxEditDistance
        }
        val cluster = if (existing != null) {
            existing.frames++
            existing.lastMs = nowMs
            // Canonical = the most trustworthy reading: exact beats corrected, then higher OCR confidence.
            if (rank(read) > rank(existing.best)) existing.best = read
            existing
        } else {
            Cluster(read, nowMs).also { clusters += it }
        }

        if (cluster.promoted) return null
        val steady = cluster.frames >= minFrames && cluster.best.ocrConfidence >= minConfidence
        val fast = cluster.frames >= fastFrames &&
            cluster.best.readKind == "exact" && cluster.best.ocrConfidence >= fastConfidence
        val instant = cluster.frames >= instantFrames && cluster.best.ocrConfidence >= instantConfidence
        if (!steady && !fast && !instant) return null
        cluster.promoted = true
        return Promotion(
            id = UUID.randomUUID().toString(),
            plateText = cluster.best.text,
            rawText = cluster.best.rawText,
            readKind = cluster.best.readKind,
            confidence = cluster.best.ocrConfidence,
            country = cluster.best.country,
            firstSeenMs = cluster.firstMs,
            frames = cluster.frames,
        )
    }

    private fun rank(r: PlateRead): Float =
        r.ocrConfidence + if (r.readKind == "exact") 1f else 0f

    /** Levenshtein distance with an early exit once it must exceed [cap]. */
    private fun editDistance(a: String, b: String, cap: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), sub)
                rowMin = min(rowMin, cur[j])
            }
            if (rowMin > cap) return cap + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
