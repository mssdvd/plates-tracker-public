# Building the capture app (Phase 0 → Phase 1b)

How the capture app gets built. This expands the top-level plan's Phase 0/1b; it does not restate
it. Read [`spike.md`](spike.md) (feasibility) and [`model-specs.md`](model-specs.md) (model I/O)
first — they hold the rationale this plan assumes.

The build is **gated**, not linear. Two cheap tests must pass before the expensive app work, and
each can send us back to fix capture/models instead of writing more Kotlin.

---

## Gate A — reads work on real footage (workstation, no phone) ⬅️ do first

**Question:** does fast-alpr read *Italian plates from a moving car*? This is the make-or-break
constraint and the cheapest possible test — it needs no app.

- **Blocked on the user:** record 1–2 min of windshield video on a real drive (city + a faster
  road, ideally a dusk clip too). Nothing downstream proceeds without this footage.
- Run the already-verified harness:
  ```bash
  cd models
  .venv-convert/bin/python spike_video_test.py --video drive.mp4 --fps 8 --annotated out.mp4
  ```
- **Pass:** real plates show up as ✓ valid reads at the speeds driven, per-frame latency is sane.
- **Fail:** fix *capture* before any phone work — faster shutter, better mount, or bump detector
  input 384→512/608 (`model-specs.md`). Do not build the app to work around unreadable frames.

## Gate B — reads work on-device (throwaway spike app)

A deliberately minimal Kotlin app: CameraX preview → throttled `ImageAnalysis` → ORT detector +
OCR → overlay the read text on screen. **No DB, no upload, no service.** Just proves the pipeline
runs on the phone and measures per-frame latency at the throttle rate.

- Runtime: **`onnxruntime-android`** feeding the `.onnx` models directly (see `spike.md` for why
  ORT over TFLite for the spike).
- ⚠️ **ORT runs on CPU (XNNPACK).** NNAPI is deprecated (Android 15), so there is no NPU acceleration on this path. If CPU latency at 5–10 fps is acceptable, this is sufficient.
- **Pass:** stable reads + a per-frame budget that fits the chosen throttle without thermal
  throttling over a ~20 min drive.

Both gates green → build the real app.

---

## Phase 1b — the capture app

Stack: **Kotlin + CameraX + ONNX Runtime + Room + WorkManager + FusedLocationProvider.** Native
Kotlin (a real-time camera+ML loop fights cross-platform frameworks).

### The wire contract is already fixed — mirror it, don't invent it

The upload payload is **exactly** the `sighting` struct in `server/cmd/seed/main.go` (the seeder is
the reference producer). The Kotlin data class mirrors it field-for-field; the app is "integrated"
when its records are indistinguishable from the seeder's. Posts go to `POST /records` (batch,
bearer token). Fields:

| json | source on device | notes |
|---|---|---|
| `id` | client-generated UUID, **stable per logical sighting** | backend dedups on this — see idempotency below |
| `plate_text` | OCR | raw model output, verbatim |
| `read_kind` | constant | `"exact"` (2026-07-09: no more corrected/generic/invalid variants — see below) |
| `confidence` | OCR | 0–1 |
| `captured_at` | clock at capture | RFC3339 |
| `lat` / `lon` | FusedLocation | |
| `speed_kmh` | FusedLocation | |
| `accuracy_m` | FusedLocation | |
| `country` | OCR model's region head | ISO-2 (argmax over `cct-s-v2-global`'s 66-class country head — see `docs/model-specs.md`); `"?"` when it says "Unknown", or when the decoded plate text is under 7 chars (2026-07-10, see component 5) |
| `source_device` | constant | e.g. device model / `"android-phone"` |

**2026-07-09: format-regex validation is not a gate on OCR output itself** — `plate_text`/`country`
still come straight from the OCR model, there is no more `plate_type`, and no more corrected/
generic/invalid read variant (`read_kind` is always `"exact"`). **2026-07-10 update:**
`PlateValidator`/`shared/plate_validation.py` are back on the promotion path, narrowly:
`DedupEngine`'s 1-frame `instant` path now requires a structurally exact match before it will fire
(see component 5) — the `steady`/`fast` paths (>=2-3 frames) still don't gate on it. See the class
docs and `docs/model-specs.md`.

### Components

1. **Project scaffold** — Gradle, target SDK matching the phone (Android 14+). Deps: CameraX,
   `onnxruntime-android`, Room, WorkManager, `play-services-location`. Bundle the two `.onnx`
   models as assets; alphabet/slot-count mirror `cct_s_v2_global_plate_config.yaml` as Kotlin
   constants (`OcrDecoder`) rather than parsing the yaml on-device.
2. **Capture** — CameraX `ImageAnalysis`, **throttle to 5–10 fps** (not the full 30). Throttle is
   a first-class thermal/battery budget the spike measured, not an afterthought.
3. **Inference.** The *bulk of the work here is
   porting fast-alpr's Python pre/post-processing to Kotlin*: normalize → crop each detection →
   BGR2RGB → resize to 128×64 → run OCR → decode `[1,10,37]` as 10 slots × 37 classes (argmax per
   slot, alphabet/length from the yaml). None of this is free on Android.
4. **Validation** — `shared/plate_validation.py`/`PlateValidator.kt` port fast-alpr-style format
   matching + bounded confusion-correction. **2026-07-09: not a gate on OCR output itself** (see the
   wire-contract note above) — but **2026-07-10: back on the promotion path**, narrowly, as
   component 5's `instant`-path structure gate. Otherwise still used for `estimateRegistrationYear`
   (opportunistically against whatever OCR text happens to match a known format) and as reference.
5. **Dedup / multi-frame voting** — collapse the same physical plate across frames to **one
   record**. Gate A proved this is a **first-class component, not a footnote**: a single lead car
   produced ~6 slightly different variants of its own plate from per-frame OCR slips (edit distance
   1–2 from the canonical read), so **exact-string dedup would log one car as many**. Required:
   - **Fuzzy clustering** — group reads within a short time window AND small edit distance into one
     candidate; keep the highest-confidence reading as the canonical text.
   - **Persistence + confidence gate** — three paths promote a cluster: **steady** (≥3 frames,
     conf ≥0.70), **fast** (≥2 frames, conf ≥0.75, added 2026-07-04 for oncoming traffic), and
     **instant** (≥1 frame, conf ≥0.90, added 2026-07-09: the 2026-07-09 drive's field data showed
     83% of real clusters are single-frame, which the first two paths can never promote no matter
     how confident that one read was). Accepted tradeoff on the instant path: OCR confidence no
     longer reliably separates a genuine read from confident garble on this model (re-verified
     numbers in `docs/model-specs.md`) — 0.90 bounds, not eliminates, that risk.
   - **2026-07-09 afternoon field results (first real drive on this config — ground truth
     all-Italian traffic, 297 promotions, full analysis in
     `device-dumps/2026-07-09_184031/REPORT.md`): ~27% junk, three concrete dedup failure modes.**
     (1) *Fragment siblings*: a truncated 3-char read is more than `maxEditDistance`=2 edits from
     its full 7-char plate because Levenshtein counts the length gap, so it seeds its own
     cluster and instant-promotes seconds before/after the real one — same car, two records, and
     the region head labels the fragment a foreign country. (2) *Window-expiry re-promotion*: a
     car followed in traffic re-promotes with a fresh `id` every time reads gap >`windowMs` (10 s)
     — one plate became 5 records in 2 minutes; the stable-`id` idempotency only guards upload
     retries, not this. (3) *Cluster text drift*: a fragment-seeded sibling cluster can absorb
     later reads until its `best` is within 2 edits of the full text, then promote the identical
     string a second time inside the window (observed 1.0 s apart) — `clusters.firstOrNull`
     matching on the *mutating* `best.text` makes this order-dependent. Empty OCR reads (all-pad
     output, conf ≈1.0 — see `docs/model-specs.md`) also sail through instant: 44 rows.
   - **2026-07-10: implemented, and re-measured against the same dataset (plate strings withheld —
     see the repo's redaction policy).** Re-classifying the 297 rows with the full validator (not
     the field report's simpler regex) puts real junk at 85/297 (5 more than the report's manual 80
     — IT-shaped 7-char fragments whose series isn't plausible yet, which the report's format-only
     check missed but `PlateValidator`'s issue-date prior correctly rejects). Fixes, all in
     `DedupEngine`/`OcrDecoder`/`Ocr` unless noted:
     - **Min-length gate (`minReadLength` = 7)** at the top of `observe()`, before a read can seed or
       join a cluster — covers both call sites (live + burst) from one place. Alone: 75/85 junk
       rows removed, 0 clean rows affected.
     - **Structure gate on the `instant` path only**: an extra `PlateValidator.validate(text)
       .confidence == EXACT` check before a 1-frame promotion fires (`steady`/`fast` unchanged —
       see the class docs). Alone: 82/85 removed, 0 clean rows affected.
     - **Both together: 83/85 removed.** The 2 residual rows are genuine 7-char Italian-format
       reads the region head mislabeled as foreign — a known limit of trusting the region head at
       exactly the 7-char boundary (see the next bullet), not a promotion-count failure: they're
       real, once-only sightings, just with the wrong `country`.
     - **Region head distrusted below 7 chars** (`OcrDecoder.decodeRegion(text, flat)`): a fragment
       no longer argmaxes to a confident wrong country, which also silences the false foreign-plate
       tone.
     - **Dedup hardening**: a read within edit distance *or* a fragment match (substring of an
       existing cluster's best text, allowing <=1 char slip) now merges via best-match (lowest edit
       distance, ties on most recent) instead of first-match, and a *structurally valid* reading
       always outranks a fragment/garble as the cluster's canonical text. A promoted cluster's
       expiry is extended to 60 s (`promotedWindowMs`) so a followed car's >10 s read gaps in
       traffic no longer mint a fresh `id` each time.
     - **Not changed**: the `Log`-stripping theory in the buffered-capture-v2 section below turned
       out to be wrong — see that section's 2026-07-10 update.
6. **Location** — `FusedLocationProviderClient` supplies lat/lon/speed/accuracy; attach at capture.
7. **Storage** — plain SQLite (`SightingStore`), not Room. Rows carry a `synced` flag.
   2026-07-09: the `unconfirmed_reads` twin table (2026-07-06 — logged reads that passed
   plate-format validation but never cleared step 5's gate) is retired along with the format-regex
   gate itself; see `docs/model-specs.md`.
8. **Sync** — WorkManager job drains the sightings queue (`POST /records`),
   marks rows synced, retries
   with backoff. Default constraints: Wi-Fi + charging (configurable).
9. **UI** — glanceable and hands-free. Mount-and-drive: one-tap start, live today's-count, current
   detections, sync status. No interaction required while moving (you can't tap a shutter at speed).

### Buffered capture v2 — RAM ring + burst re-scan (added 2026-07-08, undocumented until now)

Component 2's single live pass has a hard limit: a fast-closing oncoming plate can cross from "too
small to OCR" to "out of range" in well under the live scan's compute budget. Capture v2
(`CaptureService.kt`, `FrameRingEncoder.kt`/`AuRing.kt`, `BurstPlanner.kt`/`BurstProcessor.kt`,
gated by the `capture_v2` setting) works around this: every other delivered camera frame (~15fps of
the native ~30fps feed — `BurstProcessor` only ever consumes footage at that rate anyway, so
encoding faster than that is pure waste) is fed to a hardware HEVC encoder into an in-RAM ring
buffer (`AuRing`, ~6-7s of 4K, sized by bitrate not frame count). When the live pass finds a box too
small to OCR confidently (`onFarHit`), the surrounding ~1.5s of ring footage is handed to a
lower-priority "burst" thread for a slower, higher-effort re-scan, and any resulting read still
flows through the normal `DedupEngine`/promotion path.

**This only rescues a box the live pass already found** — it does nothing for a plate the detector
never boxed at all (below `BoxMapper.CONF` or too small at the detector's 384px input); that's a
separate, so-far-unaddressed gap.

**Known fragility, both fixed 2026-07-08:**
- The whole ring/burst path silently falls back to a plain live-only scan (`analyzeV1`, no burst,
  ever, for the rest of that session) if a single delivered frame reports `rotationDegrees != 0`
  (`CaptureService.kt`'s `analyze()`). `ImageAnalysis`'s target rotation is now pinned explicitly at
  bind time via `DisplayManager` (`startCamera()`/`targetRotation()`) instead of left to CameraX's
  implicit inference for a windowless `Service`, which is what made it non-deterministic.
- Ring encoding and the burst trigger both suspend at `THERMAL_STATUS_SEVERE` — which a
  dashboard-mounted phone in direct sun can reach. There's no visibility after a drive into whether
  this happened, or into which path (v1 vs v2) actually ran, without a live logcat session (its ring
  buffer is small and rotates within minutes). Per-session diagnostics now persist via
  `AppSettings.saveCaptureStats`/`readCaptureStats` (same pattern as `last_sync_status`) and render
  in the HUD: frames on v1 vs v2, far-hit/burst-promotion counts, and thermal-bucket dwell time —
  see `CaptureService.buildCaptureStats()`.
  2026-07-09 field dump: both fragilities confirmed in the wild — VIRTUAL-SKIN hit SEVERE
  (45.0 °C) near session end with burst promotions thinning accordingly. The dump's own `Log`
  lines were entirely absent, which the report's diagnostics-gaps section attributed to the
  installed build stripping `Log` calls; main-buffer retention measured ~10 minutes at pull time
  either way. Burst-path read quality itself was fine — 18% junk vs live's 26%
  (`device-dumps/2026-07-09_184031/REPORT.md`).
  **2026-07-10: that theory doesn't hold — checked, not fixed.** `app/build.gradle.kts`'s `release`
  buildType has `isMinifyEnabled = false` and there's no `proguard-rules.pro` in the repo to strip
  anything either way; the field device's installed package is confirmed `DEBUGGABLE` (`dumpsys
  package`), and rebuilding + installing the current debug APK and running a capture session on
  the device produces normal `CaptureService` `Log.i` lines in `adb logcat` immediately (including
  one that live-reproduced the empty-read bug: `recorded  (exact, 2 frames)`). The build was never
  stripping logs. The far more likely explanation is what the report already measured: ~10 minutes
  of main-buffer retention against a 2.4-hour drive, so anything from mid-session is long gone by
  pull time. The persisted capture-stats summary (`AppSettings.saveCaptureStats`) remains the only
  signal that reliably survives a full drive; a promotion-level journal (report recommendation 5's
  alternative) would go further but hasn't been built — flagged, not implemented, since the
  original premise motivating it was wrong.

### Runtime gotchas to settle during scaffolding (don't guess from memory)

- **Foreground service, screen-off while driving.** Android 14 requires a declared
  `foregroundServiceType` for camera + location plus the matching runtime-permission flow.
  **Verify the exact FGS type strings and permission requirements at the target API level** when
  scaffolding — don't hardcode from memory.
- **Idempotency.** WorkManager *will* retry; the backend keys on `id`. The dedup step must mint a
  **stable** `id` per logical sighting so a retry is a server-side no-op (proven by the backend's
  `TestUploadAndIdempotency`). Don't generate a fresh UUID at upload time.
- **Thermal/battery** over a real ~20 min+ drive — the spike's job is to find the throttle rate
  that survives this, and the app inherits that number.
- **Country.** `IT` is derivable from the validator; foreign-plate *format* detection is hard and
  deferred — non-IT reads default to `"?"` (which the backend/stats already bucket as unknown).

## Sequence summary

1. **Gate A** — user records a drive; run `spike_video_test.py`; judge real-plate reads. *(blocks on footage)*
2. **Gate B** — throwaway ORT spike app on the phone; measure CPU latency + thermal at throttle.
3. **Phase 1b** — build components 1–9; integrate against the live Go backend; confirm uploaded
   records match the seeder's shape and dedup on retry.
