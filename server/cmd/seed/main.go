// seed generates synthetic Italian plate sightings and uploads them to the backend.
//
// Realistic-ish so the dashboard looks alive: valid Italian car plates (LL DDD LL, 22-letter
// alphabet), clustered around real cities, times weighted to commute hours, and a long tail where
// some cars are seen many times (gives min_count / top-plates real signal).
//
// Usage:
//
//	API_TOKEN=devtoken go run ./cmd/seed                 # 500 sightings, last 30 days
//	go run ./cmd/seed -url http://localhost:8000 -token devtoken -n 1000 -days 60
package main

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	mrand "math/rand"
	"net/http"
	"os"
	"time"
)

const letters = "ABCDEFGHJKLMNPRSTVWXYZ" // Italian plate alphabet (A-Z minus I,O,Q,U)

type city struct {
	name     string
	lat, lon float64
	jitter   float64 // ~degrees of spread
}

// A few Italian cities to cluster sightings around (so the map isn't random noise).
var cities = []city{
	{"Milano", 45.4642, 9.1900, 0.06},
	{"Roma", 41.9028, 12.4964, 0.07},
	{"Torino", 45.0703, 7.6869, 0.05},
	{"Bologna", 44.4949, 11.3426, 0.04},
	{"Napoli", 40.8518, 14.2681, 0.05},
	{"Firenze", 43.7696, 11.2558, 0.04},
}

type sighting struct {
	ID           string  `json:"id"`
	PlateText    string  `json:"plate_text"`
	ReadKind     string  `json:"read_kind"`
	Confidence   float64 `json:"confidence"`
	CapturedAt   string  `json:"captured_at"`
	Lat          float64 `json:"lat"`
	Lon          float64 `json:"lon"`
	SpeedKmh     float64 `json:"speed_kmh"`
	AccuracyM    float64 `json:"accuracy_m"`
	Country      string  `json:"country"`
	SourceDevice string  `json:"source_device"`
}

// car is one vehicle in the pool — a plate keeps a stable registration country across sightings.
type car struct {
	plate, country string
}

// foreignMix: codes of non-Italian cars seen on Italian roads, with rough relative weights.
var foreignMix = []string{"FR", "FR", "DE", "DE", "CH", "CH", "AT", "SI", "ES"}

func main() {
	url := flag.String("url", "http://localhost:8000", "backend base URL")
	token := flag.String("token", os.Getenv("API_TOKEN"), "API token (default $API_TOKEN)")
	n := flag.Int("n", 500, "number of sightings to generate")
	days := flag.Int("days", 30, "spread sightings over the last N days")
	batch := flag.Int("batch", 100, "upload batch size")
	seed := flag.Int64("seed", time.Now().UnixNano(), "RNG seed (set for reproducible data)")
	flag.Parse()

	if *token == "" {
		fmt.Fprintln(os.Stderr, "ERROR: provide -token or set API_TOKEN")
		os.Exit(1)
	}

	rng := mrand.New(mrand.NewSource(*seed))

	// Pool of unique cars. A "frequent" fifth of them accounts for most sightings (commute cars),
	// the rest are seen rarely — a realistic long tail. ~85% Italian, the rest foreign.
	numUnique := max(12, *n/5)
	cars := make([]car, numUnique)
	for i := range cars {
		if rng.Float64() < 0.85 {
			cars[i] = car{randomPlate(rng), "IT"}
		} else {
			cars[i] = car{foreignPlate(rng), foreignMix[rng.Intn(len(foreignMix))]}
		}
	}
	frequent := cars[:max(2, numUnique/5)]

	sightings := make([]sighting, *n)
	for i := range sightings {
		veh := cars[rng.Intn(len(cars))]
		if rng.Float64() < 0.6 {
			veh = frequent[rng.Intn(len(frequent))]
		}
		c := cities[rng.Intn(len(cities))]
		inCity := rng.Float64() < 0.7
		speed := 25 + rng.Float64()*35 // city
		if !inCity {
			speed = 90 + rng.Float64()*50 // highway
		}
		conf := 0.55 + rng.Float64()*0.44
		kind := "exact"
		if conf < 0.7 {
			kind = "corrected"
		}
		sightings[i] = sighting{
			ID:           uuid(),
			PlateText:    veh.plate,
			ReadKind:     kind,
			Confidence:   round2(conf),
			CapturedAt:   randomTime(rng, *days).Format(time.RFC3339),
			Lat:          round5(c.lat + (rng.Float64()*2-1)*c.jitter),
			Lon:          round5(c.lon + (rng.Float64()*2-1)*c.jitter),
			SpeedKmh:     round1(speed),
			AccuracyM:    round1(3 + rng.Float64()*12),
			Country:      veh.country,
			SourceDevice: "synthetic",
		}
	}

	fmt.Printf("generated %d sightings (%d unique cars, %d frequent) over %d days\n",
		*n, numUnique, len(frequent), *days)

	totalIns, totalDup := 0, 0
	for start := 0; start < len(sightings); start += *batch {
		end := min(start+*batch, len(sightings))
		ins, dup, err := upload(*url, *token, sightings[start:end])
		if err != nil {
			fmt.Fprintf(os.Stderr, "upload failed at batch %d: %v\n", start, err)
			os.Exit(1)
		}
		totalIns += ins
		totalDup += dup
		fmt.Printf("  uploaded %d-%d: inserted=%d duplicates=%d\n", start, end, ins, dup)
	}
	fmt.Printf("done: inserted=%d duplicates=%d → %s\n", totalIns, totalDup, *url)
}

func randomPlate(rng *mrand.Rand) string {
	b := []byte("AA000AA")
	b[0], b[1] = letters[rng.Intn(22)], letters[rng.Intn(22)]
	b[2], b[3], b[4] = byte('0'+rng.Intn(10)), byte('0'+rng.Intn(10)), byte('0'+rng.Intn(10))
	b[5], b[6] = letters[rng.Intn(22)], letters[rng.Intn(22)]
	return string(b)
}

// foreignPlate makes a generic non-Italian-looking plate (display only, format not country-exact).
func foreignPlate(rng *mrand.Rand) string {
	b := []byte("AA-000-AA")
	b[0], b[1] = letters[rng.Intn(22)], letters[rng.Intn(22)]
	b[3], b[4], b[5] = byte('0'+rng.Intn(10)), byte('0'+rng.Intn(10)), byte('0'+rng.Intn(10))
	b[7], b[8] = letters[rng.Intn(22)], letters[rng.Intn(22)]
	return string(b)
}

// randomTime returns a time in the last `days`, weighted toward commute hours.
func randomTime(rng *mrand.Rand, days int) time.Time {
	day := rng.Intn(days)
	hour := commuteHour(rng)
	t := time.Now().UTC().AddDate(0, 0, -day)
	return time.Date(t.Year(), t.Month(), t.Day(), hour, rng.Intn(60), rng.Intn(60), 0, time.UTC)
}

func commuteHour(rng *mrand.Rand) int {
	switch r := rng.Float64(); {
	case r < 0.30:
		return 7 + rng.Intn(2) // morning rush 7-8
	case r < 0.60:
		return 17 + rng.Intn(2) // evening rush 17-18
	case r < 0.85:
		return 9 + rng.Intn(8) // daytime 9-16
	default:
		return rng.Intn(24) // anytime
	}
}

func upload(url, token string, recs []sighting) (inserted, duplicates int, err error) {
	body, _ := json.Marshal(recs)
	req, _ := http.NewRequest("POST", url+"/records", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return 0, 0, fmt.Errorf("HTTP %d: %s", resp.StatusCode, b)
	}
	var out struct{ Inserted, Duplicates int }
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return 0, 0, err
	}
	return out.Inserted, out.Duplicates, nil
}

func uuid() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func round1(f float64) float64 { return float64(int(f*10+0.5)) / 10 }
func round2(f float64) float64 { return float64(int(f*100+0.5)) / 100 }
func round5(f float64) float64 { return float64(int(f*1e5+0.5)) / 1e5 }
