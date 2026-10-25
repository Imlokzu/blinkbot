# Blink Wave1 — Lane A: persona identity block + greeting style seed

Date: 2026-10-25
Lane owner: OpenCode GLM-5.3-Flash worker, assigned files only.
Prior ZCode worker: stopped after repeated network failures, made **no edits** to any file in this lane.

## Change

A compact identity block is now folded into the existing `persona` part of the
system prompt, plus an optional greeting STYLE SEED adapted from the stored
wizard greeting.

- **No new prompt part name and no order change.** Parts remain exactly
  `persona, self, time, tools, profile, memory_rule, notes, channel, voice,
  tts, asr` — channel still before voice, `asr` still last (blocked later by
  `test_channel_prompt.py` semantics staying intact).
- The identity block declares: small virtual pixel-crab companion on the
  owner's device screen, expressive face driven by the `[emotion:*]` tags,
  talks plainly and usefully — and explicitly states that the owner's chosen
  temperament or custom persona **always wins** (concise/professional/custom
  personas are not washed into forced warmth), that honest physical limits
  stated elsewhere in the prompt stay, and that nothing in the block grants
  new abilities.
- **No identity constant value change elsewhere in the repo, no preset IDs,
  labels, hints, icons, configured/default name, or emoji registry touched.**
  `profile_store.py` was **not edited** at all (no helper proved necessary —
  the greeting is read via a plain `profile.get("greeting")` in `brains.py`;
  the 300-character save cap is untouched).
- Greeting behaviour: nonempty (after strip) greeting → one STYLE SEED line
  inside the persona part, says the greeting may be used as a variation,
  adapted to the local time already supplied by the `time` part, and
  expressly forbids repeating it at the start of every turn. Empty or
  whitespace-only greeting → the helper returns `""` and **zero** extra
  instruction text is added. **No first-contact-of-day tracking is invented**
  — appropriateness is judged per turn by the model from the conversation.
- Language: new prompt instructions and all touched comments/docstrings are
  English; the identity block is pure ASCII (tested). No UI strings added.
  Untouched Ukrainian prompt text is left as-is (existing policy-debt, out of
  lane scope).

## Files changed

| File | Change |
|---|---|
| `Virtual Bot/brains.py` | Added `_IDENTITY_BLOCK` constant (~470 chars, ceiling-tested < 600), `_greeting_style_seed()` helper, and their assembly into the `persona` part of `system_prompt_parts` (lines ~72–109 and ~318–335). Two English comments added around the assembly. |
| `Virtual Bot/tests/test_persona_prompt.py` | **New** focused test file, patches `profile_store.load`, `brain_context.init_user_brain`, `brains.load_user_profile`, `brains.find_relevant_notes` — so no real `bot_profile.json`, `.env` or `brain/` data is read. |
| `reports/wave1-2026-10-25/lane-A.md` | This report. |

`Virtual Bot/profile_store.py`: **not modified** (helper turned out unnecessary).

## Tests NOT RUN — shell unavailable in this worker

The coordinator must run:

```bash
cd "Virtual Bot"
PYTHONPATH="$PWD" .venv/bin/pytest tests/test_persona_prompt.py tests/test_channel_prompt.py tests/test_profile_setup.py tests/test_chat_context_compact.py -q
```

- `test_profile_setup.py` and `test_channel_prompt.py` semantics are expected
  unchanged; `test_chat_context_compact.py` covers the parts↔prompt join-back
  invariant, which holds because the new text sits inside the existing
  `persona` part.
- If a preset/persona-content assertion in some other test pinned the persona
  part text byte-for-byte, it will surface on this run; no such test is known
  from bounded inspection (`grep` over tests showed only content-substring
  assertions like the names/index ones above).

## Verification notes for coordinator

- Identity block length check is in-repo: `len(brains._IDENTITY_BLOCK) < 600`
  asserted by `test_identity_block_stays_under_600_characters`.
- No git operations performed (staging/commits/push disabled for this lane).
- No model/provider/config settings were read or changed; `.env`,
  `bot_profile.json`, `config.yaml`, and `user_data/brain` were not read.
