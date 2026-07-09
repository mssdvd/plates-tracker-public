// plates-tracker backend — private, single-user record store + query API.
//
// Stores plate sightings (text, time, location) synced from the phone and serves them to the
// webapp's map + stats. SQLite via the pure-Go modernc.org/sqlite driver (no cgo). Auth is a
// single bearer token (API_TOKEN).
//
// Run:  API_TOKEN=secret go run .
package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// Sighting is one plate observation. ID is a client-generated UUID and the idempotency key.
type Sighting struct {
	ID           string    `json:"id"`
	PlateText    string    `json:"plate_text"`
	ReadKind     *string   `json:"read_kind,omitempty"`
	Confidence   float64   `json:"confidence"`
	CapturedAt   time.Time `json:"captured_at"`
	Lat          float64   `json:"lat"`
	Lon          float64   `json:"lon"`
	SpeedKmh     *float64  `json:"speed_kmh,omitempty"`
	AccuracyM    *float64  `json:"accuracy_m,omitempty"`
	Country      *string   `json:"country,omitempty"` // ISO-2 of plate registration, e.g. "IT"
	SourceDevice *string   `json:"source_device,omitempty"`
	CreatedAt    time.Time `json:"created_at,omitempty"`
}

func (s Sighting) validate() error {
	switch {
	case s.ID == "":
		return errors.New("id is required")
	case s.PlateText == "":
		return errors.New("plate_text is required")
	case s.Lat < -90 || s.Lat > 90:
		return errors.New("lat out of range")
	case s.Lon < -180 || s.Lon > 180:
		return errors.New("lon out of range")
	case s.CapturedAt.IsZero():
		return errors.New("captured_at is required")
	}
	return nil
}

type server struct {
	db     *sql.DB
	token  string
	webDir string // optional: serve the built webapp (dist/) for a single-binary deploy
}

const schema = `
CREATE TABLE IF NOT EXISTS sightings (
	id            TEXT PRIMARY KEY,
	plate_text    TEXT NOT NULL,
	read_kind     TEXT,
	confidence    REAL NOT NULL,
	captured_at   TEXT NOT NULL,
	lat           REAL NOT NULL,
	lon           REAL NOT NULL,
	speed_kmh     REAL,
	accuracy_m    REAL,
	country       TEXT,
	source_device TEXT,
	created_at    TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sightings_plate   ON sightings(plate_text);
CREATE INDEX IF NOT EXISTS idx_sightings_country ON sightings(country);
CREATE INDEX IF NOT EXISTS idx_sightings_time  ON sightings(captured_at);
CREATE INDEX IF NOT EXISTS idx_sightings_lat   ON sightings(lat);
CREATE INDEX IF NOT EXISTS idx_sightings_lon   ON sightings(lon);

-- 2026-07-09: plate_type column and the unconfirmed_reads table (reads that passed plate-format
-- validation but never cleared DedupEngine's frame/confidence gate on-device, 2026-07-06) are
-- retired along with the on-device format-regex gate itself — see docs/model-specs.md. No
-- migration for plate_type: nothing has ever synced from the field (see the project handoff doc),
-- so this schema is only ever applied fresh — a stale local plates.db from earlier dev/testing
-- should just be deleted, not migrated.
DROP TABLE IF EXISTS unconfirmed_reads;
`

func newServer(db *sql.DB, token string) (*server, error) {
	if _, err := db.Exec(schema); err != nil {
		return nil, err
	}
	return &server{db: db, token: token}, nil
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)
	mux.HandleFunc("POST /records", s.auth(s.uploadRecords))
	mux.HandleFunc("GET /records", s.auth(s.listRecords))
	mux.HandleFunc("GET /stats", s.auth(s.stats))
	// The webapp calls /api/* (its dev server proxies that prefix to us); serve the same
	// handlers there so the single-binary deploy works without a rewriting proxy in front.
	mux.HandleFunc("POST /api/records", s.auth(s.uploadRecords))
	mux.HandleFunc("GET /api/records", s.auth(s.listRecords))
	mux.HandleFunc("GET /api/stats", s.auth(s.stats))
	// Optional: serve the built webapp at / (more specific API routes above take precedence).
	if s.webDir != "" {
		mux.HandleFunc("GET /", s.serveWebapp)
	}
	return mux
}

// serveWebapp serves static files from webDir, falling back to index.html (SPA routing).
func (s *server) serveWebapp(w http.ResponseWriter, r *http.Request) {
	p := filepath.Join(s.webDir, filepath.Clean(r.URL.Path))
	if st, err := os.Stat(p); err == nil && !st.IsDir() {
		http.ServeFile(w, r, p)
		return
	}
	http.ServeFile(w, r, filepath.Join(s.webDir, "index.html"))
}

// auth wraps a handler with bearer-token checking.
func (s *server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.token == "" {
			httpError(w, http.StatusInternalServerError, "server misconfigured: API_TOKEN not set")
			return
		}
		if r.Header.Get("Authorization") != "Bearer "+s.token {
			httpError(w, http.StatusUnauthorized, "missing or invalid bearer token")
			return
		}
		next(w, r)
	}
}

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// uploadRecords does an idempotent batch insert: re-uploading the same client ids is a no-op.
func (s *server) uploadRecords(w http.ResponseWriter, r *http.Request) {
	var recs []Sighting
	if err := json.NewDecoder(r.Body).Decode(&recs); err != nil {
		httpError(w, http.StatusBadRequest, "invalid JSON body: "+err.Error())
		return
	}
	for _, rec := range recs {
		if err := rec.validate(); err != nil {
			httpError(w, http.StatusBadRequest, err.Error())
			return
		}
	}

	tx, err := s.db.Begin()
	if err != nil {
		httpError(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer tx.Rollback()

	// INSERT OR IGNORE makes both retried uploads and intra-batch dup ids no-ops.
	stmt, err := tx.Prepare(`INSERT OR IGNORE INTO sightings
		(id, plate_text, read_kind, confidence, captured_at, lat, lon, speed_kmh, accuracy_m, country, source_device, created_at)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`)
	if err != nil {
		httpError(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer stmt.Close()

	now := time.Now().UTC().Format(time.RFC3339)
	inserted := 0
	for _, rec := range recs {
		res, err := stmt.Exec(rec.ID, rec.PlateText, rec.ReadKind, rec.Confidence,
			rec.CapturedAt.UTC().Format(time.RFC3339), rec.Lat, rec.Lon, rec.SpeedKmh, rec.AccuracyM,
			rec.Country, rec.SourceDevice, now)
		if err != nil {
			httpError(w, http.StatusInternalServerError, err.Error())
			return
		}
		n, _ := res.RowsAffected()
		inserted += int(n)
	}
	if err := tx.Commit(); err != nil {
		httpError(w, http.StatusInternalServerError, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]int{
		"received":   len(recs),
		"inserted":   inserted,
		"duplicates": len(recs) - inserted,
	})
}

// listRecords filters by bounding box (map viewport), the shared filter set, and limit.
func (s *server) listRecords(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f, err := parseFilters(q)
	if err != nil {
		httpError(w, http.StatusBadRequest, err.Error())
		return
	}
	where, args := f.where("sightings")

	// Bounding box is map-viewport-specific, so it lives on /records only (not /stats).
	for param, clause := range map[string]string{
		"min_lat": " AND lat >= ?", "max_lat": " AND lat <= ?",
		"min_lon": " AND lon >= ?", "max_lon": " AND lon <= ?",
	} {
		if v := q.Get(param); v != "" {
			b, err := strconv.ParseFloat(v, 64)
			if err != nil {
				httpError(w, http.StatusBadRequest, "bad "+param)
				return
			}
			where += clause
			args = append(args, b)
		}
	}

	limit := 1000
	if v := q.Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = min(n, 10000)
		}
	}
	args = append(args, limit)

	rows, err := s.db.Query(`SELECT id, plate_text, read_kind, confidence, captured_at,
		lat, lon, speed_kmh, accuracy_m, country, source_device, created_at FROM sightings WHERE `+
		where+` ORDER BY captured_at DESC LIMIT ?`, args...)
	if err != nil {
		httpError(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()

	out := []Sighting{}
	for rows.Next() {
		s, err := scanSighting(rows)
		if err != nil {
			httpError(w, http.StatusInternalServerError, err.Error())
			return
		}
		out = append(out, s)
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *server) stats(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	topN := 10
	if v := q.Get("top"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			topN = min(n, 100)
		}
	}

	f, err := parseFilters(q)
	if err != nil {
		httpError(w, http.StatusBadRequest, err.Error())
		return
	}
	where, args := f.where("sightings")

	rows, err := s.db.Query(`SELECT plate_text, captured_at, country FROM sightings WHERE `+where, args...)
	if err != nil {
		httpError(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()

	perDay := map[string]int{}
	perHour := map[int]int{}
	for h := 0; h < 24; h++ {
		perHour[h] = 0
	}
	plateCounts := map[string]int{}
	byCountry := map[string]int{}
	total := 0
	for rows.Next() {
		var plate, capturedAt string
		var country sql.NullString
		if err := rows.Scan(&plate, &capturedAt, &country); err != nil {
			httpError(w, http.StatusInternalServerError, err.Error())
			return
		}
		total++
		plateCounts[plate]++
		c := country.String
		if !country.Valid || c == "" {
			c = "?"
		}
		byCountry[c]++
		if t, err := time.Parse(time.RFC3339, capturedAt); err == nil {
			perDay[t.Format("2006-01-02")]++
			perHour[t.Hour()]++
		}
	}

	type plateCount struct {
		Plate string `json:"plate"`
		Count int    `json:"count"`
	}
	top := make([]plateCount, 0, len(plateCounts))
	for p, c := range plateCounts {
		top = append(top, plateCount{p, c})
	}
	sort.Slice(top, func(i, j int) bool {
		if top[i].Count != top[j].Count {
			return top[i].Count > top[j].Count
		}
		return top[i].Plate < top[j].Plate
	})
	if len(top) > topN {
		top = top[:topN]
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"total_sightings": total,
		"unique_plates":   len(plateCounts),
		"per_day":         perDay,
		"per_hour":        perHour,
		"top_plates":      top,
		"by_country":      byCountry,
	})
}

// filters is the shared filter set applied to BOTH /records and /stats, so stats always reflect
// whatever the dashboard has filtered to.
type filters struct {
	since, until       *time.Time
	plate              string // case-insensitive substring of plate_text
	country            string // exact match: ISO-2, e.g. "IT"
	minSpeed, maxSpeed *float64
	minConfidence      *float64
	minCount           *int // only plates seen >= N times (within the other filters)
}

func parseFilters(q url.Values) (filters, error) {
	var f filters
	parseTime := func(key string) (*time.Time, error) {
		if v := q.Get(key); v != "" {
			t, err := time.Parse(time.RFC3339, v)
			if err != nil {
				return nil, errors.New("bad " + key + " (want RFC3339)")
			}
			tu := t.UTC()
			return &tu, nil
		}
		return nil, nil
	}
	parseFloat := func(key string) (*float64, error) {
		if v := q.Get(key); v != "" {
			n, err := strconv.ParseFloat(v, 64)
			if err != nil {
				return nil, errors.New("bad " + key)
			}
			return &n, nil
		}
		return nil, nil
	}

	var err error
	if f.since, err = parseTime("since"); err != nil {
		return f, err
	}
	if f.until, err = parseTime("until"); err != nil {
		return f, err
	}
	if f.minSpeed, err = parseFloat("min_speed"); err != nil {
		return f, err
	}
	if f.maxSpeed, err = parseFloat("max_speed"); err != nil {
		return f, err
	}
	if f.minConfidence, err = parseFloat("min_confidence"); err != nil {
		return f, err
	}
	if v := q.Get("min_count"); v != "" {
		n, e := strconv.Atoi(v)
		if e != nil || n < 1 {
			return f, errors.New("bad min_count")
		}
		f.minCount = &n
	}
	f.plate = strings.ToUpper(strings.TrimSpace(q.Get("plate")))
	f.country = strings.ToUpper(strings.TrimSpace(q.Get("country")))
	return f, nil
}

// base returns the WHERE clauses (sans min_count) and their args.
func (f filters) base() ([]string, []any) {
	clauses := []string{"1=1"}
	var args []any
	add := func(clause string, arg any) {
		clauses = append(clauses, clause)
		args = append(args, arg)
	}
	if f.since != nil {
		add("captured_at >= ?", f.since.Format(time.RFC3339))
	}
	if f.until != nil {
		add("captured_at <= ?", f.until.Format(time.RFC3339))
	}
	if f.plate != "" {
		add("UPPER(plate_text) LIKE ?", "%"+f.plate+"%")
	}
	if f.country != "" {
		add("UPPER(country) = ?", f.country)
	}
	if f.minSpeed != nil {
		add("speed_kmh >= ?", *f.minSpeed)
	}
	if f.maxSpeed != nil {
		add("speed_kmh <= ?", *f.maxSpeed)
	}
	if f.minConfidence != nil {
		add("confidence >= ?", *f.minConfidence)
	}
	return clauses, args
}

// where builds the final WHERE SQL + args against [table]. min_count wraps the base in a subquery
// that keeps only plates whose count (under the same base filters, same table) meets the threshold.
func (f filters) where(table string) (string, []any) {
	clauses, args := f.base()
	base := strings.Join(clauses, " AND ")
	if f.minCount == nil {
		return base, args
	}
	sub := "plate_text IN (SELECT plate_text FROM " + table + " WHERE " + base +
		" GROUP BY plate_text HAVING COUNT(*) >= ?)"
	full := append([]any{}, args...) // base args for the outer WHERE
	full = append(full, args...)     // base args repeated inside the subquery
	full = append(full, *f.minCount)
	return base + " AND " + sub, full
}

func scanSighting(rows *sql.Rows) (Sighting, error) {
	var s Sighting
	var capturedAt, createdAt string
	if err := rows.Scan(&s.ID, &s.PlateText, &s.ReadKind, &s.Confidence,
		&capturedAt, &s.Lat, &s.Lon, &s.SpeedKmh, &s.AccuracyM, &s.Country, &s.SourceDevice, &createdAt); err != nil {
		return s, err
	}
	s.CapturedAt, _ = time.Parse(time.RFC3339, capturedAt)
	s.CreatedAt, _ = time.Parse(time.RFC3339, createdAt)
	return s, nil
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func httpError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func main() {
	token := os.Getenv("API_TOKEN")
	if token == "" {
		log.Fatal("API_TOKEN is required")
	}
	dbPath := os.Getenv("DB_PATH")
	if dbPath == "" {
		dbPath = "./plates.db"
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "8000"
	}

	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()

	srv, err := newServer(db, token)
	if err != nil {
		log.Fatal(err)
	}
	srv.webDir = os.Getenv("WEB_DIR") // optional: e.g. ../webapp/dist for single-binary serving

	log.Printf("plates-tracker backend listening on :%s (db=%s)", port, dbPath)
	log.Fatal(http.ListenAndServe(":"+port, srv.routes()))
}
