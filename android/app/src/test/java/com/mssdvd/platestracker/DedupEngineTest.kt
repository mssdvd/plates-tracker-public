package com.mssdvd.platestracker

import com.mssdvd.platestracker.alpr.PlateBox
import com.mssdvd.platestracker.alpr.PlateRead
import com.mssdvd.platestracker.tracking.DedupEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DedupEngineTest {

    private val box = PlateBox(0, 0, 10, 10, 0.9f)

    private fun read(
        text: String,
        conf: Float = 0.82f,
        kind: String = "exact",
    ) = PlateRead(box, text, conf, kind, "IT")

    @Test
    fun exactHighConfidencePromotesOnSecondFrame() {
        // Fast path (2026-07-03 drive): an oncoming pass yields ~2 processed frames, so exact
        // reads >= 0.75 must not wait for the third.
        val engine = DedupEngine()
        assertNull(engine.observe(read("CN555PL"), 0))
        val p = engine.observe(read("CN555PL"), 125)
        assertNotNull(p)
        assertEquals("CN555PL", p!!.plateText)
        assertEquals(2, p.frames)
        // Absorbed, not re-promoted.
        assertNull(engine.observe(read("CN555PL"), 250))
        assertNull(engine.observe(read("CN555PL"), 375))
    }

    @Test
    fun correctedReadsStillNeedThreeFrames() {
        val engine = DedupEngine()
        assertNull(engine.observe(read("CN555PL", conf = 0.80f, kind = "corrected"), 0))
        assertNull(engine.observe(read("CN555PL", conf = 0.80f, kind = "corrected"), 125))
        val p = engine.observe(read("CN555PL", conf = 0.80f, kind = "corrected"), 250)
        assertNotNull(p)
        assertEquals(3, p!!.frames)
    }

    @Test
    fun exactBelowFastConfidenceWaitsForThirdFrame() {
        val engine = DedupEngine()
        assertNull(engine.observe(read("CN555PL", conf = 0.72f), 0))
        assertNull(engine.observe(read("CN555PL", conf = 0.72f), 125)) // < 0.75: no fast path
        val p = engine.observe(read("CN555PL", conf = 0.72f), 250)     // >= 0.70: steady path
        assertNotNull(p)
        assertEquals(3, p!!.frames)
    }

    @Test
    fun misreadHaloCollapsesToOneCanonicalSighting() {
        // Gate-A case: CN555PL's halo (GN555PL, CN655PL, ...) is the same car within edit distance 2.
        val engine = DedupEngine()
        assertNull(engine.observe(read("GN555PL", conf = 0.45f, kind = "corrected"), 0))
        // Second frame is exact + high confidence -> fast path fires here.
        val p = engine.observe(read("CN555PL", conf = 0.83f), 125)
        assertNotNull(p)
        // Canonical is the exact high-confidence reading, not the first read.
        assertEquals("CN555PL", p!!.plateText)
        assertEquals("exact", p.readKind)
        // The rest of the halo is absorbed silently.
        assertNull(engine.observe(read("CN655PL", conf = 0.51f, kind = "corrected"), 250))
        assertNull(engine.observe(read("CN555PL"), 375))
        assertNull(engine.observe(read("GN555PL", conf = 0.4f, kind = "corrected"), 500))
    }

    @Test
    fun lowConfidenceNeverPromotes() {
        // The false-positive path from the spike: N frames of low-conf "corrected" garble.
        val engine = DedupEngine()
        for (t in 0..10) {
            assertNull(engine.observe(read("ET560LT", conf = 0.45f, kind = "corrected"), t * 125L))
        }
    }

    @Test
    fun lateHighConfidenceReadUnlocksPromotion() {
        val engine = DedupEngine()
        assertNull(engine.observe(read("FH874LW", conf = 0.5f, kind = "corrected"), 0))
        assertNull(engine.observe(read("FH874LW", conf = 0.55f, kind = "corrected"), 125))
        assertNull(engine.observe(read("FH874LW", conf = 0.6f, kind = "corrected"), 250))
        val p = engine.observe(read("FH874LW", conf = 0.81f), 375)
        assertNotNull(p)
        assertEquals(0.81f, p!!.confidence, 1e-6f)
    }

    @Test
    fun distinctPlatesPromoteSeparately() {
        val engine = DedupEngine()
        // Two real cars in frame together: > 2 edits apart, so they never merge.
        val promoted = mutableListOf<DedupEngine.Promotion>()
        for (t in 0..2) {
            engine.observe(read("CN555PL"), t * 125L)?.let { promoted += it }
            engine.observe(read("DE084GW"), t * 125L)?.let { promoted += it }
        }
        assertEquals(setOf("CN555PL", "DE084GW"), promoted.map { it.plateText }.toSet())
        assertEquals(2, promoted.size)
        // Further reads are absorbed, not re-promoted.
        assertNull(engine.observe(read("CN555PL"), 500))
        assertNull(engine.observe(read("DE084GW"), 500))
    }

    @Test
    fun windowExpiryMintsNewSightingWithNewId() {
        val engine = DedupEngine()
        engine.observe(read("CN555PL"), 0)
        val first = engine.observe(read("CN555PL"), 125)!!
        // Same car passes again well past even the promoted-cluster's extended window (60 s):
        // a new logical sighting, new idempotency key.
        engine.observe(read("CN555PL"), 70_000)
        val second = engine.observe(read("CN555PL"), 70_125)!!
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun emptyReadNeverPromotes() {
        // 2026-07-09 field data: an all-pad OCR output is "confident emptiness" — mean of slot
        // maxima over pad slots sails over every confidence bar. The length gate drops it before
        // it can even seed a cluster, regardless of confidence.
        val engine = DedupEngine()
        for (t in 0..5) {
            assertNull(engine.observe(read("", conf = 1.0f), t * 125L))
        }
    }

    @Test
    fun fragmentThenFullPlatePromotesOnce() {
        // 2026-07-09 field data: a <7-char crop of a plate read seconds before/after the full
        // read used to promote as its own (mislabeled-foreign) sighting. Now it's dropped outright
        // by the length gate, so only the full read's own cluster can ever promote.
        val engine = DedupEngine()
        assertNull(engine.observe(read("CN5", conf = 0.92f), 0))
        val p = engine.observe(read("CN555PL", conf = 0.95f), 1_400)
        assertNotNull(p)
        assertEquals("CN555PL", p!!.plateText)
        assertEquals(1, p.frames)
        // The fragment can also arrive after the full read — still just dropped, not a second cluster.
        assertNull(engine.observe(read("CN5", conf = 0.9f), 2_000))
    }

    @Test
    fun promotedClusterAbsorbsReadsWithinExtendedWindow() {
        // 2026-07-09 field data: a followed car's reads gap >10 s (old windowMs) in traffic,
        // re-promoting with a fresh UUID every time — one plate became 5 records in 2 minutes.
        // The promoted-cluster window is extended to 60 s so gaps like these stay one sighting.
        val engine = DedupEngine()
        val first = engine.observe(read("AB123CD", conf = 0.95f), 0)
        assertNotNull(first)
        assertNull(engine.observe(read("AB123CD"), 20_000)) // 20 s later: absorbed
        assertNull(engine.observe(read("AB123CD"), 65_000)) // 45 s after that: still absorbed
    }

    @Test
    fun gapBeyondExtendedWindowStillMintsNewSighting() {
        // The extended window isn't unlimited: a gap past it is a genuine re-encounter.
        val engine = DedupEngine()
        val first = engine.observe(read("AB123CD", conf = 0.95f), 0)!!
        val second = engine.observe(read("AB123CD", conf = 0.95f), 61_000)!!
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun fragmentBeyondEditDistanceMergesInsteadOfDrifting() {
        // 2026-07-09 field data: a fragment-seeded sibling cluster (too far by edit distance to
        // merge) absorbed reads until it drifted within range of the full text, then promoted the
        // same plate a second time. A >=7-char misread that's a substring-with-<=1-slip fragment
        // of the eventual full read must now merge on the *first* read, not drift into range later.
        val engine = DedupEngine()
        // "XXXCN555PL" is 3 inserts away from "CN555PL" (edit distance 3 > maxEditDistance 2), but
        // "CN555PL" sits exactly inside it (fragment match) and is also structurally invalid on its
        // own, so this can't self-promote.
        assertNull(engine.observe(read("XXXCN555PL", conf = 0.5f, kind = "corrected"), 0))
        // Merges into the same cluster via the fragment match; the structurally-exact "CN555PL"
        // outranks the garbled seed, becomes canonical, and the fast path fires (2 frames, exact).
        val p = engine.observe(read("CN555PL", conf = 0.83f), 100)
        assertNotNull(p)
        assertEquals("CN555PL", p!!.plateText)
        assertEquals(2, p.frames)
        // A later near-duplicate garble absorbs into the same (already-promoted) cluster instead
        // of seeding a new sibling that could drift into its own promotion.
        assertNull(engine.observe(read("YYCN555PL", conf = 0.6f, kind = "corrected"), 200))
    }

    @Test
    fun instantConfidencePromotesOnFirstFrame() {
        // 2026-07-09: a single very-confident read promotes immediately — field data showed 83%
        // of real clusters are single-frame, so neither the steady nor fast path can ever fire.
        val engine = DedupEngine()
        val p = engine.observe(read("CN555PL", conf = 0.95f), 0)
        assertNotNull(p)
        assertEquals(1, p!!.frames)
        assertEquals("CN555PL", p.plateText)
        // Absorbed, not re-promoted.
        assertNull(engine.observe(read("CN555PL", conf = 0.95f), 125))
    }

    @Test
    fun belowInstantConfidenceWaitsForAnotherPath() {
        val engine = DedupEngine()
        // 0.89 < instantConfidence(0.90) and < fastConfidence(0.75)... exact but frames=1 < fastFrames.
        assertNull(engine.observe(read("CN555PL", conf = 0.89f), 0))
        // Second frame clears the fast path (frames=2, exact, >= 0.75), not the instant one.
        val p = engine.observe(read("CN555PL", conf = 0.89f), 125)
        assertNotNull(p)
        assertEquals(2, p!!.frames)
    }
}
