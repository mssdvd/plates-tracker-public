package com.mssdvd.platestracker

import com.mssdvd.platestracker.capture.BurstPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstPlannerTest {

    @Test
    fun hitQueuesWindowWithPreAndPostRoll() {
        val planner = BurstPlanner()
        assertTrue(planner.onHit(10_000_000))
        val w = planner.next()!!
        assertEquals(9_200_000L, w.t0Us)  // -0.8 s pre-roll
        assertEquals(10_700_000L, w.t1Us) // +0.7 s post-roll
        assertNull(planner.next())
    }

    @Test
    fun hitsWithinMinGapAreDropped() {
        val planner = BurstPlanner()
        assertTrue(planner.onHit(10_000_000))
        // A car sitting far ahead re-triggers every scan; the gap keeps that to ~1 burst/1.2 s.
        assertFalse(planner.onHit(10_500_000))
        assertFalse(planner.onHit(11_000_000))
        assertEquals(1, planner.pending())
    }

    @Test
    fun overlappingWindowsMergeIntoOne() {
        val planner = BurstPlanner()
        assertTrue(planner.onHit(10_000_000))
        // 1.3 s later: past the rate limit, but the windows overlap -> extend, don't duplicate.
        assertTrue(planner.onHit(11_300_000))
        assertEquals(1, planner.pending())
        val w = planner.next()!!
        assertEquals(9_200_000L, w.t0Us)
        assertEquals(12_000_000L, w.t1Us)
    }

    @Test
    fun distantHitsComeOutFifo() {
        val planner = BurstPlanner()
        assertTrue(planner.onHit(10_000_000))
        assertTrue(planner.onHit(20_000_000))
        assertEquals(2, planner.pending())
        assertEquals(9_200_000L, planner.next()!!.t0Us)
        assertEquals(19_200_000L, planner.next()!!.t0Us)
    }
}
