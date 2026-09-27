# Voice Loop — Step 3, connected to OpenClaw

Closes the "smart speaker" loop on the laptop: microphone → local STT (faster-whisper)
→ OpenClaw (memory, tool use, `vision_check_camera`, Claude itself is already
inside) → TTS into speakers. No hardware (RPi) needed — everything is on this
computer, as planned in `claude-bot-dev-order.md`.

## Diagram

```
microphone -> record_utterance() -> faster-whisper -> text
                                                        |
                                                        v
                                    OpenClawClient.send_message()
                                                        |
                                                        v
                        POST http://127.0.0.1:18789/v1/chat/completions
                                                        |
                                                        v
                                              OpenClaw agent (memory,
                                              vision_check_camera, Claude)
                                                        |
                                                        v
                                              reply text -> pyttsx3 -> speakers
```

## Prerequisites

1. OpenClaw is running (`openclaw onboard --install-daemon`, Gateway is working at
   `127.0.0.1:18789`).
2. Vision Agent + OpenClaw Vision Plugin (adjacent folders) are already connected —
   then the bot can answer "I see someone" via `vision_check_camera`.
3. Enabled OpenAI-compatible endpoint in the Gateway config
   (`~/.openclaw/openclaw.json`) — it is disabled by default:

```json5
{
  gateway: {
    http: {
      endpoints: {
        chatCompletions: { enabled: true },
      },
    },
  },
}
```

Restart Gateway after the change.

4. Take the gateway token (`gateway.auth.token` or `OPENCLAW_GATEWAY_TOKEN`) and
   paste it into the `config.yaml` of this project (`openclaw.token`).

## Installation

```bash
pip install -r requirements.txt --break-system-packages
```

On macOS, nothing extra is needed for pyttsx3 (uses NSSpeechSynthesizer).
On Linux, `espeak`/`espeak-ng` will be needed:

```bash
sudo apt install espeak-ng
```

## Launch

```bash
python voice_loop.py
```

Speak after "Слухаю…" (Listening...) — the phrase completes automatically after ~1s of silence.
Exit — `Ctrl+C`.

## Files

- `voice_loop.py` — main loop: microphone recording (RMS silence detection),
  faster-whisper STT, OpenClaw call, pyttsx3 TTS
- `openclaw_client.py` — separate HTTP client for
  `/v1/chat/completions` OpenClaw (easily testable without microphone)
- `config.yaml` — URL/token for OpenClaw, recording parameters, Whisper model selection,
  TTS
- `requirements.txt` — dependencies

## Verified

- `openclaw_client.py` tested against a local mock server that
  reproduces the exact format of the OpenClaw response (`choices[0].message.content`)
  — authorization header and response parsing confirmed correct.
- Both files are syntactically valid (`py_compile`).
- Microphone capture, real Whisper model and pyttsx3 speech synthesis — this already
  depends on a live microphone/speakers, so it is tested directly on
  your computer via `python voice_loop.py`.

## What's next

TTS right now is `pyttsx3` — a simple offline MVP from dev-order.md. When you want
a better voice, replace `speak()` with ElevenLabs API or ChatterboxTTS (locally)
— the rest of the pipeline will not change.
