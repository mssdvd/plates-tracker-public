package com.mssdvd.platestracker

import com.mssdvd.platestracker.alpr.BoxMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Asserts the pure Kotlin detector post-processing reproduces the Python reference box mapping.
 * Fixture det_rows.txt holds the raw [N,7] model output + letterbox (r, dw, dh) and the expected
 * un-letterboxed box, from models/dump_test_vectors.py.
 */
class BoxMapperTest {

    @Test
    fun mapsReferenceRowsToExpectedBox() {
        val lines = javaClass.getResourceAsStream("/det_rows.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        val (r, dw, dh) = lines[0].trim().split(" ").map { it.toFloat() }
        val exp = lines[1].trim().split(" ").map { it.toInt() } // x1 y1 x2 y2
        val rows = lines.drop(2)
            .map { line -> line.trim().split(" ").map { it.toFloat() }.toFloatArray() }
            .toTypedArray()

        val boxes = BoxMapper.mapBoxes(rows, r, dw, dh)
        assertTrue("expected at least one box", boxes.isNotEmpty())
        val best = boxes.maxByOrNull { it.score }!!
        // Allow ±1px for float-rounding differences across platforms.
        assertTrue("x1 ${best.x1} vs ${exp[0]}", abs(best.x1 - exp[0]) <= 1)
        assertTrue("y1 ${best.y1} vs ${exp[1]}", abs(best.y1 - exp[1]) <= 1)
        assertTrue("x2 ${best.x2} vs ${exp[2]}", abs(best.x2 - exp[2]) <= 1)
        assertTrue("y2 ${best.y2} vs ${exp[3]}", abs(best.y2 - exp[3]) <= 1)
    }

    @Test
    fun dropsRowsBelowConfidence() {
        // col6 = score; one above, one below the 0.4 default.
        val rows = arrayOf(
            floatArrayOf(0f, 10f, 20f, 30f, 40f, 0f, 0.9f),
            floatArrayOf(0f, 10f, 20f, 30f, 40f, 0f, 0.1f),
        )
        val boxes = BoxMapper.mapBoxes(rows, r = 1f, dw = 0f, dh = 0f)
        assertEquals(1, boxes.size)
        assertEquals(0.9f, boxes[0].score, 1e-6f)
    }
}
