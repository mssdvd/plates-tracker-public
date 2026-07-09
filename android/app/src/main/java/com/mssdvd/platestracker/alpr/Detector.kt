package com.mssdvd.platestracker.alpr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLO plate detector — direct port of onnx_reference.py `letterbox` + `detect`.
 *
 * input  "images"  float32 [1,3,384,384] NCHW, RGB, /255, letterboxed (aspect-preserving, pad 114)
 * output "output0" float32 [N,7] = [batch, x1, y1, x2, y2, class, score]   (score is col 6!)
 */
class Detector(private val env: OrtEnvironment, modelBytes: ByteArray) {

    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())

    /** Detect plates; returns boxes in [bitmap] pixel coordinates. */
    fun detect(bitmap: Bitmap): List<PlateBox> {
        val w = bitmap.width
        val h = bitmap.height
        val r = min(SIZE.toFloat() / h, SIZE.toFloat() / w)
        val nw = (w * r).roundToInt()
        val nh = (h * r).roundToInt()
        val dw = (SIZE - nw) / 2f
        val dh = (SIZE - nh) / 2f

        // Letterbox: scale keeping aspect, then center-pad to SIZE x SIZE with gray 114.
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val canvasBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(canvasBmp).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(scaled, (dw - 0.1f).roundToInt().toFloat(), (dh - 0.1f).roundToInt().toFloat(), null)
        }
        if (scaled !== bitmap) scaled.recycle() // createScaledBitmap returns src when dims already match

        // CHW, RGB, /255 into a flat NCHW buffer.
        val px = IntArray(SIZE * SIZE)
        canvasBmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        canvasBmp.recycle()
        val plane = SIZE * SIZE
        val arr = FloatArray(3 * plane)
        for (i in px.indices) {
            val p = px[i]
            arr[i] = ((p ushr 16) and 0xFF) / 255f          // R -> channel 0
            arr[plane + i] = ((p ushr 8) and 0xFF) / 255f    // G -> channel 1
            arr[2 * plane + i] = (p and 0xFF) / 255f          // B -> channel 2
        }

        OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
            .use { input ->
                session.run(mapOf("images" to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val rows = (result[0].value as Array<FloatArray>)
                    return BoxMapper.mapBoxes(rows, r, dw, dh)
                }
            }
    }

    fun close() = session.close()

    companion object {
        const val SIZE = 384
    }
}
