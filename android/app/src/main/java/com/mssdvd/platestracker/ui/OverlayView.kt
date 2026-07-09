package com.mssdvd.platestracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.mssdvd.platestracker.alpr.PlateRead
import kotlin.math.max

/**
 * Draws detection boxes + OCR text over the camera preview. Maps analysis-frame pixel coordinates
 * to view pixels assuming the preview is FILL_CENTER (PreviewView default), so boxes line up with
 * what's on screen.
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var reads: List<PlateRead> = emptyList()
    private var frameW = 1
    private var frameH = 1

    // 2026-07-09: no more format-validity verdict to color by (see PlateRead's class doc) — reuse
    // the two paints for confidence instead, at DedupEngine's instantConfidence bar (0.90), so the
    // overlay still distinguishes "would promote on this frame alone" from "needs more frames".
    private val validPaint = Paint().apply {
        color = Color.rgb(36, 255, 12); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val noisePaint = Paint().apply {
        color = Color.argb(180, 180, 180, 180); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 64f; isAntiAlias = true; isFakeBoldText = true
    }
    private val textBg = Paint().apply { color = Color.argb(160, 0, 0, 0) }

    fun setResults(results: List<PlateRead>, sourceW: Int, sourceH: Int) {
        reads = results
        frameW = sourceW.coerceAtLeast(1)
        frameH = sourceH.coerceAtLeast(1)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = max(width.toFloat() / frameW, height.toFloat() / frameH)
        val dx = (width - frameW * scale) / 2f
        val dy = (height - frameH * scale) / 2f

        for (r in reads) {
            val left = r.box.x1 * scale + dx
            val top = r.box.y1 * scale + dy
            val right = r.box.x2 * scale + dx
            val bottom = r.box.y2 * scale + dy
            canvas.drawRect(left, top, right, bottom, if (r.ocrConfidence >= 0.90f) validPaint else noisePaint)

            if (r.text.isNotEmpty()) {
                val label = "${r.text} ${"%.2f".format(r.ocrConfidence)}"
                val tw = textPaint.measureText(label)
                val fm = textPaint.fontMetrics
                val labelH = fm.descent - fm.ascent
                // Baseline above the box when there's room, otherwise below it.
                val ty = if (top - labelH - 12f > 0f) top - 12f - fm.descent else bottom + 12f - fm.ascent
                canvas.drawRect(left, ty + fm.ascent - 6f, left + tw + 20f, ty + fm.descent + 6f, textBg)
                canvas.drawText(label, left + 10f, ty, textPaint)
            }
        }
    }
}
