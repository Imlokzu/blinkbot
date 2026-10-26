# Blink Android 0.5.0

Android version code 30. The first release under the new numbering: a
noticeable feature bumps the minor version, a small feature or fix the patch.

## Rounded interface fonts

The interface font now matches the rounded Solar line icons (source c182cfc2).
**Nunito** is the default. Settings / Appearance / Font also offers
**M PLUS Rounded**, **Blink Soft** and **Google Sans**; each option is drawn in
its own font. The choice is stored per device and applies to the whole app,
including Markdown headings.

- Fonts come from google/fonts under the SIL Open Font License, instantiated at
  400/600 where variable and subset to Latin, Latin Extended, Cyrillic and
  common punctuation. Ukrainian ґ, є, і, ї and the apostrophe are verified in
  every bundled file.
- M PLUS Rounded 1c has no 600 master, so its Medium fills the SemiBold slot.
- Blink Soft is Comfortaa. "Comfortaa" is a Reserved Font Name, so the subset is
  renamed internally and in the picker. It renders at 0.9 font scale, because its
  wide forms and tall x-height read a size larger than the others.
- Zen Maru Gothic was evaluated and dropped: its Cyrillic lacks ґ, є, і and ї.
- Manrope remains bundled because the web editors (Tiptap, CodeMirror) still
  load it; they keep their previous font.

The release was built from committed source in a clean sparse worktree with only
the version bump applied. The APK grows by about 0.55 MB.

The owner-distributed APK is `mobile-app/build/Blink-0.5.0.apk`
(7716288 bytes), signed with the same certificate as 0.4.18–0.4.22
(`dd6356d2…ff89a3`).

SHA-256: `693924bc82f1eb79bf8e2c90590a8c2f5612bf2c16dd76746862970a30201314`.

Published to the stable in-app update channel on 2026-10-26 with
`scripts/mobile_publish.sh --restart --public` (zero active mobile jobs). Live
metadata offers code 30 to code 29 on stable and beta and reports code 30 as
current; both download routes serve bytes matching the checksum above.

## Verification

- 332 shared unit tests pass.
- 154 of 158 Android instrumentation tests pass on a Pixel 8 API 35 emulator,
  with the same four pre-existing failures; `FileWorkspaceIntegrationTest` was
  excluded for the emulator WebView crash recorded in 0.4.21.
- A new test opens Appearance in Ukrainian, selects each font, checks the stored
  preference, and captures Appearance and a chat with Ukrainian letters in every
  font. The captures were reviewed; Nunito and M PLUS differ pixel-wise as
  expected.
- The R8 release APK installs in place over 0.4.22 and cold-starts in Nunito
  without a crash.
