# Night-agent wake intent repair — 2026-10-12

Starting revision: `75021bb0`. This pass inspected the recent screen voice/wake
work and the October 12 audit, then repaired V-01. The original audit probe
reproduced all four findings. In its wake-mode case, sustained level interrupted
a synthetic reply, armed the recording, and dispatched an unnamed sentence that
the idle unarmed screen correctly ignored. No owner recording, microphone,
provider, credential or real conversation was used.

## Result

Microphone level now confirms speech without inventing addressed intent.
The recorder freezes its invitation state when the detector first identifies
a speech candidate. A discarded noise candidate clears that state; a later
candidate captures its own invitation. Captured invitation metadata survives
confirmation and wake-window expiry while recognition completes. A follow-up
window opened after the first sound cannot retroactively authorize that utterance.

At confirmed start, only Always listening or a previously invited Wake word
utterance can interrupt from level alone. Unarmed Wake word input waits for
recognized name/stop intent. The existing final recognized command path still
accepts name-plus-request, standalone stop remains local, and interim name-only
recognition can acknowledge/interrupt without a model turn. Push mode retains
its explicit input behavior. Existing playback-echo filtering remains.

The implementation changes only the recorder's candidate/intent capture and
early interruption gate. No new visible string, provider, dependency, transport
or threshold was introduced. Existing voice documentation now explains the
mode-specific policy and dates the previous release's validation separately.

## Fresh verification

- Final full backend: **1,883 passed, eight skipped, 178 subtests passed**, with
  six warnings, in 88.57 seconds. Five warnings are existing deprecations; one
  is pytest's already-imported-anyio rewrite warning. The earlier full run
  before the late-invitation regression had 1,882 passes and is not the final
  evidence.
- Focused voice/wake: **108 passed**, including **27 new boundary regressions**.
  Tests execute shipped functions, detector and parser with inert media and
  controlled recognition, clock and reply adapters. They cover idle/generation/
  playback, unarmed speech, named requests, local stop, interim name cues,
  expired invitations, late-opened invitations, noise, open/push modes, echo,
  and unarmed/name/stop behavior through the browser fallback.
- The first focused run had 106 passes and one failure: an existing detector
  stub omitted the real detector's retained first-sound candidate. The fixture
  now models that state; all original assertions remain unchanged. The final
  focused and independent reruns passed. New tests retain the existing skip
  policy when Node is unavailable.
- Dashboard: **231 tests passed**, zero failures/skips; TypeScript passed.
  Catalog validation and **21 i18n checker tests** passed. JavaScript syntax,
  changed Python AST checks, and scoped whitespace checks passed. No production
  dashboard build was needed.

The guarded Python runner clears inherited provider/updater variables, blocks
owner dotenv/profile/runtime paths, supplies synthetic configuration, redirects
brain/workspace/code/chat/widgets/database/integration state to temporary
storage, and uses offline mode. Descriptor-relative fixture paths remain
available for normal test cleanup. Evidence covers working source, including
unrelated shared drafts; it does not certify a clean deployed snapshot.

Run the focused checks from `Virtual Bot/`, with the Mac muted:

```bash
PYTHONPATH="$PWD" .venv/bin/pytest tests/test_screen_voice.py tests/test_screen_voice_review.py tests/test_wake_feedback.py tests/test_wake_feedback_review.py tests/test_wake_intent.py -q
node --check static/screen/screen.js
```

## Independent review and browser/HTTP smoke

Two separate supported native reviewers worked at maximum effort under the
installed code-reviewer/architect instructions. Fable was unavailable; the
configured specialist model had been unsupported in previous passes. This is
native independent review, not Fable certification. LSP/ast-grep were unavailable;
actual-source tests/probes, Python AST and Node syntax were explicitly authorized
diagnostic fallbacks.

Review reproduced a late-arming edge: an unarmed first sound followed by a
reply-completion invitation during confirmation could still dispatch. Candidate
identity capture and the exact regression now prevent it. The code lane also
caught missing Node skip propagation in the new test file; it was corrected.
Final code/spec/security verdict: **APPROVE**, with 108 independent passes.
Architecture: **CLEAR**, with 107 initial focused passes and 31 affected tests
after the candidate repair. The strongest tradeoff is recognition latency for
unarmed Wake word interruption; open conversation retains early interruption.

The complete screen loaded in isolated Chromium at **320×240** and passed six
scenarios with zero uncaught page errors: ignored unarmed bystander input in
playback/generation, named request, bare stop, open-mode interruption, and an
invited follow-up. A temporary copy added fixture controls; microphone/recorder,
ASR response and model submission were synthetic adapters. Actual recorder,
level, recognition-dispatch and presentation functions ran, with no real audio
or provider call. The model adapter captured input and reproduced the primary
dispatch boundary's reply cancellation. Its empty configuration, fresh browser
namespace/session and loopback allowlist excluded owner browser state. The final
candidate revision passed; the screenshot was inspected. Browser/server exited
and port 18143 was confirmed closed.

After both reviews, uninstrumented actual `main.app` ran temporarily at
**127.0.0.1:18144**, lifespan disabled and external connections/DNS blocked.
**42 curl expectations passed** for app/status/inventory routes, mobile-host
401/404 boundaries, three traversal responses of 400, direct dashboard assets,
five voice scripts and the timer module. Existing timer creation, persisted
restart generation, paused extension and cancellation also passed their body
assertions. The server exited zero and its port was confirmed closed.

## Separate follow-ups and limits

V-02 cap-split recognition loss, V-03 source-conversation binding and C-01 history
selection races remain unfixed. The historical audit probe expects its original
defects; the new tests define V-01's repaired behavior. This pass does not change
recognition queuing, navigation, paid-turn delivery or conversation selection.

The browser fallback's synthetic invited-input-during-ordinary-playback
asymmetry predates this change. A common reachable path for that setup was not
established during review; it is outside this recorder repair. Neither simulated
levels nor mocked transcripts establish physical ASR accuracy, speaker echo,
bystander identity, or real interruption latency. A wake name is an interaction
gate, not speaker authentication.

No production reload, deployment, real microphone, speech synthesis, provider
request or owner-state mutation was performed. Other agents' drafts remain
preserved; test services are stopped, and the Mac remains muted.
