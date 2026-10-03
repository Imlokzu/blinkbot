# Image generation with Codex

Codex is the first image provider. The bot uses the official local Codex
app-server and its native image tool with a ChatGPT login. It does not copy
OAuth credentials or fall back to separately billed API calls.

## Setup

1. Install [Codex CLI](https://learn.chatgpt.com/docs/codex/cli) on the machine
   running Virtual Bot. The integration was checked with CLI 0.159.3.
2. Run `codex login` and choose ChatGPT. Use `codex login status` to check the
   login. A supported paid plan is required; workspace policies and current
   usage limits also apply.
3. Restart Virtual Bot after installing this feature. With Clerk enabled,
   include the owner's Clerk user ID in `VBOT_OPERATOR_USER_IDS`. Existing
   direct loopback development access uses the same operator rules as the
   OpenClaw control pages.
4. In Chat, choose **+ → Create image**, enter a description, and generate.
   **Check again** refreshes readiness after signing in.

You can also send `/image A small pixel crab`, `Create an image of a crab`,
or the corresponding localized creation request. Explicit creation requests
are recognized before text inference and follow the same operator gate.
The generated image and description remain in the conversation's history.
Click its preview to enlarge or download it. Later text turns receive bounded
image references; their captions are original prompt descriptions, not visual
analysis of the generated pixels.

OpenAI documents that [native image generation](https://learn.chatgpt.com/docs/image-generation)
uses included Codex usage limits. A successful sign-in is a readiness check,
not proof that every model, plan or workspace permits a generation. Failures
appear in the conversation without claiming that an image was created.

## Provider contract

- One new image per request, from a text prompt of at most 8,000 characters.
- Reference attachments and editing are not supported by this first provider.
  Such requests return a localized explanation rather than dropping references.
- One generation at a time, with a 240-second deadline. Cancellation, timeout
  and provider failures clean up the process group and release the slot.
- Completed PNG/JPEG/WebP images are capped at 10 MiB, published atomically
  with 0600 permissions and owned upload names. Downloads authenticate the
  owner and use `Cache-Control: no-store`; the UI uses revocable blob URLs.
- Provider threads are ephemeral, use the catalog's default model, and disable
  inherited MCP servers and execution/browser tools. Raw process diagnostics,
  account details and credentials are not exposed to the browser.

`GET /api/images/status` is operator-only and returns `provider`, `available`
and a stable `code`. It never generates an image. Both the local tool registry
and MCP bridge declare `image_generate(prompt)`; the bridge has a 270-second
request budget. The HTTP tool endpoint still requires operator authentication.
A shared tokenless OpenClaw MCP runtime cannot establish a caller's ownership:
allowlisting the tool alone does not grant it image-generation access.

## Validation

Backend coverage includes native events and RPC ordering, subscription gating,
configuration isolation, owner downloads, output bounds, private publication,
process cleanup, cancellation, streaming/nonstreaming history and operator
rejection. Full Virtual Bot suite: 1047 passed, 6 skipped, 178 subtests passed.
Dashboard tests, TypeScript and a production build passed.

`dashboard/tests/image-generation.browser.mjs` runs against an isolated backend
with `VBOT_IMAGE_QA_MOCK=1`. It exercises real chat SSE and upload publication
with a fixture image, both locales, desktop/phone layout, focus, the viewer and
scoped accessibility audits. The environment switch exists only in the external
QA launcher, not in production code. HTTP smoke covered all release resources,
private upload/history, a forwarded operator request and traversal guards (400).

Independent native reviewers fixed the adapter and reviewed its architecture.
Real subscription generation remains unverified on this machine because the
local CLI currently requires ChatGPT sign-in.
