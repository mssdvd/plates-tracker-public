# Backend (Phase 2)

Private single-user API that stores plate sightings from the phone and serves them to the webapp.
**Go** (stdlib `net/http`), SQLite via the pure-Go `modernc.org/sqlite` driver (no cgo). Single
binary, no runtime deps.

> Built on a parallel track while the Phase 0 read-quality spike runs. The sighting **schema may
> get minor tweaks** once the spike tells us what's worth storing (e.g. region, crop hash).

## Run

```bash
API_TOKEN=$(openssl rand -hex 16) go run .          # listens on :8000
# or build a static binary:
go build -o plates-server . && API_TOKEN=… ./plates-server
```

Env: `API_TOKEN` (required, the bearer token), `DB_PATH` (default `./plates.db`), `PORT` (default
`8000`). For Postgres later, swap the driver/DSN (the SQL is standard; bbox uses plain columns).

## Test

```bash
go test ./...     # 12 tests, isolated temp SQLite per test
go vet ./...
```

## API

All endpoints except `/health` require `Authorization: Bearer $API_TOKEN`.

|Method|Path                  |Purpose                                                                                                   |
|------|----------------------|----------------------------------------------------------------------------------------------------------|
|GET   |`/health`             |Liveness (no auth)                                                                                        |
|POST  |`/records`            |**Idempotent** batch insert (`INSERT OR IGNORE` on client `id`) → `{received, inserted, duplicates}`      |
|GET   |`/records`            |List; filter by bbox (`min_lat/min_lon/max_lat/max_lon`) + time (`since/until`, RFC3339), `limit` (≤10000)|
|GET   |`/stats`              |Totals, unique plates, per-day / per-hour histograms, `top_plates` (`?top=`)                              |

### Sighting JSON
`id` (client UUID = idempotency key), `plate_text`, `read_kind?`, `confidence`,
`captured_at` (RFC3339 UTC), `lat`, `lon`, `speed_kmh?`, `accuracy_m?`, `source_device?`. Server
adds `created_at`. **No image is stored** (fully-on-device decision).

2026-07-09: `plate_type` and the separate `unconfirmed_reads` table/endpoints (added 2026-07-06,
for reads that passed on-device format validation but never cleared `DedupEngine`'s frame/
confidence gate) are retired along with that on-device format-regex gate — see
`docs/model-specs.md`. `country` now comes from the OCR model's own region-classification head
instead of the matched plate format.

## Notes
- Bounding-box filtering uses plain indexed lat/lon columns — no PostGIS needed at personal scale.
- The phone's WorkManager sync posts batches and retries; `INSERT OR IGNORE` makes retries safe and
  also dedups repeated ids inside a single batch.
