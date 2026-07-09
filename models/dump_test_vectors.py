#!/usr/bin/env python3
"""Dump raw model I/O from the verified pipeline as fixtures for the Android JVM unit tests.

Writes files into android/app/src/test/resources/ so OcrDecoder/BoxMapper (the pure Kotlin ports)
can be asserted against ground truth produced by onnx_reference.py — without a device. Source
image is the same control image the androidTest instrumented control test uses
(../android/app/src/androidTest/assets/), so these fixtures are reproducible by anyone who clones
the repo — no real (and gitignored) dashcam footage required.

  ocr_vector.txt    : line1 = expected plate text; line2 = 370 space-separated floats (raw OCR output)
  det_rows.txt      : line1 = "r dw dh"; line2 = expected "x1 y1 x2 y2"; then N lines of 7 floats
  region_vector.txt : line1 = expected region label; line2 = 66 space-separated floats (raw region output)
"""

from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

from onnx_reference import DET_CONF, DET_PATH, DET_SIZE, OCR_PATH, detect, letterbox, ocr

OUT = Path("../android/app/src/test/resources")
CONTROL_IMAGE = "../android/app/src/androidTest/assets/control_CN555PL.png"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    det = ort.InferenceSession(DET_PATH)
    ocr_sess = ort.InferenceSession(OCR_PATH)

    frame = cv2.imread(CONTROL_IMAGE)
    if frame is None:
        raise SystemExit(f"couldn't read {CONTROL_IMAGE} — run from models/")
    boxes = detect(det, frame)
    if not boxes:
        raise SystemExit("no confident detection found in control image")
    box = max(boxes, key=lambda b: b[4])
    print(f"control image, box {box[:4]} score {box[4]:.3f}")

    # --- detector raw rows + letterbox params ---
    im, r, dw, dh = letterbox(frame, DET_SIZE)
    t = im.transpose(2, 0, 1)[::-1]
    t = (t / 255.0).astype(np.float32)[None]
    rows = det.run(["output0"], {"images": t})[0]  # [N,7]
    # expected mapped box = highest-score row above threshold, mapped back
    best = max((row for row in rows if row[6] >= DET_CONF), key=lambda r_: r_[6])
    ex1 = int(round((best[1] - dw) / r))
    ey1 = int(round((best[2] - dh) / r))
    ex2 = int(round((best[3] - dw) / r))
    ey2 = int(round((best[4] - dh) / r))
    with open(OUT / "det_rows.txt", "w") as f:
        f.write(f"{r} {dw} {dh}\n")
        f.write(f"{ex1} {ey1} {ex2} {ey2}\n")
        for row in rows:
            f.write(" ".join(repr(float(v)) for v in row) + "\n")
    print(f"det_rows.txt: {len(rows)} rows, expected box ({ex1},{ey1},{ex2},{ey2})")

    # --- ocr raw 370-vector + region 66-vector for the best box ---
    h, w = frame.shape[:2]
    x1, y1 = max(box[0], 0), max(box[1], 0)
    x2, y2 = min(box[2], w), min(box[3], h)
    crop = frame[y1:y2, x1:x2]
    rgb = cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)
    res = cv2.resize(rgb, (128, 64), interpolation=cv2.INTER_LINEAR)
    inp = res[None, :, :, :].astype(np.uint8)
    plate_vec, region_vec = ocr_sess.run(["plate", "region"], {"input": inp})
    plate_vec = plate_vec.reshape(-1)  # [370]
    region_vec = region_vec.reshape(-1)  # [66]
    text, conf, region = ocr(ocr_sess, frame, box)
    with open(OUT / "ocr_vector.txt", "w") as f:
        f.write(text + "\n")
        f.write(" ".join(repr(float(v)) for v in plate_vec) + "\n")
    print(f"ocr_vector.txt: text={text!r} conf={conf:.3f}, {plate_vec.size} floats")
    with open(OUT / "region_vector.txt", "w") as f:
        f.write(region + "\n")
        f.write(" ".join(repr(float(v)) for v in region_vec) + "\n")
    print(f"region_vector.txt: region={region!r}, {region_vec.size} floats")


if __name__ == "__main__":
    raise SystemExit(main())
