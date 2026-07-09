package com.mssdvd.platestracker.capture

import android.graphics.Rect
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.mssdvd.platestracker.alpr.Alpr
import com.mssdvd.platestracker.alpr.Detector
import com.mssdvd.platestracker.alpr.PlateBox
import com.mssdvd.platestracker.alpr.PlateRead
import com.mssdvd.platestracker.alpr.YuvConvert
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Re-scans ring footage around far-plate hits (capture v2). The throttled live scan sees a small
 * detector box it can rarely OCR before an oncoming car is gone; this thread decodes the
 * surrounding [BurstPlanner] window from the RAM ring — ~30 fps of 4K where the live scan got 2-8
 * — and pushes every read through the shared dedup, so a 0.3 s pass still collects the agreeing
 * frames a promotion needs. The budget is seconds, not milliseconds: the promotion lands while
 * the driver can still check the plate against the car.
 *
 * Each selected frame gets the same center-zoom detection as the live pass (converted subsampled,
 * so the 4K frame never materializes as a full bitmap), then full-resolution OCR on each box.
 */
class BurstProcessor(
    private val ring: AuRing,
    private val encoder: FrameRingEncoder,
    private val alpr: () -> Alpr?,
    private val onRead: (PlateRead, Long) -> Unit, // (read, camera pts of its frame, µs)
) {

    private val planner = BurstPlanner()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "burst") }

    @Volatile
    private var closed = false

    val pending: Int get() = planner.pending()

    /** Live scan saw a far plate at camera pts [ptsUs]: queue its window for re-scanning. */
    fun onFarHit(ptsUs: Long) {
        if (closed) return
        if (planner.onHit(ptsUs)) {
            executor.execute {
                try {
                    runNext()
                } catch (t: Throwable) {
                    Log.e(TAG, "burst failed", t)
                }
            }
        }
    }

    /** Stop accepting work and wait (briefly) for an in-flight burst — Alpr is closed after us. */
    fun close() {
        closed = true
        planner.clear()
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun runNext() {
        val window = planner.next() ?: return // merged into a window another execute() ran
        val engine = alpr() ?: return
        val format = encoder.outputFormat ?: return
        // The encoder runs a couple of frames behind the camera: wait (bounded) for the tail.
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (ring.latestPtsUs() < window.t1Us && !closed && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        if (closed) return
        val aus = ring.snapshot(window.t0Us, window.t1Us)
        // Thin the window to ~15 fps — consecutive 33 ms frames vote on nearly the same pixels.
        val selected = HashSet<Long>()
        var last = Long.MIN_VALUE / 2
        for (au in aus) {
            if (au.ptsUs < window.t0Us || selected.size >= MAX_FRAMES) continue
            if (au.ptsUs - last >= STRIDE_US) {
                selected.add(au.ptsUs)
                last = au.ptsUs
            }
        }
        if (selected.isEmpty()) return
        var frames = 0
        val t0 = SystemClock.elapsedRealtime()
        BurstDecoder.decode(format, aus, selected) { image, ptsUs ->
            if (!closed) {
                frames++
                scanFrame(engine, image, ptsUs)
            }
        }
        Log.i(TAG, "burst: ${aus.size} AUs -> $frames frames in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private fun scanFrame(engine: Alpr, image: Image, ptsUs: Long) {
        // Same zoom-pass geometry as CaptureService.centerZoom, subsampled straight to ~384 px.
        val side = minOf(image.height, image.width * 2 / 5)
        if (side < Detector.SIZE) return
        val sub = maxOf(1, side / Detector.SIZE)
        val left = (image.width - side) / 2
        val top = (image.height - side) / 2
        val small = YuvConvert.toBitmap(image, Rect(left, top, left + side, top + side), sub)
        val boxes = engine.detect(small)
        small.recycle()
        for (b in boxes) {
            // Back to full-res frame coordinates, padded one subsample step against quantization.
            val x1 = (left + b.x1 * sub - sub).coerceAtLeast(0)
            val y1 = (top + b.y1 * sub - sub).coerceAtLeast(0)
            val x2 = (left + b.x2 * sub + sub).coerceAtMost(image.width)
            val y2 = (top + b.y2 * sub + sub).coerceAtMost(image.height)
            if (x2 - x1 < 8 || y2 - y1 < 4) continue
            val crop = YuvConvert.toBitmap(image, Rect(x1, y1, x2, y2), 1)
            val read = engine.read(crop, PlateBox(0, 0, crop.width, crop.height, b.score))
            crop.recycle()
            if (read.text.isNotEmpty()) {
                onRead(read.copy(box = PlateBox(x1, y1, x2, y2, b.score)), ptsUs)
            }
        }
    }

    companion object {
        private const val TAG = "BurstProcessor"
        private const val STRIDE_US = 66_000L // ~15 fps through the window
        private const val MAX_FRAMES = 24
        private const val WAIT_MS = 3_000L
    }
}
