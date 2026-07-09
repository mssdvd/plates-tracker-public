// Shared reactive app state (Svelte 5 runes in a .svelte.js module).

// Filter set — mirrors the backend's shared filters; '' means "not applied".
export const filters = $state({
  since: '',
  until: '',
  plate: '',
  country: '',
  minSpeed: '',
  maxSpeed: '',
  minConfidence: '',
  minCount: '',
})

export const data = $state({
  records: [],
  stats: null,
  loading: false,
  error: '',
})

export const auth = $state({
  token: localStorage.getItem('pt_token') || '',
})

// UI state that isn't a backend filter: plate drill-down + map layer toggles.
export const ui = $state({
  selectedPlate: '', // '' = no detail panel; set by map/top-plates clicks
  flyTo: null, // [lon, lat] one-shot request for the map
  showHeatmap: true,
  showPoints: true,
})

export function resetFilters() {
  for (const k of Object.keys(filters)) filters[k] = ''
}
