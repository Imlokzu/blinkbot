# AGENTS.md — Instructions for AI Agents & Coding Tools

This file defines the rules that **every** AI agent or coding tool (Claude Code,
Cursor, Copilot, Fable reviewers, etc.) MUST follow when working in this
repository. Read this file fully before making any change.

Project overview and full context live in `HANDOFF.md` and
`blink-full-spec-v3.md`. Everything you add to this repo is **English
only** — commit messages, code, comments, and docs. See section 5.

---

## 1. Git commit policy (MANDATORY)

**Commit after every logical change.** Do not batch unrelated work into one
commit. A "logical change" = one bug fix, one feature, one file/module edit that
stands on its own.

Workflow for each change:

```bash
git status
git add <the files you changed>
GIT_AUTHOR_NAME='Imlokzu' \
GIT_AUTHOR_EMAIL='lokzuhd@gmail.com' \
GIT_COMMITTER_NAME='Imlokzu' \
GIT_COMMITTER_EMAIL='lokzuhd@gmail.com' \
git commit -m "<type>: <short summary>"
git push
```

If the branch has no upstream yet, use `git push -u origin HEAD` instead of
`git push`.

- Never use `git add -A` / `git add .` blindly — stage only what you changed and
  verify no secrets or ignored files sneak in (`git status` first).
- Never use `--force` on shared branches. Never force-push.
- Do not switch branches in order to commit or push. Push the branch you are
  already on.
- If a change spans multiple files that belong together, commit them together,
  then push that commit.
- A change is not done until it is on GitHub (`origin`).

### Commit message convention (Conventional Commits)

```
<type>(<optional scope>): <imperative summary, <=72 chars>

<optional body: what & why, wrap at 72 cols>
```

Allowed `type` values:
- `feat` — new functionality
- `fix` — bug fix (reference the HANDOFF bug number if applicable, e.g. "fix: voice-loop tts reuse hang (bug #1)")
- `refactor` — code change that neither fixes a bug nor adds a feature
- `docs` — documentation only (README, wiki, this file, spec)
- `test` — adding or fixing tests
- `chore` — tooling, deps, config, build
- `perf` — performance improvement

Scope examples: `vision-agent`, `voice-loop`, `display`, `virtual-bot`,
`remote-control`, `setup-wizard`, `plugin`.

Examples:
```
fix(voice-loop): reinit pyttsx3 per call on darwin to avoid silent hang (bug #1)
feat(virtual-bot): add /api/memory endpoints with path-traversal guard
docs: add project wiki.html
```

---

## 2. Identity / attribution

The owner requires every commit in this repository to be authored **and**
committed as:

- name: `Imlokzu`
- email: `lokzuhd@gmail.com`

Do not commit as any other name or email. Do not change `git config`. Do not
use `--author`. Set `GIT_AUTHOR_*` and `GIT_COMMITTER_*` for that commit only,
as in section 1. This identity is the owner's; using it is required, not
impersonation.

---

## 3. Secrets — hard rules

- **Never** commit tokens, API keys, or credentials. This includes
  `OPENCLAW_TOKEN`, `ANTHROPIC_API_KEY`, and anything in `config.yaml` that
  holds a secret.
- Secrets come from environment variables (preferred) or local, git-ignored
  config files. See `.gitignore`.
- If you discover a committed secret, stop and flag it — do not just delete it in
  a new commit (it stays in history).

### The agents' vault — always go through it

Agents have their own Bitwarden account, `vault@ag.waveio.me` on
vault.bitwarden.com (free plan). It is separate from the owner's personal
account. It is the single place for every password, API key, and login that
an agent uses or creates.

- **Need a secret?** Look in the vault first, before asking the owner or
  digging through `.env` files.
- **Got a new one** (signed up somewhere, created an API key, generated a
  password)? Save it to the vault **right away**, in the same step. Generate
  passwords with `bw generate`, not by hand.
- **Never** paste a secret into chat, notes, Obsidian, a commit, or a log.
  Refer to it by the vault item name.
- Services that read `.env` keep doing so. When you put a key in `.env`, save
  it to the vault too: the vault is the source of truth.

How to use it from the Mac (the master password is in the macOS Keychain):

```bash
export BITWARDENCLI_APPDATA_DIR=~/.config/bw-agents   # agents' profile
export BW_SESSION="$(BW_PW="$(security find-generic-password \
  -a vault@ag.waveio.me -s bitwarden-agents -w)" bw unlock --passwordenv BW_PW --raw)"
bw sync
bw get password "<item name>"
bw lock                                                # when done
```

- **Never** use the default `bw` profile: it is the owner's personal account.
  Do not log it out either.
- The free plan has no TOTP codes and no attachments. If you need either, ask
  the owner.
- Cloud agents (Jules, Hoplite) cannot reach the Keychain, so they have no
  vault access. If they need a secret, the owner decides how to pass it.

---

## 4. Code-change etiquette

- Prefer **minimal, surgical edits** to existing files over rewrites — this repo
  historically had no diffs, so avoid large blind overwrites.
- Run the relevant build/tests after a change before committing:
  - Python modules: `pytest` in the module folder.
  - TS/JS modules: `npm test` / `tsc --noEmit` / `npm run build`.
- Follow the API contracts already agreed in `HANDOFF.md` and
  `blink-display/API_CONTRACT.md`.

### Delegate routine asynchronous work

When Jules is configured, prefer handing off routine, self-contained, and
non-urgent work that can wait for a pull request, such as translating comments
or documentation, mechanical refactors, or repetitive test updates. Give Jules
the exact scope, acceptance criteria, and validation commands. Keep work in the
current session when it needs immediate completion, interactive decisions,
local-only context, or secrets Jules cannot access.

---

## 5. Language policy

### The language of the request does not set the language of the code

An agent may be prompted in Ukrainian, English, or anything else. **That says
nothing about what language the artifacts should be in.** Do not mirror the
prompt. The rules below apply no matter how the task was phrased.

### What goes in which language

**Everything inside the repository is English. Ukrainian lives in two places
only: the owner's Obsidian vault, and locale files.**

The reason is release, not taste. This repo is meant to be published, and a
codebase commented in Ukrainian is not something you hand to strangers.

| Artifact | Language | Why |
|---|---|---|
| Identifiers, types, API fields, file names | **English** | read by every tool, linter and library in the stack |
| Commit messages, `AGENTS.md`, `CONTRIBUTING.md` | **English** | every coding tool reads them |
| **Code comments and docstrings** | **English** | the repo is releasable; keep explaining *why*, just do it in English |
| Anything a user can see | **never hardcoded** | see below |
| Owner's notes, design rationale, evaluations | **Ukrainian, in Obsidian** | that is the owner's own space, not the product |

Comments keep their character — this repo's comments explain *why* a thing is
the way it is, and that is its strongest asset. Only the language changes.

### User-visible strings are always keys, never literals

A string that reaches a human eye — a label, a button, a toast, an error, a
placeholder — **must not be written inline in any language**. It gets a key and
lives in the locale file.

The screen is the reference implementation: `Virtual Bot/static/screen/i18n.js`
holds 604 keys in `uk` and `en`, used as `t("state.head")`, with
`applyStatic()` filling `data-i18n` attributes in HTML. Copy that shape.

A new screen or panel that ships hardcoded text is **not done**, even if the
text is correct and in the right language.

### Known debt — do not add to it

The rules above are the target, not the current state. Measured 2026-09-19:

| Debt | Scale | Where it goes |
|---|---|---|
| Ukrainian comments | **~3220 lines** (91 of 101 `.py`, 63 of 64 `.tsx`) | translate to English |
| Hardcoded Ukrainian strings | **~3329 lines** | lift into locale files, stay Ukrainian |
| Dashboard has no i18n at all | all 52 `.tsx` files | needs a locale module like the screen's |

Do not extend either pattern. When you touch a file, bring the part you touched
in line — translate the comments you edit, key the strings you add. Do not open
a repo-wide migration on your own initiative; it is the owner's call when to
spend that.

The screen is proof it is doable: `static/screen/i18n.js` carries 604 keys in
`uk` and `en`, and no screen file hardcodes a label.

---

## 6. Review process (owner requirement)

Every working agent's output should be verified by a separate adversarial
reviewer agent (Fable, max effort) that hunts for and **fixes** bugs, followed by
a smoke test (start server, curl endpoints incl. a path-traversal attempt
expecting 400, verify static assets, then shut processes down).

---

## 7. Agents that build features and screen apps

This section is for an agent doing **product work** — a feature, a module, or a
screen app — usually with no human watching each step. Everything above still
applies; this adds what is specific to building rather than fixing.

### Commit as you go, not at the end

You have no human to notice that an hour of work vanished. A session can be cut
short at any moment, so work only exists once it is **committed as
`Imlokzu <lokzuhd@gmail.com>` and pushed to `origin`**. Commit and push each
part as soon as it stands on its own — a passing module, a new endpoint, a
new test file — rather than saving one large commit for the end.

If you finish a task and `git status` is not clean, the task is not finished.

### Verify it yourself — nobody else will

Before you call a feature done:

```bash
cd "Virtual Bot"
PYTHONPATH="$PWD" .venv/bin/pytest tests/ -q     # PYTHONPATH is required
```

A new feature without a test is not done. Put the test next to the existing
ones and match their style — they carry explanations of *why* the behaviour
matters, not just assertions.

Then exercise the real thing, not only the test: start the server, hit the
endpoint, look at the screen. Tests pass on code that is wired to nothing.

### Mute the Mac first — every time

Agents work at night, and the owner has been woken at 3am by a test that
spoke. Before anything that can make a sound — tests, TTS, music, video, a
browser session, starting the server — mute the output:

```bash
osascript -e "set volume output muted true"
```

Never unmute it afterwards. `tests/conftest.py` already mutes at the start of
every pytest run (opt out only with `VIRTUAL_BOT_TEST_SOUND=1`), but a manual
`curl /api/tts` or a browser test is on you.

### Screen apps (`Virtual Bot/store/packages/`)

A new app is just a new folder — no build step, no registration:

```
store/packages/<id>/
  package.json   # id, type, label, icon, tint, version, author, description, entry
  index.html     # entry point, self-contained
```

Constraints that are not negotiable, because the target is a 2.4" 320x240
display on a Raspberry Pi 3:

- animate **only** `transform` and `opacity`;
- no `filter`, `blur`, or `box-shadow` on anything that moves;
- no gradients under animation;
- the app must work offline — no CDN, no external fonts.

Read `Virtual Bot/docs/SCREEN-PLATFORM.md` before writing one.

**Every app needs its own icon** in the drawer and the store. Icons are
drawn in code in `Virtual Bot/static/screen/app-icons.js`, a gradient disc
with one bold white glyph (Deep UI, re-inked tonally for Material You),
not as image files. To add
or restyle one, follow the skill
`.agents/skills/screen-app-icons/SKILL.md`: its rules, palette, helpers,
test and visual check. A new built-in package without a design fails
`test_every_screen_and_package_has_its_own_design`.

**Every built-in app is native**: full screen, on the app kit
(`static/screen/app-kit.css` + `app-kit.js`), in whatever style the
screen wears (Material You or Deep UI, any theme or skin). To write or
restyle one, follow the skill `.agents/skills/screen-app-native/SKILL.md`.
Bump the app's version, or installed copies stay old.

### Do not widen the scope

Build what was asked. If you find a second problem on the way, finish the first
one, commit it, and report the second — do not fold an unrequested refactor into
the same change. The owner decides what gets built.

---

## 7a. Agent email — waveio.me mailboxes

Agents have their own e-mail under `ag.waveio.me`, handled by the Cloudflare
worker `agent-mail-worker` (routes `mail.waveio.me` / `send.waveio.me`,
KV `AG_MAILBOX`). Brevo sends outbound mail; the master Brevo key never leaves
the worker — agents only hold an `AGENT_TOKEN`.

**Inboxes (catch-all receives anything):**

| Address | Purpose |
|---|---|
| `lokzu@ag.waveio.me` | this assistant's default mailbox (signups, correspondence) |
| `vault@ag.waveio.me` | Bitwarden agents' vault account |
| `ceo@waveio.me` | owner's public contact — **forwards to `lokzuhd@gmail.com`** (Cloudflare rule, not the worker) |

Any other `*@ag.waveio.me` address also receives mail; mail to any other
non-agent `*@waveio.me` address is stored in KV **and** forwarded to
`lokzuhd@gmail.com` by the worker.

**How to read/send mail — MCP `agent-mail`:**

- The stdio MCP server `agent-mail-mcp` is built from this repo
  (`agent-mail-mcp/`, `go build -o bin/agent-mail-mcp .`).
- Registered in Claude Code user scope as MCP `agent-mail`:
  binary `~/bin/agent-mail-mcp`, env `AGENT_TOKEN` (same value as
  `AGENT_MAIL_API_KEY` in `Virtual Bot/.env`) and `AGENT_EMAIL=lokzu@ag.waveio.me`.
- Tools: `my_email`, `check_inbox`, `read_email`, `download_attachment`
  (lands in `~/agent-mail/attachments/`), `delete_email`, `send_email`,
  `get_verification_code` (latest OTP/link, optional `service` filter).
- Every mailbox tool accepts an optional `address` to read another
  `*@ag.waveio.me` box (e.g. `vault@ag.waveio.me`).

**Without MCP, the raw HTTP API** (same token as `Authorization: Bearer`):

```
GET  https://mail.waveio.me/api/inbox?to=<addr>&limit=20
GET  https://mail.waveio.me/api/message?to=<addr>&id=<id>   (add &peek=1 to stay unread)
GET  https://mail.waveio.me/api/latest-otp?to=<addr>
GET  https://mail.waveio.me/api/attachment?to=<addr>&id=<id>&index=0
POST https://send.waveio.me/v1/send   {"to","subject","body","sender_name"}
DELETE https://mail.waveio.me/api/message?to=<addr>&id=<id>
```

**Rules:**

- Never print the `AGENT_TOKEN` / `AGENT_MAIL_API_KEY` in chat, commits, or
  notes. It lives in `Virtual Bot/.env` and the agents' Bitwarden.
- `send_email` goes out as `noreply@waveio.me` (only Brevo-verified sender)
  with `Reply-To` set to the agent address — replies land in the agent inbox.
- Changing Cloudflare Email Routing rules (new addresses, forwards) uses the
  account Cloudflare credentials from `.env` (zone waveio.me) — the global API
  key works; the scoped API token does **not** cover email routing.
- Worker source, deploy and tests: `agent-mail-worker/` (`npm run deploy`,
  needs wrangler auth). Don't redeploy it for new mailboxes — storage is
  catch-all, only Cloudflare rules change routing.

---

## 8. Quick checklist before you finish a task

- [ ] `git status` reviewed — only intended files staged
- [ ] No secrets in the diff
- [ ] Build/tests pass for the touched module
- [ ] Commit is `Imlokzu <lokzuhd@gmail.com>`, message is English and follows the convention above
- [ ] That commit is pushed to `origin`
- [ ] `HANDOFF.md` updated if the change affects project state / known bugs
