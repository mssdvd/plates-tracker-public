package com.mssdvd.platestracker

import com.mssdvd.platestracker.capture.AuRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuRingTest {

    private fun au(ptsUs: Long, key: Boolean = false, size: Int = 100) =
        AuRing.Au(ptsUs, key, ByteArray(size))

    @Test
    fun evictsOldestWhenOverCapacity() {
        val ring = AuRing(capacityBytes = 350)
        for (i in 0..4) ring.add(au(i * 33_000L, key = true, size = 100))
        // 500 bytes added into a 350-byte budget: the two oldest AUs must be gone.
        val all = ring.snapshot(Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(3, all.size)
        assertTrue(all.none { it.ptsUs < 66_000L })
    }

    @Test
    fun snapshotStartsAtKeyframeBeforeWindow() {
        val ring = AuRing(1_000_000)
        // Two GOPs: keys at 0 and 1_000_000, deltas every 33 ms.
        for (pts in longArrayOf(0, 33_000, 66_000)) ring.add(au(pts, key = pts == 0L))
        for (pts in longArrayOf(1_000_000, 1_033_000, 1_066_000)) ring.add(au(pts, key = pts == 1_000_000L))
        val clip = ring.snapshot(1_050_000, 1_100_000)
        // Starts at the second GOP's keyframe, not inside the first GOP.
        assertEquals(1_000_000L, clip.first().ptsUs)
        assertTrue(clip.first().keyFrame)
        assertEquals(1_066_000L, clip.last().ptsUs)
    }

    @Test
    fun snapshotFallsBackToFirstKeyframeAfterEviction() {
        val ring = AuRing(1_000_000)
        // Eviction ate the GOP that covered the window start: only a later key remains.
        ring.add(au(0, key = false)) // orphaned delta frame
        ring.add(au(100_000, key = true))
        ring.add(au(133_000))
        val clip = ring.snapshot(0, 133_000)
        assertEquals(100_000L, clip.first().ptsUs)
        assertTrue(clip.first().keyFrame)
    }

    @Test
    fun snapshotEmptyWithoutAnyKeyframe() {
        val ring = AuRing(1_000_000)
        ring.add(au(0))
        ring.add(au(33_000))
        assertTrue(ring.snapshot(0, 33_000).isEmpty())
    }

    @Test
    fun snapshotExcludesFramesPastWindowEnd() {
        val ring = AuRing(1_000_000)
        for (pts in longArrayOf(0, 33_000, 66_000, 99_000)) ring.add(au(pts, key = pts == 0L))
        assertEquals(66_000L, ring.snapshot(0, 70_000).last().ptsUs)
    }

    @Test
    fun latestPtsTracksNewestAu() {
        val ring = AuRing(1_000_000)
        assertEquals(Long.MIN_VALUE, ring.latestPtsUs())
        ring.add(au(0, key = true))
        ring.add(au(33_000))
        assertEquals(33_000L, ring.latestPtsUs())
    }
}
