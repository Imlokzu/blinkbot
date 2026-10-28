# Blink Android 0.6.0 — phone session recovery

Issue: [#9](https://github.com/Imlokzu/blinkbot/issues/9).

Web Settings / Devices now keeps expired and revoked phone sessions visible,
shows rejected sign-ins, and offers Extend session or Repair connection.
Restoring revoked access requires explicit confirmation. Renewal preserves
the existing device identity and credential and provides at least 90 days of
access. Only the authenticated web owner or direct local operator may renew.

Blink explains a rejected saved connection and provides Repair connection on
the web plus Retry. Credentials, drafts and queued messages remain available
after repair. Invalid pairing codes retain their own retryable feedback.

Validation: 344 shared and 78 Android unit tests passed; optimized release build
passed. Dashboard: 244 unit tests, TypeScript and the mocked English/Ukrainian
browser fixture passed, including 320px layout, confirmation, keyboard focus,
Escape, polling races and malformed receipts. Backend: 134 focused tests and
56 isolated real-HTTP smoke checks passed, including authentication, same-token
repair and traversal rejection. Independent backend review used Fable 5.1;
the remaining UI review and fixes used Astra at the owner's request.

The complete clean-source backend suite passed 2,128 tests and 489 subtests,
with 8 skipped. Earlier shared-checkout runs overlapped separate screen/app-kit
work; those screen failures were handed to that agent and its committed fixes
are in the clean base. No screen repair is included in this feature. The paid
live ASR test was disabled in this task's broad runs.
Native GUI checks were blocked by an incomplete existing desktop JCEF cache;
no emulator, physical-phone or iOS runtime verification is claimed.

Android version code: 33. APK: 7,716,552 bytes.
SHA-256: `874656b26b6683b789d21c18e72493c4fee679448974b22d196a17a3b17ff10b`.

This records the prepared APK; automatic update publication is a separate step.

The web recovery controls are live on the local dashboard from source
`6c657976`. All 369 served release resources matched the clean build byte for
byte, and the complete mocked recovery browser fixture passed against `/dash/`.
The live backend advertises the renewal route. The APK signing certificate
matches the previously published Android release.

The strict generated-asset scan required five new literal bundle paths for
already-approved Solar icon hash metadata and Excalidraw Firebase client data.
Astra verified the existing full fingerprints and canonical copies. No detector
or fingerprint was added; the unchanged policy tests passed 13 cases and 25
subtests.
