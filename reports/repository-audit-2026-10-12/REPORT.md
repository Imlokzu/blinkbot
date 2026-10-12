# Repository audit — 2026-10-12

Four fresh findings in the screen's voice and conversation selection lifecycle,
with solution examples, acceptance checks, and three additions I would build.
This folder follows the existing `reports/repository-audit-<date>/` convention.
Product repairs remain proposals; the audit changes reports and synthetic
probes only.

Starting revision: `147a0818`. The latest [timer activation repair](../night-agent-timer-activations-2026-10-11.md)
addresses October 11 T-01 and is not counted again. Its DST/publication follow-ups
and the older project/interaction findings remain separate. I compared October
1–11 reports and inspected the current [screen voice contract](../../Virtual%20Bot/docs/SCREEN-VOICE.md),
new recognition/playback functions and session-selection code. This is a bounded
review, not coverage of every repository module or a physical-audio certification.

The checkout contains other agents' unfinished edits. Credentials, owner
profiles, real conversations, stored media and provider/device sessions were
left alone. See [validation](VALIDATION.md) and the [reproducible probe](probes.mjs).

## Ranked findings

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| V-01 | High for wake-intent control | Level-triggered interruption arms wake mode before checking the spoken words | Real detector and recorder/dispatch functions |
| V-02 | Medium under overlapping cap-split recognition | Automatic cap rollover discards a prior segment under latest-result cancellation | Real level cap, recorder rollover and ASR with deferred HTTP |
| V-03 | High for conversation context | Pending recognition dispatches into a newly selected chat | Real new-chat transition and ASR/dispatch functions |
| C-01 | Medium | A late history response replaces the view of a newer selection | Two real `openSession()` calls resolved in reverse order |

Confidence is high in the reproduced source behavior. All media/HTTP adapters
are synthetic, so no real ASR accuracy, sound level, physical echo, browser timing
or paid-model effect is claimed. Dispatch is captured at `sendChat`; these probes
do not create real backend turns or demonstrate cross-account data access.

## V-01 — Wake mode promotes any detected speech into an addressed command

**Source:** [`screen.js`](../../Virtual%20Bot/static/screen/screen.js), `2437–2459`,
`2529–2589`, `2699–2750`;
[`voice-turn.js`](../../Virtual%20Bot/static/screen/voice-turn.js), `26–43`.

`onMicLevel()` reacts to sustained microphone level while a reply is generating
or playing. For every mode except Push, it calls `bargeIn(false)` before ASR
determines whether the words address the bot. In Wake mode, `bargeIn()` opens
the follow-up window. `onMicLevel()` then copies that newly opened window into
the recorder's `wakeArmed` flag. The final transcript is consequently parsed as
already addressed, even if it contains no wake name or stop command.

The same inert sentence, “This sentence is for someone else,” is ignored when
the fixture is idle and unarmed. While a reply is active, a 200ms sustained level
causes the source request to abort, captures `wakeArmed=true`, and dispatches that
unnamed sentence. This is state promotion caused by the interruption itself,
not evidence that a person said the name. The browser recognition fallback's
`heardWhileSpeaking()` instead checks wake intent, highlighting the two paths'
different semantics.

**Impact:** a bystander or unrelated utterance can become a command during a
wake-mode response and interrupt its work. The fixture demonstrates the routing
condition; it does not attribute a real voice to a person or claim that every
background noise reaches ASR. Open/free-conversation mode intentionally has a
broader interruption policy and should retain that behavior.

**How I would fix it:** capture wake intent at the start of the recording before
any interruption changes it. Keep the existing first-sound/candidate-window
capture and noise-spike reset at `screen.js:2443–2447`: the original window may
expire during the 180ms confirmation delay. Keep free-conversation interruption
separate from wake-mode authorization. Within the confirmed-start block:

```javascript
if (boundary.start) {
  const addressedAtStart = !!mediaRec.wakeArmed;
  spoke = true;
  if (voiceMode === 'open' && (botSpeaking || activeChat)) bargeIn(false);
  mediaRec.wakeArmed = addressedAtStart;
}
```

For an unarmed wake recording, wait for an explicit name/stop or a validated
interim transcript before cancelling a reply. If temporary audio ducking is
needed to hear recognition, it must not grant command intent. Preserve the
recording's original window through ASR latency rather than copying a window
opened by `bargeIn()` back into the same utterance.

**Acceptance:** the same unnamed sentence is ignored in unarmed Wake mode both
idle and during generation/playback. Name plus request and explicit stop still
interrupt; a genuine in-window follow-up remains valid after its window expires.
Run both recorder and browser-recognition paths with matching intent fixtures.
Physical speaker echo still needs separate device testing.

## V-02 — Automatic recorder rollover is treated as utterance replacement

**Source:** `screen.js:2437–2455`, `2699–2744`, `2778–2811`.

Continuous recording starts its next recorder before sending the finished clip
to ASR. Every `sendToAsr()` invocation aborts the global `asrController`, including
a prior clip from the same microphone epoch. If another clip finishes while the
first request is still pending, the first transcript is discarded even if the
HTTP adapter later returns it successfully.

The first fixture finishes two recorded clips with both ASR responses held.
The second request aborts the first signal. Resolving both responses submits
only “Second separate sentence.” The first recognized sentence receives no
dispatch and no discarded-input indication. Latest-request-wins cancellation can
be intentional for independent utterances; this result alone does not establish
that they should queue.

A second fixture advances the clock through uninterrupted synthetic microphone
level. The real level handler reaches its 15-second cap twice, ending clips at
15.2 and 30.4 seconds without silence or an explicit stop. Both recognition
responses are held. The second cap upload aborts the first, so the supplied
transcripts “Set the timer for” and “twelve minutes” dispatch only “twelve minutes.”
This establishes segment loss when automatic rollover overlaps recognition;
it does not measure physical speech, recognition accuracy or real ASR latency.
Requests that finish before the next clip completes are not shown broken.

The recorder carries no utterance/segment identity, so the current code cannot
distinguish a cap-split continuation from an additional request or an intentional
replacement. Define that boundary explicitly. A latest-utterance-wins policy
can remain for independent input while keeping one utterance's segments coherent.

**How I would fix it:** assign immutable utterance/segment IDs and distinguish
ordinary recording rollover from explicit supersession/cancellation. If the
chosen contract retains ordinary input, use a bounded recognition queue or
bounded parallel recognition with ordered results inside the microphone epoch.
If latest-utterance-wins is chosen, identify displaced input explicitly and
keep segments of one utterance together; do not infer replacement from every
recorder rollover. A useful record shape is:

```typescript
type RecognitionInput = {
  microphoneEpoch: number;
  conversationEpoch: number;
  utteranceId: string;
  segmentIndex: number;
  replacesUtteranceId?: string;
};
```

Only a user stop, mode/account/session change, or declared replacement should
invalidate accepted input. Combine cap-split segments before submitting a model
turn, and define whether successive independent phrases queue or replace work.
Bound queue count/audio bytes/time and expose a localized busy or dropped-input
status when admission fails. Do not remove cancellation guards that correctly
reject obsolete epochs, and do not replay the same accepted transcript twice.

**Acceptance:** slow ASR plus two ordinary clips preserves the chosen input
contract; a cap-split utterance keeps both segments. Explicit replacement and
stop cancel the intended records. Out-of-order results, provider failure,
backpressure and mode changes never reorder or duplicate accepted input.

## V-03 — Recorded input is not bound to its source conversation

**Source:** `screen.js:2699–2734`, `2778–2811`, `3030–3042`, `3079–3087`;
`screen.js:1590–1601`, `1728–1740` for chat dispatch.

Recorder/ASR guards capture only `micEpoch`; they do not capture the selected
conversation or a conversation-selection generation. `startNewChat()` and
`openSession()` change the global `sessionId` without invalidating pending
recognition. When the old result arrives, `handleFinalText()` invokes `sendChat()`,
which reads the new global session.

The fixture records under `fixture-old` and leaves ASR pending. It invokes the
real new-chat transition, selecting synthetic `screen-8`. The old ASR signal
is still active. Delivering the old transcript creates one dispatch associated
with `screen-8`, not its recording's original session.

**Impact:** words captured for one conversation can enter another conversation's
context after navigation, confusing saved history and agent intent. This is
within one synthetic owner; cross-account leakage and real model side effects
were not tested. It differs from earlier account-independent history caches:
the input itself loses its conversation binding before any server history read.

**How I would fix it:** capture `(sessionId, conversationEpoch)` when admitting
an utterance, before recording or microphone-permission admission. Increment the
conversation epoch on new-chat and selection changes,
cancel or explicitly retain pending voice work according to the chosen policy,
and check that binding before dispatch. For an invalidate-on-navigation policy:

```javascript
const origin = { sessionId, conversationEpoch };
const text = await recognizeAudio(blob, signal);
if (origin.conversationEpoch !== conversationEpoch || origin.sessionId !== sessionId) return;
dispatchRecognized(text, origin.sessionId);
```

This is a design sketch, not an existing helper. Bind in-progress recording and
partial results too, not just a request that has already started. Guard late
permission completion, success, failure and cleanup against the same selection
generation. If retained
input is offered later, identify its original destination visibly; never route
it implicitly into whatever chat is currently selected. Use the same transition
to handle old speech playback/captions where required.

**Acceptance:** switching/new-chat during recording, recognition or a partial
response cannot send old words to the new chat. Cancellation is visible where
needed; valid current-session input works normally. Test navigation away/back,
old failures, pending microphone permission and same-ID selection generations.

## C-01 — History loading has no selection-generation guard

**Source:** `screen.js:3029–3043`.

`openSession()` sets `sessionId` immediately, then awaits history and renders
it without confirming the selection is still current. A slower earlier load
can overwrite the newer conversation's title/history. Its error branch can
also clear a newer successful history view.

The fixture opens A then B, delivers B's history first, then A's. The selected
session remains `fixture-B`, but the last title is A and the last rendered text
is A's history. This is a deterministic callback-order reproduction, not a
network-performance claim. New input reads the selected session B, so the view
and destination can disagree.

**How I would fix it:** make selection a shared generation boundary, abort old
loads, and guard success and failure before touching the view. For example:

```javascript
const selection = ++conversationEpoch;
sessionId = id;
const history = await loadHistory(id, controller.signal);
if (selection !== conversationEpoch || sessionId !== id) return;
renderHistory(history.messages);
```

Apply the same check to title updates, error/empty rendering, loading state,
partial/final recognition and stale-request cleanup.
New-chat must increment that generation too. Coordinate this with V-03 so two
independent counters do not disagree about which conversation owns input.

**Acceptance:** reversed A/B responses and failures keep B's selected history,
title and destination coherent. A delayed load cannot repopulate a fresh chat.
Repeat same-ID navigation is checked by generation, not ID equality alone.

## Additions I would build

1. **A voice input receipt.** Show recording → recognizing → accepted/cancelled
   with the captured conversation and a safe reason for cancellation. Allow
   review/resend of retained transcripts without replaying paid work. This
   specializes earlier lifecycle receipt ideas for voice input; use locale keys.
2. **A clear wake-intent indicator.** Distinguish microphone activity, waiting
   for the name, an addressed utterance, and a timed follow-up window. A level
   meter is not proof of command intent. Keep indications consistent between
   server ASR and browser fallback, with a simple tap to pause.
3. **A navigation/recognition replay fixture.** Reuse the existing audio-free
   test approach to replay late ASR, recorder rollover, reversed history loads,
   stop and mode changes from one event script. This extends earlier clock/
   gateway replay ideas and should exercise the shared conversation boundary.

I would fix wake-intent promotion and source-conversation binding first, add the
shared history-generation guard, then define recognition queue/replacement
semantics. These use the existing JavaScript/Python async stack; no replacement
runtime, speech provider, or new service is proposed. Passing checks here
validate current behavior and evidence, not shipped product repairs.
