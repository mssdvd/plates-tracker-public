#!/usr/bin/env python3
"""Raw-onnxruntime ALPR reference — the spec the Kotlin/Android port transliterates.

Why this exists: the Android app cannot use fast-alpr (Python). It must reimplement the exact
pre/post-processing in Kotlin against onnxruntime-android. This script reproduces fast-alpr's
result using ONLY `onnxruntime.InferenceSession` + hand-written numpy pre/post-processing, and
asserts it matches the high-level fast-alpr output on a real frame. If raw == fast-alpr here, the
Kotlin is a mechanical transliteration of verified code — not a hopeful guess.

Pipeline contract (verified against the installed open-image-models / fast-plate-ocr source):

DETECTOR  work/detector.onnx
  input  "images"   float32 [1,3,384,384]  NCHW, RGB, /255
  output "output0"  float32 [N,7] = [batch_idx, x1, y1, x2, y2, class_id, score]  (NMS baked in)
  preprocess: LETTERBOX to 384 (keep aspect, pad 114) -> CHW -> BGR2RGB -> /255 -> float32
  postprocess: keep score(col6) >= 0.4; map box back: (coord - pad) / ratio

OCR  work/ocr.onnx  (cct-s-v2-global-model — european-plates-mobile-vit-v2-model is now legacy
                     upstream, see docs/model-specs.md: 86.7% vs 97.5% plate_acc on Italy)
  input  "input"  uint8  [1,64,128,3]  NHWC, RGB, RAW 0-255 (NO /255 — model normalizes internally)
  output "plate"   float32 [1,10,37] = 10 slots x 37 classes (pre-shaped, no manual reshape needed)
  output "region"  float32 [1,66] — country classification head (2026-07-09: now fetched; label
                   order is REGIONS below, from the model's own plate config yaml, not alphabetical)
  preprocess: clamp bbox to frame -> crop -> BGR2RGB -> PLAIN resize to (W=128,H=64), bilinear
  postprocess: argmax per slot -> alphabet -> rstrip pad '_'; conf = mean(max) over all 10 slots;
               region = argmax over REGIONS
"""

from __future__ import annotations

import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

DET_PATH = "work/detector.onnx"
OCR_PATH = "work/ocr.onnx"
DET_SIZE = 384
OCR_H, OCR_W = 64, 128
ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_"
PAD_CHAR = "_"
MAX_SLOTS = 10
DET_CONF = 0.4  # fast_alpr ALPR default detector_conf_thresh

# "region" output (float32 [1,66]) label order, from the model's own cct_s_v2_global_plate_config
# .yaml (plate_regions:) — NOT alphabetical. Index order matters for argmax; copied verbatim from
# ~/.cache/fast-plate-ocr/cct-s-v2-global-model/cct_s_v2_global_plate_config.yaml, do not reorder.
REGIONS = [
    "Albania", "Andorra", "Argentina", "Armenia", "Australia", "Austria", "Azerbaijan", "Bahrain",
    "Belarus", "Belgium", "Bosnia and Herzegovina", "Brazil", "Bulgaria", "Cambodia", "Canada",
    "Croatia", "Cyprus", "Czech Republic", "Denmark", "Estonia", "Finland", "France", "Georgia",
    "Germany", "Gibraltar", "Greece", "Guernsey", "Hungary", "Iceland", "Indonesia", "Ireland",
    "Israel", "Italy", "Latvia", "Liechtenstein", "Lithuania", "Luxembourg", "Malaysia", "Malta",
    "Mexico", "Moldova", "Monaco", "Montenegro", "Netherlands", "New Zealand", "North Macedonia",
    "Norway", "Poland", "Portugal", "Qatar", "Romania", "San Marino", "Serbia", "Singapore",
    "Slovakia", "Slovenia", "Spain", "Sweden", "Switzerland", "Thailand", "Turkey",
    "United States", "Ukraine", "United Kingdom", "Vietnam", "Unknown",
]


# ---- detector ----------------------------------------------------------------

def letterbox(img, new=384, color=(114, 114, 114)):
    """Aspect-preserving resize + pad to new x new. Mirrors open_image_models.preprocess.letterbox."""
    h, w = img.shape[:2]
    r = min(new / h, new / w)
    nw, nh = round(w * r), round(h * r)
    dw, dh = (new - nw) / 2, (new - nh) / 2
    if (w, h) != (nw, nh):
        img = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    top, bottom = round(dh - 0.1), round(dh + 0.1)
    left, right = round(dw - 0.1), round(dw + 0.1)
    img = cv2.copyMakeBorder(img, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color)
    return img, r, dw, dh


def detect(sess, frame_bgr):
    """Return list of (x1,y1,x2,y2,score) in original-frame pixels."""
    im, r, dw, dh = letterbox(frame_bgr, DET_SIZE)
    im = im.transpose(2, 0, 1)[::-1]  # HWC->CHW, BGR->RGB
    im = (im / 255.0).astype(np.float32)[None]  # add batch
    out = sess.run(["output0"], {"images": im})[0]  # [N,7]
    boxes = []
    for row in out:
        score = float(row[6])  # col6 = score (col5 = class) — verified in postprocess.py
        if score < DET_CONF:
            continue
        x1 = (row[1] - dw) / r
        y1 = (row[2] - dh) / r
        x2 = (row[3] - dw) / r
        y2 = (row[4] - dh) / r
        boxes.append((int(x1), int(y1), int(x2), int(y2), score))
    return boxes


# ---- ocr ---------------------------------------------------------------------

def ocr(sess, frame_bgr, box):
    """Run OCR on one detection box; return (text, mean_confidence, region)."""
    h, w = frame_bgr.shape[:2]
    x1, y1 = max(box[0], 0), max(box[1], 0)
    x2, y2 = min(box[2], w), min(box[3], h)
    crop = frame_bgr[y1:y2, x1:x2]
    if crop.size == 0:
        return "", 0.0, "Unknown"
    rgb = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)
    resized = cv2.resize(rgb, (OCR_W, OCR_H), interpolation=cv2.INTER_LINEAR)  # (64,128,3)
    inp = resized[None, :, :, :].astype(np.uint8)  # (1,64,128,3), RAW uint8
    plate_out, region_out = sess.run(["plate", "region"], {"input": inp})
    probs = plate_out.reshape(MAX_SLOTS, len(ALPHABET))
    idx = probs.argmax(axis=-1)
    text = "".join(ALPHABET[i] for i in idx).rstrip(PAD_CHAR)
    conf = float(probs.max(axis=-1).mean())
    region = REGIONS[int(region_out.reshape(-1).argmax())]
    return text, conf, region


# ---- validation against fast-alpr -------------------------------------------

def find_frame_with_plate(video, det_sess):
    """Scan the clip for a frame the raw detector is confident about; return (frame, box)."""
    cap = cv2.VideoCapture(video)
    best = None
    idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        idx += 1
        if idx % 30:  # ~1 fps scan is plenty to find a good frame
            continue
        for b in detect(det_sess, frame):
            if best is None or b[4] > best[2][4]:
                best = (idx, frame.copy(), b)
        if best and best[2][4] > 0.85 and idx > 300:
            break
    cap.release()
    return best


def main():
    video = sys.argv[1] if len(sys.argv) > 1 else "footage/20260629_1853_overcast.mp4"
    if not Path(DET_PATH).exists():
        sys.exit(f"missing {DET_PATH} — run from models/")

    det = ort.InferenceSession(DET_PATH)
    ocr_sess = ort.InferenceSession(OCR_PATH)

    found = find_frame_with_plate(video, det)
    if not found:
        sys.exit("no confident detection found in clip")
    fidx, frame, box = found
    print(f"using frame {fidx}, raw-detector box {box[:4]} score {box[4]:.3f}")

    # Raw-ORT pipeline result
    raw_text, raw_conf, raw_region = ocr(ocr_sess, frame, box)
    print(f"RAW-ORT:   text={raw_text!r}  conf={raw_conf:.3f}  region={raw_region!r}")

    # fast-alpr reference on the SAME frame
    from fast_alpr import ALPR
    alpr = ALPR(detector_model="yolo-v9-t-384-license-plate-end2end",
                ocr_model="cct-s-v2-global-model")
    results = alpr.predict(frame)
    if not results:
        sys.exit("fast-alpr found nothing on this frame (unexpected)")
    r0 = max(results, key=lambda r: r.detection.confidence)
    fa_box = (r0.detection.bounding_box.x1, r0.detection.bounding_box.y1,
              r0.detection.bounding_box.x2, r0.detection.bounding_box.y2)
    fa_text = r0.ocr.text if r0.ocr else ""
    print(f"FAST-ALPR: text={fa_text!r}  box={fa_box}  det_conf={r0.detection.confidence:.3f}")

    # Verdict
    def iou(a, b):
        ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
        ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
        inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
        ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return inter / ua if ua else 0.0

    box_iou = iou(box[:4], fa_box)
    print(f"\nbox IoU (raw vs fast-alpr): {box_iou:.3f}")
    ok_text = raw_text == fa_text
    ok_box = box_iou > 0.9
    print(f"text match: {ok_text}   box match (IoU>0.9): {ok_box}")
    if ok_text and ok_box:
        print("\n✅ RAW-ORT pipeline reproduces fast-alpr. Kotlin can transliterate this file.")
        return 0
    print("\n❌ mismatch — fix the reference before porting to Kotlin.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
