# Webapp (Phase 3)

Svelte + MapLibre GL dashboard: a map of plate sightings with **live stats** and **filters** that
drive both. Talks to the Go backend (`../server`).

## Dev

```bash
# 1. start the backend (separate terminal)
cd ../server && API_TOKEN=devtoken go run .

# 2. start the webapp (Vite proxies /api → http://localhost:8000)
npm install
npm run dev            # http://localhost:5173
```

Paste the same `API_TOKEN` into the token box in the header (stored in localStorage). The dev
server proxies `/api/*` to the backend, so there's no CORS to configure.

## Build / deploy (single binary)

```bash
npm run build                                   # → dist/
cd ../server
API_TOKEN=… WEB_DIR=../webapp/dist go run .      # serves the app at / AND the API
```

The Go server serves `dist/` at `/` with SPA fallback, so the whole thing ships as one binary.

## Structure

| File | Role |
|---|---|
| `src/lib/state.svelte.js` | Shared reactive state (filters, data, auth) — Svelte 5 runes |
| `src/lib/api.js` | Builds query params from filters; fetches `/records` + `/stats` |
| `src/lib/Filters.svelte` | Date range, plate search, type, speed, confidence, **seen-more-than-once** |
| `src/lib/MapView.svelte` | MapLibre map (OSM raster tiles, heatmap + points, click popups) |
| `src/lib/Stats.svelte` | Totals, by-hour + recent-days bars, most-seen plates |
| `src/App.svelte` | Layout + debounced refetch when filters/token change |

Filters map 1:1 to backend query params, and **both** the map and stats reflect the active filters
(e.g. "only plates seen more than once" = `min_count=2`).

## Notes
- Map tiles come from OpenStreetMap (no API key). For heavy use, switch to a vector tile provider.
- The MapLibre bundle is ~1 MB — fine for a personal tool; code-split later if it matters.
