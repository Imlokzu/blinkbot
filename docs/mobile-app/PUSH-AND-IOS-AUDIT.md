# Push and iOS readiness audit

Reviewed 2026-10-25 against the local checkout based on `24915d9f`, including
existing uncommitted mobile changes. This is a source and documentation audit;
no mobile build, account setup, deployment or physical-device test was performed.
Labels distinguish **Code**, **Doc**, **Platform**, **Proposal**, and **Unknown**.

## Current behavior

| Area | Evidence and boundary |
| --- | --- |
| Reply arrival | **Code:** `shared/.../state/AppController.kt:825` consumes replayable job events; its `done` branch calls `notifyReply` at line 862. The helper at line 1028 requires the notification preference and a backgrounded app. This needs an executing client event consumer. |
| Android notification | **Code:** `androidApp/.../platform/AndroidBridge.kt:496` requests notification permission; `notifyReply` at line 503 creates the local `conversation-replies` channel and an authenticated-conversation navigation intent. The manifest declares `POST_NOTIFICATIONS`; it has no Firebase messaging service or exact-alarm permission. |
| Android outbox | **Code:** `androidApp/.../platform/OutboxWorker.kt:12` drains approved outgoing work. Lines 46–79 schedule network-constrained unique WorkManager work with backoff and credential-epoch checks. This sends queued work; it is not an incoming-push receiver or an alarm clock. |
| iOS notification | **Code:** `iosApp/Sources/NativeBridge.swift:268` requests notification permission; lines 278–292 submit an immediate local `UNNotificationRequest` with no trigger. The inspected host has no APNs registration callback or background-push capability configuration. |
| iOS lifecycle | **Code/Doc:** the shared controller observes foreground changes (`AppController.kt:131`); `mobile-app/README.md` explicitly documents foreground retry and no iOS background outbox worker. `iosApp/project.yml` and the Xcode project target iOS 16.0. |
| Backend capability | **Code:** `Virtual Bot/mobile_api.py:614` advertises `push: False`. Device authentication and revocation already exist; no push-registration or delivery contract was found in the inspected mobile API. |
| Delivery validation | **Doc:** the README and architecture document report iOS simulator validation, while signed distribution and physical-device verification remain separate. Those historical test counts were not rerun for this audit. |

Paths containing `...` above are under `kotlin/me/waveio/claudebot/` in the
named module. Shared controller paths start at `mobile-app/shared/src/commonMain/`.

## What server-to-phone push needs

**Proposal:** add authenticated, device-scoped registration with platform,
provider registration identifier, application/environment and a revision.
Bind it to the existing owner/device identity. Rotation, sign-out, re-pairing
and revocation must invalidate the appropriate registration without transferring
another device's pending state. Keep provider sending credentials on the server.

Persist a delivery/outbox record when a job reaches a terminal state. Use a
stable event ID for duplicate suppression across retries, SSE and push. Recheck
revocation at dispatch, bound retries, retire invalid destinations, and make
notification previews opt-in. Send a small identifier and fetch protected
content through the existing API rather than putting conversation text in every
payload. These are proposed contracts, not existing endpoints.

**Platform:** Android needs a configured Firebase project, a compatible FCM
SDK and registration lifecycle handling. The current official guide prefers
Firebase Installation IDs and marks the older registration-token interface
deprecated; pin the chosen SDK and validate its identifier contract before
implementation. Android 13+ notification permission remains a separate user
choice. Sources: [FCM setup](https://firebase.google.com/docs/cloud-messaging/android/get-started)
and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

**Proposal/Unknown:** iOS needs the owner's Apple application identity,
signing/capability setup and APNs provider authorization, plus device
registration and environment handling. Existing Apple/Firebase account access
was not inspected or changed.

**Doc/Inference:** `TUNNEL.md` describes inbound HTTPS to the existing host.
Push dispatch is outbound from that host to APNs/FCM; it does not require those
providers to call through the tunnel. The tunnel still matters when a tapped
notification fetches its conversation. A stopped bot cannot originate a new
push, and a live tunnel alone does not make a stopped bot available.

## Phone-side backup alarm

**Proposal:** persist an explicit, opt-in phone alarm ahead of its deadline,
with schedule revision, timezone, occurrence ID and cancel/update reconciliation.
A missing heartbeat can mean a network partition; it does not prove the bot is
off. Agree whether the product prefers possible duplicate alerts or possible
silence. Do not advertise an exact, outage-only guarantee from heartbeat polling.

**Platform:** Android exact alarms require the applicable special access and
use-case eligibility. Check permission before scheduling; permission revocation
and reboot require recovery. Repeating AlarmManager alarms are inexact, so an
exact recurring design must deliberately schedule subsequent occurrences.
WorkManager is not a replacement for an exact wake-up alarm.
[Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms).

**Platform:** iOS/iPadOS 26 provide AlarmKit, with per-app authorization and
`NSAlarmKitUsageDescription`. Its alarms support schedules and can alert through
Focus/silent mode. Blink's iOS 16 deployment target therefore needs an availability
guard and an explicitly different older-OS experience; do not silently raise
the target or claim ordinary local notifications have identical semantics.
[AlarmKit introduction](https://developer.apple.com/videos/play/wwdc2025/230/),
[authorization and scheduling](https://developer.apple.com/documentation/AlarmKit/scheduling-an-alarm-with-alarmkit).

**Platform:** Apple does not guarantee background-notification delivery and may
delay or throttle it. A silent push cannot guarantee a just-in-time heartbeat
check or backup cancellation.
[Background updates](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app).

## First bounded milestone

**Proposal:** Android private reply-completion push for an already paired
device, using the existing host/job lifecycle. Keep alarm failover and iOS
background work as separate milestones.

Acceptance criteria:

- Opt-in registration is owner/device scoped; rotation and revocation have
  deterministic tests, including a revocation racing queued dispatch.
- A terminal job creates one durable notification event. Retries and concurrent
  SSE reception do not display duplicate notifications.
- Permission denial and unavailable registration retain normal foreground
  operation. Notification tap fetches only the authorized conversation.
- Private previews are the default; disabled notifications do not expose reply
  text. No provider sending credential enters the app or repository.
- A physical Android device receives an actual provider notification while the
  app process is absent, then opens the correct conversation. Record platform
  restrictions and test force-stop separately; simulator fixtures alone do not
  certify delivery.
- Preserve existing mobile API behavior and capability reporting until the
  feature is configured and tested. No production enablement is part of Wave 1.
