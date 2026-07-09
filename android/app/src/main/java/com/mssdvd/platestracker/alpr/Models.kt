package com.mssdvd.platestracker.alpr

/** One detected plate box in analysis-frame pixel coordinates, plus detector score. */
data class PlateBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val score: Float)

/**
 * A full read: detector box + OCR text/confidence/country, straight off the model.
 *
 * 2026-07-09: format-regex validation/correction is no longer a gate here (see PlateValidator's
 * class doc and docs/model-specs.md) — [text] is the raw OCR output verbatim and [country] comes
 * from the OCR model's own region head ([OcrDecoder.decodeRegion]), not format matching.
 * [readKind] is always "exact" now (no more corrected/generic/invalid variants); kept only because
 * it's still a wire field ([com.mssdvd.platestracker.data.Sighting.readKind]).
 */
data class PlateRead(
    val box: PlateBox,
    val text: String,
    val ocrConfidence: Float,
    val readKind: String,
    val country: String, // ISO-2 from the OCR model's region head; "?" when it says "Unknown"
    // Local-only "pre-correction" text for the history screen (Sighting.rawText); always equals
    // [text] now that there's no correction step, but the field stays so that screen keeps working.
    val rawText: String = text,
)
