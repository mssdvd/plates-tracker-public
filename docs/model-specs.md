# ALPR model specs

The two open-source fast-alpr models we target, as downloaded (`models/download_models.py`), with
their ONNX I/O.

## Detector — `yolo-v9-t-384-license-plate-end2end` (open-image-models)

| | |
|---|---|
| Input  | `images` — `float32 [1, 3, 384, 384]` (NCHW, static batch, 384×384 RGB) |
| Output | `output0` — `float32 [batch, 7]` → `(batch_idx, x1, y1, x2, y2, class_id, score)` — **score is column 6** (verified against open-image-models `postprocess.py`; an earlier revision of this table had score/class swapped) |

- ✅ Static input shape.
- ⚠️ **"end2end" = NMS baked in.** The dynamic `[batch, 7]` output contains the detections.
- ℹ️ **open-image-models ships ONLY end2end variants** — sizes 256/384/416/512/608/640, all
  `yolo-v9-…-end2end`. There is no non-e2e export to switch to.
- 📐 **Size choice:** we have 384. Larger inputs (512/608) read smaller/farther plates better
  (highway) at higher latency; 256/384 are faster (city). Revisit after the in-car spike.
  2026-07-08: the in-car spike now confirms this is worth revisiting (oncoming plates still missed
  in good conditions). `models/download_models.py --detector-model yolo-v9-t-512-license-plate-end2end`
  fetches the 512 variant for comparison (same I/O contract, just 512 not 384 — verified). A
  workstation-only full-frame 384-vs-512 A/B was inconclusive by construction (tests a different
  config than proposed — see the false-negatives plan) and confirmed a real ~55-78% detector-latency
  cost. Don't ship this without the real drive test the plan calls for.
  **TODO (open, needs a real drive test, not started):**
  1. Try the 512 detector input for the zoom-pass crop specifically (keep 384 for the full-frame
     pass to bound latency on close-range detection) — the 512 ONNX is already downloaded
     (`models/work/detector-yolo-v9-t-512-license-plate-end2end.onnx`); wiring it into
     `Detector.kt`/`Alpr.kt` is not done.
  2. Only if #1 isn't enough: lower `BoxMapper.CONF` (0.4) for the zoom pass only, and watch
     false-positive promotions (not just recall) given the `instant`-path confidence-ceiling risk
     documented below.

## OCR — `cct-s-v2-global-model` (fast-plate-ocr)

**2026-07-06: switched from `european-plates-mobile-vit-v2-model`.** fast-plate-ocr moved that
model (and `global-plates-mobile-vit-v2-model`) to a "legacy — not recommended for continued
development" tier; the current default is the CCT family. Pulled the upstream validation JSONs
(GitHub release `arg-plates`) and compared **Italy-specific** `plate_acc`:

| Model | Italy plate_acc | Status |
|---|---|---|
| european-plates-mobile-vit-v2 (what we shipped) | 86.7% | legacy |
| global-plates-mobile-vit-v2 | 90.4% | legacy |
| cct-xs-v2-global | 96.8% | current |
| **cct-s-v2-global (now shipping)** | **97.5%** | current, upstream-recommended default |

Also faster on their GPU bench (0.68 ms vs 2.9 ms/plate) — no accuracy/speed tradeoff. Re-verified
on the same real frame `onnx_reference.py` already used: text still decodes correctly, now at
conf 1.000 (was 0.826 with the old model), box IoU 1.000 against `fast-alpr`'s own output.

**A/B'd old vs new on 3 real dashcam clips** (not just that one frame) — same detector, both OCR
models run on every detected crop, validated with `shared/plate_validation.py`:

| Clip | OLD valid reads (mean conf) | NEW valid reads (mean conf) | OLD invalid conf (p90) | NEW invalid conf (p90) |
|---|---|---|---|---|
| overcast (762 boxes) | 684 (0.796) | 688 (0.975) | 0.634 | 0.992 |
| sun (18 boxes) | 12 (0.681) | 13 (0.919) | 0.785 | 0.996 |
| dusk (296 boxes) | 254 (0.774) | 233 (0.991) | 0.520 | **1.000** |

⚠️ **NEW invalid conf (p90) column re-verified 2026-07-09 and doesn't reproduce exactly** — see the
🔴 bullet below for the redone numbers (different frame sampling stride, so box counts and
percentiles both shift; treat the direction as real, not these precise figures).

Distinct-car counts (3 frames + conf≥0.70 gate, same as `DedupEngine`) were equal-or-better with
the new model on every clip (e.g. overcast: 20 vs 17 cars — the new model additionally promoted
three plates that the old model's noisier per-frame reads never accumulated).

| | |
|---|---|
| Input  | `input` — `uint8 [?, 64, 128, 3]` (NHWC, **dynamic batch**, 128×64 **RGB**, not grayscale) |
| Output | `plate` — `float32 [?, 10, 37]` (**pre-shaped**, no manual reshape; 10 char slots) |
| Output | `region` — `float32 [?, 66]` — country classification head; **fetched since 2026-07-09** (`OcrDecoder.decodeRegion`/`REGIONS`, `Ocr.kt`) — argmax label mapped to ISO-2 (`REGION_TO_ISO2`), "Unknown" → `"?"`. Label order is `plate_regions:` in the model's own `cct_s_v2_global_plate_config.yaml`, not alphabetical — verified against a real Italian plate crop (decodes to `"Italy"`). ⚠️ Field-tested 2026-07-09: reliable on full 7-char reads (220/220 IT in all-Italian traffic) but **argmax on truncated crops confidently invents countries** — every one of the 32 foreign labels that drive was a false positive, 29 of them fragments under 7 chars (`device-dumps/2026-07-09_184031/REPORT.md`). Don't honor the head on short reads. |
| Config | `cct_s_v2_global_plate_config.yaml` (alphabet unchanged: `0-9A-Z_`, 37 classes) |

- ✅ `uint8` input — already quantization-friendly; pairs well with INT8.
- ⚠️ **Dynamic batch** is typically run with batch = 1.
- **RGB, not grayscale** — a real contract change from the old model, not just a size bump. Crop is
  BGR2RGB-converted (order still color-convert-then-resize, matching upstream), not grayscaled.
- 10 max plate slots (was 9). `shared/plate_validation.py`/`PlateValidator.kt` (Italian `LL DDD LL`
  + other formats) can validate/correct this output, but **2026-07-09: no longer does so in
  production** — see the 🔴 bullet below.
- Architecture is Conv2D tokenizer + transformer encoder (SiLU activations), not MobileViT. Its
  onnx2tf convertibility is **untested**.
- 🔴 **`DedupEngine`'s confidence floors rest on an assumption the new model partially breaks: that
  confidence separates genuinely-correct reads from garbled ones.** The A/B above measured
  confidence on *invalid* (non-format-matching) reads as a proxy for OCR noise: with the old model,
  invalid-read confidence topped out around p90 0.52–0.79 — comfortably below the 0.70 floor, so the
  floor did real filtering work. With the new model there's a **real high-confidence tail, smaller
  than first reported.** Re-run 2026-07-09 (real detector+OCR pipeline, same 3 clips,
  `shared/plate_validation.py`, sampled every 4th frame): 128 invalid reads total, p90 0.87–0.94 on
  2 of 3 clips (only the sunny clip hit the originally-claimed 0.99+ p90) and combined median only
  0.61 — so *most* invalid reads are still clearly separated from a correct read. The problem is the
  tail, not the bulk: **11 of 128 (8.6%) invalid reads still cleared 0.90**, including concrete
  garbled strings at near-certain confidence (e.g. 0.9996, 0.9994, 0.9945).
  Caveat: this measures format-*invalid* garbage as a proxy — the harder case (format-*valid* but
  wrong plate) isn't directly measurable without ground truth, so 8.6% is a lower bound on the real
  risk, not the real number.
  **2026-07-09 decision: added an `instant` path anyway** (≥1 frame, conf ≥0.90 — see
  `docs/android-app.md` component 5), on top of the existing `steady` (≥3 frames, ≥0.70) and `fast`
  (≥2 frames, ≥0.75) paths, because the 2026-07-09 real drive showed **83% of clusters are
  single-frame** — cars only ever caught in one processed frame, which `steady`/`fast` can
  structurally never promote regardless of confidence. This is a knowing tradeoff, not a resolution
  of the calibration problem above: `instant` inherits the same ~8.6%-lower-bound false-positive
  exposure, with zero persistence to fall back on (worse than `fast`, which itself was already the
  more exposed of the original two paths). Needs a real drive-test pass focused specifically on
  false-positive promotions from the `instant` path, not just read-rate, before trusting it further.
  **2026-07-09 (evening): that drive test happened and the exposure is real, and worse than the
  8.6% proxy** — full analysis in `device-dumps/2026-07-09_184031/REPORT.md`. Afternoon drive, 297
  promotions, ground truth all-Italian traffic: **~27% junk**, in two modes the clip A/B could not
  see. (1) **Empty reads at conf ≈1.0** (44 rows): the mean-of-slot-maxima confidence includes pad
  slots, so an all-pad output scores near-certain and clears the 0.90 instant bar — the ⚠️
  pad-inflation caveat in the decode row below, weaponized. (2) **Plate fragments** (33 rows): a
  truncated crop reads as a confident substring (e.g. a 3-char and a 4-char fragment of two
  different full plates, both promoted seconds apart), and the **region head argmaxes fragments to
  confident wrong countries**
  — all 32 foreign-labeled rows in the drive were false positives, 29/32 under 7 chars. Neither
  mode is garble in the A/B's sense: the characters read are *correct*, there are just too few of
  them, so per-read confidence can never catch this. Cheapest fixes measured against this data: a
  ≥7 min-length gate removes 74/80 junk rows; requiring a `PlateValidator` exact match on the
  instant path only removes all 80.
  **The `unconfirmed_reads` table (added 2026-07-06 for exactly this uncertainty) was retired the
  same day `instant` shipped** — see `docs/android-app.md` component 7. That trades away the
  earlier hedge (keep the data, decide later) for a direct bet (promote it now); if a false-positive
  problem shows up, there's no `unconfirmed_reads` safety net left to mine for how bad it was.
- 🔴 **Bounded confusion-correction in `PlateValidator`/`plate_validation.py` is disabled by
  default** (`enableCorrection`/`enable_correction`, defaults `false`, 2026-07-06). A faithful port
  of `DedupEngine` measured on real footage found it decided 0 of 24 real promotions — exact reads
  always arrived and always outrank a corrected one in the same dedup cluster (`rank()`), and
  `minConfidence` kills low-confidence corrections before they can promote regardless. Kept in the
  code rather than deleted (3 clips isn't exhaustive); pass `enableCorrection = true` to re-enable
  while investigating further. Tests cover both the disabled-by-default behavior and the opt-in
  correction behavior. **2026-07-09: doubly moot in production now** — `Alpr.kt` no longer calls
  `PlateValidator.validate()` at all (see the wire-contract note in `docs/android-app.md`), so this
  flag only matters if `PlateValidator` is called directly (e.g. from a future re-add of format
  validation, or ad hoc analysis).

## Pipeline configuration audit (2026-07)

Every pre/post-processing value in the shipping pipeline, checked against the upstream
fast-alpr / open-image-models / fast-plate-ocr sources (via the verified `models/onnx_reference.py`,
which reproduces fast-alpr byte-for-byte):

|Value                                                                                     |Where                                         |Verdict                                                                                                                                                                                                                                                                                               |
|------------------------------------------------------------------------------------------|----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|Detector conf threshold **0.4**                                                           |`onnx_reference.py DET_CONF`, `BoxMapper.CONF`|✅ fast-alpr's `detector_conf_thresh` default; consistent Python/Kotlin                                                                                                                                                                                                                                |
|Letterbox 384, pad **114**, aspect-preserving, ±0.1 rounding                              |`letterbox()`, `Detector.kt`                  |✅ mirrors `open_image_models` exactly; Kotlin box parity ≤1 px (unit-tested)                                                                                                                                                                                                                          |
|Detector input RGB **/255** NCHW                                                          |both                                          |✅ upstream                                                                                                                                                                                                                                                                                            |
|Score = **column 6** of `[N,7]`                                                           |both                                          |✅ verified in upstream `postprocess.py`                                                                                                                                                                                                                                                               |
|OCR input **raw uint8**, no /255                                                          |both                                          |✅ the model normalizes internally — do not add /255                                                                                                                                                                                                                                                   |
|OCR crop: clamp → exact bbox (no margin) → **BGR2RGB → resize** 128×64 bilinear           |both                                          |✅ upstream order (color-convert before resize); changed from grayscale 140×70 with the 2026-07-06 model swap                                                                                                                                                                                          |
|Decode: argmax/slot, strip *trailing* pad, conf = **mean of slot maxima over all 10 slots**|`OcrDecoder`, reference                      |✅ upstream fast-alpr behavior (`postprocess_output` + `statistics.mean` over the full, untrimmed confidence list). ⚠️ Caveat carried over from the old model: pad slots are usually near-certain and inflate the mean. The dedup gate (`steady` ≥3 frames ≥0.70, `fast` ≥2 frames ≥0.75, `instant` ≥1 frame ≥0.90 — 2026-07-09) was calibrated on Gate-A data **with the old model's confidence distribution**; the 🔴 bullet above has the 2026-07-09 re-verification against this model.|
|ORT sessions: default `SessionOptions()`                                                  |`Detector.kt`/`Ocr.kt`                        |✅ CPU EP. Note: the XNNPACK EP is *opt-in* in onnxruntime-android, not automatic — a possible on-device speedup to measure during Gate B, not to enable blind.                                                                                                                                        |
|No min-box-size filter before OCR                                                         |pipeline                                      |ℹ️ upstream has none either; tiny far crops cost one OCR pass (~ms) and are then killed by validation/dedup. Revisit only if drive thermals demand it.                                                                                                                                                |

