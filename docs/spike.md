# Phase 0 — feasibility spike

**Make-or-break question:** can we read Italian plates from a *moving* car (blur, closing speed,
angle)? Answer this before building anything on the phone.

## Step 1 — test on real footage NOW (workstation, no app)

fast-alpr already runs in `models/.venv-convert`. Record 1–2 min of windshield video on a drive
(city + a stretch of faster road), then:

```bash
cd models
.venv-convert/bin/python spike_video_test.py --video drive.mp4 --fps 8 --annotated out.mp4
```

It runs the same detector + OCR the app will use, validates each read against the Italian format
(`shared/plate_validation.py`), and prints unique plates + per-frame latency. **Verified working**
end-to-end on a synthetic plate (`AB123CD` → exact match, ~41 ms/frame CPU). Judge: do real plates
show up as ✓ valid reads at the speeds you drove? If not, fix capture (shutter speed, mounting,
detector input size 384→512/608) before any phone work.

## Gate A results — real dusk footage

First real-footage run: a 6.5 min, 1080p dusk clip (~21:18 CEST), `--fps 8`.
**Gate A passes — but narrowly, and with design consequences.**

- **Latency is not the bottleneck.** p50 38 ms / p95 63 ms per frame on CPU (~26 fps capable) —
  an 8 fps throttle has large headroom before any NPU.
- **A followed (lead) car reads reliably.** One plate held in frame ~339 processed frames (~42 s)
  → exact, conf **0.82**. For "log the cars I follow," this works *today*.
- **Oncoming / passing traffic is mostly unreadable at dusk.** The long tail is single-frame,
  low-confidence (0.4–0.5), format-invalid garbage — motion blur + closing speed + angle, exactly
  the predicted hard problem.
- **The "13 passed validation" count is misleading.** ~5–6 of them are *the same lead car* — its
  own per-frame misreads (edit distance 1–2 from the canonical read), several only passing
  *because* `plate_validation`'s confusion-correction reshaped garbled output into a format-valid
  string at 0.4–0.7 conf / 1 frame. True distinct count ≈ **one well-read car + a halo of its
  misreads + a few untrustworthy singles.**

**Two design rules this forces (now first-class, were footnotes):**

1. **Dedup must be fuzzy, not exact-string.** Exact-match collapse treats per-frame OCR variants
   of the same plate as distinct cars. Need **edit-distance + temporal clustering** (same plate
   within a short time window, within ~1–2 char edits, collapses to the highest-confidence
   reading). See
   [`android-app.md`](android-app.md) component 5.
2. **A sighting requires N-frame persistence AND a confidence floor.** A 1-frame "corrected" 0.4
   read must never become a record — that's the false-positive path. Confusion-correction without a
   persistence gate manufactures plates.

**Resolved — light is the dominant lever, not closing speed.** Re-ran a brighter overcast
daylight clip (13 min). Applying an honest gate (seen ≥3 frames, conf ≥0.70) + fuzzy
near-duplicate merge (Levenshtein ≤2, canonical = most frames) to both clips:

| clip | light | raw reads | distinct cars (gated + deduped) |
|---|---|---|---|
| dusk (6.5 min) | dusk | 390 | **1** |
| overcast (13 min) | overcast daylight | 1067 | **18** |

- **~18 distinct vehicles in 13 min of daylight** (~1.4/min), all multi-frame exact reads at
  0.80–0.85. The dusk clip gave **one** (the lead car). The dusk pessimism was a *lighting*
  artifact, not a "too fast" wall.
- **Many of the 18 are brief 3–15 frame catches** — passing/oncoming cars read fine in adequate
  light, so the product is *not* limited to followed vehicles.
- **The gate + fuzzy dedup is validated:** 187 raw rows → 19 gated → 18 distinct; one plate's
  misread halo (single-edit variants from per-frame OCR slips) collapsed correctly to one car.
- **Capture implication:** time-of-day/exposure is the #1 lever (bigger than detector size or fps).
  App should favour daylight; dusk/night is a degraded mode. Adaptive-fps (burst the rate while a
  plate is in frame) is worthwhile *in good light* — it pushes brief single-frame catches over the
  ≥3-frame promotion bar. (Clustering script: `scratchpad/cluster.py`, throwaway.)

Annotated outputs (git-ignored, local): `models/footage/annotated/`.

### Adaptive-fps experiment (overcast clip, 13.8 min)

`spike_video_test.py --adaptive` drops to a low idle rate and bursts to a high rate while a plate
is in frame. Compared against fixed rates on the same clip (gate: ≥3 frames, conf ≥0.70):

| mode | frames processed | distinct cars | cars / 1k frames |
|---|---|---|---|
| fixed 8 fps | 6,165 | 19 | 3.08 |
| fixed 16 fps | 12,330 | 22 | 1.78 |
| adaptive (idle 2 / burst 16, hold 1.5 s) | **4,242** | 18 | **4.24** |

- **Adaptive is an efficiency win, not a recall win:** ~fix-8 recall (18 vs 19) for **~31% less
  compute**, and ~2× denser sampling of the plates it does catch (1,985 raw reads vs fix-8's 1,057
  → higher-confidence canonical reads).
- **It did not raise the car count.** Idle 2 fps samples only every ~0.5 s, so a sub-0.5 s pass is
  never detected and the burst never fires (it missed one 3-frame car fix-8 caught).
- **fix-16 buys only +3 cars for 2× compute** — steep diminishing returns; the extra cars are
  brief passes.
- **Lever:** recall is set by the *baseline* rate (don't miss brief entries), battery by how low it
  drops. Idle-2 over-saved by one car; idle-**4** is the likely sweet spot (recover brief plates,
  still cheaper than fix-8) — untested. On-device, "burst" also means a thermal headroom decision,
  not just recall.

### Image enhancement (contrast/brightness) — negative result

Tested `--preprocess {clahe, gamma, clahe_gamma, clahe_sharpen}` on the dusk clip (fix-8), where
there's the most headroom (baseline: 1 car). **No method moved the needle** — all within noise:

| preprocess | raw reads | distinct cars |
|---|---|---|
| none | 380 | 1 |
| clahe | 383 | 1 |
| gamma 0.6 | 385 | 1 |
| clahe_gamma | 378 | 1 |
| clahe_sharpen | 384 | 1 |

**Conclusion:** dusk's bottleneck is **motion blur + photon starvation**, not tonal range. CLAHE/
gamma only redistribute brightness; unsharp masking sharpens the blur. Post-processing can't restore
spatial detail the sensor never captured. **Low-light reads are a capture-time problem** (shutter
speed vs. ISO-noise tradeoff), not a post-filter one — don't build enhancement filters into the app.
The code path stays in `spike_video_test.py` for re-testing, but the lever is exposure, or just
prefer daylight (consistent with Gate A: light is the dominant variable).

## Step 2 — on-device spike runtime: ONNX Runtime Mobile (decision)

When moving to the phone, build the spike against **`onnxruntime-android`**, feeding the `.onnx`
models directly — **not** TFLite/LiteRT. Rationale:

- The MobileViT OCR **does not convert through onnx2tf** cleanly (channel-placement bug on
  depthwise-BN; would need a hand-authored parameter-replacement JSON). ONNX Runtime runs it as-is.
- The detector's **end2end NMS** is an asset here: ORT returns
  final boxes, so there's **no NMS to reimplement in Kotlin**.
- The Kotlin pre/post-processing (normalize → crop → 70×140 grayscale → decode `[1,333]` = 9×37
  argmax, alphabet/length from `european_mobile_vit_v2_ocr_config.yaml`) is the **same work** TFLite
  would need — so no rework is lost by choosing ORT for the spike.

> **2026-07-06 update:** the OCR model shipped since this spike was swapped from
> `european-plates-mobile-vit-v2-model` to `cct-s-v2-global-model` (the mobile-vit models are now
> legacy upstream, and scored far worse on Italy specifically — see `docs/model-specs.md`). The
> MobileViT-specific onnx2tf gotcha described above no longer applies to the shipping model.
