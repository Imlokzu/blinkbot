# Blink Android 0.5.2

Android version code 32. Fix: question cards no longer disappear (source 882f0886).

Cards from the bot's `ask_question` tool existed only while the app followed the
live job stream. Reopening the chat or restarting the app lost the card while
the reply still said "pick a card". The owner saw this on a tablet about 30
minutes after the turn (job c4ae90a5, 27.10.2026); the backend had delivered the
question correctly, and the device had read the event stream.

Now, when the open chat's newest message is a finished reply whose
`ask_question` step completed and nothing was written since, the card is
restored from history. Answered or dismissed questions stay closed: receipts
match by tool call ID, so a receipt from the live card also covers its history
copy, and a live prompt replaces a restored one for the same call. Stopped or
failed steps never restore.

The release was built from committed source in a clean sparse worktree with only
the version bump applied.

The owner-distributed APK is `mobile-app/build/Blink-0.5.2.apk`
(7716292 bytes), signed with the same certificate as 0.4.18–0.5.1
(`dd6356d2…ff89a3`).

SHA-256: `1ee1fe55e2fbaf76cddecf1fb70287b670c5304d076535500642ccda969975a4`.

Published to the stable in-app update channel on 2026-10-27 with
`scripts/mobile_publish.sh --restart --public` (zero active mobile jobs). Live
metadata offers code 32 to code 31 on stable and beta and reports code 32 as
current; both download routes serve bytes matching the checksum above.

## Verification

- 336 shared unit tests pass, including four new controller tests: the real
  gateway event sequence of job c4ae90a5 replayed on an open job and after a
  send, a card restored on reopening a chat (and staying closed after
  dismissal), and superseded or failed questions staying closed.
- **Not run:** Android instrumentation tests and the emulator install/cold-start
  smoke. The disk had about 0.6 GB free (swap at 12 GB) and the emulator refused
  to start. The change is limited to controller state; the card UI is unchanged.
