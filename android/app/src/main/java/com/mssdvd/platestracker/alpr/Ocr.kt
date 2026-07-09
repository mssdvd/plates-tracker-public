package com.mssdvd.platestracker.alpr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * cct-s-v2-global plate OCR — direct port of onnx_reference.py `ocr`.
 *
 * input  "input"  uint8  [1,64,128,3] NHWC RGB, RAW 0-255 (NO normalization)
 * output "plate"  float32 [1,10,37] = 10 slots x 37 classes
 * output "region" float32 [1,66] = country classification head (2026-07-09: now fetched)
 *
 * Preprocess: clamp+crop the detection box, BGR2RGB (Bitmap pixels already are RGB once the alpha
 * byte is dropped), resize to 128x64 (plain, not letterboxed).
 * Decode: argmax per slot over the alphabet, strip trailing pad, confidence = mean of per-slot max;
 * country = argmax over the region head, distrusted below [OcrDecoder.MIN_TRUSTED_REGION_LENGTH]
 * chars of decoded text ([OcrDecoder.decodeRegion]).
 */
class Ocr(private val env: OrtEnvironment, modelBytes: ByteArray) {

    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())

    /** OCR one detection box; text + mean + per-char confidence. Empty crop -> empty read. */
    fun read(bitmap: Bitmap, box: PlateBox): OcrDecoder.Decoded {
        val x1 = box.x1.coerceIn(0, bitmap.width)
        val y1 = box.y1.coerceIn(0, bitmap.height)
        val x2 = box.x2.coerceIn(0, bitmap.width)
        val y2 = box.y2.coerceIn(0, bitmap.height)
        val cw = x2 - x1
        val ch = y2 - y1
        if (cw <= 0 || ch <= 0) return OcrDecoder.Decoded("", 0f, FloatArray(0))

        val crop = Bitmap.createBitmap(bitmap, x1, y1, cw, ch)
        val resized = Bitmap.createScaledBitmap(crop, W, H, true) // bilinear, matches cv2.INTER_LINEAR
        if (resized !== crop) crop.recycle() // may return src when crop is already W x H

        val px = IntArray(W * H)
        resized.getPixels(px, 0, W, 0, 0, W, H)
        resized.recycle()
        val bytes = ByteArray(W * H * 3)
        for (i in px.indices) {
            val p = px[i]
            bytes[i * 3] = ((p ushr 16) and 0xFF).toByte() // R
            bytes[i * 3 + 1] = ((p ushr 8) and 0xFF).toByte() // G
            bytes[i * 3 + 2] = (p and 0xFF).toByte() // B
        }

        OnnxTensor.createTensor(
            env, ByteBuffer.wrap(bytes), longArrayOf(1, H.toLong(), W.toLong(), 3), OnnxJavaType.UINT8
        ).use { input ->
            session.run(mapOf("input" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val slots = (result.get("plate").get().value as Array<Array<FloatArray>>)[0] // [10][37]
                val flat = FloatArray(OcrDecoder.SLOTS * OcrDecoder.VOCAB)
                for (s in slots.indices) slots[s].copyInto(flat, s * OcrDecoder.VOCAB)
                @Suppress("UNCHECKED_CAST")
                val regionFlat = (result.get("region").get().value as Array<FloatArray>)[0] // [66]
                val decoded = OcrDecoder.decode(flat)
                return decoded.copy(country = OcrDecoder.decodeRegion(decoded.text, regionFlat))
            }
        }
    }

    fun close() = session.close()

    companion object {
        const val W = 128
        const val H = 64
    }
}
