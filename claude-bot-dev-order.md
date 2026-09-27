# Claude Bot — Development Order (Agent-first approach)

**Version:** 1.0
**Principle:** we build the «мозок» (brain) (agent) completely first, and only then attach the sense organs and face to it.

---

## Step 1: OpenCV Agent Setup (base)

The goal of this step is to spin up the backend process itself, which is the «тіло» (body) of the future agent, without any sensors yet. Just a skeleton that can take a frame and do something with it.

**What we do:**
```bash
pip install fastapi uvicorn opencv-python --break-system-packages
```

- FastAPI server with one endpoint `/vision/frame` (from the previous document, §5.2)
- OpenCV Haar Cascade — face/motion detection
- Test on the laptop webcam (`cv2.VideoCapture(0)`) — no need to wait for any hardware
- Processing mode (local/cloud/hybrid) — we lay down the switch in `config.yaml` immediately, even if everything is local right now

**Step result:** a working service that says «бачу обличчя» / «бачу рух» (I see a face / I see motion) on a frame from any camera.

---

## Step 2: RAG memory («наш RAG» (our RAG))

The goal is to give the agent a memory even before it actually "sees" or "hears" us in reality.

**What we do:**
```bash
pip install chromadb anthropic --break-system-packages
```

- Structure of notes `brain/people/`, `brain/topics/`, `brain/logs/`
- ChromaDB (embedded mode) — vector search across notes
- Function `search_memory(query) → top-5 relevant notes`
- Function `save_memory(text)` — Claude decides what to write down after a conversation
- Simple test: manually put a few notes → ask something related → verify that the correct context is pulled

**Step result:** the agent «пам'ятає» (remembers) — RAG search works and is connected to the Claude API as context before every request.

---

## Step 3: Voice (STT + TTS)

The goal is for the agent to hear and speak.

**What we do:**
```bash
pip install faster-whisper sounddevice --break-system-packages
```

- STT: `faster-whisper` (locally on i5) — voice → text
- Text → Claude (with connected RAG from step 2) → response
- TTS: we pick a provider (options: ElevenLabs API, ChatterboxTTS locally, or simpler `pyttsx3` as a temporary MVP) — Claude's response → voice
- While everything is on the i5/laptop — the computer's mic+speakers act as a temporary «рот і вуха» (mouth and ears)

**Step result:** a fully fledged «розумна колонка» (smart speaker) with memory — you talk to it, it remembers and answers with voice.

---

## Step 4: Emotion control

The goal is for the agent to express state/mood, even before a physical screen or LED appears.

**What we do:**
- Define the base set of states: `idle`, `listening`, `thinking`, `speaking`, `happy`, `confused`
- Claude (or intermediate lightweight logic) determines the current emotional state from the response context → returns a state tag along with the text
- While there is no screen/LED — we output the state directly to the console/log or via a primitive text indicator on the PC to verify the state switching logic itself
- This is the layer that will later control the LED strip, the «обличчя» (face) on the screen — but first we test the logic itself isolated from the hardware

**Step result:** the agent not only answers but accompanies the answer with a defined emotional state — a ready layer for future visualization.

---

## Step 5: Cameras

The goal is now to connect real vision (not a test webcam) and link it to the OpenCV pipeline from step 1.

**What we do:**
- Connect the CSI camera OV5647 to the RPi3
- RPi sends frames to the backend (the same `/vision/frame` from step 1, now from a real source)
- Face recognition (recognizing «хто це» (who this is)) on top of base OpenCV detection
- Link with RAG (step 2): «бачу [ім'я]» (I see [name]) → pulls up the note about this person
- Link with emotions (step 4): saw a familiar person → `happy` state

**Step result:** the agent sees, recognizes, and reacts with an emotional state — everything that was separate pieces now works together.

---

## Step 6: UI (apps + physical screen)

The goal is what was previously discussed as a companion app and a «обличчя» (face) on the display — done last, when all the logic is already tested and stable.

**What we do:**
- Mobile + PC app (setup, pairing, settings) — from the previous document
- SPI screen 2.4" on RPi — rendering the «обличчя» (face) (Kivy/pygame), which displays the emotional state from step 4
- Swipe between screens (face/status/settings)
- LED strip (optional) — the same emotional state duplicated by color

**Step result:** a complete UI layer on top of an already ready and tested «мозок» (brain).

---

## Why exactly this order is logical

Each subsequent step **relies on the previous one, and is not blocked by hardware**:

| Step | What we test | Special hardware needed |
|---|---|---|
| 1. OpenCV Agent | Base service + vision on a test webcam | No |
| 2. RAG | Memory and context | No |
| 3. Voice | STT/TTS on a computer | No (laptop mic/speakers) |
| 4. Emotions | State logic (for now just in the console) | No |
| 5. Cameras | Real vision on RPi | Yes — RPi + camera |
| 6. UI | Visualization and apps | Yes — screen, mobile/PC |

The first four steps can be done completely right now, without a single part from AliExpress — the entire «мозок» (brain) is ready and tested before the first piece of hardware arrives.

---

## Where to start right now

Step 1 — the `main.py` file with FastAPI + OpenCV Haar Cascade (the skeleton was already shown earlier). Do you want me to write the full working code for this step immediately, with a test script for the laptop webcam?
