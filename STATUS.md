# Current Status

**Review Date:** 2026-09-03
**Scope:** documentation, roadmap, and state of components based on `HANDOFF.md`, specification, and README of modules.

This is an operational summary file: after every significant block, the status, brief description, checks, and residual risks should appear here. Detailed historical context remains in [`HANDOFF.md`](HANDOFF.md).

## Roadmap and sections

| Direction | Status | Description | Verification |
|---|---|---|---|
| Phase 0: software prototype | In progress | Virtual operation of modules before purchasing hardware | Partially verified |
| Step 1: vision | Done | Vision Agent: face/motion detection, HTTP and MJPEG | Tests and manual checks completed |
| Step 2: RAG/memory | Done | Long-term memory, reasoning, and search | `memory.py`, `dream_cycle.py`, `test_memory_reasoning.py` |
| Step 3: voice | Done with risk | Whisper STT → OpenClaw → pyttsx3 TTS | Verified; has critical bug |
| Step 4: emotions | Not started | Emotional layer and states | No tests |
| Step 5: cameras | Partial | Camera integration and spatial awareness | Partial |
| Step 6: UI/display | Partially done | Pixel eyes, screens, and WebSocket | Build and 6 pytest clean |
| Virtual Bot | In progress | Web dashboard before physical device appears; Agent Talk/Watch and chat participants | Base: 218 passed, 1 skipped; `npm run build` clean |

Remote Control and Device Setup Wizard passed available verification.

## Current snapshot: 2026-09-03

- Branch: `feat/bot-tools-workspace-and-chat-ui`, HEAD `070ef2b`.
- In the working copy there is an uncommitted "chat participants" feature: backend, dashboard, compiled static assets, and test `tests/test_chat_participants.py`.
- Before committing, the feature must close the leave-contract, safe name normalization, cleanup of empty sessions without bypassing `_prune`, as well as UI errors with the hotkey and system messages.
- Base checks of the current snapshot: `.venv/bin/pytest -q` — **218 passed, 1 skipped**; `npm run build` in `Virtual Bot/chat-panel` — clean.

## Critical and known bugs

1. **Voice Loop (P1):** repeated use of pyttsx3 on macOS can hang or stay silent; on error, the cached engine is not reset.
2. **Vision Agent (P2):** large images decode before size limit check; empty buffer can yield 500 instead of 400.
3. **Display (P2):** WebSocket cleanup in StrictMode may leave duplicates; shared idle timer conflicts with long custom screens.
4. **Low priority:** narrow race condition in camera shutdown and display timer drifts.

## Next steps

1. Complete and commit the chat participants feature based on the agreed API contract.
2. Run adversarial review and smoke test for Virtual Bot, including path-traversal → 400.
3. Fix P1 Voice Loop, then P2 Vision Agent and display.
4. Complete offline `wiki.html` in Ukrainian.
5. Update this file after significant changes.

Statuses are operational and do not imply that incomplete items are ready for production.
