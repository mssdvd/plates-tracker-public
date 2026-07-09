# Plate formats & validation spec

This is the **canonical spec** for plate validation. The Python reference implementation lives in
`shared/plate_validation.py`; the Android app reimplements the same rules in Kotlin. Keep the two
in sync — this doc is the source of truth.

## Alphabet

Italian plates use a **22-letter alphabet**: `A–Z` **excluding `I`, `O`, `Q`, `U`**
(they are skipped to avoid confusion with digits / each other).

```
ABCDEFGHJKLMNPRSTVWXYZ
```

## Supported formats (Italy)

| Type            | Pattern (after normalization) | Example     | Notes |
|-----------------|-------------------------------|-------------|-------|
| Car (1994–)     | `L L D D D L L`               | `AB123CD`   | Primary target. Letters from the 22-letter alphabet. |
| Motorcycle      | `L L D D D D D`               | `AB12345`   | Two letters + five digits. |
| Moped/scooter   | `D D L L L`                   | `12ABC`     | Older small-vehicle format; lower priority. |

## Supported formats (foreign)

| Type              | Pattern (after normalization) | Example    | Gate | Notes |
|-------------------|-------------------------------|------------|------|-------|
| France SIV (2009–)| `LL DDD LL`                   | `AQ123BC`  | series chronology | 23 letters (no I/O/U — **Q allowed**). Same shape as the Italian car format! |
| Spain (2000–)     | `DDDD LLL`                    | `1234BCD`  | series chronology | 20 consonants (no vowels/Ñ/Q); the letter triplet is the counter. |
| Netherlands       | 7 sidecode layouts (6 chars)  | `PDA01D`   | layout = era | Nationally sequential; the *layout itself* dates the plate (see eras below). **Exact reads only** — a bare layout is too weak a prior to be a correction target. |
| Croatia           | `LL DDD(D) L(L)`              | `ZG1234AB` | 34 city codes | ASCII-fied codes (ČK→CK, ŠI→SI, VŽ→VZ…); no chronology. |
| Ukraine (2004–)   | `LL DDDD LL`                  | `BC1234AX` | region codes + 12-letter alphabet | Only Cyrillic/Latin lookalikes `A B C E H I K M O P T X`; 2004 codes + 2013 re-issue (A→K, B→H, C→I); no chronology. |
| Romania           | `LL DD LLL`, `B DD(D) LLL`    | `CJ12ABC`  | 42 county codes | No `Q` anywhere; Bucharest is the single-letter `B` with 2–3 digits; no chronology. |

**Dutch sidecode eras** (the dating source for NL): SC5 `XXXX99` 1991–99 · SC6 `99XXXX` 1999–2008
· SC7 `99XXX9` 2008–13 · SC8 `9XXX99` 2013–15 · SC9 `XX999X` 2015–19 · SC10 `X999XX` 2019–24 ·
SC11 `XXX99X` Jun 2024–. Estimates return the era midpoint (coarse by design).

> ⚠️ **Text-level ambiguities are fundamental** — the country band isn't in the OCR text. Format
> order resolves them, and earlier formats win:
> - **IT beats FR** (identical `LLDDDLL`, both around H): only a `Q` (which Italy skips, ≈16 % of
>   French plates) positively identifies France.
> - **IT/FR beat HR** on `LLDDDLL`: `BJ123AB` (Bjelovar) reads as Italian; Croatian codes beyond
>   the IT/FR series frontier (`PU`, `ST`, `RI`, `ZG`…) fall through correctly to Croatia.
> - **UA beats HR** on the shared `LLDDDDLL`: the few city codes inside Ukraine's alphabet *and*
>   region set (`KA`, `KC`, `KT`, `MA`, `CK`) resolve as Ukrainian.
> - **HR beats NL** on `LLDDDL` (its city-code gate is stronger than a bare layout).
> - **UK plates** (`LLDDLLL`, not in scope) collide with the Romanian county format when the
>   prefix is also a county code (e.g. `AB12CDE` → Alba) — a known accepted limitation.
>
> The rest of the EU (DE/CH/AT/SI…) uses district-based, variable-length systems with no closed
> text-level gate: they stay behind the generic-EU fallback. (A Swiss `ZH12345` even collides
> with the Italian moto format — another reason text-only country detection stays conservative.)

> Only the **car** format is required for v1. Moto/moped are recognized opportunistically; the
> validator can be configured to accept/reject them (`accept_moto`, `accept_moped`,
> `accept_foreign`).

## Normalization

1. Uppercase.
2. Strip everything that isn't `A–Z` or `0–9` (spaces, dashes, the blue EU band text, province
   codes, the year roundel).
3. Drop a leading `I` country prefix only if it's clearly the EU band code (handled by the
   detector cropping, not here — be conservative).

## OCR confusion correction (position-aware)

Because the letter/digit positions are fixed, mis-reads can be corrected by slot:

- **Letter slots** — map stray digits to their look-alike letters:
  `0→D, 1→L, 2→Z, 4→A, 5→S, 6→G, 7→T, 8→B`
- **Digit slots** — map stray letters to their look-alike digits:
  `O→0, D→0, Q→0, I→1, L→1, Z→2, A→4, S→5, G→6, T→7, B→8`

Correction is **best-effort** and flagged: a corrected read is marked lower-confidence than a
read that validated as-is. Never invent characters to pad length — a wrong-length read is dropped.

## Series chronology (issue-date prior)

Italian, French (SIV) and Spanish plates are issued **sequentially**, so the dated letter pair
(first pair for IT/FR, first two triplet letters for ES) puts a plate on a timeline. That makes
plate text far from uniformly probable, and the validator uses it two ways:

1. **Prior (validation):** a candidate whose series its country can't plausibly have issued yet
   is rejected — as an exact read (a real `MA123CD` is neither Italian nor French: both are
   around H) and especially as a correction target: a stray `5` or `1` in the first slot must
   never be "corrected" into the unissued `SA…`/`LA…`.
2. **Estimate:** interpolating the pair index between dated checkpoints gives an approximate
   registration year (`estimate_registration_year`), e.g. IT `EW…` ≈ 2014, FR `GA…` ≈ 2021,
   ES `…MBB` ≈ 2021.

**What the model does and doesn't encode.** Registration *volume* (car sales) is what the
checkpoints measure: their irregular spacing — Italy's ~5-year letter blocks, Spain's
2008-crisis stretch and current fast N series — *is* the sales history, and estimates
interpolate through it. The frontier extrapolates the *recent observed* rate (not a sales
forecast) with headroom. Fleet *age/survival* (old series are rarer on the road because cars
get scrapped) is deliberately **not** modeled: the prior is a hard validity gate, and gating on
age would reject legitimately old-but-alive cars. A soft fleet-age weight could someday refine
*ranking* (e.g. canonical-read selection in dedup), never validity.

**Future-proofing — the frontier is computed, not configured.** There is no constant to bump:
the validator extrapolates each chronology's recent issuance rate (the faster of the ~5-year
trend and the latest interval) from its last checkpoint to *now*, with generous headroom (1.5×
rate + 2 steps), and gates on the **first letter** of that frontier. Rejecting a real plate is
worse than accepting a fabricated one, so the gate always opens a new letter block *early*
(e.g. Italian `J` becomes acceptable ~2029, likely a year before real `JA` plates appear). A
device clock behind the checkpoint data falls back to the last checkpoint — it can never reject
what the table already knows exists. Refreshing the epochs tables (~yearly) keeps *estimates*
sharp but is never needed for validity.

**Italy** — first-pair checkpoints (year the series was being issued):

| 1994 | 1999 | 2002 | 2003 | 2005 | 2006 | 2007 | 2008 | 2009 | 2010 | 2011 | 2012 | 2013 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| AA | BB | BX | CE | CR | CZ | DD | DM | DS | EA | EF | EK | EP |

| 2014 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | mid-2026 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ET | FB | FH | FN | FT | GA | GD | GH | GK | GS | GX→HA | HC |

**France (SIV)** — exact first-pair start dates (francoplaque.fr); ~6.5 steps/year:

| Apr 2009 | Sep 2010 | Jan 2012 | Oct 2013 | Feb 2016 | Aug 2018 | Jun 2021 | Oct 2024 |
|---|---|---|---|---|---|---|---|
| AA | BA | CA | DA | EA | FA | GA | HA |

**Spain** — triplet-letter checkpoints. `BBB` (Sep 2000) and `N` (Apr 2025) are firm; the
interior points are ±1–2 years (the 2008 crisis slowed issuance, the current N series is
running fast):

| 2000 | 2002 | 2004 | 2006 | 2008 | 2011 | 2013 | 2016 | 2019 | 2021 | Apr 2025 | Apr 2026 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| BB | CB | DB | FB | GB | HB | JB | KB | LB | MB | NB | NM |

> The Italian moto/moped formats have their own (different) chronologies; no reliable public
> table was found, so the prior applies to the **car** formats only.

## EU / other countries

France, Spain and the Netherlands are first-class because their systems are sequential and
datable; Croatia, Ukraine and Romania because their closed prefix-code sets are a strong
text-level gate. Everything else falls to the **configurable** `EU_GENERIC` fallback (4–8
alphanumerics with at least one letter and one digit, used only when no country-specific pattern
matches and strict mode is off) — district systems without a compact code set (DE/CH/AT/SI…)
carry no usable prior.

## Sources
- [Vehicle registration plates of Italy — Wikipedia](https://en.wikipedia.org/wiki/Vehicle_registration_plates_of_Italy)
  (22-letter alphabet excluding I/O/Q/U; `LL DDD LL` since 1994).
- [Targhe d'immatricolazione dell'Italia — Wikipedia](https://it.wikipedia.org/wiki/Targhe_d%27immatricolazione_dell%27Italia)
  (AA = 25 May 1994, GA = Dec 2019, HA = May 2025; sequential assignment order).
- [Money.it — targhe: tabella serie/anno (apr 2026)](https://www.money.it/sai-cosa-significano-lettere-numeri-targhe-auto)
  (yearly series checkpoints 1999–2026; 2026 ≈ HA–HC).
- [Motor1.it — nuove targhe GA (2020)](https://it.motor1.com/news/399271/nuove-targhe-auto-ga-in-pensione-fz/)
  (F→G transition, ~one first letter every 5 years).
- [Francoplaque — datation des plaques SIV](https://francoplaque.fr/site_html/f_new3.html)
  (exact SIV first-pair start dates AA 2009 → HA Oct 2024; ~8 weeks per second letter).
- [Mesplaques.fr — série H (2024)](https://www.mesplaques.fr/blog/immatriculation-francaise-plaque-serie-h/)
  (GZ→HA transition; SIV alphabet excludes I/O/U, skips SS/WW).
- [Dealcar — última matrícula DGT (2026)](https://dealcar.io/blog/ultima-matricula-dgt)
  (Spain ~NLZ/NMB in Apr 2026; system basics).
- [Fechasmatriculas.com](https://fechasmatriculas.com/fyj.html) (Spanish series dating, e.g.
  FYJ = Dec 2007); N series since Apr 2025, P expected ~2027 (DGT via press).
- [Vehicle registration plates of the Netherlands — Wikipedia](https://en.wikipedia.org/wiki/Vehicle_registration_plates_of_the_Netherlands)
  + [alpina.nl](https://www.alpina.nl/en/car-insurance/registration-mark/last-license-plate/)
  (sidecode formats; SC11 `XXX-99-X` since 4 Jun 2024, first plate PDA-01-D).
- [Vehicle registration plates of Croatia — Wikipedia](https://en.wikipedia.org/wiki/Vehicle_registration_plates_of_Croatia)
  (city code + 3–4 digits + 1–2 letters; the ~34 city codes incl. diacritic ones).
- [Vehicle registration plates of Ukraine — Wikipedia](https://en.wikipedia.org/wiki/Vehicle_registration_plates_of_Ukraine)
  (2 letters + 4 digits + 2 letters; Latin-lookalike alphabet; 2004 regions + 2013 re-issue).
- [Vehicle registration plates of Romania — Wikipedia](https://en.wikipedia.org/wiki/Vehicle_registration_plates_of_Romania)
  (county code + 2 digits + 3 letters, Bucharest B + 2–3 digits; no Q; per-county numbering).
