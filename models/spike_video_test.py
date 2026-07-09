#!/usr/bin/env python3
"""Feasibility spike (workstation): run fast-alpr over windshield footage and report read quality.

This answers the make-or-break question — *can we read Italian plates from a moving car?* — with
ZERO Android code. Record 1-2 min of windshield video on a drive, then:

  .venv-convert/bin/python spike_video_test.py --video drive.mp4 --fps 8 --annotated out.mp4

It throttles to ~--fps (mimicking the on-device pipeline), runs the same detector+OCR the app will
use, validates each read against the Italian plate format (shared/plate_validation.py), and prints
a summary of unique plates + per-frame latency. Look at whether real plates show up at city vs
highway speed before building anything on the phone.

Deps: the models/.venv-convert venv (fast-alpr, opencv-python-headless). No GPU needed.
"""

from __future__ import annotations

import argparse
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

# Reuse the canonical Italian validator.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "shared"))
from plate_validation import Confidence, validate  # noqa: E402


def _conf(c) -> float:
    """OcrResult.confidence is float or per-char list[float]; reduce to a single mean score."""
    if isinstance(c, (list, tuple)):
        return sum(c) / len(c) if c else 0.0
    return float(c)


def _ts(frame_idx, src_fps) -> str:
    """Video-relative time of a frame as M:SS.d — for locating the read in the source clip."""
    secs = frame_idx / src_fps if src_fps else 0.0
    m, s = divmod(secs, 60)
    return f"{int(m)}:{s:04.1f}"


def _runtag(args) -> str:
    """Short, unique-per-config label baked into the annotated filename so runs don't clobber."""
    base = (
        f"adaptive_b{args.baseline_fps:g}_burst{args.burst_fps:g}_hold{args.burst_hold:g}"
        if args.adaptive
        else f"fix{args.fps:g}"
    )
    if args.preprocess != "none":
        base += f"_{args.preprocess}"
        if "gamma" in args.preprocess:
            base += f"{args.gamma:g}"
    return base


def _preprocess(cv2, np, frame, method: str, gamma: float):
    """Enhance the analysis frame before detection/OCR. Aimed at low-light (dusk) recovery.

    Cheap enough to run per-frame on-device. Returns a BGR uint8 frame.
    """
    if method == "none":
        return frame
    if method == "clahe":  # local contrast on luminance — classic low-light ALPR boost
        lab = cv2.cvtColor(frame, cv2.COLOR_BGR2LAB)
        l, a, b = cv2.split(lab)
        l = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(l)
        return cv2.cvtColor(cv2.merge((l, a, b)), cv2.COLOR_LAB2BGR)
    if method == "gamma":  # lift shadows (gamma < 1 brightens)
        lut = np.array([((i / 255.0) ** gamma) * 255 for i in range(256)], dtype=np.uint8)
        return cv2.LUT(frame, lut)
    if method == "clahe_gamma":
        return _preprocess(cv2, np, _preprocess(cv2, np, frame, "gamma", gamma), "clahe", gamma)
    if method == "clahe_sharpen":
        out = _preprocess(cv2, np, frame, "clahe", gamma)
        blur = cv2.GaussianBlur(out, (0, 0), 3)
        return cv2.addWeighted(out, 1.5, blur, -0.5, 0)  # unsharp mask
    raise SystemExit(f"unknown --preprocess {method}")


def _lev(a: str, b: str) -> int:
    """Levenshtein distance, short-circuited — same fuzzy-merge rule as the dedup design."""
    if abs(len(a) - len(b)) > 2:
        return 9
    dp = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        prev, dp[0] = dp[0], i
        for j, cb in enumerate(b, 1):
            prev, dp[j] = dp[j], min(dp[j] + 1, dp[j - 1] + 1, prev + (ca != cb))
    return dp[-1]


def _distinct(seen: dict, min_frames: int, min_conf: float) -> list[str]:
    """Distinct cars = format-valid reads clearing the persistence+confidence gate, fuzzy-merged.

    Mirrors the on-device promotion rule (docs/spike.md): a single car emits a halo of per-frame
    misreads, so collapse reads within edit distance 2, keeping the most-seen as canonical.
    """
    gated = [
        (k, v) for k, v in seen.items() if v["kind"] != "invalid" and v["best"] >= min_conf and v["frames"] >= min_frames
    ]
    gated.sort(key=lambda kv: -kv[1]["frames"])  # canonical = most frames
    canon: list[str] = []
    for k, _ in gated:
        if not any(_lev(k, c) <= 2 for c in canon):
            canon.append(k)
    return canon


def _draw(cv2, frame, results, ocr_conf: float):
    """Annotate from predictions we already computed (no second inference pass)."""
    for r in results:
        bb = r.detection.bounding_box
        x1, y1, x2, y2 = int(bb.x1), int(bb.y1), int(bb.x2), int(bb.y2)
        cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 255, 0), 2)
        if r.ocr and r.ocr.text and _conf(r.ocr.confidence) >= ocr_conf:
            label = f"{r.ocr.text} {_conf(r.ocr.confidence):.2f}"
            cv2.putText(frame, label, (x1, max(12, y1 - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
    return frame


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--video", required=True, help="Path to a video file (windshield footage).")
    ap.add_argument("--fps", type=float, default=8.0, help="Fixed mode: frames/sec to process.")
    ap.add_argument("--annotated", metavar="DIR", help="Write annotated video into DIR (filename auto-derived, unique per clip+config).")
    ap.add_argument("--ocr-conf", type=float, default=0.4, help="Min OCR confidence to keep a read.")
    ap.add_argument("--preprocess", default="none",
                    choices=["none", "clahe", "gamma", "clahe_gamma", "clahe_sharpen"],
                    help="Enhance frames before detection/OCR (low-light recovery).")
    ap.add_argument("--gamma", type=float, default=0.6, help="Gamma for --preprocess gamma/* (<1 brightens).")
    # Adaptive throttle: idle cheap, burst the rate while a plate is in frame.
    ap.add_argument("--adaptive", action="store_true", help="Burst the processing rate while a plate is detected.")
    ap.add_argument("--baseline-fps", type=float, default=2.0, help="Adaptive: idle rate when no plate is in frame.")
    ap.add_argument("--burst-fps", type=float, default=16.0, help="Adaptive: rate while a plate is in frame.")
    ap.add_argument("--burst-hold", type=float, default=1.5, help="Adaptive: keep bursting N seconds after the last detection.")
    # Distinct-car gate (mirrors the on-device promotion rule).
    ap.add_argument("--gate-frames", type=int, default=3, help="Report: min frames to count a plate as a distinct car.")
    ap.add_argument("--gate-conf", type=float, default=0.70, help="Report: min confidence to count a distinct car.")
    args = ap.parse_args()

    try:
        import cv2
        import numpy as np
        from fast_alpr import ALPR
    except ImportError as e:
        sys.exit(f"ERROR: run inside models/.venv-convert ({e}).")

    cap = cv2.VideoCapture(args.video)
    if not cap.isOpened():
        sys.exit(f"ERROR: cannot open video {args.video}")
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0

    if args.adaptive:
        baseline_stride = max(1, round(src_fps / args.baseline_fps))
        burst_stride = max(1, round(src_fps / args.burst_fps))
        hold_frames = max(1, round(args.burst_hold * src_fps))
        print(f"[spike] {args.video}: {src_fps:.1f} fps source, ADAPTIVE "
              f"(idle {args.baseline_fps:g}fps / burst {args.burst_fps:g}fps, hold {args.burst_hold:g}s)")
    else:
        fixed_stride = max(1, round(src_fps / args.fps))
        print(f"[spike] {args.video}: {src_fps:.1f} fps source, FIXED every {fixed_stride} frame(s) (~{args.fps:g}fps)")

    out_path = None
    if args.annotated:
        out_dir = Path(args.annotated)
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / f"{Path(args.video).stem}__{_runtag(args)}.mp4"

    alpr = ALPR(
        detector_model="yolo-v9-t-384-license-plate-end2end",
        ocr_model="cct-s-v2-global-model",
    )

    writer = None
    latencies: list[float] = []
    # plate_text -> {best_conf, frames, valid_kind, norm, first_frame, first_ts}
    seen: dict[str, dict] = defaultdict(lambda: {
        "best": 0.0,
        "frames": 0,
        "kind": "invalid",
        "norm": "",
        "first_frame": 0,
        "first_ts": "0:00.0",
    })
    raw_reads = 0
    frame_idx = 0
    last_processed = -(10**9)
    burst_until = -(10**9)

    while True:
        ok, frame = cap.read()
        if not ok:
            break
        frame_idx += 1
        if args.adaptive:
            stride = burst_stride if frame_idx <= burst_until else baseline_stride
        else:
            stride = fixed_stride
        if frame_idx - last_processed < stride:
            continue
        last_processed = frame_idx

        t0 = time.perf_counter()
        frame = _preprocess(cv2, np, frame, args.preprocess, args.gamma)
        results = alpr.predict(frame)
        latencies.append((time.perf_counter() - t0) * 1000.0)

        if args.adaptive and results:  # a plate is in frame → harvest the next burst window
            burst_until = frame_idx + hold_frames

        for r in results:
            if r.ocr is None or not r.ocr.text:
                continue
            conf = _conf(r.ocr.confidence)
            if conf < args.ocr_conf:
                continue
            raw_reads += 1
            v = validate(r.ocr.text)
            key = v.normalized or r.ocr.text
            rec = seen[key]
            rec["frames"] += 1
            if rec["frames"] == 1:
                rec["first_frame"] = frame_idx
                rec["first_ts"] = _ts(frame_idx, src_fps)
            if conf > rec["best"]:
                rec["best"] = conf
                rec["kind"] = v.confidence.value
                rec["norm"] = v.normalized

        if out_path:
            _draw(cv2, frame, results, args.ocr_conf)
            if writer is None:
                h, w = frame.shape[:2]
                play_fps = args.burst_fps if args.adaptive else args.fps
                writer = cv2.VideoWriter(str(out_path), cv2.VideoWriter_fourcc(*"mp4v"), play_fps, (w, h))
            writer.write(frame)

    cap.release()
    if writer:
        writer.release()
        print(f"[spike] annotated → {out_path}")

    _report(seen, latencies, raw_reads, frame_idx, src_fps, args.gate_frames, args.gate_conf)
    return 0


def _report(seen, latencies, raw_reads, frames, src_fps, gate_frames, gate_conf):
    valid = {k: v for k, v in seen.items() if v["kind"] != "invalid"}
    proc = len(latencies)
    dur = frames / src_fps if src_fps else 0.0
    eff = proc / dur if dur else 0.0
    print("\n================ SPIKE REPORT ================")
    print(f"frames read: {frames}   frames processed: {proc}   raw OCR reads: {raw_reads}")
    print(f"effective processing rate: {eff:.1f} fps over {dur / 60:.1f} min")
    if latencies:
        lat = sorted(latencies)
        p50 = statistics.median(lat)
        p95 = lat[min(len(lat) - 1, int(0.95 * len(lat)))]
        print(f"latency/frame (CPU): p50 {p50:.0f} ms   p95 {p95:.0f} ms   (=> ~{1000 / p50:.1f} fps ceiling)")
    cars = _distinct(seen, gate_frames, gate_conf)
    print(f"\nDISTINCT CARS (>= {gate_frames} frames, conf >= {gate_conf}, fuzzy-merged): {len(cars)}")
    if cars:
        print(f"{'plate':10} {'kind':10} {'best_conf':>9} {'frames':>6} {'timestamp':>10}")
        cars_recs = [(c, seen[c]) for c in cars]
        cars_recs.sort(key=lambda x: x[1]["first_frame"])
        for k, v in cars_recs:
            mark = "✓" if v["kind"] != "invalid" else " "
            print(f"{mark} {k:10} {v['kind']:10} {v['best']:9.2f} {v['frames']:6d} {v['first_ts']:>10}")
    else:
        print("  (none)")

    print(f"\nunique reads: {len(seen)}   passing Italian-format validation: {len(valid)}")
    print(f"{'plate':10} {'kind':10} {'best_conf':>9} {'frames':>6} {'timestamp':>10}")
    for k, v in sorted(seen.items(), key=lambda kv: kv[1]["first_frame"]):
        mark = "✓" if v["kind"] != "invalid" else " "
        print(f"{mark} {k:10} {v['kind']:10} {v['best']:9.2f} {v['frames']:6d} {v['first_ts']:>10}")
    print("=============================================")
    print("Judgement: are real plates appearing as ✓ valid reads at the speeds you drove?")


if __name__ == "__main__":
    raise SystemExit(main())
