// API client for the Go backend (via the /api dev proxy, or same-origin in prod).

const PARAM_MAP = {
  since: 'since',
  until: 'until',
  plate: 'plate',
  country: 'country',
  minSpeed: 'min_speed',
  maxSpeed: 'max_speed',
  minConfidence: 'min_confidence',
  minCount: 'min_count',
}

function toQuery(filters) {
  const p = new URLSearchParams()
  for (const [key, apiKey] of Object.entries(PARAM_MAP)) {
    let v = filters[key]
    if (v === '' || v == null) continue
    // datetime-local values are local time → send RFC3339 UTC.
    if (key === 'since' || key === 'until') v = new Date(v).toISOString()
    p.set(apiKey, v)
  }
  return p
}

/** Full history of one plate (exact text), newest first — ignores the active filters. */
export async function fetchPlateHistory(plate, token) {
  const qs = new URLSearchParams({ plate, limit: '1000' })
  const res = await fetch(`/api/records?${qs}`, { headers: { Authorization: `Bearer ${token}` } })
  if (!res.ok) throw new Error(`records: HTTP ${res.status}`)
  // The backend matches substrings; keep only exact hits.
  return (await res.json()).filter((r) => r.plate_text === plate)
}

const CSV_COLUMNS = [
  'id', 'plate_text', 'read_kind', 'confidence', 'captured_at',
  'lat', 'lon', 'speed_kmh', 'accuracy_m', 'country', 'source_device',
]

/** The currently shown records as a CSV string (same columns as the wire contract). */
export function recordsToCsv(records) {
  const escape = (v) => {
    if (v == null) return ''
    const s = String(v)
    return /[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s
  }
  const rows = records.map((r) => CSV_COLUMNS.map((c) => escape(r[c])).join(','))
  return [CSV_COLUMNS.join(','), ...rows].join('\n')
}

export async function fetchAll(filters, token) {
  const qs = toQuery(filters)
  const headers = { Authorization: `Bearer ${token}` }
  const recQs = new URLSearchParams(qs)
  recQs.set('limit', '5000')

  const [recRes, stRes] = await Promise.all([
    fetch(`/api/records?${recQs}`, { headers }),
    fetch(`/api/stats?${qs}`, { headers }),
  ])
  if (recRes.status === 401 || stRes.status === 401) throw new Error('Unauthorized — check your API token')
  if (!recRes.ok) throw new Error(`records: HTTP ${recRes.status}`)
  if (!stRes.ok) throw new Error(`stats: HTTP ${stRes.status}`)
  return { records: await recRes.json(), stats: await stRes.json() }
}
