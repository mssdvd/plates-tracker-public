package com.mssdvd.platestracker.alpr

import kotlin.math.roundToInt

/**
 * Pure detector post-processing — maps raw model rows back to original-frame boxes. No Android deps,
 * so it is JVM-unit-testable against onnx_reference.py. Row layout: [batch, x1, y1, x2, y2, class,
 * score] in letterboxed-384 space; un-letterbox with (coord - pad) / ratio. Score is column 6.
 */
object BoxMapper {
    const val CONF = 0.4f // fast_alpr ALPR default detector_conf_thresh

    fun mapBoxes(rows: Array<FloatArray>, r: Float, dw: Float, dh: Float, conf: Float = CONF): List<PlateBox> {
        val out = ArrayList<PlateBox>(rows.size)
        for (row in rows) {
            val score = row[6]
            if (score < conf) continue
            val x1 = ((row[1] - dw) / r).roundToInt()
            val y1 = ((row[2] - dh) / r).roundToInt()
            val x2 = ((row[3] - dw) / r).roundToInt()
            val y2 = ((row[4] - dh) / r).roundToInt()
            out.add(PlateBox(x1, y1, x2, y2, score))
        }
        return out
    }
}
