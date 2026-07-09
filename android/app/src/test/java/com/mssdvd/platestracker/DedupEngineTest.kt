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
        // Same car passes again well past the window: a new logical sighting, new idempotency key.
        engine.observe(read("CN555PL"), 60_000)
        val second = engine.observe(read("CN555PL"), 60_125)!!
        assertNotEquals(first.id, second.id)
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
