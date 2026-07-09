# Plates Tracker — Android capture app

**Status: Gate-B spike.** Camera → on-device ALPR → on-screen overlay. No database, no upload, no
location yet. The single question this answers: *do plate reads + per-frame latency hold up on the
actual phone CPU?* (See `../docs/spike.md` Step 2 and `../docs/android-app.md`.)

## What it does

`ImageAnalysis` frames (throttled to ~8 fps) run through the same two ONNX models proven on the
workstation, reimplemented in Kotlin against **onnxruntime-android** (CPU/XNNPACK):

- `Detector.kt` / `Ocr.kt` are a **direct transliteration of `../models/onnx_reference.py`**, which
  was verified to reproduce fast-alpr's output byte-for-byte (same text, IoU 1.000) on a real frame.
  If reads differ on-device, suspect capture (rotation/exposure) before the math.
- `PlateValidator.kt` is a minimal Italian-format regex for the spike; the full
  `shared/plate_validation.py` port comes after Gate B.

The HUD shows EMA latency, detection count, and the best valid plate. Boxes draw over the preview.

## Build & run

1. Open the **`android/`** folder in Android Studio (Giraffe+).
2. Let it sync Gradle (AGP 9.2.1 / Gradle 9.6.1 / compileSdk 37 — Android 17 / built-in Kotlin).
3. Plug in the Android phone (USB debugging on), Run.

> **Don't try `./gradlew` from a plain shell on this box** — the system JDK is Java 25, which AGP
> rejects. Android Studio's bundled JDK (21) builds fine. That's expected, not a bug to chase.

### Verified on-host (no device)

This project was **compiled and unit-tested without a device** using Android Studio's bundled JDK 21
(`/opt/android-studio/jbr`) + SDK platform-35. Reproduce with the helper (a `gradlew` substitute that
invokes the wrapper's main class directly):

```sh
./build-and-test.sh assembleDebug      # ✅ produces app/build/outputs/apk/debug/app-debug.apk
./build-and-test.sh testDebugUnitTest  # ✅ 7 tests pass
```

The unit tests (`app/src/test/`) check the **pure ALPR math against the Python reference**, not a
device: `OcrDecoder` decodes a real model `[370]` vector to `CN555PL`, and `BoxMapper` reproduces the
reference box within 1px. Fixtures in `app/src/test/resources/` are dumped by
`../models/dump_test_vectors.py`. So the only thing still unverified is on-device runtime (Gate B
itself) — the math is proven.

The model files (`app/src/main/assets/{detector,ocr}.onnx`, ~12 MB) are committed as app assets —
they are open-source models.

## Control test — run this on the phone BEFORE driving

`AlprControlTest` (instrumented) feeds a **known still image** (`androidTest/assets/control_CN555PL.png`
— a plate with no bearing on any real vehicle, not sourced from this project's own dashcam footage)
through the *full on-device pipeline* — real ORT inference plus the Android
Bitmap preprocessing the JVM unit tests can't reach (letterbox via Canvas, RGBA→RGB, BGR→gray,
`createScaledBitmap`) — and asserts it reads **CN555PL**. The Python reference reads exactly that from
this image, so this is a ground-truth control.

```sh
# with the Android phone connected (USB debugging on):
./build-and-test.sh connectedDebugAndroidTest      # or run AlprControlTest from Android Studio
```

**Why run it first:** it decouples the two Gate-B failure modes. Control **passes** → the pipeline is
correct on-device, so any poor *driving* reads are motion/capture/speed, not a port bug. Control
**fails** → the Android-graphics port drifted from `../models/onnx_reference.py`; fix that before
trusting any drive. (The pure math is already covered by the JVM unit tests; this covers the
Android-graphics + real-ORT path they can't.)

## Gate-B test procedure

Once the control passes, mount the phone, drive a daylight route (per the spike findings, light is the
dominant lever), and judge:

1. **Reads** — do real Italian plates show on screen as the green (valid) box with correct text?
2. **Latency** — does the HUD hold a usable rate (workstation CPU was ~37 ms; phone CPU will differ)?
3. **Thermals** — does it stay stable over ~15–20 min without the rate collapsing?

Pass → proceed to Phase 1b (dedup/voting, location, Room, WorkManager sync to the Go backend).
The wire contract is already fixed (see `../docs/android-app.md`).

## Known spike-level caveats (intentional, not bugs)

- **Box alignment is approximate** — overlay assumes PreviewView FILL_CENTER; minor offset is fine
  for judging reads.
- **No multi-frame voting / dedup yet** — every frame is independent, so the same plate flickers and
  low-confidence noise appears. That's the next component, gated on this passing.
- **CPU only** — runs on CPU/XNNPACK by design (see `../docs/spike.md`).
- **Resampling parity** — Android `createScaledBitmap` (bilinear) vs OpenCV `INTER_LINEAR` differ by
  sub-pixel amounts; not expected to change argmax decisions.
