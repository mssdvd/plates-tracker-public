package com.mssdvd.platestracker.capture

import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import com.mssdvd.platestracker.AppLog

/**
 * One-shot synchronous decode of a ring snapshot (capture v2). Configured straight from the
 * encoder's output format (which carries the codec-specific data), no surface — outputs come back
 * as flexible-YUV Images. Every AU is fed (P-frames need their reference chain) but only frames
 * whose pts is in [selectedPtsUs] reach [onFrame]; the Image is only valid inside the callback.
 */
object BurstDecoder {

    fun decode(
        format: MediaFormat,
        aus: List<AuRing.Au>,
        selectedPtsUs: Set<Long>,
        onFrame: (Image, Long) -> Unit,
    ) {
        if (aus.isEmpty() || selectedPtsUs.isEmpty()) return
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var eosQueued = false
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!eosQueued) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        if (fed < aus.size) {
                            val au = aus[fed++]
                            codec.getInputBuffer(inIndex)!!.put(au.data)
                            codec.queueInputBuffer(inIndex, 0, au.data.size, au.ptsUs, 0)
                        } else {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosQueued = true
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && info.presentationTimeUs in selectedPtsUs) {
                    codec.getOutputImage(outIndex)?.let { onFrame(it, info.presentationTimeUs) }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (eos) return
            }
            AppLog.w(TAG, "decode timed out after $TIMEOUT_MS ms (${aus.size} AUs, fed $fed)")
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private const val TAG = "BurstDecoder"
    private const val TIMEOUT_MS = 4_000L
}
