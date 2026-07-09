package com.mssdvd.platestracker.alpr

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image

/**
 * Flexible-YUV_420_888 [Image] -> ARGB [Bitmap] for burst frames decoded off the RAM ring
 * (capture v2). [roi] selects the source region and [subsample] keeps every n-th pixel, so the
 * detector's zoom pass converts a 1536-px square straight into its 384-px input without ever
 * materializing the 4K frame as a bitmap; OCR crops convert their small region at full res.
 */
object YuvConvert {

    fun toBitmap(image: Image, roi: Rect, subsample: Int): Bitmap {
        val w = maxOf(1, roi.width() / subsample)
        val h = maxOf(1, roi.height() / subsample)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val out = IntArray(w * h)
        var i = 0
        for (oy in 0 until h) {
            val sy = roi.top + oy * subsample
            val yRow = sy * yPlane.rowStride
            val uRow = (sy / 2) * uPlane.rowStride
            val vRow = (sy / 2) * vPlane.rowStride
            for (ox in 0 until w) {
                val sx = roi.left + ox * subsample
                val y = yBuf.get(yRow + sx * yPlane.pixelStride).toInt() and 0xFF
                val u = (uBuf.get(uRow + (sx / 2) * uPlane.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuf.get(vRow + (sx / 2) * vPlane.pixelStride).toInt() and 0xFF) - 128
                // BT.601 full range, fixed point (x1024).
                val r = y + ((1436 * v) shr 10)
                val g = y - ((352 * u + 731 * v) shr 10)
                val b = y + ((1815 * u) shr 10)
                out[i++] = (0xFF shl 24) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or
                    b.coerceIn(0, 255)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
