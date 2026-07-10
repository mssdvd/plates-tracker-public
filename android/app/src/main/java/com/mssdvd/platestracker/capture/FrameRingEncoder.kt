package com.mssdvd.platestracker.capture

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.camera.core.ImageProxy
import com.mssdvd.platestracker.AppLog

/**
 * Hardware video encoder feeding the RAM [AuRing] (capture v2). [feed] is called on a ~15fps clock
 * (CaptureService.kt's `RING_FEED_INTERVAL_MS`, time-based since the native delivered frame rate
 * varies by device) matching what BurstProcessor already downsamples ring footage to before it
 * ever reads it, so this doesn't cost burst any information. Each call copies into a codec input
 * Image (a ~12 MB YUV memcpy, a few ms — cheap enough for the analyzer callback) and comes back out
 * as a ~100 KB compressed access unit, which is what makes a multi-second 4K buffer fit in RAM at
 * all. Keyframe interval is 1 s so burst snapshots never decode more than ~1 s of lead-in.
 *
 * Encoder trouble (unsupported size, codec died) permanently disables the ring for the run —
 * capture degrades to live-scan-only rather than failing.
 */
class FrameRingEncoder(private val ring: AuRing) {

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var failed = false
    private var released = false

    /** Output format captured at the first drain; carries csd, the decoder configures from it. */
    @Volatile
    var outputFormat: MediaFormat? = null
        private set

    /**
     * Copy one camera frame into the encoder and drain whatever it has finished compressing.
     * Synchronized against [release]: freeing the codec mid-copy invalidates the input Image's
     * backing memory under the memcpy (native SIGSEGV, 2026-07-05 field crash).
     */
    @Synchronized
    fun feed(proxy: ImageProxy) {
        if (failed || released) return
        try {
            val c = codec ?: start(proxy.width, proxy.height) ?: return
            val index = c.dequeueInputBuffer(0)
            if (index >= 0) {
                val dst = c.getInputImage(index)
                if (dst == null) {
                    c.queueInputBuffer(index, 0, 0, 0, 0)
                } else {
                    copyYuv(proxy, dst)
                    c.queueInputBuffer(
                        index, 0, proxy.width * proxy.height * 3 / 2,
                        proxy.imageInfo.timestamp / 1_000, 0,
                    )
                }
            } // else: encoder busy — drop the frame, the ring tolerates gaps
            drain(c)
        } catch (t: Throwable) {
            AppLog.e(TAG, "encoder failed — ring disabled for this run", t)
            failed = true
            release()
        }
    }

    /** Terminal: blocks until any in-flight [feed] finishes, then no later frame can restart. */
    @Synchronized
    fun release() {
        released = true
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun start(width: Int, height: Int): MediaCodec? {
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            try {
                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    // Matches the actual ~15fps feed() cadence (CaptureService.kt thins the
                    // native ~30fps input) so the encoder's bits-per-frame budgeting is accurate.
                    setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val c = MediaCodec.createEncoderByType(mime)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                codec = c
                AppLog.i(TAG, "ring encoder $mime ${width}x$height @ ${BIT_RATE / 1_000_000} Mbps")
                return c
            } catch (t: Throwable) {
                AppLog.w(TAG, "encoder $mime unavailable at ${width}x$height", t)
            }
        }
        failed = true
        return null
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            when (val index = c.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = c.outputFormat
                in Int.MIN_VALUE..-1 -> return
                else -> {
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        val buf = c.getOutputBuffer(index)!!
                        val data = ByteArray(bufferInfo.size)
                        buf.position(bufferInfo.offset)
                        buf.get(data)
                        ring.add(
                            AuRing.Au(
                                bufferInfo.presentationTimeUs,
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                                data,
                            )
                        )
                    }
                    c.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /** Plane-wise YUV copy honoring row/pixel strides on both sides; bulk rows when layouts agree. */
    private fun copyYuv(src: ImageProxy, dst: Image) {
        for (i in 0 until 3) {
            val s = src.planes[i]
            val d = dst.planes[i]
            val w = if (i == 0) src.width else src.width / 2
            val h = if (i == 0) src.height else src.height / 2
            val sBuf = s.buffer
            val dBuf = d.buffer
            if (s.pixelStride == d.pixelStride) {
                val len = (w - 1) * s.pixelStride + 1
                val row = ByteArray(len)
                for (y in 0 until h) {
                    sBuf.position(y * s.rowStride)
                    sBuf.get(row, 0, len)
                    dBuf.position(y * d.rowStride)
                    dBuf.put(row, 0, len)
                }
            } else {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        dBuf.put(
                            y * d.rowStride + x * d.pixelStride,
                            sBuf.get(y * s.rowStride + x * s.pixelStride),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "FrameRingEncoder"
        private const val BIT_RATE = 20_000_000
    }
}
