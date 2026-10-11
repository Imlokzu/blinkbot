# Screen voice conversation

Open `/screen`, select **Always listening** with the mode chip, and tap the
microphone. New browser profiles start in this mode but wait for a tap before
requesting microphone access. Existing Push/Wake preferences remain supported.
Tap the microphone again to pause listening; tap during an answer to stop it.
The long press and mode chip still open the mode picker.

Say the bot's configured name alone to hear a short acknowledgment: “Hi!”
or “What do you need?” (localized). Both its full name and first-word shorthand
work in Always listening and Wake word modes. These two cues are prepared when
listening starts, so a recognized call does not wait for Luna or another TTS
request. Visual feedback is immediate; voice starts from the cached clip.
Name plus request still sends the request once, and stop interrupts the cue.
Language, speed and screen voice-selection changes invalidate the cached clips.

In a free conversation, sustained speech interrupts the answer. A standalone
“stop”, “stop it”, or Ukrainian “стоп” cancels playback and the active chat
request without creating another model turn. The microphone stays available
for the next sentence. Requests such as “stop the music” still reach the agent.

The pipeline is the existing configured ASR, OpenClaw's GPT-6 Luna route,
and the configured TTS provider (ElevenLabs on the development host). Screen
voice alone uses `voice.screen_model`, defaulting to `openai/gpt-6-luna`, with
thinking disabled for that message. The dashboard picker and global thinking
preference are retained. The selected voice remains available in Settings.

Screen ASR uploads opt into the optional `detect_language=true` multipart
field. Regolo then detects the spoken language; existing dictation clients retain
their configured language hint. Local Whisper retains its existing configured
language detection. ElevenLabs follows the reply text instead of forcing
Ukrainian pronunciation. Voice prompts ask for short replies in the speaker's
language. Recognition errors and provider outages still use the existing UI.

The echo-cancelled microphone stays open during recognition, generation and
playback. Short turns end after 650 ms of silence; a longer thought gets 950 ms.
Speech must last 180 ms to interrupt, so a single level spike does not stop the
answer. Silent recordings do not reach paid recognition. Playback-text matching
filters likely echo from browser/server transcripts. This is level-based turn
detection, not a neural speech detector; physical speaker echo and background
speech need device testing.

Cancellation invalidates pending permission, recognition and synthesis results.
Old recorder chunks cannot enter a new recording; stale streams cannot resume
speech or overwrite the next caption. TTS begins at a complete sentence from
24 characters and prefetches the next chunk. Audio remains sentence-buffered;
this does not add an ElevenLabs streaming transport.

## Validation

Latest suite: **1,832 passed, 7 skipped, 178 subtests passed**. The focused
wake/voice set passed **131 tests**. Post-review curl smoke passed **19 checks**,
including two traversal guards returning 400. The registered backend was
reloaded with zero active phone jobs and exposes the new ASR field and assets.
Independent maximum-effort native review found no remaining reproducible blocker;
Fable and the installed specialist models were unavailable. LSP diagnostics were
unavailable; JavaScript syntax and Python compilation passed.

From `Virtual Bot/`:

```sh
PYTHONPATH="$PWD" .venv/bin/pytest tests/test_wake_feedback.py tests/test_wake_feedback_review.py tests/test_screen_voice.py tests/test_screen_voice_review.py tests/test_screen_js.py tests/test_voice_latency.py tests/test_asr_regolo.py tests/test_asr_languages.py tests/test_chat_context_compact.py -q
VBOT_OFFLINE=1 PYTHONPATH="$PWD" .venv/bin/pytest tests/ -q
node --check static/screen/screen.js
node --check static/screen/voice-turn.js
node --check static/screen/wake.js
node --check static/screen/wake-cues.js
```

The focused tests execute the actual JavaScript functions with controlled media,
timing and fetch results. Independent adversarial review covers late permission,
ASR/partial delivery, mute, audio promises, recorder rollover, old captions and
echo. Browser checks exercise microphone controls, interruption, stop-without-a-
new-turn, resumed speech, both locales and continued recording during playback.
The browser microphone and playback were simulated; physical audio was not used.

Real synthetic provider checks on 2026-10-11:

| Check | Observed result |
| --- | --- |
| Sarah says “Стоп.” | ElevenLabs 200, 1.762 s; Regolo 200, 0.405 s, “Стоп!” |
| Sarah says “How are you doing today?” | ElevenLabs 200, 0.334 s; Regolo autodetection 200, 0.483 s, exact text |
| Luna first greeting | First words 12.077 s, complete 12.386 s |
| Luna fresh short phrase | First words 5.272 s, complete 5.378 s |
| Luna next phrase in the same session | First words 3.091 s, complete 3.296 s |

Luna receipts identify `openai/gpt-6-luna`; these turns used zero tools. These
are individual measurements, not latency guarantees. OpenClaw/provider cold
starts remain slower than the local interruption path. Test chats and their
Gateway transcripts were deleted. Smoke uses a temporary loopback server with
isolated chat/brain/usage paths and lifespan disabled.

Provider references: [Regolo STT](https://docs.regolo.ai/models/families/stt/),
[ElevenLabs speech API](https://elevenlabs.io/docs/api-reference/text-to-speech/convert).

The wake-cue follow-up passed independent maximum-effort review, including 34
adversarial cases for interim/final duplication, requests over the greeting,
short-cue echo, pause/setting changes, named stop, and wake-window expiration.
A current-source browser run confirmed two named calls, one normal request and
named stop with only two greeting syntheses and no greeting model calls. Real
ElevenLabs returned valid MP3 clips for both English cues (3,048/4,407 bytes);
cached “Hi!” playback started about 88 ms after a mocked recognition result.
This measures browser playback scheduling/decoding, not physical ASR latency.
The user must enable the microphone; detection still depends on ASR.
