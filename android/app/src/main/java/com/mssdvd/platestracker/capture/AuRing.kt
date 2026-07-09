package com.mssdvd.platestracker.capture

/**
 * In-RAM ring of encoded video access units (capture v2). The encoder appends ~30 fps of 4K HEVC
 * (~2.5 MB/s at 20 Mbps, so the default budget holds ~6-7 s); the burst thread snapshots a time
 * window for decoding. Snapshots always start at a keyframe — P-frames are undecodable without
 * their reference chain — so the encoder keeps the keyframe interval short (1 s).
 *
 * Pure Kotlin, no Android deps: eviction and snapshot bounds are JVM-tested.
 */
class AuRing(private val capacityBytes: Int) {

    class Au(val ptsUs: Long, val keyFrame: Boolean, val data: ByteArray)

    private val aus = ArrayDeque<Au>()
    private var bytes = 0

    @Synchronized
    fun add(au: Au) {
        aus.addLast(au)
        bytes += au.data.size
        while (bytes > capacityBytes && aus.size > 1) {
            bytes -= aus.removeFirst().data.size
        }
    }

    /** Newest buffered pts, or Long.MIN_VALUE when empty — the burst thread waits on this. */
    @Synchronized
    fun latestPtsUs(): Long = aus.lastOrNull()?.ptsUs ?: Long.MIN_VALUE

    /**
     * The decodable clip covering [fromUs, toUs]: starts at the last keyframe at-or-before
     * [fromUs] (or the ring's first keyframe when eviction already ate that far back) and ends at
     * the last AU at-or-before [toUs]. Empty when the ring holds no keyframe yet.
     */
    @Synchronized
    fun snapshot(fromUs: Long, toUs: Long): List<Au> {
        var start = -1
        var firstKey = -1
        for (i in aus.indices) {
            val au = aus[i]
            if (!au.keyFrame) continue
            if (firstKey == -1) firstKey = i
            if (au.ptsUs <= fromUs) start = i else break // pts is monotonic
        }
        if (start == -1) start = firstKey
        if (start == -1) return emptyList()
        val out = ArrayList<Au>()
        for (i in start until aus.size) {
            val au = aus[i]
            if (au.ptsUs > toUs) break
            out.add(au)
        }
        return out
    }

    @Synchronized
    fun clear() {
        aus.clear()
        bytes = 0
    }
}
