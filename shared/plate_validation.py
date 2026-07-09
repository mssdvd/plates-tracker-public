"""Reference implementation of plate normalization + validation.

Canonical spec: ../docs/plate-formats.md. The Android app reimplements these rules in Kotlin;
keep them in sync. Pure standard library — no third-party deps, runs on any Python 3.9+.

Primary target: Italian car plates (``LL DDD LL``, 22-letter alphabet excluding I/O/Q/U).
Foreign systems are also recognized: sequential ones with a chronology (FR SIV, ES, NL sidecodes)
and district-based ones gated by their closed prefix-code sets (HR, UA, RO). District systems
without a usable text-level gate (DE/CH/AT/SI) stay behind the generic-EU fallback.
"""

from __future__ import annotations

import datetime as _dt
import re
from dataclasses import dataclass
from enum import Enum

# 22-letter Italian alphabet: A-Z without I, O, Q, U.
ITALIAN_LETTERS = "ABCDEFGHJKLMNPRSTVWXYZ"
# 23-letter French SIV alphabet: A-Z without I, O, U (Q is allowed — a text-level FR marker).
FRENCH_LETTERS = "ABCDEFGHJKLMNPQRSTVWXYZ"
# 20-consonant Spanish alphabet: no vowels, no Ñ/Q.
SPANISH_LETTERS = "BCDFGHJKLMNPRSTVWXYZ"
# Ukrainian plates use only Cyrillic glyphs that look Latin: А В С Е Н І К М О Р Т Х.
UKRAINIAN_LETTERS = "ABCEHIKMOPTX"

_L_IT = f"[{ITALIAN_LETTERS}]"
_L_FR = f"[{FRENCH_LETTERS}]"
_L_ES = f"[{SPANISH_LETTERS}]"
_L_UA = f"[{UKRAINIAN_LETTERS}]"
_L_RO = "[A-PR-Z]"  # Romania never uses Q
_D = "[0-9]"

# Closed prefix-code sets for the district-based systems (checked after a variant matches).
# Croatia: city codes, diacritics ASCII-fied as the OCR reads them (ČK→CK, ŠI→SI, VŽ→VZ, …).
HR_CITY_CODES = frozenset(
    "BJ BM CK DA DE DJ DU GS IM KA KC KR KT KZ MA NA NG OG OS PU PZ RI SB SI SK SL ST "
    "VK VT VU VZ ZD ZG ZU".split()
)
# Ukraine: 2004-series oblast codes; the 2013 re-issue maps the first letter A→K, B→H, C→I.
_UA_2004 = (
    "AA AB AC AE AH AI AK AM AO AP AT AX BA BB BC BE BH BI BK BM BO BT BX CA CB CE CH".split()
)
UA_REGION_CODES = frozenset(_UA_2004) | frozenset(
    c[0].translate(str.maketrans("ABC", "KHI")) + c[1] for c in _UA_2004
)
# Romania: county codes (Bucharest "B" is handled by its own single-letter variants).
RO_COUNTY_CODES = frozenset(
    "AB AG AR BC BH BN BR BT BV BZ CJ CL CS CT CV DB DJ GJ GL GR HD HR IF IL IS MH MM MS "
    "NT OT PH SB SJ SM SV TL TM TR VL VN VS".split()
)


@dataclass(frozen=True)
class PlateFormat:
    """One recognizable plate format: country, its pattern/layout variants, optional prefix gate.

    ``corrigible=False`` keeps a format out of confusion-correction: correction is only safe when
    a strong prior (restricted alphabet, series chronology, or a closed prefix-code set) can
    reject fabrications, and a format that is *just a layout* (NL) would let garbage be
    "corrected" into it. Such formats still match exact reads.
    """

    name: str
    country: str
    variants: tuple[tuple[re.Pattern[str], str], ...]  # (pattern on normalized text, slot layout)
    prefix_codes: frozenset[str] | None = None         # leading letter pair must be in this set
    corrigible: bool = True

    def prefix_ok(self, text: str, layout: str) -> bool:
        # The code gate applies to two-letter prefixes only (Bucharest's single "B" is in-pattern).
        if self.prefix_codes is None or not layout.startswith("LL"):
            return True
        return text[:2] in self.prefix_codes


def _v(pattern: str, layout: str) -> tuple[re.Pattern[str], str]:
    return re.compile(pattern), layout


# Ordered by priority — earlier formats win ambiguous reads (see docs for the collision notes:
# IT beats the textually identical FR; UA beats HR on the shared 8-char layout; HR's city-code
# gate beats NL's code-less 6-char sidecodes; UK plates may collide with RO county format).
FORMATS: tuple[PlateFormat, ...] = (
    PlateFormat("it_car", "IT", (_v(f"^{_L_IT}{_L_IT}{_D}{_D}{_D}{_L_IT}{_L_IT}$", "LLDDDLL"),)),
    PlateFormat("it_moto", "IT", (_v(f"^{_L_IT}{_L_IT}{_D}{_D}{_D}{_D}{_D}$", "LLDDDDD"),)),
    PlateFormat("it_moped", "IT", (_v(f"^{_D}{_D}{_L_IT}{_L_IT}{_L_IT}$", "DDLLL"),)),
    PlateFormat("fr_car", "FR", (_v(f"^{_L_FR}{_L_FR}{_D}{_D}{_D}{_L_FR}{_L_FR}$", "LLDDDLL"),)),
    PlateFormat("es_car", "ES", (_v(f"^{_D}{_D}{_D}{_D}{_L_ES}{_L_ES}{_L_ES}$", "DDDDLLL"),)),
    PlateFormat("ua_car", "UA", (
        _v(f"^{_L_UA}{_L_UA}{_D}{_D}{_D}{_D}{_L_UA}{_L_UA}$", "LLDDDDLL"),
    ), prefix_codes=UA_REGION_CODES),
    PlateFormat("hr_car", "HR", (
        _v(f"^[A-Z][A-Z]{_D}{_D}{_D}[A-Z][A-Z]$", "LLDDDLL"),
        _v(f"^[A-Z][A-Z]{_D}{_D}{_D}{_D}[A-Z][A-Z]$", "LLDDDDLL"),
        _v(f"^[A-Z][A-Z]{_D}{_D}{_D}[A-Z]$", "LLDDDL"),
        _v(f"^[A-Z][A-Z]{_D}{_D}{_D}{_D}[A-Z]$", "LLDDDDL"),
    ), prefix_codes=HR_CITY_CODES),
    PlateFormat("ro_car", "RO", (
        _v(f"^{_L_RO}{_L_RO}{_D}{_D}{_L_RO}{_L_RO}{_L_RO}$", "LLDDLLL"),
        _v(f"^B{_D}{_D}{_D}{_L_RO}{_L_RO}{_L_RO}$", "LDDDLLL"),   # Bucharest, 3 digits
        _v(f"^B{_D}{_D}{_L_RO}{_L_RO}{_L_RO}$", "LDDLLL"),        # Bucharest, 2 digits
    ), prefix_codes=RO_COUNTY_CODES),
    # NL sidecodes: nationally sequential; the layout itself dates the plate (see NL_SIDECODE_ERAS).
    PlateFormat("nl_car", "NL", (
        _v(f"^[A-Z]{{3}}{_D}{_D}[A-Z]$", "LLLDDL"),   # SC11 2024–
        _v(f"^[A-Z]{_D}{_D}{_D}[A-Z][A-Z]$", "LDDDLL"),  # SC10 2019–2024
        _v(f"^[A-Z][A-Z]{_D}{_D}{_D}[A-Z]$", "LLDDDL"),  # SC9 2015–2019
        _v(f"^{_D}[A-Z]{{3}}{_D}{_D}$", "DLLLDD"),    # SC8 2013–2015
        _v(f"^{_D}{_D}[A-Z]{{3}}{_D}$", "DDLLLD"),    # SC7 2008–2013
        _v(f"^{_D}{_D}[A-Z]{{4}}$", "DDLLLL"),        # SC6 1999–2008
        _v(f"^[A-Z]{{4}}{_D}{_D}$", "LLLLDD"),        # SC5 1991–1999
    ), corrigible=False),  # layout-only format: exact reads only, never a correction target
)

_FORMATS_BY_NAME = {f.name: f for f in FORMATS}

# Registration country implied by each format ("?" = unknown).
COUNTRIES: dict[str, str] = {f.name: f.country for f in FORMATS}

# NL sidecode eras, keyed by layout; None = still being issued ("now").
NL_SIDECODE_ERAS: dict[str, tuple[float, float | None]] = {
    "LLLLDD": (1991.0, 1999.0),
    "DDLLLL": (1999.0, 2008.0),
    "DDLLLD": (2008.0, 2013.0),
    "DLLLDD": (2013.0, 2015.2),
    "LLDDDL": (2015.2, 2019.2),
    "LDDDLL": (2019.2, 2024.4),
    "LLLDDL": (2024.4, None),  # SC11, since June 2024
}

# Position-aware OCR confusion maps (see spec).
DIGIT_TO_LETTER = {"0": "D", "1": "L", "2": "Z", "4": "A", "5": "S", "6": "G", "7": "T", "8": "B"}
LETTER_TO_DIGIT = {
    "O": "0", "D": "0", "Q": "0", "I": "1", "L": "1", "Z": "2",
    "A": "4", "S": "5", "G": "6", "T": "7", "B": "8",
}

_EU_GENERIC = re.compile(r"^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{4,8}$")

# A per-character OCR confidence at or above this bar is an "anchor": correction may not overwrite
# that glyph. Keeps garble from being rewritten into a format-valid plate on the strength of one
# confidently-wrong-shaped neighbour. Only applied when the caller passes per-char confidences.
ANCHOR_CONFIDENCE = 0.90


# ---- registration-series chronology (issue-date prior) --------------------------------------
#
# Sequential systems date a plate from its letters (see docs/plate-formats.md "Series
# chronology" for tables + sources). Two uses:
#  1. PRIOR: a series the country hasn't plausibly reached yet cannot be one of its plates —
#     reject it instead of letting confusion-correction fabricate it (e.g. a "5" in the first
#     slot must not become the unissued "SA…").
#  2. ESTIMATE: interpolate the pair index between dated checkpoints → registration year.
#
# FUTURE-PROOFING: the frontier is not a hardcoded constant. It is extrapolated from the
# checkpoints' recent issuance rate to "now" (injectable), with generous headroom — 1.5× the
# rate plus two second-letter steps — because rejecting a real plate is worse than accepting a
# fabricated one. Only the FIRST letter of the frontier is enforced. The epochs table only needs
# a refresh to keep *estimates* sharp; validity keeps working unattended.
#
# WHAT THE MODEL DOES AND DOESN'T ENCODE: registration *volume* (car sales) is what the
# checkpoints measure — irregular spacing (Italy's 5-year letter blocks, Spain's 2008-crisis
# stretch) is real sales history, and the frontier extrapolates the *recent observed* rate, not
# a forecast. Fleet *age/survival* (old series are rarer on the road because cars get scrapped)
# is deliberately NOT modeled: the prior is a hard gate, and hard-gating on age would reject
# legitimately old-but-alive cars. A soft fleet-age weight could someday refine *ranking*
# (e.g. canonical-read selection in dedup), never validity.

_RATE_MARGIN = 1.5   # tolerate issuance speeding up 50% before the prior could bite
_STEP_MARGIN = 2     # absolute slack, and the floor when the clock reads earlier than the data


def _year_now() -> float:
    today = _dt.date.today()
    return today.year + (today.timetuple().tm_yday - 1) / 365.25


@dataclass(frozen=True)
class SeriesSystem:
    """One country's sequential issuance: its letter alphabet + dated first-pair checkpoints."""

    letters: str
    epochs: tuple[tuple[str, float], ...]  # (letter pair, ~year issuance reached it), ascending

    def index(self, pair: str) -> int:
        return self.letters.index(pair[0]) * len(self.letters) + self.letters.index(pair[1])

    def _rate(self) -> float:
        """Second-letter steps per year — the faster of the ~5-year trend and the latest interval,
        so a country currently issuing above trend (post-slump recovery) can't outrun the prior."""
        i_last, t_last = self.index(self.epochs[-1][0]), self.epochs[-1][1]
        ref = self.epochs[0]
        for p, t in self.epochs:
            if t <= t_last - 5:
                ref = (p, t)
        i_ref, t_ref = self.index(ref[0]), ref[1]
        trend = (i_last - i_ref) / (t_last - t_ref)
        p_prev, t_prev = self.epochs[-2]
        if t_last - t_prev >= 0.8:  # too-short intervals make a noisy rate
            trend = max(trend, (i_last - self.index(p_prev)) / (t_last - t_prev))
        return trend

    def frontier(self, at_year: float) -> int:
        """Highest pair index plausibly issued by ``at_year`` (extrapolated + headroom)."""
        i_last, t_last = self.index(self.epochs[-1][0]), self.epochs[-1][1]
        ahead = max(0.0, at_year - t_last)  # a clock behind the data falls back to the last epoch
        return i_last + round(self._rate() * ahead * _RATE_MARGIN) + _STEP_MARGIN

    def plausible(self, pair: str, at_year: float) -> bool:
        """First-letter check: could this series exist by ``at_year``?"""
        if pair[0] not in self.letters or pair[1] not in self.letters:
            return False
        return self.letters.index(pair[0]) <= self.frontier(at_year) // len(self.letters)

    def estimate_year(self, pair: str, at_year: float) -> int | None:
        """Approximate issue year of a series, or None if it can't exist yet."""
        idx = self.index(pair)
        if idx > self.frontier(at_year):
            return None
        if idx <= self.index(self.epochs[0][0]):
            return round(self.epochs[0][1])
        for (p0, y0), (p1, y1) in zip(self.epochs, self.epochs[1:]):
            i0, i1 = self.index(p0), self.index(p1)
            if idx <= i1:
                return round(y0 + (idx - i0) / (i1 - i0) * (y1 - y0))
        i_last, t_last = self.index(self.epochs[-1][0]), self.epochs[-1][1]
        return round(t_last + (idx - i_last) / self._rate())  # beyond the last checkpoint


# Italy — AA=May 1994; yearly steps from the money.it table; HA=May 2025, ~HC mid-2026.
IT_CAR_SERIES = SeriesSystem(ITALIAN_LETTERS, (
    ("AA", 1994.4), ("BB", 1999.0), ("BX", 2002.0), ("CE", 2003.0), ("CR", 2005.0),
    ("CZ", 2006.0), ("DD", 2007.0), ("DM", 2008.0), ("DS", 2009.0), ("EA", 2010.0),
    ("EF", 2011.0), ("EK", 2012.0), ("EP", 2013.0), ("ET", 2014.0), ("FB", 2016.0),
    ("FH", 2017.0), ("FN", 2018.0), ("FT", 2019.0), ("GA", 2020.0), ("GD", 2021.0),
    ("GH", 2022.0), ("GK", 2023.0), ("GS", 2024.0), ("GX", 2025.0), ("HA", 2025.4),
    ("HC", 2026.5),
))

# France (SIV) — exact first-pair start dates from francoplaque.fr; ~6.5 steps/year.
FR_CAR_SERIES = SeriesSystem(FRENCH_LETTERS, (
    ("AA", 2009.29), ("BA", 2010.71), ("CA", 2012.02), ("DA", 2013.83),
    ("EA", 2016.16), ("FA", 2018.66), ("GA", 2021.45), ("HA", 2024.83),
))

# Spain — BBB=Sep 2000 and N=Apr 2025 are firm; interior checkpoints are ±1–2 years.
# The dated element is the letter triplet; its first two letters index the chronology.
ES_CAR_SERIES = SeriesSystem(SPANISH_LETTERS, (
    ("BB", 2000.7), ("CB", 2002.5), ("DB", 2004.3), ("FB", 2006.1), ("GB", 2008.1),
    ("HB", 2010.8), ("JB", 2013.5), ("KB", 2016.3), ("LB", 2018.7), ("MB", 2021.0),
    ("NB", 2025.3), ("NM", 2026.3),
))

# Which formats carry a pair-indexed chronology, and where the dated pair sits.
SERIES: dict[str, tuple[SeriesSystem, slice]] = {
    "it_car": (IT_CAR_SERIES, slice(0, 2)),
    "fr_car": (FR_CAR_SERIES, slice(0, 2)),
    "es_car": (ES_CAR_SERIES, slice(4, 6)),
}


class Confidence(Enum):
    EXACT = "exact"          # matched a known pattern with no correction
    CORRECTED = "corrected"  # matched only after OCR confusion correction
    GENERIC = "generic"      # matched the loose EU fallback only
    INVALID = "invalid"      # no match


@dataclass(frozen=True)
class PlateResult:
    raw: str
    normalized: str
    plate_type: str | None       # e.g. "it_car", or None if invalid
    confidence: Confidence

    @property
    def is_valid(self) -> bool:
        return self.confidence is not Confidence.INVALID

    @property
    def country(self) -> str:
        """ISO-2 registration country implied by the format; "?" when unknown."""
        return COUNTRIES.get(self.plate_type or "", "?")


def normalize(raw: str) -> str:
    """Uppercase and strip to A-Z0-9 only."""
    return re.sub(r"[^A-Z0-9]", "", raw.upper())


def _align_to_normalized(raw: str, conf: list[float]) -> list[float]:
    """Reduce ``conf`` (aligned to ``raw``, ASCII OCR alphabet) to the chars ``normalize`` keeps,
    so it stays index-aligned with the normalized text. Missing entries default to fully confident.
    """
    up = raw.upper()
    return [
        (conf[i] if i < len(conf) else 1.0)
        for i, c in enumerate(up)
        if c.isascii() and c.isalnum()
    ]


def estimate_registration_year(raw: str, country: str | None = None, at_year: float | None = None) -> int | None:
    """Approximate year a plate's series was issued, or None (unknown format / unissued series /
    a district-based system with no chronology).

    ``country`` ("IT"/"FR"/"ES"/"NL") picks the system when known (e.g. from a stored record);
    otherwise formats are tried in validation order — the IT/FR formats are textually identical,
    so ambiguous plates date as Italian. Dutch plates date by sidecode era (coarse: the layout
    changes every few years); HR/UA/RO are per-district and undatable from text.
    """
    norm = normalize(raw)
    at = at_year if at_year is not None else _year_now()
    for name, (system, where) in SERIES.items():
        if country is not None and COUNTRIES[name] != country:
            continue
        if any(p.match(norm) for p, _ in _FORMATS_BY_NAME[name].variants):
            return system.estimate_year(norm[where], at)
    if country in (None, "NL"):
        for pattern, layout in _FORMATS_BY_NAME["nl_car"].variants:
            if pattern.match(norm):
                y0, y1 = NL_SIDECODE_ERAS[layout]
                return round((y0 + (y1 if y1 is not None else at)) / 2)
    return None


def _coerce_to_layout(
    text: str,
    layout: str,
    conf: list[float] | None = None,
    anchor: float = ANCHOR_CONFIDENCE,
) -> tuple[str, int] | None:
    """Position-aware confusion correction.

    Returns (corrected_text, n_changes), or None on length mismatch — or when a change would fall on
    an anchor (a ``conf`` position >= ``anchor``): that glyph is trusted, so a layout reachable only
    by overwriting it is not a real candidate. Each correction is otherwise a *guess*, so callers
    also cap ``n_changes`` — structural validation alone cannot disambiguate, e.g. a real motorcycle
    plate ``AB12345`` is only two edits from the car plate ``AB123AS``.
    """
    if len(text) != len(layout):
        return None
    out: list[str] = []
    changes = 0
    for i, (ch, slot) in enumerate(zip(text, layout)):
        if slot == "L" and ch.isdigit():
            mapped = DIGIT_TO_LETTER.get(ch, ch)
        elif slot == "D" and ch.isalpha():
            mapped = LETTER_TO_DIGIT.get(ch, ch)
        else:
            mapped = ch
        if mapped != ch:
            if conf is not None and i < len(conf) and conf[i] >= anchor:
                return None
            changes += 1
        out.append(mapped)
    return "".join(out), changes


def validate(
    raw: str,
    *,
    accept_moto: bool = True,
    accept_moped: bool = False,
    accept_foreign: bool = True,
    allow_generic_eu: bool = False,
    enable_correction: bool = False,
    max_corrections: int = 2,
    series_prior: bool = True,
    at_year: float | None = None,
    char_confidences: list[float] | None = None,
    anchor_confidence: float = ANCHOR_CONFIDENCE,
) -> PlateResult:
    """Normalize, then try exact match, then bounded confusion-corrected match, then EU fallback.

    ``max_corrections`` caps how many characters confusion-correction may change before a candidate
    is rejected, limiting fabricated matches. Corrected reads are flagged ``Confidence.CORRECTED``
    so downstream multi-frame voting / per-char OCR confidence can prefer exact reads.

    ``series_prior`` applies the issue-date prior to the sequential systems (IT/FR/ES): plates
    aren't uniformly probable — a candidate whose series its country can't plausibly have issued
    by ``at_year`` (default: now, extrapolated with headroom from the chronology) is rejected
    rather than matched, both as an exact read and as a correction target. The district systems
    (HR/UA/RO) are gated by their closed prefix-code sets instead.

    ``accept_foreign`` enables the foreign formats (FR/ES/NL/HR/UA/RO). Format order resolves the
    text-level ambiguities (see docs/plate-formats.md): IT beats the identical FR shape; UA beats
    HR on the shared 8-char layout; HR's city codes beat NL's code-less sidecodes.

    ``char_confidences`` (aligned to ``raw``, from the OCR decoder) anchors correction: a position
    >= ``anchor_confidence`` is trusted and never overwritten, so only genuinely uncertain glyphs
    are correction candidates. None keeps the pure-structural behaviour used by the parity tests.

    ``enable_correction`` defaults to **False** (2026-07-06): measured against real footage with a
    faithful port of the Kotlin ``DedupEngine``, the correction step accounted for 0 of 24 real
    promotions on the current OCR model — exact reads always arrived and always outrank a
    corrected one in the same dedup cluster. Kept in the code (not deleted) since the sample was
    only 3 clips; pass ``enable_correction=True`` to re-enable while investigating further. See
    docs/model-specs.md.
    """
    norm = normalize(raw)
    norm_conf = _align_to_normalized(raw, char_confidences) if char_confidences is not None else None
    at = at_year if at_year is not None else _year_now()

    enabled = ["it_car"]
    if accept_moto:
        enabled.append("it_moto")
    if accept_moped:
        enabled.append("it_moped")
    if accept_foreign:
        enabled += ["fr_car", "es_car", "ua_car", "hr_car", "ro_car", "nl_car"]

    def series_ok(name: str, text: str) -> bool:
        if not series_prior or name not in SERIES:
            return True
        system, where = SERIES[name]
        return system.plausible(text[where], at)

    # 1) exact match on normalized text (across all enabled formats first)
    for name in enabled:
        fmt = _FORMATS_BY_NAME[name]
        for pattern, layout in fmt.variants:
            if pattern.match(norm) and fmt.prefix_ok(norm, layout) and series_ok(name, norm):
                return PlateResult(raw, norm, name, Confidence.EXACT)

    # 2) bounded position-aware correction; prefer the candidate needing the fewest changes.
    # Disabled by default — see `enable_correction` doc above.
    if enable_correction:
        best: tuple[int, str, str] | None = None  # (n_changes, name, corrected)
        for name in enabled:
            fmt = _FORMATS_BY_NAME[name]
            if not fmt.corrigible:
                continue
            for pattern, layout in fmt.variants:
                result = _coerce_to_layout(norm, layout, norm_conf, anchor_confidence)
                if result is None:
                    continue
                corrected, changes = result
                if changes == 0 or changes > max_corrections:
                    continue
                if (
                    pattern.match(corrected)
                    and fmt.prefix_ok(corrected, layout)
                    and series_ok(name, corrected)
                    and (best is None or changes < best[0])
                ):
                    best = (changes, name, corrected)
        if best is not None:
            return PlateResult(raw, best[2], best[1], Confidence.CORRECTED)

    # 3) loose EU fallback (only when explicitly allowed)
    if allow_generic_eu and _EU_GENERIC.match(norm):
        return PlateResult(raw, norm, "eu_generic", Confidence.GENERIC)

    return PlateResult(raw, norm, None, Confidence.INVALID)


if __name__ == "__main__":
    samples = [
        "AB 123 CD", "A8I23CD", "GA456KX", "AQ-123-BC", "1234 BCD",
        "PDA-01-D", "GB-123-X", "ZG 1234-AB", "PU 123-AB", "BC 1234 AX", "CJ 12 ABC", "B 123 ABC",
        "12ABC", "HELLO",
    ]
    for s in samples:
        r = validate(s, accept_moped=True)
        year = estimate_registration_year(r.normalized, country=r.country if r.country != "?" else None)
        print(f"{s!r:14} -> {r.normalized:9} {str(r.plate_type):9} {r.country} (~{year})  ({r.confidence.value})")
