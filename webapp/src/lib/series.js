// Plate series → approximate registration year. Mirror of shared/plate_validation.py
// (docs/plate-formats.md "Series chronology"): IT/FR/ES issue plates sequentially, so the dated
// letter pair puts a plate on a timeline. Beyond the last checkpoint we extrapolate at the
// recent issuance rate, capped at "now" — the display equivalent of the validator's frontier.

const SYSTEMS = {
  IT: {
    letters: 'ABCDEFGHJKLMNPRSTVWXYZ',
    pattern: /^[A-Z]{2}\d{3}[A-Z]{2}$/,
    pairAt: 0,
    epochs: [
      ['AA', 1994.4], ['BB', 1999], ['BX', 2002], ['CE', 2003], ['CR', 2005],
      ['CZ', 2006], ['DD', 2007], ['DM', 2008], ['DS', 2009], ['EA', 2010],
      ['EF', 2011], ['EK', 2012], ['EP', 2013], ['ET', 2014], ['FB', 2016],
      ['FH', 2017], ['FN', 2018], ['FT', 2019], ['GA', 2020], ['GD', 2021],
      ['GH', 2022], ['GK', 2023], ['GS', 2024], ['GX', 2025], ['HA', 2025.4],
      ['HC', 2026.5],
    ],
  },
  FR: {
    letters: 'ABCDEFGHJKLMNPQRSTVWXYZ',
    pattern: /^[A-Z]{2}\d{3}[A-Z]{2}$/,
    pairAt: 0,
    epochs: [
      ['AA', 2009.29], ['BA', 2010.71], ['CA', 2012.02], ['DA', 2013.83],
      ['EA', 2016.16], ['FA', 2018.66], ['GA', 2021.45], ['HA', 2024.83],
    ],
  },
  ES: {
    letters: 'BCDFGHJKLMNPRSTVWXYZ',
    pattern: /^\d{4}[A-Z]{3}$/,
    pairAt: 4,
    epochs: [
      ['BB', 2000.7], ['CB', 2002.5], ['DB', 2004.3], ['FB', 2006.1], ['GB', 2008.1],
      ['HB', 2010.8], ['JB', 2013.5], ['KB', 2016.3], ['LB', 2018.7], ['MB', 2021.0],
      ['NB', 2025.3], ['NM', 2026.3],
    ],
  },
}

function estimate(sys, pair) {
  const idx = (p) => sys.letters.indexOf(p[0]) * sys.letters.length + sys.letters.indexOf(p[1])
  if (sys.letters.indexOf(pair[0]) < 0 || sys.letters.indexOf(pair[1]) < 0) return null
  const i = idx(pair)
  const last = sys.epochs.at(-1)
  if (i <= idx(sys.epochs[0][0])) return Math.round(sys.epochs[0][1])
  for (let k = 1; k < sys.epochs.length; k++) {
    const [p0, y0] = sys.epochs[k - 1]
    const [p1, y1] = sys.epochs[k]
    if (i <= idx(p1)) {
      const i0 = idx(p0)
      return Math.round(y0 + ((i - i0) / (idx(p1) - i0)) * (y1 - y0))
    }
  }
  // Beyond the last checkpoint: extrapolate at the recent rate, but never past "now".
  const ref = sys.epochs.at(-4) ?? sys.epochs[0]
  const rate = (idx(last[0]) - idx(ref[0])) / (last[1] - ref[1])
  const now = new Date().getFullYear() + 0.5
  const year = last[1] + (i - idx(last[0])) / rate
  return year > now + 1 ? null : Math.round(Math.min(year, now))
}

// Dutch sidecodes: the layout itself dates the plate (era midpoint; open era → up to now).
const NL_SIDECODES = [
  [/^[A-Z]{3}\d{2}[A-Z]$/, 2024.4, null],
  [/^[A-Z]\d{3}[A-Z]{2}$/, 2019.2, 2024.4],
  [/^[A-Z]{2}\d{3}[A-Z]$/, 2015.2, 2019.2],
  [/^\d[A-Z]{3}\d{2}$/, 2013.0, 2015.2],
  [/^\d{2}[A-Z]{3}\d$/, 2008.0, 2013.0],
  [/^\d{2}[A-Z]{4}$/, 1999.0, 2008.0],
  [/^[A-Z]{4}\d{2}$/, 1991.0, 1999.0],
]

/**
 * Approximate year a plate's series was issued, or null. `country` (from the stored record)
 * picks the system; without it, formats are tried in validation order (ambiguous LLDDDLL → IT).
 * HR/UA/RO are district-based — no chronology, always null.
 */
export function registrationYear(plate, country = null) {
  const norm = String(plate).toUpperCase().replace(/[^A-Z0-9]/g, '')
  for (const [code, sys] of Object.entries(SYSTEMS)) {
    if (country != null && code !== country) continue
    if (sys.pattern.test(norm)) return estimate(sys, norm.slice(sys.pairAt, sys.pairAt + 2))
  }
  if (country == null || country === 'NL') {
    for (const [pattern, y0, y1] of NL_SIDECODES) {
      if (pattern.test(norm)) return Math.round((y0 + (y1 ?? new Date().getFullYear() + 0.5)) / 2)
    }
  }
  return null
}
