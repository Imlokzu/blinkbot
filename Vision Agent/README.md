# Vision Agent — Step 1 (OpenCV Agent Setup)

Basic skeleton of the agent's "body" from `claude-bot-dev-order.md`. FastAPI server that
receives a frame and says: I see a face / I see motion. Tested on a laptop webcam —
no hardware (RPi, CSI camera) needs to be awaited.

## Installation

```bash
pip install -r requirements.txt --break-system-packages
```

## Starting the server

```bash
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Verification that it's up:

```bash
curl http://127.0.0.1:8000/health
# {"status":"ok","mode":"local"}
```

## Test on webcam

In a separate terminal, while the server is running:

```bash
python test_webcam.py
```

A window will open with video from the webcam, green bounding boxes around found faces and
text status (number of faces, whether there is motion, current mode). Exit — `q`.

## Files

- `main.py` — FastAPI server, endpoint `POST /vision/frame` (Haar Cascade
  face detection + frame-differencing motion detection)
- `config.yaml` — processing mode switch (`local` / `cloud` / `hybrid`,
  currently only `local` is implemented) and detection thresholds
- `test_webcam.py` — client for laptop webcam, runs frames through the API live
- `requirements.txt` — dependencies

## Verified

`main.py` tested automatically (FastAPI TestClient): `/health` returns
correct status, `/vision/frame` correctly decodes JPEG and returns detection;
on synthetic motion (frame change) `motion_detected` triggered correctly
(score 0.157 vs threshold 0.01). Real test on webcam — remains to be
run on your laptop via `test_webcam.py`.

## What's next

Step 2 from `claude-bot-dev-order.md` — RAG memory (ChromaDB + Claude API),
connected independently of this step, still without real hardware.
