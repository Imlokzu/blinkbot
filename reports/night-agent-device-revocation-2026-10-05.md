# Night agent review: paired-device stream revocation

This pass checked the recent native Updates screen, file previews and stream
changes, then fixed audit finding L-02. Removing a paired phone already blocked
new requests, but its open job stream could still receive later reply text.

The stream now retains its original request credential and uses the existing
device-authentication read before each batch and event. A committed revocation,
expiry or owner mismatch closes that listener without cancelling the accepted
job or another device's listener. Idle streams recheck at the next poll.
Nonmobile listeners retain the parent authentication policy. No schema, client
update, provider call or new authentication system is needed.

The response closes with EOF after its headers have been sent. A fresh request
returns HTTP 401. Frames already yielded to the HTTP transport or buffered by a
proxy cannot be recalled; each subsequent credential check observes committed
revocations before yielding another event. This fixes paired-device job streams,
not the separate untargeted dashboard-event finding L-01.

## Verification

- Five authorization cases failed before the fix; valid terminal replay passed.
  Final seven cases cover both owner forms, expiry and revocation within a
  replay batch, idle closure, valid device/Clerk terminal replay, and actual
  loopback HTTP streaming. The real HTTP case verifies EOF, no later private
  data, surviving-device delivery, unchanged running-job state and fresh 401.
- Focused mobile API/stream/lifecycle regression run: 47 tests passed. The final
  authorization suite passed all seven cases, including after independent review.
- Final offline Python suite: 1,559 passed, 7 skipped, 178 subtests passed.
  The earlier baseline had 1,551 passed and one failure in the previously
  recorded image-generation test's empty-PID-file readiness race. Its isolated
  rerun passed unchanged; the final complete suite passed.
- Native JVM checks: 231 shared and 78 Android tests passed. These check the
  current workspace, including other agents' pending native changes; no physical
  device, Android installer or iOS runtime verification is claimed.
- Dashboard: 215 unit tests and TypeScript typecheck passed. No dashboard
  assets were changed for this server-only fix.
- Independent code/spec/security review: APPROVE. Independent architecture
  review: CLEAR. Fable and the configured specialist models were unavailable;
  supported native agents followed the installed role instructions at maximum
  effort. Python syntax checks replaced unavailable LSP tooling.
- Following review, an isolated actual-app server passed 26 curl checks: app,
  dashboard, screen, status/auth/memory/workspace/mobile capabilities, synthetic
  file reads and referenced static resources returned 200. Memory/workspace
  traversal guards returned 400. Provider health was mocked, dotenv reads were
  blocked, runtime paths were temporary, and server lifespan was disabled.
  The smoke server stopped and port 18105 was confirmed closed.

Revalidation adds an indexed, read-only SQLite lookup per event and batch;
it avoids a separate revocation cache. Replay throughput was not benchmarked.
Tests and smoke used synthetic credentials/content only, and no production job,
device, provider setting or service was changed. The Mac remains muted. Other
agents' staged changes and native release drafts are preserved.
