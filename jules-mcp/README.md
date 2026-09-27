# jules-mcp — `jude`

Delegate long coding tasks to [Jules](https://jules.google), Google's
asynchronous cloud coding agent, and get told when it comes back. In this
repo the agent goes by **Jude** and the command is `jude`.

Jules clones a GitHub repo into its own VM, plans, edits, runs commands and
opens a pull request. That makes it a good helper for another agent: Claude
Code hands off a slow, self-contained job (translate comments, add tests, a
mechanical refactor), keeps working on something else, and picks the result up
when Jules is done or has a question.

One Go binary, no dependencies, two modes:

- `jude mcp` — MCP server over stdio (Claude Code, Cursor, Virtual Bot);
- `jude <command>` — CLI for a terminal or a shell-driving agent.

## Setup

1. Connect the repo: install the Jules GitHub app from https://jules.google.com.
2. Create an API key at https://jules.google.com/settings#api.
3. Put it in the repo root `.env` (gitignored):

   ```
   JULES_API_KEY=...
   ```

   The binary reads `JULES_API_KEY` from the environment, or from the nearest
   `.env` above the working directory or the binary. That way the key never has
   to be copied into an MCP config.

4. Build and register:

   ```bash
   cd jules-mcp
   go build -o bin/jude .
   claude mcp add jude --scope local -- "$PWD/bin/jude" mcp
   ```

## MCP tools

| Tool | What it does |
|---|---|
| `jude_start` | Start a session: prompt, repo (default: origin remote), branch, `auto_pr` (default true), `require_plan_approval` |
| `jude_wait` | Block until the session hands control back or `timeout_seconds` passes (default 480) |
| `jude_status` | State, PR, recent activities |
| `jude_list` | Recent sessions |
| `jude_reply` | Answer the agent or give follow-up work |
| `jude_approve` | Approve a pending plan |
| `jude_patch` | Latest unified diff plus suggested commit message |
| `jude_sources` | Repos connected to Jules |

"Hands control back" means one of `COMPLETED`, `FAILED`,
`AWAITING_USER_FEEDBACK`, `AWAITING_PLAN_APPROVAL`, `PAUSED`. The wait result
includes the agent's last message, so a question from Jules arrives together
with the notice that it stopped.

## CLI

```bash
jude start --title "Translate comments" "Translate every Ukrainian comment in Voice Loop/ to English..."
jude start - < task.md          # long prompt from a file
jude wait 1234567890 --timeout 3h
jude status 1234567890
jude reply 1234567890 "Yes, cover the CLI too"
jude patch 1234567890 | git apply
```

`wait` exit codes: `0` completed · `1` failed or error · `2` timed out ·
`3` needs you (a question, plan approval, or paused). An agent can run
`jude wait` in the background and branch on the code when it exits.

## Environment

| Variable | Meaning |
|---|---|
| `JULES_API_KEY` | API key (env or `.env`) |
| `JULES_SOURCE` | Default repo when none is given, e.g. `Imlokzu/claude-bot` |
| `JULES_API_URL` | Override the API base (tests use a fake server) |
| `JULES_POLL_SECONDS` | Poll interval for `wait` (default 30) |

The Jules API is alpha (`v1alpha`); field names may change.

## Tests

```bash
go test ./...
```

The tests run against an in-process fake of the API, so they need no key and
no network.
