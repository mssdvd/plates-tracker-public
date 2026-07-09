package com.mssdvd.platestracker.tracking

import com.mssdvd.platestracker.alpr.PlateRead
import com.mssdvd.platestracker.alpr.PlateValidator
import java.util.UUID
import kotlin.math.min

/**
 * Multi-frame voting / dedup (docs/android-app.md component 5). Gate A showed per-frame OCR slips
 * turn one physical car into several strings (e.g. edit-distance-1 OCR variants of the same plate), so:
 *
 *  - reads shorter than [minReadLength] chars are dropped before anything else: they can't seed or
 *    join a cluster. IT car (`LLDDDLL`) and moto (`LLDDDDD`) are both 7 chars, so 7 is the floor
 *    that doesn't cost real Italian reads; mopeds (`DDLLL` = 5) are out of scope for now — see
 *    2026-07-09 field data below;
 *  - reads within [windowMs] (or [promotedWindowMs] once a cluster has promoted) and either
 *    <= [maxEditDistance] edits or a fragment match (one text a substring of the other, allowing
 *    <= 1 char slip) of an existing cluster collapse into it — matched against the *best*-fitting
 *    cluster (lowest edit distance, ties broken by most recently seen), not just the first one that
 *    qualifies;
 *  - a cluster becomes a sighting after >= [minFrames] reads AND a best read >= [minConfidence];
 *  - an *exact* best read >= [fastConfidence] promotes already at [fastFrames] reads: the 2026-07-03
 *    drive showed an oncoming car at 70-90 km/h spends well under three processed frames in read
 *    range;
 *  - 2026-07-09: a single read >= [instantConfidence] AND structurally EXACT
 *    ([PlateValidator.validate]) promotes immediately ([instantFrames] = 1). The 2026-07-09 drive's
 *    field data showed 83% of clusters were single-frame — a car only ever caught in one processed
 *    frame structurally can't clear either path above, no matter how confident that one read was.
 *    The same drive also showed confidence alone can't carry a 1-frame decision (44 empty reads
 *    promoted at confidence ~=1.0, 33 plate fragments promoted as separate cars) — the structure
 *    gate, plus the length gate above, closed that: see docs/model-specs.md and the field report at
 *    device-dumps/2026-07-09_184031/REPORT.md. The steady and fast paths stay structure-free — they
 *    already require corroborating frames, which the field data didn't show producing junk;
 *  - the promotion mints the sighting's stable UUID, the server-side idempotency key. Later reads of
 *    the same cluster are absorbed silently, so one pass = one record.
 *
 * Defaults come from the Gate-A gate that validated 187 raw rows -> 18 distinct cars, tightened by
 * the 2026-07-09 field data above. Pure Kotlin, no Android deps; the caller supplies timestamps so
 * tests control the clock.
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
    private val minReadLength: Int = 7,
    // A followed car in traffic gaps > windowMs between reads; without this, its already-promoted
    // cluster gets pruned and the next read re-promotes with a fresh UUID (2026-07-09: one plate
    // became 5 records in 2 minutes). Only promoted clusters get the longer leash — unpromoted ones
    // still expire at windowMs so stale noise doesn't linger.
    private val promotedWindowMs: Long = 60_000L,
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
     * (too short, noise, already promoted, or not yet over the bar). A cluster is checked against
     * the gates on every frame including its first — [instantFrames] = 1 by default, so a single
     * sufficiently-confident read can promote on the same call that starts its cluster.
     */
    fun observe(read: PlateRead, nowMs: Long): Promotion? {
        if (read.text.length < minReadLength) return null
        clusters.removeAll { nowMs - it.lastMs > if (it.promoted) promotedWindowMs else windowMs }

        val existing = bestMatch(read)
        val cluster = if (existing != null) {
            existing.frames++
            existing.lastMs = nowMs
            // Canonical = the most trustworthy reading: a structurally valid plate beats a
            // fragment/garble that merged in on edit distance or a substring match, then exact
            // beats corrected, then higher OCR confidence.
            if (rank(read) > rank(existing.best)) existing.best = read
            existing
        } else {
            Cluster(read, nowMs).also { clusters += it }
        }

        if (cluster.promoted) return null
        val steady = cluster.frames >= minFrames && cluster.best.ocrConfidence >= minConfidence
        val fast = cluster.frames >= fastFrames &&
            cluster.best.readKind == "exact" && cluster.best.ocrConfidence >= fastConfidence
        val instant = cluster.frames >= instantFrames && cluster.best.ocrConfidence >= instantConfidence &&
            PlateValidator.validate(cluster.best.text).confidence == PlateValidator.Confidence.EXACT
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

    /**
     * The cluster [read] belongs to, if any: lowest edit distance among clusters within
     * [maxEditDistance] or fragment-matching (see [isFragment]), ties broken by most recently
     * seen. Fragment-only matches (edit distance beyond the cap, e.g. a 3-char crop of a 7-char
     * plate) rank behind any real edit-distance match.
     */
    private fun bestMatch(read: PlateRead): Cluster? {
        var best: Cluster? = null
        var bestRank = maxEditDistance + 1
        for (c in clusters) {
            val dist = editDistance(c.best.text, read.text, maxEditDistance)
            val matches = dist <= maxEditDistance || isFragment(c.best.text, read.text)
            if (!matches) continue
            val rank = min(dist, maxEditDistance) // fragment-only match ranks like a cap-distance one
            if (best == null || rank < bestRank || (rank == bestRank && c.lastMs > best.lastMs)) {
                best = c
                bestRank = rank
            }
        }
        return best
    }

    /**
     * True if the shorter of [a]/[b] fits, with at most one character slip, inside some window of
     * the longer one — the "truncated crop" pattern from the 2026-07-09 field data (a partial read
     * that's an exact or near-exact substring of the full plate, e.g. an O/0 OCR slip on one
     * character). Equal-length strings are never a "fragment" of each other; that case is edit
     * distance's job.
     */
    private fun isFragment(a: String, b: String): Boolean {
        val short: String
        val long: String
        when {
            a.length < b.length -> { short = a; long = b }
            b.length < a.length -> { short = b; long = a }
            else -> return false
        }
        if (short.isEmpty()) return false
        for (start in 0..long.length - short.length) {
            var mismatches = 0
            for (i in short.indices) {
                if (short[i] != long[start + i]) mismatches++
                if (mismatches > 1) break
            }
            if (mismatches <= 1) return true
        }
        return false
    }

    private fun rank(r: PlateRead): Float {
        val structural = PlateValidator.validate(r.text).confidence == PlateValidator.Confidence.EXACT
        return (if (structural) 100f else 0f) + r.ocrConfidence + if (r.readKind == "exact") 1f else 0f
    }

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
