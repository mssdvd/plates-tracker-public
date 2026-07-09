#!/usr/bin/env python3
"""Resolve the fast-alpr ONNX models (detector + OCR) into work/ for conversion.

fast-alpr downloads its detector (open-image-models YOLO) and OCR (fast-plate-ocr) the first time
an `ALPR` is constructed, caching them as ONNX. This script triggers that download, then locates
the cached `.onnx` files and copies them to work/detector.onnx and work/ocr.onnx.

NOTE: fast-alpr's exact model identifiers and cache layout should be confirmed against the version
pinned in requirements.txt. Run with -v to see what was found.

USAGE:
  python download_models.py [--ocr-model cct-s-v2-global-model] [--detector-model yolo-v9-t-512-license-plate-end2end]
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

WORK = Path(__file__).parent / "work"

# The shipped default (android/app/src/main/assets/detector.onnx, Detector.SIZE=384). Kept as a
# named constant so a non-default --detector-model writes to a distinctly-named file instead of
# silently clobbering it — see docs/model-specs.md:23-24's deferred "revisit input size" TODO.
DEFAULT_DETECTOR_MODEL = "yolo-v9-t-384-license-plate-end2end"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument(
        "--ocr-model",
        default="cct-s-v2-global-model",
        help="fast-plate-ocr OCR model id. european-plates-mobile-vit-v2-model is legacy "
        "(worse Italy plate_acc, 86.7%% vs 97.5%%) — see docs/model-specs.md.",
    )
    ap.add_argument(
        "--detector-model",
        default=DEFAULT_DETECTOR_MODEL,
        help="open-image-models YOLO detector id (default: the currently shipped 384 variant). "
        "256/384/416/512/608/640 are available (open_image_models.detection.core.hub); larger "
        "inputs read smaller/farther plates better at higher latency, per the deferred TODO in "
        "docs/model-specs.md. A non-default choice is written to work/detector-<model>.onnx "
        "instead of overwriting work/detector.onnx, so both can be compared side by side.",
    )
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    try:
        from fast_alpr import ALPR
    except ImportError:
        sys.exit("ERROR: fast-alpr not installed. pip install -r requirements.txt (Python 3.10/3.11).")

    # Constructing the pipeline triggers model download into the on-disk cache.
    print(f"[download] initializing fast-alpr (detector={args.detector_model}, ocr={args.ocr_model}) to fetch models ...")
    ALPR(detector_model=args.detector_model, ocr_model=args.ocr_model)

    # Search the cache roots fast-alpr's components actually use (note the HYPHENS).
    roots = [
        Path.home() / ".cache" / "open-image-models",   # detector (open-image-models)
        Path.home() / ".cache" / "fast-plate-ocr",      # OCR (fast-plate-ocr)
        Path.home() / ".cache" / "fast_alpr",
        Path.home() / ".cache" / "huggingface",
    ]
    found = sorted({p for root in roots if root.exists() for p in root.rglob("*.onnx")})
    if args.verbose:
        for p in found:
            print(f"  found: {p}")
    if not found:
        sys.exit(
            "ERROR: no .onnx files found in known cache roots. Inspect the cache and copy the "
            "detector/OCR ONNX into work/detector.onnx and work/ocr.onnx manually."
        )

    WORK.mkdir(parents=True, exist_ok=True)
    # The requested size (e.g. "512" out of "yolo-v9-t-512-...") disambiguates the cache once it
    # holds more than one detector variant (this script may be run repeatedly with different
    # --detector-model values to compare them).
    size_match = re.search(r"-(\d+)-", args.detector_model)
    size_tag = size_match.group(1) if size_match else None
    detector = _pick(found, ("detect", "yolo", "plate"), prefer_tag=size_tag)
    ocr = _pick(found, ("ocr", "vit", "rec", "cct"))
    detector_out = WORK / "detector.onnx" if args.detector_model == DEFAULT_DETECTOR_MODEL \
        else WORK / f"detector-{args.detector_model}.onnx"
    if detector:
        shutil.copy(detector, detector_out)
        print(f"[download] detector -> {detector_out}  (from {detector.name})")
    if ocr:
        shutil.copy(ocr, WORK / "ocr.onnx")
        print(f"[download] ocr      -> {WORK / 'ocr.onnx'}  (from {ocr.name})")
    if not (detector and ocr):
        print("WARNING: could not unambiguously identify both models; copy manually from the list above.")
    return 0


def _pick(paths: list[Path], keywords: tuple[str, ...], prefer_tag: str | None = None) -> Path | None:
    candidates = [p for p in paths if any(k in p.name.lower() for k in keywords)]
    if prefer_tag:
        tagged = [p for p in candidates if f"-{prefer_tag}-" in p.name]
        if tagged:
            return tagged[0]
    return candidates[0] if candidates else None


if __name__ == "__main__":
    raise SystemExit(main())
