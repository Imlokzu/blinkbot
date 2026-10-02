# Repository audit — 2026-10-02

This is an independent follow-up to `reports/repository-review-2026-10-01.md`.
I checked the current checkout across the launcher, Virtual Bot API, memory,
coding mode, setup wizard, Vision Agent, and display service. I did not repeat
the earlier upload, setup-route, URL-save, screen-store, or context-preview
findings where the current source already contains the described fix or where
the finding was already recorded.

The working tree already had unrelated staged, modified, and generated files
before this audit. This report is the only new artifact from this pass.

## Validation snapshot

- `Virtual Bot`: `PYTHONPATH=. .venv/bin/pytest tests/ -q` — **963 passed, 6
  skipped, 150 subtests passed**.
- The tool bridge proxy check was reproduced with `TestClient`: a request whose
  socket peer was `127.0.0.1` and which carried `X-Forwarded-For` or
  `Forwarded` was accepted without a Clerk token.
- Isolated loopback smoke on port `18102`: `/` → 200, `/api/status` → 200,
  `/static/index.html` → 200, and `/api/memory/file?path=../../etc/passwd` →
  400. The server was stopped after the checks.
- The image proxy, memory, launcher, and service findings below are source
  findings; the display broadcast and alarm findings were reproduced with the
  small fake-client/time fixtures described below.
- A separate adversarial review rechecked R-01 through R-11, the medium checks,
  and the proposed fixes; verdict **READY**, with no report corrections needed.
- Confidence is separated from impact: “High” means the repository directly
  supports the claim, not that the issue is reachable in the default loopback
  installation.

## Priority order

| ID | Finding | Severity | Confidence | First action |
| --- | --- | --- | --- | --- |
| R-01 | Launcher exposes the web server on every interface while disabling Clerk | High/Critical when LAN reachable | High | Bind loopback by default; require explicit LAN mode with auth |
| R-02 | Forwarded requests can impersonate the local tool bridge | High | Reproduced | Reject forwarded loopback requests or require a bridge token |
| R-03 | Any signed-in user can install an arbitrary stdio MCP command | Critical in multi-user mode | High | Make custom MCP operator-only and sandbox it |
| R-04 | Connector credentials and pair ownership are globally mutable by any user | High | High | Lock integration ownership to an operator/account |
| R-05 | Clerk brain isolation is defeated by the shared default owner root | High in multi-user mode | Reproduced | Scope profile, notes, facts, and approvals by Clerk user |
| R-06 | Coding mode runs an unsandboxed `omp --approval-mode yolo` process | High in multi-user mode | High | Add an OS/container sandbox before exposing it to other users |
| R-07 | Image proxy checks SSRF only before following redirects | High when remotely reachable | High | Validate every redirect hop and cap the chain |
| R-08 | Setup paths put API keys in process arguments or return raw CLI output | High | High | Use stdin/fd secrets and redact all output |
| R-09 | Vision Agent camera/settings endpoints have no auth and advertise wildcard exposure | High when manually/LAN deployed | High | Bind loopback or add service auth and narrow CORS |
| R-10 | OpenClaw execution/security settings are user-gated, not operator-gated | High in multi-user mode | High | Apply the operator gate to global security controls |
| R-11 | Workspace append writes bypass the documented file-size limit | Medium | High | Enforce the limit on the final file size |

## Findings

### R-01 — The launcher contradicts its own loopback threat model

**Evidence.** `launcher/main.go:58-62` starts the web service with
`uvicorn ... --host 0.0.0.0`. `launcher/main.go:218-240` adds
`CLERK_DISABLED=1` by default. The comment at `:234` says the dashboard is
loopback-only, but the command does not enforce that. The repository's
`Virtual Bot/tunnel_guard.py:1-8` explicitly describes auth-disabled public
exposure as fatal.

**Inference and impact.** A laptop or Pi on a LAN can receive the dashboard and
state-changing API with no token. A Wi-Fi peer can then reach chat, workspace,
service, screen, or integration routes. This is a deployment bug even though
the hand-written README normally tells users to run on `127.0.0.1`.

**How I would fix it.** Make the safe mode true in code, and make LAN exposure
an explicit opt-in:

```go
host := "127.0.0.1"
if os.Getenv("VBOT_ALLOW_LAN") == "1" {
    host = "0.0.0.0"
    // Refuse startup unless Clerk or the tunnel guard is configured.
}
args := []string{"-m", "uvicorn", "main:app", "--host", host, ...}
```

Add a launcher test that asserts the default web args contain `127.0.0.1`, and
an end-to-end check that a LAN request cannot use the auth-disabled bypass.

### R-02 — The tool bridge trusts a socket address that a proxy can forge

**Evidence.** `Virtual Bot/main.py:443-462` allows a missing token whenever
`_is_loopback(request)` is true. Unlike `_require_openclaw_operator` at
`:421-436`, it does not reject `Forwarded` or `X-Forwarded-*` headers and does
not validate the public Host/Origin. A reverse proxy commonly connects to the
backend from `127.0.0.1`, even for a remote caller.

**Observed behavior.** With Clerk enabled and a loopback `TestClient`,
`POST /api/tools/call` returned 200 without a token while carrying either
`X-Forwarded-For: 203.0.113.4` or `Forwarded: for=203.0.113.4`.

**How I would fix it.** Prefer a signed bridge credential for the local MCP
process. If a loopback exception must remain, centralize trusted-proxy handling
and reject forwarded requests before allowing it:

```python
if not token and _is_loopback(request):
    if any(name == "forwarded" or name.startswith("x-forwarded-")
           for name in request.headers):
        raise HTTPException(status_code=401, detail="bridge_token_required")
    return ""
```

Test direct loopback, forwarded loopback, foreign peer, and invalid-token cases
as one route matrix.

### R-03 — Custom MCP installation is remote command execution

**Evidence.** `Virtual Bot/main.py:1173-1186` only calls `_require_user`, then
passes a user-controlled `command`, `args`, `env`, URL, and headers to
`openclaw_extensions.add_mcp`. That function builds `openclaw mcp add` directly
(`Virtual Bot/openclaw_extensions.py:146-183`), including `--command` and
`--arg` values. The API accepts a name and a stdio command; it is not restricted
to the curated setup catalog.

**Inference and impact.** Any authenticated Clerk account can register `/bin/sh`
or another executable, cause the gateway to probe/run it, and potentially read
the bot's environment or establish persistence. The validation is about command
shape, not executable trust or sandboxing.

**How I would fix it.** Make this route operator-only. For non-operator users,
offer only signed catalog plans. If custom MCP is required, run each server in a
separate restricted process/container with an executable allowlist, a reduced
environment, resource limits, and an explicit approval record. Add a regression
that a non-operator request never reaches the CLI, including `/bin/sh` input.

### R-04 — Telegram and Discord are global resources with no owner check

**Evidence.** Integration mutations at `Virtual Bot/main.py:2619-2663` accept
any `_require_user` caller (or no user for package sharing). The bridge stores
one global token, owner list, and pairing code in `integrations/telegram.py:110-171`
and the equivalent Discord code. The status response includes a pair code and
link (`telegram.py:121-138`). `configure()` records a `clerk_user_id`, but no
route compares that value with the caller before reconfiguring, rotating, or
disconnecting.

Google has the same global-resource shape: `main.py:2690-2716` leaves the
callback unauthenticated, while `integrations/google.py:93-123` keeps pending
state and the resulting refresh token in process-global storage without the
initiating Clerk user.

**Inference and impact.** In a multi-user deployment, one account can replace
another account's bot token, rotate its pairing code, disconnect it, or redirect
incoming messages and attachments into the attacker's brain. Existing tests
expect pair-code access from a generic authenticated context, so this boundary
is currently cemented by tests.

**How I would fix it.** Treat integrations as operator-owned resources: require
`_require_openclaw_operator` for details/config/pair/disconnect/test, persist the
owner on first configuration, and require an explicit operator rotation to
change it. Never return a pair code to a non-owner. Add an Alice/Bob route
matrix covering config, details, pair-code, disconnect, and share-package.

### R-05 — Clerk users can read the shared default profile and approvals

**Evidence.** `_active_brain()` claims Clerk account isolation in
`Virtual Bot/main.py:111-129`, but `_extract_and_save_facts()` always writes to
`init_user_brain(None)` (`main.py:1983-1992`) and the chat calls it from a worker
at `main.py:2225-2229`. Prompt construction merges that same default profile and
notes into every active brain (`Virtual Bot/brains.py:301-325`); the memory tool
does so too (`Virtual Bot/tools/registry.py:184-198`). Separately,
`Virtual Bot/tools/fs_tools.py:32,54-84,165-176` stores approved absolute paths
in one process-wide `fs_access.json` with no Clerk owner.

**Observed behavior.** The current test suite verifies session isolation in
auth-disabled mode, but a Clerk-context fixture reproduced facts written while
running as Alice under the default hash, then visible when a Bob brain built its
prompt. The same default-root merge makes the leak durable across sessions.

**How I would fix it.** Resolve the canonical root from the active Clerk ID and
pass that ID into the background fact extractor. Do not merge the default root
when a Clerk user is active. Store filesystem approvals under the user's brain
or as `{user_id, path}` records:

```python
uid = brain_context.get_active_clerk_user()
root = (brain_context.init_clerk_user_brain(uid)
        if uid else brain_context.init_user_brain(None))
```

Add a two-user test for facts, prompt notes, `memory_search`, and `fs_approve` /
`fs_read`; Alice's approval and profile must never satisfy Bob's request.

### R-06 — Coding mode is an unsandboxed shell with approval disabled

**Evidence.** The coding module documents that `cwd` is “a convenience, not a
sandbox” and says it must not be opened to untrusted users
(`Virtual Bot/coding.py:18-24`). It then starts `omp` with
`--approval-mode yolo` and the host process environment
(`coding.py:116-133`). `/api/code/chat` only checks `_require_user`
(`Virtual Bot/main.py:3871-3890`), not the operator gate.

**Inference and impact.** Any authenticated account can prompt the coding agent
to read outside `code_root`, execute commands, or inspect inherited secrets.
Project-path validation does not constrain the subprocess after it starts.

**How I would fix it.** Gate coding to an operator allowlist until a sandbox is
available. Then run it in a container or OS sandbox with a read/write mount only
for the selected project, a scrubbed environment, no host sockets, CPU/memory/
process limits, and an approval policy that is not `yolo`. Add a fixture that
attempts to read a sibling directory and asserts denial.

### R-07 — Image proxy SSRF protection stops at the first URL

**Evidence.** `Virtual Bot/image_proxy.py:84-89` validates the initial host with
`check_url(url)`, then creates `httpx.AsyncClient(..., follow_redirects=True)`.
No redirect callback re-resolves or checks the next host. The unauthenticated
`/api/image/fetch` route calls this helper at `main.py:1821-1840`.

**Inference and impact.** A public redirector can send the server to
`127.0.0.1`, an RFC-1918 address, or a metadata endpoint. The first-hop DNS
check does not protect the request that actually returns the bytes.

**How I would fix it.** Disable automatic redirects, follow at most a small
number manually, and run the same DNS/IP policy for every `Location`. Disable
ambient proxy inheritance for this fetch and enforce a byte limit before joining
chunks. Add a redirect-to-loopback regression using a mocked two-hop client.

### R-08 — Setup flows expose credentials through argv and command output

**Evidence A.** `Device Setup Wizard/electron/openclaw-cli.cjs:61-80` appends
`--anthropic-api-key <secret>` or `--groq-api-key <secret>` to the child argv.
Process listings, crash reports, and shell instrumentation can observe argv.

**Evidence B.** `Virtual Bot/main.py:1067-1080` returns the last 1,500 characters
of raw `openclaw mcp add` stdout/stderr after receiving environment values. The
curated extension path redacts values, but this setup path does not.

**How I would fix it.** Use the CLI's stdin, an inherited file descriptor, or a
0600 temporary secret file and scrub the value before every log/IPC boundary.
Return only a stable status and redacted diagnostic code. Add tests that assert
the spawned argv and response body contain neither a supplied key nor a key
shaped string.

### R-09 — Vision Agent is an unauthenticated camera service when run as documented

**Evidence.** `Vision Agent/main.py:95-105` enables `allow_origins=["*"]` for
all methods and headers, and its camera, snapshot, frame, and settings routes
have no auth dependency (`main.py:198-282` and the snapshot/stream handlers).
The canonical README command binds `0.0.0.0` (`Vision Agent/README.md:13-17`),
and `config.yaml:31-33` advertises the same host. The main launcher currently
overrides this particular child to loopback, so the risk is conditional on
manual or LAN deployment.

**Inference and impact.** A LAN site can view the camera, change detector
thresholds, or send repeated frame requests. This is a privacy and resource
boundary, not merely a CORS preference.

**How I would fix it.** Bind loopback by default and document a separate
authenticated remote mode. For remote mode, require a service token or mTLS,
narrow CORS to the Electron origin, rate-limit frame/settings calls, and read
uploads in bounded chunks (`MAX_BYTES + 1`) so chunked bodies cannot bypass the
memory limit. Add anonymous/remote tests for snapshot, stream, settings, and
over-limit chunked uploads.

### R-10 — Gateway security switches are exposed as ordinary user settings

**Evidence.** `Virtual Bot/main.py:859-878` protects `/api/openclaw/settings`
with `_require_user`, while `Virtual Bot/openclaw_settings.py:CATALOG` includes
`tools.exec.mode`, `tools.exec.security`, `tools.elevated.enabled`,
`tools.fs.workspaceOnly`, `commands.config`, and `commands.restart`.
The newer OpenClaw control router correctly uses `_require_openclaw_operator`,
but this older settings route does not.

**Inference and impact.** A normal signed-in user can globally turn on full
execution or elevated tools for the gateway, changing the security posture of
every account.

**How I would fix it.** Split the catalog into per-user preferences and
operator-only gateway controls. Call `_require_openclaw_operator` before any
security/command path and add a non-operator regression for each dangerous
setting.

### R-11 — Appending to a workspace file bypasses the write quota

**Evidence.** `Virtual Bot/workspace.py:238-252` limits the incoming content,
but the append branch does not check the existing file size before writing.
`main.py:3691-3695` passes the caller's `append` flag through unchanged.

**Inference and impact.** Repeated valid append calls can grow one file without
bound, exhausting disk on the Pi despite `MAX_WRITE_BYTES` appearing to be a
per-file guard.

**How I would fix it.** Compute `existing_size + len(content.encode("utf-8"))`
before opening the file and reject the write if it exceeds the quota. Preserve
the original file on rejection and test an append to a nearly full file.

## Additional medium reliability checks

- `claude-bot-display/backend/server.py:34-43` iterates the mutable global
  `_clients` set across `await client.send_text(...)`. A connect/disconnect that
  mutates the set during the await can raise `RuntimeError` and stop a state
  broadcast. Iterate over `tuple(_clients)`, isolate send failures, and add a
  concurrent connect/disconnect test.
- `claude-bot-display/backend/services.py:129-145` clears alarm IDs only when a
  one-second loop lands before 500ms. A loop at `00.700` misses the reset and
  suppresses the same alarm on the next day. Track the last trigger date/minute
  per alarm instead of relying on a narrow wall-clock window.
- `Virtual Bot/dashboard/src/lib/auth.ts:49-54` puts Clerk bearer tokens in
  `?token=` URLs for `EventSource`; proxy/access logs and browser history can
  retain them. Use a short-lived one-time stream ticket or an HttpOnly same-origin
  session cookie, and keep REST authentication in `Authorization` headers.
- `Virtual Bot/workspace.py:330-364` can save a remote SVG, and
  `Virtual Bot/main.py:4067-4087` serves it inline as `image/svg+xml` from the
  dashboard origin. A hostile SVG can therefore execute script with dashboard
  origin privileges when previewed. Reject SVG for saved remote media or serve
  untrusted previews from a sandboxed/download-only origin with a restrictive
  CSP; add a script-bearing SVG regression.

## Ideas I would add next

1. **Make authorization executable metadata.** Define every route as
   `public_share`, `device_loopback`, `user`, or `operator`, then generate an
   auth/forwarded-header test matrix from that table. This would have caught the
   launcher, tool bridge, connector, and OpenClaw settings inconsistencies.
2. **Add a per-user state manifest.** Keep brain, chat, connector ownership,
   filesystem approvals, uploads, and public-share records under one explicit
   owner ID. An export endpoint can then snapshot that manifest consistently,
   with retention and trash quotas instead of unbounded local state.
3. **Use atomic release swaps for generated assets.** Build dashboard assets in
   a temporary directory, verify that `index.html` and the service-worker asset
   graph is closed, then rename the directory into place. The live dashboard
   should never see half of a Vite build.
4. **Create deterministic hardware/gateway replay fixtures.** Record sanitized
   camera failures, display disconnects, gateway timeouts, TTS fallback, and
   reconnect events. A replay command would exercise recovery without hardware,
   paid providers, or network timing.
5. **Add a physical capability boundary before mobility.** Keep observation,
   reversible device actions, and physical movement as separate capabilities;
   require confirmation plus a hardware watchdog for movement, and do not let
   an LLM subprocess write GPIO directly.

## Limits

The audit did not change product code, did not inspect secrets, and did not
assume that a loopback-only deployment is publicly reachable. Findings marked
conditional become urgent when the service is bound to a LAN address, placed
behind a reverse proxy, or used by more than one Clerk account. The next useful
step is to fix R-01 through R-05 as one authorization/ownership hardening pass,
then rerun the route matrix and live smoke checks.
