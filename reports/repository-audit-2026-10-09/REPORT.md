# Repository audit — 2026-10-09

Four new findings in interactive dashboard tool cards, with repair examples,
acceptance checks, and three additions I would build. This folder follows the
existing `reports/repository-audit-<date>/` convention. Product repairs remain
proposals; this audit changes reports and a handoff pointer only.

Starting revision: `a6291e37`. I reviewed the recent inline-card change, tool
schemas/handlers, activity persistence, and chat submission. Earlier October
1–8 reports were compared to avoid repeating their findings. Their package,
release, ownership, scheduler, and display issues are not new findings here.
This is a bounded review, not coverage of every module or deployed service.

The repository also contains other agents' unfinished changes. This pass uses
synthetic state and does not inspect credentials, personal profiles, chats,
installed screen/store packages, or real provider/device sessions. See [validation](VALIDATION.md)
and the reproducible [Python](probes.py) and [browser](probes.mjs) probes.

## Ranked findings

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| U-02 | High for reliable answer delivery | A stale message bridge drops answers while the card reports success | Real runtime hook and extracted bridge effect, with synthetic stream |
| U-01 | Medium | A valid question without preset options has no inline card | Real backend handler plus React renderer |
| U-03 | Medium | Checklist progress disappears when its component remounts | Actual checkbox click and React unmount/remount |
| U-04 | Medium | Inline cards reconstruct different text from the backend UI payload | Same real tool arguments compared across Python and TypeScript |

Confidence is high in these observed behaviors. Their deployed frequency is
unknown. Browser probes render the real card/runtime code in a minimal fixture;
they do not mount the complete dashboard shell or make actual chat requests.
Mock adapters are described explicitly in the validation file.

## U-01 — Free-text questions are valid but silently disappear

**Source:** [`tools/ui_tools.py`](../../Virtual%20Bot/tools/ui_tools.py),
`31–51`, `102–116`;
[`interactiveToolData.ts`](../../Virtual%20Bot/dashboard/src/panels/chat/interactiveToolData.ts),
`60–79`;
[`BotUiOverlay.tsx`](../../Virtual%20Bot/dashboard/src/components/shell/BotUiOverlay.tsx),
`49–55`.

`ask_question` requires only `question`. Options are optional, and custom input
defaults to allowed. The real handler accepts `{"question": "What should this
fixture be called?"}`, publishes an empty-option question, and reports it shown.
The inline parser returns `null` whenever the normalized options array is empty.
The global overlay is also suppressed while Chat is selected.

The fixture gets `ok: true` from the backend, but the real inline renderer
produces zero cards and zero answer inputs. This is a valid request under the
published schema, not malformed model output. The bot's tool result tells it to
wait for a user answer, even though no card provides an answer field.

**How I would fix it:** permit an optionless question when custom input is
available. Continue rejecting an empty choice tool, whose contract requires
selectable options. For example:

```typescript
const options = choices(input.options);
const allowCustom = name === 'ask_question' && input.allow_custom !== false;
if (!options.length && !allowCustom) return null;
return {
  kind: name === 'ask_question' ? 'question' : 'choice',
  title: cleanText(input.question ?? input.title, MAX_TITLE),
  options,
  allowCustom,
};
```

Also have the backend reject a question with neither usable options nor custom
input, rather than acknowledging an impossible interaction.

**Acceptance:** omitted/empty options with custom input show a question and
input; a custom answer reaches chat; an empty choice or explicitly disabled
custom-only question gets a clear rejection. Verify valid free-text behavior in
English and Ukrainian and when restoring a saved tool step.

## U-02 — A captured idle state defeats the busy-answer handoff

**Source:** [`ChatPanel.tsx`](../../Virtual%20Bot/dashboard/src/panels/chat/ChatPanel.tsx),
`186–198`;
[`useChatRuntime.ts`](../../Virtual%20Bot/dashboard/src/panels/chat/useChatRuntime.ts),
`198–224`, `375–377`, `400–420`;
[`InteractiveToolCard.tsx`](../../Virtual%20Bot/dashboard/src/panels/chat/InteractiveToolCard.tsx),
`13–19`, `39–45`, `66`.

The message bridge is installed in an effect that depends only on `chat.send`,
but its function reads `chat.running` and `chat.cancel` from that render. In an
already selected conversation, starting a turn changes `running` without
changing the `send` callback's dependencies. The bridge can therefore continue
seeing the idle value `false` throughout that turn.

An answer click then takes the bridge's direct-send branch. The current runtime
has an active `abortRef` and returns immediately. The card cannot tell: its
bridge API returns `void`, so it unconditionally marks the answer sent after
invoking the function.

The fixture opens a synthetic existing conversation, starts an unresolved
stream, and delivers a question tool event through its real `onTool` handler.
Clicking PDF shows **“Answer sent”**, disables the options, leaves the source
stream un-aborted, and records only the original stream request. No request for
the selected answer is created. A first turn that changes the session ID can
refresh the effect and mask this; existing conversations are the clear trigger.

**How I would fix it:** first correct the captured-state dependency:

```typescript
useEffect(() => {
  window.__vbotSendMessage = (text: string) => {
    if (chat.running) void chat.cancel().then(() => chat.send(text));
    else void chat.send(text);
  };
  return () => { delete window.__vbotSendMessage; };
}, [chat.send, chat.running, chat.cancel]);
```

That example repairs the specific stale closure; it does not make a void call
a delivery receipt. Follow it with an explicit submission result, for example
`Promise<{accepted: boolean; reason?: 'busy' | 'failed'}>`. `send()` must report
rejections rather than silently returning, and a card should enter a pending
state and show “sent” only at the documented acceptance point. If acceptance
means saved by the server, return a persisted message ID; do not label optimistic
local insertion as confirmed delivery.

The existing cancel-then-send approach also needs a defined turn policy. Client
stream abort is not proof that every backend/tool side effect has stopped.
An alternative is a single queued answer tied to the source conversation and
call ID, sent after turn settlement. Choose that policy before making all card
clicks interrupt arbitrary work.

**Acceptance:** click while an existing conversation is running; the answer
either follows the defined cancellation/queue policy or stays editable with a
localized busy error. Restoration rejection, server rejection, network failure,
and duplicate click never claim successful delivery. The answer cannot drift to
another conversation during navigation.

## U-03 — Checklist state is stored only inside the component

**Source:** `InteractiveToolCard.tsx:71–94`;
`useChatRuntime.ts:126–148`;
[`activity.ts`](../../Virtual%20Bot/dashboard/src/panels/chat/activity.ts), `5–17`.

`TodoCard` initializes `items` from the recorded tool input and changes only
its local `useState`. No save callback, shared state update, or checklist mutation
is made. Reopening the conversation reconstructs the original tool input, not
the user's changed ticks. A React remount is enough to lose progress even
without reloading the page.

The real checkbox changes to checked, then returns to unchecked after its
component is unmounted and mounted with the same step. The fixture's mutation
adapter sees zero writes. The question's local sent marker also resets on
remount, making its options available again; this is marker restoration, not
proof that a delivered answer was duplicated.

This is a persistence gap in the interactive checklist, separate from the
previous audit's file-editor draft-loss issue. The initial card can be restored
from history; subsequent human interaction is not included in that history.

**How I would fix it:** move action state to a conversation-owned store keyed
by stable call ID, and persist it through an owner-checked update operation.
The card should receive current state plus an action callback, rather than
deciding independently what the transcript remembers:

```typescript
type ChecklistAction = {
  sessionId: string;
  callId: string;
  itemId: string;
  done: boolean;
  expectedRevision: number;
};
```

Use stable item IDs in a canonical UI payload, not label/index keys that change
when items are reordered. Update only the interaction record, preserving the
original tool input as provenance. Reuse existing owner and conversation-kind
scoping. Add an atomic interaction-state update with revision checking; the
current turn lease in `mobile_bridge.py:23–79` does not make arbitrary history
metadata writes transactional, and `chat_store.py:299–335` has no revision/CAS
operation. Bound action records and reject stale revisions. An optimistic tick
must revert or show an unsaved state if persistence fails.

**Acceptance:** ticks survive section navigation, conversation switching,
reload, and a second authorized device. Another owner cannot mutate the list.
Concurrent updates do not silently overwrite each other. Failed saves remain
visible, and question answer receipts restore their accepted state as well.

## U-04 — Display reconstruction does not share the handler's normalization

**Source:** `tools/ui_tools.py:27–28`, `55–71`, `74–89`;
`interactiveToolData.ts:10–15`, `29–56`, `59–79`;
[`tool_activity.py`](../../Virtual%20Bot/tool_activity.py), `107–113`.

The card reconstructs a UI from persisted tool **arguments**. The Python handler
independently sanitizes those arguments into the UI payload, but that canonical
payload is not returned in its small `ok/shown` receipt. Two implementations
therefore control what the user sees.

Valid synthetic input demonstrates disagreement:

| Value | Python UI payload | Inline TypeScript card |
| --- | --- | --- |
| A 100-character choice label | 80 characters | 100 characters |
| A 180-character checklist item | 180 characters | 120 characters |

The choice card submits its displayed label, so it can send a different literal
answer from the option that was published to other UI consumers. A long item
loses sixty characters in the inline view. This does not establish that the
model will always misunderstand the answer; it establishes inconsistent option
identity and user-visible text under valid inputs. Raw-input reconstruction
also invents the same contract separately for validation and replay.

**How I would fix it:** normalize once on the backend, return and persist the
bounded normalized UI descriptor, and render it in each client. For example,
extend the existing successful result with a typed field:

```python
return {"ok": True, "shown": "choice", "ui": {
    "version": 1, "id": payload["id"], "kind": "choice", "data": payload,
}}
```

Tie `id` to a persisted call identity and stable option IDs; do not generate a
new interaction identity on every replay. The frontend still validates the
descriptor's types, bounds, and allowlisted kinds. Keep previews inert while a
tool is active, or distinguish previews from confirmed payloads explicitly.
If old sessions need argument reconstruction, make it a versioned legacy path
with identical limits and clear missing-field behavior.

**Acceptance:** schema-valid fixtures produce identical canonical labels,
descriptions, item text and custom-input flags across inline/global/mobile
consumers that support them. Clicking an option submits its ID plus the exact
canonical value. Legacy history remains readable without claiming new receipts.

## Additions I would build

1. **A durable response inbox for questions.** A conversation can have several
   pending questions; show each with source call, status, and a clear reply or
   dismiss action. Store acceptance receipts and offer a queued answer when the
   agent is busy. Scope it by owner/conversation, expire obsolete questions, and
   make repeated submission idempotent. The mobile client already queues question
   answers through its durable outbox and records handled question IDs
   ([`AppController.kt`](../../mobile-app/shared/src/commonMain/kotlin/me/waveio/claudebot/state/AppController.kt),
   `613–649`, `695–708`). Reuse that behavior
   for a dashboard/shared inbox instead of inventing a second queue policy.
   This also extends the earlier lifecycle receipt ideas to human responses;
   it needs U-02/U-04 repaired. Mobile's local handled marker is not itself a
   shared server acceptance receipt.
2. **A synchronized task list inside the conversation.** Preserve ticks, show
   unsaved/conflict state, and offer progress summaries without sending a new
   model turn for every checkbox. Begin with U-03's action record, then expose
   the same list on the device and mobile app. Keep the original plan readable
   after edits and use locale keys for controls and status messages.
3. **A conversation “continue from here” action.** Select an old question and
   answer it in an explicit fork instead of silently reusing a stale control
   in the current turn. Carry the source call and selected context into the
   fork using the existing owner namespace and immutable snapshot machinery.
   Add a distinct continue-fork policy: `mobile_api.py:249–306` currently supports
   only edit/regenerate and snapshots before the originating user turn, so its
   existing actions cannot retain the displayed question then append an answer
   unchanged. Define and test the retained prefix explicitly. Preview what
   context will be retained and make the destination visible before submission.

I would repair valid question rendering and answer delivery first, establish a
single versioned UI descriptor next, then persist interaction state and build
the response inbox. The existing React hooks, FastAPI routes, and conversation
storage are sufficient starting points. No new agent runtime is proposed.
The passing checks here verify current behavior and evidence, not shipped fixes.
