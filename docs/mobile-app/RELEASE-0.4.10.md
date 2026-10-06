# Android 0.4.10: questions in the composer

Android version code 17 publishes the question-composer feature from
`e0cbed62` through the existing authenticated in-app updater. Bot questions
appear above the answer field in the bottom composer surface, with choices and
optional free text. Answers preserve the normal draft, attachments and edit
state; durable receipts prevent handled questions from reopening on replay.
The panel respects the keyboard, large text, tablet layout and Back priority.

The release is available under **Profile → App updates**. Builds 15 and 16 see
an available update; build 17 reports current. Download verification uses the
published SHA-256 before Android opens the system installer. Installation still
requires the user's normal Android confirmation.

Publication uses the ignored `runtime/releases/ClaudeBot-0.4.10.apk` and
`MOBILE_UPDATE_ANDROID_*` settings. The API download URL remains on
`https://api-bot.waveio.me`. The file is 2,877,403 bytes with SHA-256:

`906790598b827ab82b9c1de1de8dfe45fc9d514e4cd838a6ae6037a3c05ae5f0`

Validation: optimized release build and signature verification passed; its
certificate matches the previous distributed APK. Live backend checks verified
version availability, release notes and downloaded bytes/hash. The public
mobile API rejects missing credentials with 401 and the dashboard returns 200.
The web launchagent was restarted only after a fresh zero-active-mobile-job
check. No gateway/tunnel restart, emulator, provider turn or iOS publication was
needed. Existing mobile logic validation remains documented in HANDOFF.md.
