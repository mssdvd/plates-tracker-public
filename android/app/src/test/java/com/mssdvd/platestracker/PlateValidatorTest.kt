package com.mssdvd.platestracker

import com.mssdvd.platestracker.alpr.PlateValidator
import com.mssdvd.platestracker.alpr.PlateValidator.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors shared/test_plate_validation.py — the Python reference is the contract. */
class PlateValidatorTest {

    @Test
    fun normalizeStripsAndUppercases() {
        assertEquals("AB123CD", PlateValidator.normalize("ab 123-cd"))
        assertEquals("AB123CD", PlateValidator.normalize(" Ab123Cd\n"))
    }

    @Test
    fun exactItalianCar() {
        val r = PlateValidator.validate("CN555PL") // the reference plate
        assertTrue(r.isValid)
        assertEquals("it_car", r.plateType)
        assertEquals(Confidence.EXACT, r.confidence)
        assertEquals("IT", r.country)
        assertEquals(Confidence.EXACT, PlateValidator.validate("ab 123 cd").confidence)
    }

    @Test
    fun excludedLettersRejected() {
        // I, O, Q, U are not in the alphabet; an I in a letter slot can't be valid.
        assertFalse(PlateValidator.validate("IB123CD").isValid)
    }

    @Test
    fun confusionCorrectionLetterSlot() {
        // Disabled by default (2026-07-06, see PlateValidator doc) — must opt in explicitly.
        assertEquals(Confidence.INVALID, PlateValidator.validate("A8I23CD").confidence)
        // "8" in a letter slot -> B; "I" in a digit slot -> 1  => AB123CD
        val r = PlateValidator.validate("A8I23CD", enableCorrection = true)
        assertEquals("AB123CD", r.normalized)
        assertEquals(Confidence.CORRECTED, r.confidence)
        assertEquals("it_car", r.plateType)
        assertEquals("corrected", r.confidence.wire)
    }

    @Test
    fun confusionCorrectionDigitSlot() {
        // "O" in a digit slot -> 0
        val r = PlateValidator.validate("ABO23CD", enableCorrection = true)
        assertEquals("AB023CD", r.normalized)
        assertEquals(Confidence.CORRECTED, r.confidence)
    }

    @Test
    fun wrongLengthDropped() {
        assertFalse(PlateValidator.validate("AB12CD").isValid)
    }

    @Test
    fun motoExactAndOptOut() {
        val r = PlateValidator.validate("AB12345")
        assertEquals("it_moto", r.plateType)
        assertEquals(Confidence.EXACT, r.confidence)
        // Disabling moto: a clean moto plate is no longer a confident moto read.
        val noMoto = PlateValidator.validate("AB12345", acceptMoto = false)
        assertNotEquals("it_moto", noMoto.plateType)
        assertNotEquals(Confidence.EXACT, noMoto.confidence)
    }

    @Test
    fun correctionCapBlocksFabrication() {
        // Too many edits from any valid layout -> rejected rather than fabricated.
        val r = PlateValidator.validate("00000", acceptMoped = true, enableCorrection = true, maxCorrections = 1)
        assertFalse(r.isValid)
    }

    @Test
    fun mopedOptIn() {
        assertFalse(PlateValidator.validate("12ABC").isValid)
        assertTrue(PlateValidator.validate("12ABC", acceptMoped = true).isValid)
    }

    @Test
    fun genericEuOptIn() {
        assertFalse(PlateValidator.validate("XX1234YY").isValid)
        val r = PlateValidator.validate("XX1234YY", allowGenericEu = true)
        assertEquals(Confidence.GENERIC, r.confidence)
        assertEquals("?", r.country) // non-Italian format -> unknown registration country
        assertFalse(PlateValidator.validate("HELLO", allowGenericEu = true).isValid)
    }

    @Test
    fun seriesPriorBlocksFabricatedFutureSeries() {
        // "5"/"1" in the first letter slot map to S/L — series Italy hasn't issued (current: H).
        assertFalse(PlateValidator.validate("5A123CD", enableCorrection = true).isValid)
        assertFalse(PlateValidator.validate("1B123CD", enableCorrection = true).isValid)
        // Second-slot correction inside an issued series still works: 8 -> B gives HB.
        val r = PlateValidator.validate("H8123CD", enableCorrection = true)
        assertEquals("HB123CD", r.normalized)
        assertEquals(Confidence.CORRECTED, r.confidence)
    }

    @Test
    fun seriesPriorRejectsExactFutureSeries() {
        // MM: unissued in IT and FR alike, and not a Croatian city code (MA would be Makarska).
        assertFalse(PlateValidator.validate("MM123CD").isValid)
        assertEquals(Confidence.GENERIC, PlateValidator.validate("MM123CD", allowGenericEu = true).confidence)
        // Current and old series stay EXACT; the prior can be disabled.
        assertEquals(Confidence.EXACT, PlateValidator.validate("HA123CD").confidence)
        assertEquals(Confidence.EXACT, PlateValidator.validate("AA123CD").confidence)
        val off = PlateValidator.validate("MM123CD", seriesPrior = false)
        assertEquals("it_car" to Confidence.EXACT, off.plateType to off.confidence)
    }

    @Test
    fun recognizesDutchCroatianUkrainianRomanian() {
        // NL: the sidecode layout is the format; exact only, era midpoint as date.
        assertEquals("nl_car" to "NL", PlateValidator.validate("PDA01D").let { it.plateType to it.country })
        assertEquals("nl_car", PlateValidator.validate("G001BB").plateType)
        assertEquals(2017, PlateValidator.estimateRegistrationYear("GB123X")) // SC9 midpoint
        // never a correction target, even with correction on (the corrigible=false gate on nl_car)
        assertFalse(PlateValidator.validate("N5064A", enableCorrection = true).isValid)
        // HR: city-code gate; wins IT-shaped reads only beyond the IT/FR frontier.
        assertEquals("hr_car" to "HR", PlateValidator.validate("ZG1234AB").let { it.plateType to it.country })
        assertEquals("hr_car", PlateValidator.validate("PU123AB").plateType)
        assertEquals("hr_car", PlateValidator.validate("MA123CD").plateType) // Makarska
        assertNotEquals("hr_car", PlateValidator.validate("XX123AB").plateType)
        // UA: lookalike alphabet + region codes; beats HR on the shared 8-char layout.
        assertEquals("ua_car" to "UA", PlateValidator.validate("BC1234AX").let { it.plateType to it.country })
        assertEquals("ua_car", PlateValidator.validate("KA1234BC").plateType)
        assertFalse(PlateValidator.validate("XZ1234AX").isValid)
        // RO: county codes, Bucharest B-variants, never a Q.
        assertEquals("ro_car" to "RO", PlateValidator.validate("CJ12ABC").let { it.plateType to it.country })
        assertEquals("ro_car", PlateValidator.validate("B123ABC").plateType)
        assertNotEquals("ro_car", PlateValidator.validate("XX12ABC").plateType)
        // District systems carry no chronology.
        assertEquals(null, PlateValidator.estimateRegistrationYear("ZG1234AB", country = "HR"))
        assertEquals(null, PlateValidator.estimateRegistrationYear("CJ12ABC", country = "RO"))
    }

    @Test
    fun frontierExtrapolatesWithTheClock() {
        // Italian J plates don't exist in 2026 but will around 2030 — no constant to bump.
        assertFalse(PlateValidator.validate("JA123CD", atYear = 2026.5).isValid)
        assertEquals(Confidence.EXACT, PlateValidator.validate("JA123CD", atYear = 2032.0).confidence)
        // A clock behind the checkpoint data must never reject what the data already knows.
        assertEquals(Confidence.EXACT, PlateValidator.validate("HA123CD", atYear = 1999.0).confidence)
    }

    @Test
    fun recognizesForeignSequentialFormats() {
        // Q is skipped by Italy but issued by France — the one text-level FR marker.
        val fr = PlateValidator.validate("AQ123BC")
        assertEquals("fr_car", fr.plateType)
        assertEquals("FR", fr.country)
        // Ambiguous LLDDDLL with no Q classifies as Italian (Italian roads).
        assertEquals("it_car", PlateValidator.validate("GA123BC").plateType)
        // Spain: distinct shape, own alphabet + chronology; corrections work per-layout.
        val es = PlateValidator.validate("1234BCD")
        assertEquals("es_car" to "ES", es.plateType to es.country)
        // I -> 1
        assertEquals(Confidence.CORRECTED, PlateValidator.validate("I234BCD", enableCorrection = true).confidence)
        // Future series rejected in both systems; foreign can be disabled.
        assertFalse(PlateValidator.validate("ZQ123BC").isValid)
        assertFalse(PlateValidator.validate("1234XYZ").isValid)
        assertFalse(PlateValidator.validate("1234BCD", acceptForeign = false).isValid)
    }

    @Test
    fun estimatesRegistrationYearFromSeries() {
        assertEquals(1994, PlateValidator.estimateRegistrationYear("AA000AA"))
        assertEquals(2020, PlateValidator.estimateRegistrationYear("GA123BC"))
        assertEquals(2025, PlateValidator.estimateRegistrationYear("HA123CD"))
        val ew = PlateValidator.estimateRegistrationYear("EW555TT")!!
        assertTrue("EW estimate $ew", ew in 2013..2016)
        // country picks the system: GA is ~2020 in Italy but ~2021 in the French SIV.
        assertEquals(2021, PlateValidator.estimateRegistrationYear("GA123BC", country = "FR"))
        assertEquals(2021, PlateValidator.estimateRegistrationYear("1234MBB", country = "ES"))
        assertEquals(null, PlateValidator.estimateRegistrationYear("ZZ999ZZ"))
        assertEquals(null, PlateValidator.estimateRegistrationYear("AB12345")) // moto: no chronology
    }

    @Test
    fun rejectsOcrNoise() {
        assertFalse(PlateValidator.validate("11O11").isValid) // Gate-A garbage examples
        assertFalse(PlateValidator.validate("350844").isValid)
        assertFalse(PlateValidator.validate("").isValid)
    }
}
