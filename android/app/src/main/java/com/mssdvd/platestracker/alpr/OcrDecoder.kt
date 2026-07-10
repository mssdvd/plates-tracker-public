package com.mssdvd.platestracker.alpr

/**
 * Pure OCR output decoder — no Android/ORT deps, so it is JVM-unit-testable against the Python
 * reference (onnx_reference.py). Decodes the [370] model output = 10 slots x 37 classes.
 */
object OcrDecoder {
    const val SLOTS = 10
    const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_"
    const val PAD = '_'
    val VOCAB = ALPHABET.length // 37

    /**
     * "region" output ([1,66]) label order — from the model's own cct_s_v2_global_plate_config.yaml
     * (plate_regions:), NOT alphabetical. Copied verbatim from
     * ~/.cache/fast-plate-ocr/cct-s-v2-global-model/cct_s_v2_global_plate_config.yaml; keep in sync
     * with models/onnx_reference.py's REGIONS — do not reorder.
     */
    val REGIONS = listOf(
        "Albania", "Andorra", "Argentina", "Armenia", "Australia", "Austria", "Azerbaijan", "Bahrain",
        "Belarus", "Belgium", "Bosnia and Herzegovina", "Brazil", "Bulgaria", "Cambodia", "Canada",
        "Croatia", "Cyprus", "Czech Republic", "Denmark", "Estonia", "Finland", "France", "Georgia",
        "Germany", "Gibraltar", "Greece", "Guernsey", "Hungary", "Iceland", "Indonesia", "Ireland",
        "Israel", "Italy", "Latvia", "Liechtenstein", "Lithuania", "Luxembourg", "Malaysia", "Malta",
        "Mexico", "Moldova", "Monaco", "Montenegro", "Netherlands", "New Zealand", "North Macedonia",
        "Norway", "Poland", "Portugal", "Qatar", "Romania", "San Marino", "Serbia", "Singapore",
        "Slovakia", "Slovenia", "Spain", "Sweden", "Switzerland", "Thailand", "Turkey",
        "United States", "Ukraine", "United Kingdom", "Vietnam", "Unknown",
    )

    // REGIONS' country names -> the ISO-2 codes the wire contract/webapp use (FLAGS map keys on
    // these). "Unknown" is deliberately absent so it falls through to "?", the app's existing
    // unknown-country sentinel.
    private val REGION_TO_ISO2 = mapOf(
        "Albania" to "AL", "Andorra" to "AD", "Argentina" to "AR", "Armenia" to "AM",
        "Australia" to "AU", "Austria" to "AT", "Azerbaijan" to "AZ", "Bahrain" to "BH",
        "Belarus" to "BY", "Belgium" to "BE", "Bosnia and Herzegovina" to "BA", "Brazil" to "BR",
        "Bulgaria" to "BG", "Cambodia" to "KH", "Canada" to "CA", "Croatia" to "HR",
        "Cyprus" to "CY", "Czech Republic" to "CZ", "Denmark" to "DK", "Estonia" to "EE",
        "Finland" to "FI", "France" to "FR", "Georgia" to "GE", "Germany" to "DE",
        "Gibraltar" to "GI", "Greece" to "GR", "Guernsey" to "GG", "Hungary" to "HU",
        "Iceland" to "IS", "Indonesia" to "ID", "Ireland" to "IE", "Israel" to "IL",
        "Italy" to "IT", "Latvia" to "LV", "Liechtenstein" to "LI", "Lithuania" to "LT",
        "Luxembourg" to "LU", "Malaysia" to "MY", "Malta" to "MT", "Mexico" to "MX",
        "Moldova" to "MD", "Monaco" to "MC", "Montenegro" to "ME", "Netherlands" to "NL",
        "New Zealand" to "NZ", "North Macedonia" to "MK", "Norway" to "NO", "Poland" to "PL",
        "Portugal" to "PT", "Qatar" to "QA", "Romania" to "RO", "San Marino" to "SM",
        "Serbia" to "RS", "Singapore" to "SG", "Slovakia" to "SK", "Slovenia" to "SI",
        "Spain" to "ES", "Sweden" to "SE", "Switzerland" to "CH", "Thailand" to "TH",
        "Turkey" to "TR", "United States" to "US", "Ukraine" to "UA", "United Kingdom" to "GB",
        "Vietnam" to "VN",
    )

    /** argmax over the region head -> ISO-2 country code; "Unknown" (or an unmapped label) -> "?". */
    fun decodeRegion(flat: FloatArray): String {
        var bestIdx = 0
        var bestVal = flat[0]
        for (i in 1 until REGIONS.size) {
            if (flat[i] > bestVal) {
                bestVal = flat[i]
                bestIdx = i
            }
        }
        return REGION_TO_ISO2[REGIONS[bestIdx]] ?: "?"
    }

    // 2026-07-09 field data: a truncated crop's plate text still argmaxes the region head to a
    // confident (wrong) country — 29/32 foreign-labeled false positives were <7 chars, the IT car
    // and moto minimum. Below this length the region head isn't trusted at all.
    const val MIN_TRUSTED_REGION_LENGTH = 7

    /**
     * [decodeRegion], distrusted below [MIN_TRUSTED_REGION_LENGTH] chars of decoded [text]. At or
     * above that length, an EXACT structural match (see [PlateValidator]) overrides a disagreeing
     * region head — 2026-07-10 field data: the head has now misfired on 3 structurally exact reads
     * and never once been right when structure disagreed, so at exactly 7 chars structure is the
     * stronger signal (device-dumps/2026-07-10_203742/REPORT.md).
     */
    fun decodeRegion(text: String, flat: FloatArray): String {
        if (text.length < MIN_TRUSTED_REGION_LENGTH) return "?"
        val structural = PlateValidator.validate(text)
        if (structural.confidence == PlateValidator.Confidence.EXACT && structural.country != "?") {
            return structural.country
        }
        return decodeRegion(flat)
    }

    /**
     * Decoded OCR output. [charConfidences] is the per-slot max softmax aligned char-for-char with
     * [text] (same length). [confidence] is their mean (the whole-read score). [country] is the
     * region head's ISO-2 guess ([decodeRegion]); [decode] itself only sees the "plate" output, so
     * it defaults to "?" — [Ocr.read] fills it in from the sibling "region" output of the same pass.
     * Destructures as `(text, confidence)` for callers that don't need the per-char detail.
     */
    data class Decoded(
        val text: String,
        val confidence: Float,
        val charConfidences: FloatArray,
        val country: String = "?",
    ) {
        // Value semantics for the array field (data class would compare it by identity).
        override fun equals(other: Any?): Boolean =
            other is Decoded && text == other.text && confidence == other.confidence &&
                charConfidences.contentEquals(other.charConfidences) && country == other.country

        override fun hashCode(): Int =
            ((text.hashCode() * 31 + confidence.hashCode()) * 31 + charConfidences.contentHashCode()) *
                31 + country.hashCode()
    }

    /** argmax per slot -> alphabet char + its softmax; strip trailing pad; mean = read confidence. */
    fun decode(flat: FloatArray): Decoded {
        val chars = CharArray(SLOTS)
        val confs = FloatArray(SLOTS)
        var confSum = 0f
        for (s in 0 until SLOTS) {
            val base = s * VOCAB
            var bestIdx = 0
            var bestVal = flat[base]
            for (c in 1 until VOCAB) {
                val v = flat[base + c]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = c
                }
            }
            chars[s] = ALPHABET[bestIdx]
            confs[s] = bestVal
            confSum += bestVal
        }
        // Trim trailing pads off both the text and its aligned confidences.
        var end = SLOTS
        while (end > 0 && chars[end - 1] == PAD) end--
        return Decoded(
            String(chars, 0, end),
            confSum / SLOTS,
            confs.copyOfRange(0, end),
        )
    }
}
