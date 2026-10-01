# OpenClaw controls in the dashboard

The dashboard exposes the gateway's agent roster, session activity, automation
and channel health in the bot's existing warm interface. Open these routes under
`/dash/`: `#/agents`, `#/sessions`, `#/automation`, and `#/channels`.

Chat, Usage, tools, skills and MCP settings retain their existing surfaces.
These pages use the installed OpenClaw RPC contracts through the backend CLI;
there is no additional gateway token in browser storage.

## Operator access

The launcher defaults to a local single-user dashboard with `CLERK_DISABLED=1`.
These controls allow that mode only for direct loopback requests with local
Host/Origin and no forwarding headers. A tunnel or reverse proxy does not gain
access merely because it connects from localhost.

For an authenticated installation, set `VBOT_OPERATOR_USER_IDS` to a
comma-separated list of permitted Clerk user IDs in the server environment.
Other authenticated chat users receive HTTP 403 on these gateway-wide controls.
Keep Clerk enabled when exposing the dashboard outside loopback.

## Pages and behavior

Agents shows the configured model, fallbacks, runtime, thinking default and
whether a workspace is configured. Links filter sessions or automation for an
agent. Model routes are defaults; Usage reports the actual model after fallback.

Sessions requests 50 rows at a time from currently configured agents and
preserves gateway pagination. Its search covers the current page's metadata.
Token counts represent context snapshots, with explicit freshness, rather than
lifetime usage. Opaque session identifiers replace gateway keys. No conversation
titles, previews, transcript content, paths or ownership data is returned.

Automation requests 50 jobs per page. It can create daily jobs in an IANA time
zone or recurring jobs at intervals from 5 minutes to 7 days. Creation always
uses an isolated agent session, starts paused, and disables delivery and failure
alerts. Enabling a job schedules real agent work. Existing jobs retain their
original payload and delivery settings; a revision guard rejects stale edits.
Run history exposes only the 20 latest timestamps, statuses, durations and model
metadata. It omits prompts, results, errors, addresses and shell commands.

Channels reads cached gateway status without probing or messaging. Configured,
running and connected remain separate values; a missing status is unknown.
Accounts use opaque IDs, and raw channel errors or credentials are not returned.

## API

All routes require operator access and return `Cache-Control: no-store`.

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/openclaw/control/agents` | Configured agent metadata |
| GET | `/api/openclaw/control/sessions?offset=0&agent=main` | Session metadata |
| GET | `/api/openclaw/control/jobs?offset=0&agent=main` | Job metadata and scheduler state |
| GET | `/api/openclaw/control/channels` | Channel account health |
| GET | `/api/openclaw/control/jobs/{id}/runs` | Recent run metadata |
| POST | `/api/openclaw/control/jobs` | Create a paused agent job |
| POST | `/api/openclaw/control/jobs/{id}/enabled` | Revision-guarded enable/pause |

GET inventory and run-history routes accept `refresh=true`. Agent filters apply
before gateway pagination, including on the Automation page. Reads briefly share cached results
and coalesce concurrent requests. Mutations invalidate reads before and after
the RPC, including a failed reply that may represent a committed gateway write.

The create body contains `name`, `message`, `agent`, `kind` (`daily` or `every`),
`timezone`, and either `hour`/`minute` or `minutes`. Enabled-state updates require
`enabled` as a strict boolean and the observed `revision` from the job listing.
Unknown fields are rejected; this API cannot forward arbitrary RPCs or commands.

Gateway failures, including stale revision conflicts, currently return a generic
502 because the shared CLI helper does not preserve structured RPC errors.
Refresh the list before retrying. A failed create reply can be ambiguous: check
the list before creating the same task again.

## Verification

Backend regression: `PYTHONPATH="$PWD" .venv/bin/pytest tests/test_openclaw_control.py -q`
from Virtual Bot. Full backend suite: the same command with `tests/`.

Dashboard: `npm test`, `npm run typecheck`, `npm run build`. Opt-in browser checks:
`DASHBOARD_TEST_URL=http://127.0.0.1:8117 node tests/control.browser.mjs` from
the dashboard directory, against an isolated server serving the built assets.
The browser script uses isolated route fixtures for all writes and never
creates or modifies the owner's gateway jobs.

Mute macOS output before running tests, browser sessions or a smoke server;
leave it muted afterward, as required by AGENTS.md.
