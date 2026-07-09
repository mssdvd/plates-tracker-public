package com.mssdvd.platestracker.alpr

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * ALPR pipeline: detect plates -> OCR each crop. Mirrors fast_alpr.ALPR.predict and
 * onnx_reference.py. This is the one seam the rest of the app talks to.
 *
 * 2026-07-09: format-regex validation (`PlateValidator.validate`) is no longer part of this
 * pipeline — every OCR read is passed through as-is; `country` comes from the OCR model's own
 * region head instead of the matched format. See PlateValidator's class doc and
 * docs/model-specs.md for why. `PlateValidator` itself is still used elsewhere (e.g.
 * `estimateRegistrationYear` in CaptureService) — only the gating call here is gone.
 */
class Alpr(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detector = Detector(env, context.assets.open("detector.onnx").use { it.readBytes() })
    private val ocr = Ocr(env, context.assets.open("ocr.onnx").use { it.readBytes() })

    /**
     * [roi] restricts *detection* to that region of the frame at the detector's full 384-px input
     * (a zoom pass: small far-away plates survive the downscale that a full-frame letterbox would
     * lose them in). Boxes come back in full-frame coordinates and OCR always crops from the
     * full-resolution [bitmap], so callers downstream never see the difference.
     */
    fun analyze(bitmap: Bitmap, roi: Rect? = null): List<PlateRead> {
        val src = if (roi == null) bitmap
        else Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width(), roi.height())
        val boxes = detector.detect(src).map { b ->
            if (roi == null) b
            else b.copy(x1 = b.x1 + roi.left, y1 = b.y1 + roi.top, x2 = b.x2 + roi.left, y2 = b.y2 + roi.top)
        }
        if (src !== bitmap) src.recycle()
        return boxes.map { read(bitmap, it) }
    }

    /** Detection only — the burst path runs it on an already-zoomed, subsampled bitmap. */
    fun detect(bitmap: Bitmap): List<PlateBox> = detector.detect(bitmap)

    /** OCR one [box] (in [bitmap] coordinates); text/country come straight from the model. */
    fun read(bitmap: Bitmap, box: PlateBox): PlateRead {
        val ocrRead = ocr.read(bitmap, box)
        return PlateRead(
            box, ocrRead.text, ocrRead.confidence,
            readKind = "exact",
            country = ocrRead.country,
        )
    }

    fun close() {
        detector.close()
        ocr.close()
        env.close()
    }
}
