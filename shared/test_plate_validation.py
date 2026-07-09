"""Unit tests for plate_validation. Pure stdlib: run with `python3 -m unittest` from shared/."""

import unittest

from plate_validation import Confidence, estimate_registration_year, normalize, validate


class TestNormalize(unittest.TestCase):
    def test_strips_and_uppercases(self):
        self.assertEqual(normalize("ab 123-cd"), "AB123CD")
        self.assertEqual(normalize(" Ab123Cd\n"), "AB123CD")


class TestItalianCar(unittest.TestCase):
    def test_exact(self):
        r = validate("AB123CD")
        self.assertTrue(r.is_valid)
        self.assertEqual(r.plate_type, "it_car")
        self.assertEqual(r.confidence, Confidence.EXACT)

    def test_normalized_exact(self):
        self.assertEqual(validate("ab 123 cd").confidence, Confidence.EXACT)

    def test_excluded_letters_rejected(self):
        # I, O, Q, U are not in the alphabet; an I in a letter slot can't be valid.
        self.assertFalse(validate("IB123CD").is_valid)

    def test_confusion_correction_disabled_by_default(self):
        # 2026-07-06: measured 0 of 24 real DedupEngine promotions attributable to correction —
        # see plate_validation.validate's `enable_correction` doc. Must opt in explicitly now.
        self.assertEqual(validate("A8I23CD").confidence, Confidence.INVALID)

    def test_confusion_correction_letter_slot(self):
        # "8" in a letter slot -> B; "I" in a digit slot -> 1  => AB123CD
        r = validate("A8I23CD", enable_correction=True)
        self.assertEqual(r.normalized, "AB123CD")
        self.assertEqual(r.confidence, Confidence.CORRECTED)
        self.assertEqual(r.plate_type, "it_car")

    def test_confusion_correction_digit_slot(self):
        # "O" in a digit slot -> 0
        r = validate("ABO23CD", enable_correction=True)
        self.assertEqual(r.normalized, "AB023CD")
        self.assertEqual(r.confidence, Confidence.CORRECTED)

    def test_wrong_length_dropped(self):
        self.assertFalse(validate("AB12CD").is_valid)


class TestOtherTypes(unittest.TestCase):
    def test_moto(self):
        r = validate("AB12345")
        self.assertEqual(r.plate_type, "it_moto")
        self.assertEqual(r.confidence, Confidence.EXACT)

    def test_moto_can_be_disabled(self):
        # Disabling moto means a clean moto plate is no longer a confident moto read.
        # (With enable_correction=True it could still be coerced toward a car plate, but only
        # as a low-confidence CORRECTED guess — never EXACT — and correction is off by default.)
        r = validate("AB12345", accept_moto=False)
        self.assertNotEqual(r.plate_type, "it_moto")
        self.assertNotEqual(r.confidence, Confidence.EXACT)

    def test_correction_cap_blocks_fabrication(self):
        # Too many edits from any valid layout -> rejected rather than fabricated.
        r = validate("00000", accept_moped=True, enable_correction=True, max_corrections=1)
        self.assertFalse(r.is_valid)

    def test_moped_opt_in(self):
        self.assertFalse(validate("12ABC").is_valid)
        self.assertTrue(validate("12ABC", accept_moped=True).is_valid)


class TestSeriesPrior(unittest.TestCase):
    def test_correction_never_fabricates_a_future_series(self):
        # "5" in the first letter slot maps to "S", "1" to "L" — series no country in scope has
        # issued (IT/FR are both around H). Without the prior these were CORRECTED matches.
        self.assertFalse(validate("5A123CD", enable_correction=True).is_valid)
        self.assertFalse(validate("1B123CD", enable_correction=True).is_valid)

    def test_correction_still_works_in_issued_series(self):
        # "8" in the SECOND letter slot -> B: HB is issued, so the correction stands.
        r = validate("H8123CD", enable_correction=True)
        self.assertEqual(r.normalized, "HB123CD")
        self.assertEqual(r.confidence, Confidence.CORRECTED)

    def test_exact_future_series_is_not_italian(self):
        # Structurally perfect but the M series doesn't exist (IT or FR), and MM is not a
        # Croatian city code -> not a plate. (MA123CD, by contrast, is Makarska HR — see below.)
        self.assertFalse(validate("MM123CD").is_valid)
        r = validate("MM123CD", allow_generic_eu=True)
        self.assertEqual(r.confidence, Confidence.GENERIC)

    def test_current_and_old_series_accepted(self):
        self.assertEqual(validate("HA123CD").confidence, Confidence.EXACT)
        self.assertEqual(validate("AA123CD").confidence, Confidence.EXACT)

    def test_prior_can_be_disabled(self):
        r = validate("MM123CD", series_prior=False)
        self.assertEqual((r.plate_type, r.confidence), ("it_car", Confidence.EXACT))

    def test_frontier_extrapolates_with_the_clock(self):
        # J-series Italian plates don't exist in 2026 but will around 2030 — the prior must open
        # the gate on its own (no constant to bump), and generously early rather than late.
        self.assertFalse(validate("JA123CD", at_year=2026.5).is_valid)
        self.assertEqual(validate("JA123CD", at_year=2032.0).confidence, Confidence.EXACT)

    def test_clock_behind_the_data_is_safe(self):
        # A device clock reading 1999 must not reject plates the chronology already knows exist.
        self.assertEqual(validate("HA123CD", at_year=1999.0).confidence, Confidence.EXACT)


class TestForeignFormats(unittest.TestCase):
    def test_french_q_plate_recognized(self):
        # Q is skipped by Italy but issued by France — the one text-level FR marker.
        r = validate("AQ123BC")
        self.assertEqual((r.plate_type, r.country, r.confidence), ("fr_car", "FR", Confidence.EXACT))

    def test_ambiguous_it_fr_read_prefers_italian(self):
        # LLDDDLL with no Q matches both countries; on Italian roads IT wins.
        r = validate("GA123BC")
        self.assertEqual((r.plate_type, r.country), ("it_car", "IT"))

    def test_french_future_series_rejected(self):
        self.assertFalse(validate("ZQ123BC").is_valid)  # France is around H too

    def test_spanish_plate_recognized_and_corrected(self):
        r = validate("1234BCD")
        self.assertEqual((r.plate_type, r.country, r.confidence), ("es_car", "ES", Confidence.EXACT))
        r = validate("I234BCD", enable_correction=True)  # I -> 1 in a digit slot
        self.assertEqual((r.normalized, r.confidence), ("1234BCD", Confidence.CORRECTED))

    def test_spanish_future_series_rejected(self):
        self.assertFalse(validate("1234XYZ").is_valid)  # Spain is in its N series

    def test_foreign_can_be_disabled(self):
        self.assertFalse(validate("1234BCD", accept_foreign=False).is_valid)

    def test_dutch_sidecodes(self):
        # Each sidecode layout is distinct; the era dates the plate coarsely.
        self.assertEqual(validate("PDA01D").plate_type, "nl_car")   # SC11 (2024–)
        self.assertEqual(validate("G001BB").plate_type, "nl_car")   # SC10
        self.assertEqual(validate("12GBBB").plate_type, "nl_car")   # SC6
        self.assertEqual(validate("PDA01D").country, "NL")
        self.assertEqual(estimate_registration_year("GB123X"), 2017)      # SC9 midpoint
        self.assertEqual(estimate_registration_year("01GBB1", country="NL"), 2010)  # SC7 midpoint

    def test_dutch_is_never_a_correction_target(self):
        # NL is just a layout (no alphabet/series/code prior), so garbage must not be
        # "corrected" into it: N5064A is 2 edits from the SC11 layout but stays invalid, even
        # with correction on (the corrigible=False gate on nl_car, not just the default-off switch).
        self.assertFalse(validate("N5064A", enable_correction=True).is_valid)

    def test_croatian_city_codes(self):
        # ZG 1234-AB: 8 chars, Z/G aren't Ukrainian letters -> Croatia.
        r = validate("ZG1234AB")
        self.assertEqual((r.plate_type, r.country), ("hr_car", "HR"))
        # PU 123-AB has the Italian car shape, but P is beyond the IT/FR series frontier and PU
        # is Pula -> Croatia. MA123CD likewise reads as Makarska, not a future Italian series.
        self.assertEqual(validate("PU123AB").plate_type, "hr_car")
        self.assertEqual(validate("MA123CD").plate_type, "hr_car")
        # Correction is allowed (the city-code set gates it): Z in a digit slot -> 2.
        r = validate("ZG1Z34AB", enable_correction=True)
        self.assertEqual((r.normalized, r.confidence), ("ZG1234AB", Confidence.CORRECTED))
        # Not a city code -> never Croatian (it may still coerce into another format:
        # XX123AB is two edits from the ungated Italian moto layout).
        self.assertNotEqual(validate("XX123AB").plate_type, "hr_car")
        self.assertFalse(validate("XX1234AB").is_valid)  # 8-char: no gate passes at all

    def test_ukrainian_plates(self):
        # BC 1234 AX: Lviv region, all letters in the 12-letter lookalike set.
        r = validate("BC1234AX")
        self.assertEqual((r.plate_type, r.country), ("ua_car", "UA"))
        self.assertEqual(validate("KA1234BC").plate_type, "ua_car")  # 2013-series Kyiv (beats HR KA)
        # Suffix letter outside the Ukrainian set -> not UA; ZG is Croatian.
        self.assertEqual(validate("ZG1234AB").plate_type, "hr_car")
        # Region code that isn't issued -> invalid (XZ passes no gate).
        self.assertFalse(validate("XZ1234AX").is_valid)

    def test_romanian_plates(self):
        self.assertEqual(validate("CJ12ABC").plate_type, "ro_car")   # Cluj
        self.assertEqual(validate("B123ABC").plate_type, "ro_car")   # Bucharest, 3 digits
        self.assertEqual(validate("B12ABC").plate_type, "ro_car")    # Bucharest, 2 digits
        self.assertEqual(validate("CJ12ABC").country, "RO")
        # Q is never used in Romania (CJ12QBC instead reads as a Q->0 slip of Italian CJ120BC),
        # and XX is not a county.
        self.assertNotEqual(validate("CJ12QBC").plate_type, "ro_car")
        self.assertNotEqual(validate("XX12ABC").plate_type, "ro_car")
        # District systems carry no chronology.
        self.assertIsNone(estimate_registration_year("CJ12ABC", country="RO"))
        self.assertIsNone(estimate_registration_year("ZG1234AB", country="HR"))
        self.assertIsNone(estimate_registration_year("BC1234AX", country="UA"))

    def test_italian_result_country(self):
        self.assertEqual(validate("HA123CD").country, "IT")
        self.assertEqual(validate("HELLO").country, "?")


class TestRegistrationYear(unittest.TestCase):
    def test_checkpoints(self):
        self.assertEqual(estimate_registration_year("AA000AA"), 1994)
        self.assertEqual(estimate_registration_year("GA123BC"), 2020)
        self.assertEqual(estimate_registration_year("HA123CD"), 2025)

    def test_interpolates_between_checkpoints(self):
        y = estimate_registration_year("EW555TT")
        self.assertTrue(2013 <= y <= 2016, y)

    def test_country_selects_the_system(self):
        # GA is ~2020 in Italy but ~2021 in France's SIV chronology.
        self.assertEqual(estimate_registration_year("GA123BC", country="FR"), 2021)
        self.assertEqual(estimate_registration_year("AA123BC", country="FR"), 2009)
        self.assertEqual(estimate_registration_year("1234MBB", country="ES"), 2021)

    def test_extrapolates_past_the_last_checkpoint(self):
        # HD is just beyond the last Italian checkpoint (HC, mid-2026) — dated, not None.
        y = estimate_registration_year("HD123CD", at_year=2027.5)
        self.assertIsNotNone(y)
        self.assertTrue(2026 <= y <= 2028, y)

    def test_unissued_or_non_car_is_none(self):
        self.assertIsNone(estimate_registration_year("ZZ999ZZ"))
        self.assertIsNone(estimate_registration_year("MA123CD"))
        self.assertIsNone(estimate_registration_year("AB12345"))  # moto: no chronology


class TestGenericEU(unittest.TestCase):
    def test_off_by_default(self):
        self.assertFalse(validate("XX1234YY").is_valid)

    def test_opt_in(self):
        r = validate("XX1234YY", allow_generic_eu=True)
        self.assertEqual(r.confidence, Confidence.GENERIC)

    def test_garbage_rejected(self):
        self.assertFalse(validate("HELLO", allow_generic_eu=True).is_valid)


if __name__ == "__main__":
    unittest.main()
