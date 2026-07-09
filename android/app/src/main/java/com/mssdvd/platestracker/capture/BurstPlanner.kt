package com.mssdvd.platestracker.capture

import kotlin.math.max

/**
 * Decides which ring windows deserve a burst decode (capture v2). A far-plate hit at pts t asks
 * for [t - preRoll, t + postRoll] — the pre-roll covers frames where the plate was already
 * resolvable but the throttled live scan never looked. Hits closer than [minGapUs] to the last
 * accepted one are dropped (a car sitting far ahead would otherwise re-trigger every scan), and
 * overlapping windows merge so one pass never decodes the same second twice.
 *
 * Pure Kotlin, JVM-tested; the burst executor drains [next].
 */
class BurstPlanner(
    private val preRollUs: Long = 800_000L,
    private val postRollUs: Long = 700_000L,
    private val minGapUs: Long = 1_200_000L,
) {

    class Window(val t0Us: Long, var t1Us: Long)

    private val queue = ArrayDeque<Window>()
    private var lastHitUs = Long.MIN_VALUE / 2

    /** Register a far-plate hit; true when a window was queued or extended. */
    @Synchronized
    fun onHit(ptsUs: Long): Boolean {
        if (ptsUs - lastHitUs < minGapUs) return false
        lastHitUs = ptsUs
        val t0 = ptsUs - preRollUs
        val t1 = ptsUs + postRollUs
        val last = queue.lastOrNull()
        if (last != null && t0 <= last.t1Us) {
            last.t1Us = max(last.t1Us, t1)
        } else {
            queue.addLast(Window(t0, t1))
        }
        return true
    }

    @Synchronized
    fun next(): Window? = queue.removeFirstOrNull()

    @Synchronized
    fun pending(): Int = queue.size

    @Synchronized
    fun clear() = queue.clear()
}
