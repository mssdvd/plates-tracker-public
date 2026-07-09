# Model pipeline

This directory handles the open-source **fast-alpr** models and holds reference implementations and scripts.

## Environment setup

Provision a Python 3.11 environment using **uv**:

```bash
cd models
uv venv --python 3.11 .venv
source .venv/bin/activate
uv pip install -r requirements.txt
```

## Running the pipeline

```bash
# 1. Fetch fast-alpr's detector + OCR ONNX models
python download_models.py

# 2. Run the reference implementation on an image
python onnx_reference.py --image path/to/image.jpg
```

## Files
- `download_models.py` — Resolves/exports the fast-alpr ONNX models.
- `requirements.txt` — Pinned build dependencies.
- `onnx_reference.py` — Pure ONNX Runtime reference implementation.
- `spike_video_test.py` — Workstation feasibility spike running the pipeline on driving footage.
- `redact_image.py` — Utility script for redacting license plates from images.
- `work/` — Intermediates (gitignored).
