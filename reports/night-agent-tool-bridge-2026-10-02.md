# Night agent review: forwarded tool bridge requests

The 2026-10-02 repository audit reproduced a boundary bug in the OpenClaw tool
bridge. With Clerk enabled, a request whose socket peer was `127.0.0.1` was
treated as the local bridge even when it carried `X-Forwarded-For` or
`Forwarded`, which a reverse proxy can add for a remote browser.

The bridge now rejects a tokenless request when either forwarded-header family
is present. A valid bearer token still works through a proxy. The local
tokenless bridge and non-loopback denial remain unchanged. The route tests cover
direct loopback, forwarded loopback with both header spellings, remote callers,
and an authenticated forwarded call; rejected requests never execute a tool.

Verification completed:

- `tests/test_tool_events.py`: 11 passed.
- Related upload/connector tests: 59 passed.
- Full Virtual Bot suite: 995 passed, 6 skipped, 150 subtests passed.
- Dashboard: 99 unit tests passed and TypeScript typecheck passed.
- Live loopback smoke on port 8120: `/`, `/dash/`, `/screen`, status/auth/memory
  endpoints and a dashboard asset returned 200; memory and workspace traversal
  attempts returned 400. The server was stopped after the check.
- Mac output was muted before testing and remains muted.

The change is intentionally limited to the trust boundary and its regression
tests. The audit’s other findings (launcher LAN binding, operator-only MCP
installation, shared integration ownership, and image-proxy redirect checks)
remain separate follow-up work.
