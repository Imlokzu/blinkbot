# Claude Bot — Full technical project specification

**Version:** 3.0 (consolidated)
**Date:** July 2026
**Purpose of the document:** a full technical description of the DIY project of a personal AI robot based on a Raspberry Pi 3, a home server, and cloud LLM APIs. The document covers the hardware part, computing architecture, software stack, and advanced intelligent modules.

---

## 1. What is this project

Claude Bot is a personal AI companion that combines a physical body (camera, microphone, speaker, display, eventually — wheels) with the Claude language model as a "personality" and auxiliary cloud services for quick reactions. The idea emerged as an evolution of the concept from the video «I Gave ChatGPT a Body» (the Growbot project) — dividing into a fast "System 1" (reflexes, movement) and a slow "System 2" (thinking, conversation, planning).

The key difference from off-the-shelf assistants (Alexa, Google Home): Claude Bot has a physical presence, a personal memory about specific people in the house, and is capable of performing tasks that require movement and recognition («знайди мене і нагадай» (find me and remind me)).

---

## 2. Computing architecture: three tiers

| Tier | Device | Latency | Role |
|---|---|---|---|
| Edge (reaction) | Raspberry Pi 3 | <50 ms | Sensors, local UI, obstacle-avoidance reflexes |
| Fog (processing) | Home i5-10200 server | 100-500 ms | Orchestration, scheduler, memory, light ML processing |
| Cloud (thinking) | Groq / Anthropic API | 300 ms - 3 s | Vision analysis, conversation, decision generation |

Distribution principle: the more often and faster a reaction is needed, the closer to the hardware it is executed. A clock waiting for an alarm should not depend on the availability of a cloud API at the moment it triggers.

---

## 3. Hardware (Bill of Materials)

### 3.1 Computing modules
| Component | Specification | Status |
|---|---|---|
| Raspberry Pi 3 Model B v1.2 | BCM2837, 1.2GHz quad-core, 1GB RAM | Available |
| Home server | Intel i5-10200, 4C/8T | Available |
| microSD | ≥16GB, Class 10/U1 | Available |

### 3.2 Sensors
| Component | Interface | Role |
|---|---|---|
| OV5647 Camera (5MP) | CSI | Main vision |
| USB microphone (start) / ReSpeaker mic array (upgrade) | USB | Hearing, with voice direction detection capability |
| HC-SR04 (from chassis kit) | GPIO | Rangefinder for obstacle avoidance |

### 3.3 Outputs
| Component | Interface | Role |
|---|---|---|
| SPI display 2.4" (ST7789/ILI9488, touch) | SPI | «Обличчя» (Face) + swipe screens (Kivy) |
| MAX98357A + speaker | I2S | Voice |
| LED strip (optional) | GPIO/PWM | State indication: listening/thinking/speaking |

### 3.4 Power
| Component | Specification |
|---|---|
| Power bank | 5V, ≥2.5A output — powering RPi3 |
| Battery holder (from chassis kit) | Separate motor power, isolated from RPi3 |

### 3.5 Mobility (Phase 2, deferred)
| Component | Specification |
|---|---|
| 4WD chassis kit | 4× TT-motor + gearbox, L298N driver, HC-SR04 included |

Note on the motor controller: direct control from RPi3 via GPIO (gpiozero) is considered instead of a separate Arduino — simplification by one less layer, suitable for non-real-time critical movement indoors.

---

## 4. Software stack

| Layer | Technology | Reason for choice |
|---|---|---|
| OS | Raspberry Pi OS 64-bit (Bookworm) | Official camera support via libcamera |
| Video capture | picamera2 | Current replacement for deprecated picamera |
| Audio | ALSA + sounddevice | Standard for USB audio |
| Display UI | Kivy (ScreenManager + SlideTransition) | Swipe navigation out of the box |
| Server backend | FastAPI + uvicorn | Asynchronous, easy integration with external APIs |
| LLM SDK | anthropic, groq (official Python SDKs) | Direct support for providers |
| Scheduler | APScheduler | Clock independent of LLM call |
| Vector memory search | ChromaDB / sqlite-vec | Lightweight, local, without cloud dependency |
| Face recognition | face_recognition (dlib) / InsightFace | Recognizing specific people, not just detection |
| Local STT (recommended) | Whisper (base/small) on i5 | Reduces network dependency and cloud call costs |

---

## 5. Role distribution among LLM providers

| Provider / model | Role | Reason |
|---|---|---|
| Claude (Anthropic API) | Conversation, character, intent understanding, tool use | Best conversation quality and instruction following |
| Groq — qwen3.6-27b (vision) | Real-time camera scene description | Very low latency, cheap |
| Groq — lightweight text model | Short instant reaction phrases | Speed is more important than depth |
| Local Whisper (i5) | Speech-to-text (STT) | No network latency, no per-request cost |

Important: the Groq vision lineup has historically changed several times a year — the model name should be kept in configuration, not hardcoded in the code.

---

## 6. Advanced intelligent modules

### 6.1 Agent capabilities (Tool Use)
The Claude API supports function calling — the model determines the user's intent and returns a structured call executed by the server.

**First set of tools:**
- `set_alarm` — alarm clock (executes APScheduler on the server, not Claude)
- `set_reminder` — reminder, optionally linked to a specific person (section 6.3)
- `play_music` — playback via yt-dlp/Spotify API → audio on RPi
- `web_search` — information search, spoken summary
- `find_person` — physical search for a person around the apartment (section 6.4)

**Key principle:** Claude is an intent understanding layer, not a real-time executor. The scheduler on the server works independently of the Claude API availability when the alarm or reminder triggers.

**Notification channel:** Telegram Bot API — free, instant, no open ports (long polling). Used when a task could not be completed ("didn't find you at home — here's your reminder").

### 6.2 Personal memory (RAG, «Obsidian-мозок» (Obsidian brain))
Instead of passing the full conversation history in every request — a structured database of markdown notes with semantic search of relevant context.

**Structure:**
```
brain/
  people/<name>.md     — facts, preferences, important dates about a person
  topics/<topic>.md    — long-term facts about life, apartment layout
  logs/<date>.md       — raw daily logs before consolidation
```

**Lifecycle:**
1. Recording — Claude decides what to keep after the conversation
2. Search — semantic search of the top 3-5 relevant notes before a new request
3. Dream cycle — nightly background consolidation: deduplication, summarization

**Capacity:** 32GB SD card — a massive reserve for text; the bottleneck is not volume, but consolidation quality.

### 6.3 Recognizing specific people (Face Recognition)
Different from simple face detection (for UI "eyes looking at a person"): answers the question «чиє це обличчя» (whose face is this), not «чи є тут обличчя» (is there a face here).

- Detection (light) — locally on RPi3
- Recognition-embeddings (heavy) — on the i5 server, only for frames with a detected face
- Initial "registration" needed — a few photos per person
- Limitations: accuracy depends on lighting and angle; a privacy policy is needed — what can be retold to an unfamiliar person and what cannot

### 6.4 Navigation (without lidar)
The chosen approach is reactive navigation instead of full SLAM, as a complexity/cost/sufficiency tradeoff for an apartment.

| Level | Technology | Function |
|---|---|---|
| Obstacle avoidance (real-time) | HC-SR04 + local logic | Stop/turn around without network latency |
| Obstacle classification | YOLOv8n (nano) on server | «Що саме» (What exactly) is in the way |
| Depth estimation (opt.) | MiDaS small / Depth Anything V2 | Lidar-free, monocular |
| Topological navigation | Visual markers across rooms (QR/colors) | Metric map replacement |

**Person search algorithm:** navigating rooms by markers → scanning in each → face detection → server-side recognition → found (action) / bypassed all without result (Telegram notification).

---

## 7. Key technical risks

| Risk | Mitigation |
|---|---|
| Insufficient RPi3 power under peak loads | Power bank ≥2.5A; monitoring `vcgencmd get_throttled` |
| Deprecation of vision models on Groq | Model name in config, not in code |
| Conversation network latency | Local VAD and UI buffering to avoid interface blocking |
| Face recognition accuracy in real conditions | Multiple reference photos, tolerance for a few failed attempts |
| Complexity of full SLAM without lidar | Conscious rejection — topological navigation instead of metric map |

---

## 8. Implementation roadmap

**Phase 0 — Software prototype without hardware (can be done now)**
«Розумна колонка» (Smart speaker) on i5 with existing microphone/speaker — testing conversational logic, tool use, memory.

**Phase 1 — Sensor core**
Camera, microphone, speaker, display on RPi3; connection with the server; voice conversation with Claude; Groq vision commentary.

**Phase 2 — Mobility**
4WD chassis, motors, rangefinder; mounting the finished «тіло» (body) from Phase 1 onto the chassis.

**Phase 3 — Agent and memory** (independent of hardware readiness, can be parallel to Phase 0-1)
Tool use (alarm/reminder/search), Telegram notifications, RAG memory, dream cycle.

**Phase 4 — Spatial awareness** (requires completed Phases 1-2)
Face recognition, reactive navigation, topological markers, full find_person cycle.

---

## 9. Summary priority table

| Module | Depends on hardware? | Ready to start now? |
|---|---|---|
| Conversation with Claude (voice) | Minimally (mic+speakers) | Yes |
| Tool use (alarm/reminder/search) | No | Yes |
| Telegram notifications | No | Yes |
| RAG memory | No | Yes |
| Camera + vision commentary | Camera | After buying a camera |
| Face recognition | Camera | After Phase 1 |
| Reactive navigation | Chassis + rangefinder | After Phase 2 |
| Find_person (full cycle) | All of the above | Last |

---

## 10. Why this project (motivation summary)

The project is conceived not as a replacement for off-the-shelf commercial solutions (smart speakers, robot vacuums), but as:
- An educational experience in building AI agents with physical embodiment
- A personal companion with long-term memory about specific people in the house
- A platform that grows in stages — each phase is independently useful and complete, even if the subsequent phases are not implemented
