package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	_ "modernc.org/sqlite"
)

const testToken = "test-token"

func newTestServer(t *testing.T) *server {
	t.Helper()
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	srv, err := newServer(db, testToken)
	if err != nil {
		t.Fatal(err)
	}
	return srv
}

func do(t *testing.T, h http.Handler, method, target, body string, auth bool) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	if auth {
		req.Header.Set("Authorization", "Bearer "+testToken)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestHealthNoAuth(t *testing.T) {
	rec := do(t, newTestServer(t).routes(), "GET", "/health", "", false)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"ok":true`) {
		t.Fatalf("health failed: %d %s", rec.Code, rec.Body.String())
	}
}

func TestRequiresToken(t *testing.T) {
	rec := do(t, newTestServer(t).routes(), "POST", "/records", "[]", false)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", rec.Code)
	}
}

func TestUploadAndIdempotency(t *testing.T) {
	h := newTestServer(t).routes()
	recs := `[
		{"id":"id-1","plate_text":"AB123CD","confidence":0.9,"captured_at":"2026-06-20T08:00:00Z","lat":45.46,"lon":9.19},
		{"id":"id-2","plate_text":"EF456GH","confidence":0.9,"captured_at":"2026-06-20T09:30:00Z","lat":45.47,"lon":9.20}
	]`
	got := decode(t, do(t, h, "POST", "/records", recs, true))
	assertCounts(t, got, 2, 2, 0)

	// Re-upload same ids + one new → only the new inserts.
	recs2 := `[
		{"id":"id-1","plate_text":"AB123CD","confidence":0.9,"captured_at":"2026-06-20T08:00:00Z","lat":45.46,"lon":9.19},
		{"id":"id-2","plate_text":"EF456GH","confidence":0.9,"captured_at":"2026-06-20T09:30:00Z","lat":45.47,"lon":9.20},
		{"id":"id-3","plate_text":"AB123CD","confidence":0.9,"captured_at":"2026-06-21T10:00:00Z","lat":45.48,"lon":9.21}
	]`
	assertCounts(t, decode(t, do(t, h, "POST", "/records", recs2, true)), 3, 1, 2)
}

func TestValidationRejectsBadLat(t *testing.T) {
	h := newTestServer(t).routes()
	bad := `[{"id":"x","plate_text":"AB123CD","confidence":0.9,"captured_at":"2026-06-20T08:00:00Z","lat":999,"lon":9.19}]`
	if rec := do(t, h, "POST", "/records", bad, true); rec.Code != http.StatusBadRequest {
		t.Fatalf("want 400 for bad lat, got %d", rec.Code)
	}
}

func TestBBoxAndTimeFilters(t *testing.T) {
	h := seeded(t)
	// Add a Rome point outside the Milan box.
	do(t, h, "POST", "/records", `[{"id":"rm","plate_text":"RM000AA","confidence":0.9,"captured_at":"2026-06-22T08:00:00Z","lat":41.90,"lon":12.49}]`, true)

	bbox := do(t, h, "GET", "/records?min_lat=45&max_lat=46&min_lon=9&max_lon=10", "", true)
	plates := platesOf(t, bbox)
	if !plates["AB123CD"] || plates["RM000AA"] {
		t.Fatalf("bbox filter wrong: %v", plates)
	}

	since := do(t, h, "GET", "/records?since=2026-06-21T00:00:00Z", "", true)
	var list []Sighting
	if err := json.Unmarshal(since.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	for _, s := range list {
		if s.CapturedAt.Year() == 2026 && s.CapturedAt.YearDay() < 172 { // before Jun 21
			t.Fatalf("time filter leaked older record: %s", s.CapturedAt)
		}
	}
}

func TestStats(t *testing.T) {
	rec := do(t, seeded(t), "GET", "/stats", "", true)
	var s struct {
		Total     int            `json:"total_sightings"`
		Unique    int            `json:"unique_plates"`
		PerHour   map[string]int `json:"per_hour"`
		TopPlates []struct {
			Plate string `json:"plate"`
			Count int    `json:"count"`
		} `json:"top_plates"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &s); err != nil {
		t.Fatal(err)
	}
	if s.Total < 3 || s.Unique < 2 {
		t.Fatalf("stats totals off: %+v", s)
	}
	if len(s.PerHour) != 24 {
		t.Fatalf("per_hour should have 24 buckets, got %d", len(s.PerHour))
	}
	if s.TopPlates[0].Plate != "AB123CD" || s.TopPlates[0].Count < 2 {
		t.Fatalf("AB123CD should top the list: %+v", s.TopPlates)
	}
}

func TestPlateSearchFilter(t *testing.T) {
	h := seeded(t)
	if p := platesOf(t, do(t, h, "GET", "/records?plate=ab12", "", true)); !p["AB123CD"] || p["EF456GH"] {
		t.Fatalf("plate search wrong: %v", p)
	}
	if p := platesOf(t, do(t, h, "GET", "/records?plate=EF4", "", true)); !p["EF456GH"] || p["AB123CD"] {
		t.Fatalf("plate search wrong: %v", p)
	}
}

func TestMinCountFilter(t *testing.T) {
	// AB123CD seen twice, EF456GH once -> min_count=2 keeps only AB123CD.
	p := platesOf(t, do(t, seeded(t), "GET", "/records?min_count=2", "", true))
	if !p["AB123CD"] || p["EF456GH"] {
		t.Fatalf("min_count filter wrong: %v", p)
	}
}

func TestSpeedAndConfidenceFilters(t *testing.T) {
	h := newTestServer(t).routes()
	do(t, h, "POST", "/records", `[
		{"id":"f1","plate_text":"AA111AA","confidence":0.95,"captured_at":"2026-06-20T08:00:00Z","lat":45.4,"lon":9.1,"speed_kmh":30},
		{"id":"f2","plate_text":"BB222BB","confidence":0.50,"captured_at":"2026-06-20T08:00:00Z","lat":45.4,"lon":9.1,"speed_kmh":90}
	]`, true)
	if p := platesOf(t, do(t, h, "GET", "/records?min_confidence=0.8", "", true)); !p["AA111AA"] || p["BB222BB"] {
		t.Fatalf("min_confidence wrong: %v", p)
	}
	if p := platesOf(t, do(t, h, "GET", "/records?min_speed=60", "", true)); !p["BB222BB"] || p["AA111AA"] {
		t.Fatalf("min_speed wrong: %v", p)
	}
}

func TestCountryFilter(t *testing.T) {
	h := newTestServer(t).routes()
	do(t, h, "POST", "/records", `[
		{"id":"c1","plate_text":"AB123CD","country":"IT","confidence":0.9,"captured_at":"2026-06-20T08:00:00Z","lat":45.4,"lon":9.1},
		{"id":"c2","plate_text":"FR-AA-229","country":"FR","confidence":0.9,"captured_at":"2026-06-20T08:00:00Z","lat":45.4,"lon":9.1}
	]`, true)
	if p := platesOf(t, do(t, h, "GET", "/records?country=it", "", true)); !p["AB123CD"] || p["FR-AA-229"] {
		t.Fatalf("country filter wrong: %v", p)
	}
	// by_country appears in stats.
	rec := do(t, h, "GET", "/stats", "", true)
	var s struct {
		ByCountry map[string]int `json:"by_country"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &s); err != nil {
		t.Fatal(err)
	}
	if s.ByCountry["IT"] != 1 || s.ByCountry["FR"] != 1 {
		t.Fatalf("by_country wrong: %v", s.ByCountry)
	}
}

func TestStatsRespectsFilters(t *testing.T) {
	rec := do(t, seeded(t), "GET", "/stats?plate=AB123CD", "", true)
	var s struct {
		Total  int `json:"total_sightings"`
		Unique int `json:"unique_plates"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &s); err != nil {
		t.Fatal(err)
	}
	if s.Total != 2 || s.Unique != 1 {
		t.Fatalf("filtered stats should be total=2 unique=1, got %+v", s)
	}
}

// seeded returns a handler preloaded with two AB123CD + one EF456GH sighting.
func seeded(t *testing.T) http.Handler {
	h := newTestServer(t).routes()
	recs := `[
		{"id":"s1","plate_text":"AB123CD","confidence":0.9,"captured_at":"2026-06-20T08:00:00Z","lat":45.46,"lon":9.19},
		{"id":"s2","plate_text":"EF456GH","confidence":0.9,"captured_at":"2026-06-20T09:30:00Z","lat":45.47,"lon":9.20},
		{"id":"s3","plate_text":"AB123CD","confidence":0.9,"captured_at":"2026-06-21T10:00:00Z","lat":45.48,"lon":9.21}
	]`
	do(t, h, "POST", "/records", recs, true)
	return h
}

func decode(t *testing.T, rec *httptest.ResponseRecorder) map[string]int {
	t.Helper()
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
	var m map[string]int
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func assertCounts(t *testing.T, m map[string]int, received, inserted, duplicates int) {
	t.Helper()
	if m["received"] != received || m["inserted"] != inserted || m["duplicates"] != duplicates {
		t.Fatalf("counts = %v; want received=%d inserted=%d duplicates=%d", m, received, inserted, duplicates)
	}
}

func platesOf(t *testing.T, rec *httptest.ResponseRecorder) map[string]bool {
	t.Helper()
	var list []Sighting
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	out := map[string]bool{}
	for _, s := range list {
		out[s.PlateText] = true
	}
	return out
}

func TestApiPrefixAliases(t *testing.T) {
	// The built webapp requests /api/* (its dev server proxies that prefix); the single-binary
	// deploy must answer there too, with auth intact.
	h := newTestServer(t).routes()
	if rec := do(t, h, "GET", "/api/records", "", false); rec.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated /api/records: want 401, got %d", rec.Code)
	}
	do(t, h, "POST", "/api/records", `[
		{"id":"api1","plate_text":"AB123CD","confidence":0.9,"captured_at":"2026-06-20T08:00:00Z","lat":45.4,"lon":9.1}
	]`, true)
	if p := platesOf(t, do(t, h, "GET", "/api/records", "", true)); !p["AB123CD"] {
		t.Fatalf("GET /api/records missing upload: %v", p)
	}
	if rec := do(t, h, "GET", "/api/stats", "", true); rec.Code != http.StatusOK {
		t.Fatalf("GET /api/stats: want 200, got %d", rec.Code)
	}
}
