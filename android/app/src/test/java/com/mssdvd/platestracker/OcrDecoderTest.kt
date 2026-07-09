package com.mssdvd.platestracker

import com.mssdvd.platestracker.alpr.OcrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the pure Kotlin OCR decoder reproduces the Python reference exactly. The fixture
 * (ocr_vector.txt) is the raw [370] model output for the control image (a plate with no bearing
 * on any real vehicle), dumped by models/dump_test_vectors.py, where onnx_reference.py decoded it
 * to "CN555PL" @ 1.000.
 */
class OcrDecoderTest {

    @Test
    fun decodesReferenceVectorToSamePlate() {
        val lines = javaClass.getResourceAsStream("/ocr_vector.txt")!!
            .bufferedReader().readLines()
        val expectedText = lines[0].trim()
        val vec = lines[1].trim().split(" ").map { it.toFloat() }.toFloatArray()
        assertEquals(370, vec.size)

        val (text, conf) = OcrDecoder.decode(vec)
        assertEquals("CN555PL", expectedText) // sanity: fixture is what we think
        assertEquals(expectedText, text)
        assertTrue("confidence $conf out of range", conf in 0.99f..1.00f)
    }

    @Test
    fun decodesRegionVectorToSameCountry() {
        // Fixture is the region-head [66] output for the same control image as ocr_vector.txt,
        // dumped by models/dump_test_vectors.py; onnx_reference.py decoded it to "Italy" (REGIONS[31]).
        val lines = javaClass.getResourceAsStream("/region_vector.txt")!!
            .bufferedReader().readLines()
        val expectedLabel = lines[0].trim()
        val vec = lines[1].trim().split(" ").map { it.toFloat() }.toFloatArray()
        assertEquals(66, vec.size)
        assertEquals("Italy", expectedLabel) // sanity: fixture is what we think

        assertEquals("IT", OcrDecoder.decodeRegion(vec))
    }

    @Test
    fun decodeRegionPicksArgmaxAndFallsBackToUnknown() {
        // "Unknown" (last index) isn't in REGION_TO_ISO2 -> falls through to "?".
        val vec = FloatArray(OcrDecoder.REGIONS.size)
        vec[OcrDecoder.REGIONS.size - 1] = 1f
        assertEquals("Unknown", OcrDecoder.REGIONS.last())
        assertEquals("?", OcrDecoder.decodeRegion(vec))

        val franceIdx = OcrDecoder.REGIONS.indexOf("France")
        vec.fill(0f)
        vec[franceIdx] = 1f
        assertEquals("FR", OcrDecoder.decodeRegion(vec))
    }

    @Test
    fun decodeRegionDistrustsShortText() {
        // Same region vector that decodes to "IT" via the raw argmax — but a <7-char plate text
        // (a fragment/crop, per the 2026-07-09 field data) must not be trusted with any country.
        val lines = javaClass.getResourceAsStream("/region_vector.txt")!!
            .bufferedReader().readLines()
        val vec = lines[1].trim().split(" ").map { it.toFloat() }.toFloatArray()

        assertEquals("?", OcrDecoder.decodeRegion("GE489", vec)) // 5 chars: below the gate
        assertEquals("IT", OcrDecoder.decodeRegion("GE489CZ", vec)) // 7 chars: at the gate, trusted
    }

    @Test
    fun stripsTrailingPadAndIndexesSlots() {
        // Build a synthetic [10,37] one-hot: slots spell "AB1" then pads. Catches stride/alphabet bugs.
        val vocab = OcrDecoder.VOCAB
        val flat = FloatArray(OcrDecoder.SLOTS * vocab)
        fun set(slot: Int, ch: Char) {
            flat[slot * vocab + OcrDecoder.ALPHABET.indexOf(ch)] = 1f
        }
        set(0, 'A'); set(1, 'B'); set(2, '1')
        for (s in 3 until OcrDecoder.SLOTS) set(s, '_')
        val (text, conf) = OcrDecoder.decode(flat)
        assertEquals("AB1", text)
        assertEquals(1.0f, conf, 1e-6f) // every slot's max is 1.0
    }
}
