package com.mssdvd.platestracker.alpr

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Kotlin port of shared/plate_validation.py — keep the two in sync (canonical spec:
 * docs/plate-formats.md). Normalize, then exact match, then bounded position-aware OCR-confusion
 * correction, then optional loose EU fallback.
 *
 * Sequential systems (IT/FR/ES cars, NL sidecodes) carry an issue-date prior; district systems
 * (HR/UA/RO) are gated by their closed prefix-code sets. Format order resolves the text-level
 * ambiguities (see the spec's collision notes).
 *
 * 2026-07-06: the confusion-correction step is now **disabled by default**
 * ([validate]'s `enableCorrection = false`). Measured against real footage with a faithful port
 * of `DedupEngine`, it accounted for 0 of 24 real promoted sightings — exact reads always arrived
 * and always outrank a corrected one in the same dedup cluster, so it was dead weight for
 * promotion outcomes on the current (much more accurate) OCR model. Kept in the code (not
 * deleted) since the sample was only 3 clips; pass `enableCorrection = true` to re-enable while
 * investigating further. See docs/model-specs.md.
 *
 * 2026-07-10: back on the hot path, narrowly. [validate] is no longer only a reference /
 * [estimateRegistrationYear] helper — `DedupEngine`'s 1-frame `instant` promotion path now
 * requires `validate(text).confidence == Confidence.EXACT` before it will fire (see its class
 * doc). The 2026-07-09 field drive showed confidence alone can't carry a 1-frame decision (empty
 * reads at confidence ~=1.0, plate fragments read as confident short strings); structure is the
 * cheap corroborating signal a single frame doesn't otherwise have. The `steady`/`fast` paths
 * (>=2-3 corroborating frames) still don't call this — see docs/model-specs.md and
 * device-dumps/2026-07-09_184031/REPORT.md.
 */
object PlateValidator {

    // 22-letter Italian alphabet: A-Z without I, O, Q, U.
    const val ITALIAN_LETTERS = "ABCDEFGHJKLMNPRSTVWXYZ"
    // 23-letter French SIV alphabet: A-Z without I, O, U (Q allowed — a text-level FR marker).
    const val FRENCH_LETTERS = "ABCDEFGHJKLMNPQRSTVWXYZ"
    // 20-consonant Spanish alphabet: no vowels, no Ñ/Q.
    const val SPANISH_LETTERS = "BCDFGHJKLMNPRSTVWXYZ"
    // Ukrainian plates use only Cyrillic glyphs that look Latin.
    const val UKRAINIAN_LETTERS = "ABCEHIKMOPTX"

    private const val L_IT = "[$ITALIAN_LETTERS]"
    private const val L_FR = "[$FRENCH_LETTERS]"
    private const val L_ES = "[$SPANISH_LETTERS]"
    private const val L_UA = "[$UKRAINIAN_LETTERS]"
    private const val L_RO = "[A-PR-Z]" // Romania never uses Q
    private const val D = "[0-9]"

    // Closed prefix-code sets for the district-based systems (checked after a variant matches).
    private val HR_CITY_CODES = (
        "BJ BM CK DA DE DJ DU GS IM KA KC KR KT KZ MA NA NG OG OS PU PZ RI SB SI SK SL ST " +
            "VK VT VU VZ ZD ZG ZU"
        ).split(" ").toSet()
    private val UA_REGION_CODES = run {
        val first2004 =
            "AA AB AC AE AH AI AK AM AO AP AT AX BA BB BC BE BH BI BK BM BO BT BX CA CB CE CH"
                .split(" ")
        // The 2013 re-issue maps the first letter A→K, B→H, C→I.
        val remap = mapOf('A' to 'K', 'B' to 'H', 'C' to 'I')
        (first2004 + first2004.map { "${remap.getValue(it[0])}${it[1]}" }).toSet()
    }
    private val RO_COUNTY_CODES = (
        "AB AG AR BC BH BN BR BT BV BZ CJ CL CS CT CV DB DJ GJ GL GR HD HR IF IL IS MH MM MS " +
            "NT OT PH SB SJ SM SV TL TM TR VL VN VS"
        ).split(" ").toSet()

    /**
     * One recognizable plate format. [corrigible]=false keeps a format out of confusion-
     * correction: correction is only safe with a strong prior (alphabet, series, or code set),
     * and a format that is *just a layout* (NL) would let garbage be "corrected" into it.
     */
    private class PlateFormat(
        val name: String,
        val country: String,
        val variants: List<Pair<Regex, String>>, // (pattern on normalized text, slot layout)
        val prefixCodes: Set<String>? = null,    // leading letter pair must be in this set
        val corrigible: Boolean = true,
    ) {
        fun prefixOk(text: String, layout: String): Boolean {
            // The code gate applies to two-letter prefixes only (Bucharest's "B" is in-pattern).
            if (prefixCodes == null || !layout.startsWith("LL")) return true
            return text.take(2) in prefixCodes
        }
    }

    private fun v(pattern: String, layout: String) = Regex(pattern) to layout

    // Ordered by priority — earlier formats win ambiguous reads (see docs for collision notes).
    private val FORMATS = listOf(
        PlateFormat("it_car", "IT", listOf(v("^$L_IT$L_IT$D$D$D$L_IT$L_IT$", "LLDDDLL"))),
        PlateFormat("it_moto", "IT", listOf(v("^$L_IT$L_IT$D$D$D$D$D$", "LLDDDDD"))),
        PlateFormat("it_moped", "IT", listOf(v("^$D$D$L_IT$L_IT$L_IT$", "DDLLL"))),
        PlateFormat("fr_car", "FR", listOf(v("^$L_FR$L_FR$D$D$D$L_FR$L_FR$", "LLDDDLL"))),
        PlateFormat("es_car", "ES", listOf(v("^$D$D$D$D$L_ES$L_ES$L_ES$", "DDDDLLL"))),
        PlateFormat(
            "ua_car", "UA",
            listOf(v("^$L_UA$L_UA$D$D$D$D$L_UA$L_UA$", "LLDDDDLL")),
            prefixCodes = UA_REGION_CODES,
        ),
        PlateFormat(
            "hr_car", "HR",
            listOf(
                v("^[A-Z][A-Z]$D$D$D[A-Z][A-Z]$", "LLDDDLL"),
                v("^[A-Z][A-Z]$D$D$D$D[A-Z][A-Z]$", "LLDDDDLL"),
                v("^[A-Z][A-Z]$D$D$D[A-Z]$", "LLDDDL"),
                v("^[A-Z][A-Z]$D$D$D$D[A-Z]$", "LLDDDDL"),
            ),
            prefixCodes = HR_CITY_CODES,
        ),
        PlateFormat(
            "ro_car", "RO",
            listOf(
                v("^$L_RO$L_RO$D$D$L_RO$L_RO$L_RO$", "LLDDLLL"),
                v("^B$D$D$D$L_RO$L_RO$L_RO$", "LDDDLLL"), // Bucharest, 3 digits
                v("^B$D$D$L_RO$L_RO$L_RO$", "LDDLLL"),    // Bucharest, 2 digits
            ),
            prefixCodes = RO_COUNTY_CODES,
        ),
        // NL sidecodes: nationally sequential; the layout itself dates the plate (see eras below).
        PlateFormat(
            "nl_car", "NL",
            listOf(
                v("^[A-Z]{3}$D$D[A-Z]$", "LLLDDL"),      // SC11 2024–
                v("^[A-Z]$D$D$D[A-Z][A-Z]$", "LDDDLL"),  // SC10 2019–2024
                v("^[A-Z][A-Z]$D$D$D[A-Z]$", "LLDDDL"),  // SC9 2015–2019
                v("^$D[A-Z]{3}$D$D$", "DLLLDD"),         // SC8 2013–2015
                v("^$D$D[A-Z]{3}$D$", "DDLLLD"),         // SC7 2008–2013
                v("^$D$D[A-Z]{4}$", "DDLLLL"),           // SC6 1999–2008
                v("^[A-Z]{4}$D$D$", "LLLLDD"),           // SC5 1991–1999
            ),
            corrigible = false, // layout-only format: exact reads only, never a correction target
        ),
    )

    private val FORMATS_BY_NAME = FORMATS.associateBy { it.name }
    private val COUNTRIES = FORMATS.associate { it.name to it.country }

    // NL sidecode eras, keyed by layout; null end = still being issued ("now").
    private val NL_SIDECODE_ERAS = mapOf(
        "LLLLDD" to (1991.0 to 1999.0),
        "DDLLLL" to (1999.0 to 2008.0),
        "DDLLLD" to (2008.0 to 2013.0),
        "DLLLDD" to (2013.0 to 2015.2),
        "LLDDDL" to (2015.2 to 2019.2),
        "LDDDLL" to (2019.2 to 2024.4),
        "LLLDDL" to (2024.4 to null),
    )

    // Position-aware OCR confusion maps (see spec).
    private val DIGIT_TO_LETTER = mapOf(
        '0' to 'D', '1' to 'L', '2' to 'Z', '4' to 'A', '5' to 'S', '6' to 'G', '7' to 'T', '8' to 'B',
    )
    private val LETTER_TO_DIGIT = mapOf(
        'O' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'L' to '1', 'Z' to '2',
        'A' to '4', 'S' to '5', 'G' to '6', 'T' to '7', 'B' to '8',
    )

    private val EU_GENERIC = Regex("^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{4,8}$")
    private val NON_ALNUM = Regex("[^A-Z0-9]")

    // ---- registration-series chronology (issue-date prior) ------------------------------------

    private const val RATE_MARGIN = 1.5 // tolerate issuance speeding up 50% before the prior bites
    private const val STEP_MARGIN = 2   // absolute slack + the floor for a clock behind the data

    private fun yearNow(): Double {
        val today = LocalDate.now()
        return today.year + (today.dayOfYear - 1) / 365.25
    }

    /** One country's sequential issuance: its letter alphabet + dated pair checkpoints. */
    class SeriesSystem(private val letters: String, private val epochs: List<Pair<String, Double>>) {

        private fun index(pair: String): Int =
            letters.indexOf(pair[0]) * letters.length + letters.indexOf(pair[1])

        /** Steps/year — the faster of the ~5-year trend and the latest interval. */
        private fun rate(): Double {
            val (pLast, tLast) = epochs.last()
            val iLast = index(pLast)
            var ref = epochs.first()
            for (e in epochs) if (e.second <= tLast - 5) ref = e
            var trend = (iLast - index(ref.first)) / (tLast - ref.second)
            val (pPrev, tPrev) = epochs[epochs.size - 2]
            if (tLast - tPrev >= 0.8) trend = max(trend, (iLast - index(pPrev)) / (tLast - tPrev))
            return trend
        }

        /** Highest pair index plausibly issued by [atYear] (extrapolated + headroom). */
        private fun frontier(atYear: Double): Int {
            val (pLast, tLast) = epochs.last()
            val ahead = max(0.0, atYear - tLast) // a clock behind the data → last checkpoint
            return index(pLast) + (rate() * ahead * RATE_MARGIN).roundToInt() + STEP_MARGIN
        }

        /** First-letter check: could this series exist by [atYear]? */
        fun plausible(pair: String, atYear: Double): Boolean {
            if (pair[0] !in letters || pair[1] !in letters) return false
            return letters.indexOf(pair[0]) <= frontier(atYear) / letters.length
        }

        /** Approximate issue year of a series, or null if it can't exist yet. */
        fun estimateYear(pair: String, atYear: Double): Int? {
            val idx = index(pair)
            if (idx > frontier(atYear)) return null
            if (idx <= index(epochs.first().first)) return epochs.first().second.roundToInt()
            for ((a, b) in epochs.zipWithNext()) {
                val i0 = index(a.first)
                val i1 = index(b.first)
                if (idx <= i1) return (a.second + (idx - i0).toDouble() / (i1 - i0) * (b.second - a.second)).roundToInt()
            }
            val (pLast, tLast) = epochs.last()
            return (tLast + (idx - index(pLast)) / rate()).roundToInt() // beyond the last checkpoint
        }
    }

    // Italy — AA=May 1994; yearly steps from the money.it table; HA=May 2025, ~HC mid-2026.
    private val IT_CAR_SERIES = SeriesSystem(ITALIAN_LETTERS, listOf(
        "AA" to 1994.4, "BB" to 1999.0, "BX" to 2002.0, "CE" to 2003.0, "CR" to 2005.0,
        "CZ" to 2006.0, "DD" to 2007.0, "DM" to 2008.0, "DS" to 2009.0, "EA" to 2010.0,
        "EF" to 2011.0, "EK" to 2012.0, "EP" to 2013.0, "ET" to 2014.0, "FB" to 2016.0,
        "FH" to 2017.0, "FN" to 2018.0, "FT" to 2019.0, "GA" to 2020.0, "GD" to 2021.0,
        "GH" to 2022.0, "GK" to 2023.0, "GS" to 2024.0, "GX" to 2025.0, "HA" to 2025.4,
        "HC" to 2026.5,
    ))

    // France (SIV) — exact first-pair start dates from francoplaque.fr; ~6.5 steps/year.
    private val FR_CAR_SERIES = SeriesSystem(FRENCH_LETTERS, listOf(
        "AA" to 2009.29, "BA" to 2010.71, "CA" to 2012.02, "DA" to 2013.83,
        "EA" to 2016.16, "FA" to 2018.66, "GA" to 2021.45, "HA" to 2024.83,
    ))

    // Spain — BBB=Sep 2000 and N=Apr 2025 firm; interior checkpoints ±1–2 years.
    private val ES_CAR_SERIES = SeriesSystem(SPANISH_LETTERS, listOf(
        "BB" to 2000.7, "CB" to 2002.5, "DB" to 2004.3, "FB" to 2006.1, "GB" to 2008.1,
        "HB" to 2010.8, "JB" to 2013.5, "KB" to 2016.3, "LB" to 2018.7, "MB" to 2021.0,
        "NB" to 2025.3, "NM" to 2026.3,
    ))

    // Which formats carry a pair-indexed chronology, and where the dated pair starts.
    private val SERIES = mapOf(
        "it_car" to (IT_CAR_SERIES to 0),
        "fr_car" to (FR_CAR_SERIES to 0),
        "es_car" to (ES_CAR_SERIES to 4),
    )

    enum class Confidence(val wire: String) {
        EXACT("exact"),          // matched a known pattern with no correction
        CORRECTED("corrected"),  // matched only after OCR confusion correction
        GENERIC("generic"),      // matched the loose EU fallback only
        INVALID("invalid"),      // no match
    }

    data class PlateResult(
        val raw: String,
        val normalized: String,
        val plateType: String?, // e.g. "it_car", or null if invalid
        val confidence: Confidence,
    ) {
        val isValid: Boolean get() = confidence != Confidence.INVALID

        /** ISO-2 registration country implied by the format; "?" when unknown. */
        val country: String get() = COUNTRIES[plateType] ?: "?"
    }

    /** Uppercase and strip to A-Z0-9 only. */
    fun normalize(raw: String): String = NON_ALNUM.replace(raw.uppercase(), "")

    /**
     * Confidences for [raw] (OCR alphabet, ASCII) reduced to the characters [normalize] keeps, so
     * the array stays index-aligned with the normalized text. Positions dropped by normalization
     * (stray separators) drop their confidence too; missing entries default to fully confident.
     */
    private fun alignToNormalized(raw: String, conf: FloatArray): FloatArray {
        val up = raw.uppercase()
        val kept = ArrayList<Float>(up.length)
        for (i in up.indices) {
            val c = up[i]
            if (c in 'A'..'Z' || c in '0'..'9') kept.add(if (i < conf.size) conf[i] else 1f)
        }
        return kept.toFloatArray()
    }

    /**
     * Approximate year a plate's series was issued, or null (unknown format / unissued series /
     * a district system with no chronology). [country] picks the system when known; ambiguous
     * IT/FR shapes date as Italian. NL dates by sidecode era midpoint (coarse); HR/UA/RO → null.
     */
    fun estimateRegistrationYear(raw: String, country: String? = null, atYear: Double? = null): Int? {
        val norm = normalize(raw)
        val at = atYear ?: yearNow()
        for ((name, entry) in SERIES) {
            if (country != null && COUNTRIES[name] != country) continue
            if (FORMATS_BY_NAME.getValue(name).variants.any { it.first.matches(norm) }) {
                val (system, start) = entry
                return system.estimateYear(norm.substring(start, start + 2), at)
            }
        }
        if (country == null || country == "NL") {
            for ((pattern, layout) in FORMATS_BY_NAME.getValue("nl_car").variants) {
                if (pattern.matches(norm)) {
                    val (y0, y1) = NL_SIDECODE_ERAS.getValue(layout)
                    return ((y0 + (y1 ?: at)) / 2).roundToInt()
                }
            }
        }
        return null
    }

    // A per-character confidence at or above this bar is an "anchor": the OCR is sure enough of
    // that glyph that confusion-correction may not overwrite it. Blocks garble from being rewritten
    // into a format-valid plate on the strength of one confidently-wrong-shaped neighbour.
    private const val ANCHOR_CONFIDENCE = 0.90f

    /**
     * Position-aware confusion correction. Returns (corrected, nChanges) or null on length
     * mismatch — or when a change would fall on an anchor (a [conf] position >= [anchor]): the
     * character is trusted, so a layout reachable only by overwriting it is not a real candidate.
     * Each correction is a guess, so callers also cap nChanges — structural validation alone cannot
     * disambiguate, e.g. a real motorcycle plate AB12345 is only two edits from AB123AS.
     */
    private fun coerceToLayout(
        text: String,
        layout: String,
        conf: FloatArray?,
        anchor: Float,
    ): Pair<String, Int>? {
        if (text.length != layout.length) return null
        var changes = 0
        val out = CharArray(text.length)
        for (i in text.indices) {
            val ch = text[i]
            val mapped = when {
                layout[i] == 'L' && ch.isDigit() -> DIGIT_TO_LETTER[ch] ?: ch
                layout[i] == 'D' && ch.isLetter() -> LETTER_TO_DIGIT[ch] ?: ch
                else -> ch
            }
            if (mapped != ch) {
                if (conf != null && i < conf.size && conf[i] >= anchor) return null
                changes++
            }
            out[i] = mapped
        }
        return String(out) to changes
    }

    /**
     * [charConfidences], when supplied, are the OCR's per-character softmax aligned to [raw]
     * (OcrDecoder output). Correction leaves any position >= [anchorConfidence] untouched, so a
     * confidently-read character is never overwritten to reach a format — only genuinely uncertain
     * glyphs are correction candidates. Null keeps the pure-structural behaviour (every position
     * correctable), which is what the cross-language parity tests exercise.
     *
     * [enableCorrection] defaults to **false** (2026-07-06): measured against real footage, the
     * correction step accounted for 0 of 24 real `DedupEngine` promotions on the current OCR
     * model — see the class doc and docs/model-specs.md. Left in place, not deleted, in case a
     * larger sample shows it still earns its keep in cases the 3-clip sample didn't cover.
     */
    fun validate(
        raw: String,
        acceptMoto: Boolean = true,
        acceptMoped: Boolean = false,
        acceptForeign: Boolean = true,
        allowGenericEu: Boolean = false,
        enableCorrection: Boolean = false,
        maxCorrections: Int = 2,
        seriesPrior: Boolean = true,
        atYear: Double? = null,
        charConfidences: FloatArray? = null,
        anchorConfidence: Float = ANCHOR_CONFIDENCE,
    ): PlateResult {
        val norm = normalize(raw)
        val normConf = charConfidences?.let { alignToNormalized(raw, it) }
        val at = atYear ?: yearNow()

        val enabled = buildList {
            add("it_car")
            if (acceptMoto) add("it_moto")
            if (acceptMoped) add("it_moped")
            if (acceptForeign) addAll(listOf("fr_car", "es_car", "ua_car", "hr_car", "ro_car", "nl_car"))
        }

        // Issue-date prior for the sequential systems; district systems are gated by their codes.
        fun seriesOk(name: String, text: String): Boolean {
            if (!seriesPrior) return true
            val (system, start) = SERIES[name] ?: return true
            return system.plausible(text.substring(start, start + 2), at)
        }

        // 1) exact match on normalized text (across all enabled formats first)
        for (name in enabled) {
            val fmt = FORMATS_BY_NAME.getValue(name)
            for ((pattern, layout) in fmt.variants) {
                if (pattern.matches(norm) && fmt.prefixOk(norm, layout) && seriesOk(name, norm)) {
                    return PlateResult(raw, norm, name, Confidence.EXACT)
                }
            }
        }

        // 2) bounded position-aware correction; prefer the candidate needing the fewest changes.
        // Disabled by default — see [enableCorrection] doc above.
        if (enableCorrection) {
            var best: Triple<Int, String, String>? = null // (nChanges, name, corrected)
            for (name in enabled) {
                val fmt = FORMATS_BY_NAME.getValue(name)
                if (!fmt.corrigible) continue
                for ((pattern, layout) in fmt.variants) {
                    val (corrected, changes) = coerceToLayout(norm, layout, normConf, anchorConfidence) ?: continue
                    if (changes == 0 || changes > maxCorrections) continue
                    if (pattern.matches(corrected) && fmt.prefixOk(corrected, layout) &&
                        seriesOk(name, corrected) && (best == null || changes < best.first)
                    ) {
                        best = Triple(changes, name, corrected)
                    }
                }
            }
            if (best != null) return PlateResult(raw, best.third, best.second, Confidence.CORRECTED)
        }

        // 3) loose EU fallback (only when explicitly allowed)
        if (allowGenericEu && EU_GENERIC.matches(norm)) {
            return PlateResult(raw, norm, "eu_generic", Confidence.GENERIC)
        }

        return PlateResult(raw, norm, null, Confidence.INVALID)
    }
}
