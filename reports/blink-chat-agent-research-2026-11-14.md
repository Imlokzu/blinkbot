# Blink Chat client-side agent — open-source steal list (2026-11-14)

Research goal: Blink Chat (`mobile-app/standaloneApp`, `me.waveio.blinkchat`, KMP + Compose) is
today a thin client — tool calls, memory, and web search all execute on the Python host. We want
an in-app agent harness (mini Claude-Code-style loop) with memory, web search, tool calls, and
MCP. This report maps which open-source apps already do it, what exactly to copy, and what the
licenses allow.

## 0. What Blink Chat has today

- Client renders tool activity (`ToolLabels.kt` maps ~24 host tool names), reply phases, and
  expandable reasoning blocks — but every tool executes host-side (`Virtual Bot/tools/registry.py`,
  ~20 modules) over `execute_tool(name, args)`.
- Host search is a provider cascade: Exa → DuckDuckGo HTML → Bing scrape → SearXNG
  (`Virtual Bot/tools/search.py`), merged results.
- Host memory is file-based "brain notes" (`Virtual Bot/memory.py`) with `memory_search` tool.
- The app has no local persistence layer for memories and no client-side loop.

Consequence: standalone mode (any OpenAI/Anthropic-compatible endpoint, no host) has zero agent
capability. Everything below is about moving the harness into `shared/src/commonMain`.

---

## 1. License matrix — what we may copy

| Project | License | Verdict for Blink |
|---|---|---|
| PocketPal AI | MIT | **Copy code freely** |
| AnythingLLM | MIT | **Copy code freely** |
| LibreChat | MIT | **Copy code freely** |
| Jan | Apache-2.0 (+ attribution request in docs) | **Copy code, keep attribution** |
| Knotwork | Apache-2.0 | **Copy code freely** |
| LibreChat-Mobile (Switchboard) | Apache-2.0 (NOTICE covers LibreChat MIT code) | **Copy code freely** |
| ChatMCP | Apache-2.0 | Copy code freely |
| GPT Mobile | GPL-3.0 | **Patterns only — never copy code** (copyleft) |
| Cherry Studio | AGPL-3.0 (paid commercial exemption offered) | **Patterns only** |
| LobeChat | LobeHub Community License (Apache + derivative-works restriction) | **Patterns only** |
| Open WebUI | Custom "Open WebUI License" (BSD + branding clause, >50 users) | **Patterns only** |
| Joey MCP client | FSL-1.1-MIT (converts to MIT after 2 years) | Patterns for now |

---

## 2. The steal list, prioritized for Blink Chat

### Phase 1 — Agent harness core (the loop)

The best reference is **GPT Mobile** (Kotlin/Compose, same stack, GPL — pattern only):

- `Flow<AgentRunEvent>`-driven loop in `data/agent/AgentRunner.kt`; tool calls arrive as
  `ProviderEvent.ToolCall` from native tool calling (no JSON-tag parsing).
- Hard run limits (`AgentRunLimits`): run timeout 15 min, max 8 rounds, max 24 tool calls,
  max 4 concurrent tools via `Semaphore`, per-tool timeout 60 s, tool output capped at 64 KiB.
- **Graceful fallback**: if the endpoint rejects native tool definitions, fall back once to a
  chat-only run instead of failing.
- **Cancellation semantics**: cancel keeps partial text + tool trace; a process restart marks
  unfinished runs interrupted and **never replays their tools**; runs are Room-persisted
  (`AgentRunDao`, `ToolEventRecorder`) and survive via a foreground service.

**PocketPal AI** (MIT — copy code, `src/services/agent/AgentRunner.ts`):

- Pure async-generator event protocol (`run_started`, `step_started`, `token`,
  `tool_call_started/finished`, `run_finished/failed`) with zero UI imports — translates 1:1 to
  Kotlin `Flow`.
- `DEFAULT_MAX_TURNS = 5` with a `forceFinal` no-tools closing turn, prefaced by the nudge:
  *"(Tool budget exhausted. Answer now using only the information gathered above…)"*.
- `MAX_IN_FLIGHT_TOOL_CALLS = 4` via pre-built promise slots; results fed back as `role:'tool'`.
- **KV-cache-aware message building**: the final assistant turn uses the cleaned preamble, not
  raw streamed text, to keep prompt-prefix caching working.
- **`untrustedContent.ts` — prompt-injection defense, copy verbatim**: fetched web content is
  wrapped in nonce-delimited `BEGIN/END UNTRUSTED WEB CONTENT` markers, with the literal marker
  string neutralized inside the content.
- Deterministic tool-call-ID synthesis when a provider returns `id=null`.

**AnythingLLM** (MIT — copy the plugin contract, `server/utils/agents/aibitat/`):

- A skill = one module exposing `setup(aibitat)` that registers `{name, description, examples,
  parameters, handler}` — a clean shape for our `ToolRegistry`.
- `ToolReranker` utility to cut injected tool tokens when the tool set grows.

### Phase 2 — Memory (on-device, no embedding server)

Recommended: **Open WebUI's fact-list model + mem0's single-pass extraction, on SQLite FTS5,
with ChatGPT's UX.** (All pattern-level; Open WebUI code is license-restricted.)

- **Model**: one-sentence self-contained facts, first person, two kinds — `user` (durable facts,
  preferences, instructions) and `context`. Optional hierarchical paths (Open WebUI) are nice but
  can wait.
- **Extraction**: background reviewer after a reply, throttled to every N user turns (Open WebUI
  default 10). One LLM call, JSON-only output (`{"operations":[...]}`), prompt discipline from
  Open WebUI's reviewer: save durable facts/preferences/instructions; *"Do not save one-off
  activity, meals, temporary mood…"*; no secrets/credentials/transient steps/unsupported guesses;
  prefer replace/remove over duplicate add. mem0's 2026 pivot proves **single-pass ADD-only with
  dedup beats the two-phase ADD/UPDATE/DELETE pipeline** (LoCoMo 71.4 → 92.5) — skip the second
  LLM call; resolve contradictions at retrieval time (latest `updated_at` wins) or by user edit.
- **Retrieval**: FTS5 keyword match (no embeddings needed on device; mem0 itself treats keyword
  as a first-class signal). Query with recent user turns, take top ~10 + newest `user` facts.
- **Injection**: `<memory_context>...</memory_context>` block appended to the system prompt /
  last user message, hard-capped ~2000 chars, bullets `- item` (Open WebUI shape).
- **Schema** (SQLDelight supports FTS5; Room KMP does not yet expose it as cleanly):

```sql
CREATE TABLE memory (
  id INTEGER PRIMARY KEY,
  content TEXT NOT NULL,              -- one self-contained fact, first person
  kind TEXT NOT NULL DEFAULT 'user',  -- 'user' | 'context'
  source_chat_id TEXT, created_at INTEGER, updated_at INTEGER,
  last_used_at INTEGER, hit_count INTEGER DEFAULT 0, muted INTEGER DEFAULT 0
);
CREATE VIRTUAL TABLE memory_fts USING fts5(content, content='memory');
```

- **Explicit saves**: also expose LibreChat's `set_memory` / `delete_memory` tools (MIT —
  `packages/api/src/agents/memory.ts`) with their strict trigger rules: only on explicit
  "remember that…", claim a save only after the tool confirms. This hybrid (background extraction
  + explicit tool) is exactly what Open WebUI ships now.
- **UX**: ChatGPT-style Memory screen — list, add, edit, delete, mute, plus a small
  "memories used in this reply" disclosure under assistant messages (Open WebUI renders recalled
  memories as a hidden tool result; we can surface them as a chip).
- Later, if we want file-style curated memory: Jan's design is the best documented (Apache-2.0,
  `docs/agent/memory.mdx`, `tauri-plugin-agent-tools/src/memory.rs`): markdown files + one
  `memory` tool (view/search/write/edit/delete/move), auto-generated ~1,500-token `MEMORY.md`
  index, **session-start snapshot** so mid-session edits never invalidate the cached prompt
  prefix (changes arrive as a `Memory changes this session: + ~ -` block), background "tidy"
  subagent on a cheap model with a 30-change cap and a lost-edit guard.

### Phase 3 — Built-in tools (client-executed)

- **web_search**:
  - PocketPal `WebSearchEngine.ts` (MIT): result count from settings "so the model can't inflate
    it", per-query in-flight dedup, search budget + cache.
  - GPT Mobile (pattern): provider pluggability — Firecrawl / Perplexity / Exa adapters.
  - Port our own host cascade (`tools/search.py`: Exa → DDG → Bing → SearXNG, merge + rate-limit
    detection) into a Ktor-based KMP provider set — that code is ours already.
- **read_url / web_fetch**: GPT Mobile's hardening spec (pattern): HTTP(S) only, private /
  loopback / metadata IPs blocked, max 5 redirects, 1 MiB download cap, 64 KiB returned to the
  model. PocketPal's `readUrlAllowlist.ts` adds an allowlist. Jan returns bounded output
  (40k chars) and prefixes tool errors with `ERROR:` so the model distinguishes "lookup failed"
  from "no results" — cheap and worth copying.
- **Local mini-tools**: PocketPal talents (`calculate`, `datetime`, `render_html`); Knotwork's
  built-ins (Wikipedia search, workspace file executors, `HttpRequestExecutor` with host
  allowlist policy) — Apache-2.0, direct port territory.
- **Future-proof naming**: Jan ships built-ins compiled into the core, provider-neutral
  (`web_search`, never `exa_search`) with a pluggable backend — follow that.

### Phase 4 — MCP client

- **Transport reality on mobile**: everyone converged on HTTP transports only — no stdio (it
  needs spawning local processes; ChatMCP's stdio client effectively doesn't work on Android).
- **GPT Mobile is the reference** (pattern only, GPL): official **MCP Kotlin SDK**
  (`io.modelcontextprotocol.kotlin.sdk`) with `StreamableHttpClientTransport` over a shared Ktor
  client; auth: public, bearer, or **OAuth 2.1 + PKCE**; session setup serialized by a mutex;
  cursor-paginated tool listing with hard caps (`MAX_TOOL_PAGES`, `MAX_DISCOVERED_TOOLS`) and
  repeated-cursor detection. Files: `data/agent/tool/McpClientManager.kt`, `McpOAuthClient.kt`,
  `McpToolMapper.kt`.
- **Cherry Studio patterns** (AGPL — patterns only): `McpCatalogService.listTools` is
  **cache-only** so a dead/slow MCP server can never block chat startup; refreshers run in the
  background. Wire naming `mcp__<server>__<tool>_<digest>`.
- **Knotwork** (Apache-2.0, direct port): `McpConnectionPool.kt` — single owner of live
  connections, one Mutex per server URL, lock never held during a call (its comment documents the
  exact dual-pool bug class it prevents); per-hop redirect rule so a server can't redirect a
  prompt over plain HTTP; `Retry-After` capture.
- **OAuth UX**: LibreChat-Mobile launches MCP OAuth through the OS browser/custom tab
  (`McpOAuthLauncher.kt`, Apache-2.0); Joey shows an inline re-auth card in chat when a tool call
  fails on expired auth — nicer than a settings dead-end.
- **Scale-ups** (when we have many tools/servers): Cherry Studio's **defer exposition** — when
  the inline tool set is large, expose three meta-tools (`tool_search`, `tool_inspect`,
  `tool_invoke`) with safety gates, and never defer approval-gated tools; Jan's **keyword MCP
  orchestrator** — a zero-LLM intent classifier scores connected servers (+4 exact capability,
  +2 partial, +1 description token, route top 5) when >5 servers are connected.
- **Malformed tool args**: Cherry Studio does a one-shot LLM repair pass
  (`experimental_repairToolCall`) before failing the call.

### Phase 5 — Tool-call UI on a small screen

- **Collapsible trace blocks** (GPT Mobile `ToolTraceBlock.kt` — pattern): expand/collapse card
  per tool event with status, persisted to Room; `AgentRunStatusBlock` for run-level state.
  Blink Chat already renders tool labels + phases; this extends to persisted traces.
- **LibreChat-Mobile** (Apache-2.0 — copy): the richest KMP tool UI found —
  `StreamingToolCallCard`, `ToolApprovalPanel`, per-tool cards (`FileToolCallCard`,
  `McpSignInToolCallCard` — sign-in offered inline in the tool card), `ToolCallVerdict`,
  and **tool calls persisted as first-class message parts** (`ToolCallPartEntity` + DAO) —
  queryable, replayable history.
- **Four-stage tool UI** (LobeChat pattern): each tool declares a `Placeholder` (streaming
  skeleton) → `Inspector` (live per-step status) → `Render` (final inline result) → `Portal`
  (full side panel). Scales well and reads great on phones.
- **Approvals** (Knotwork, Apache-2.0): `ToolInvocationGate` + `ToolRisk` + per-tool
  `ApprovalPolicy`; destructive calls always pause, including background runs; approval via
  notification actions. Cherry Studio's state machine adds: approval-gated tools never deferred,
  dead cards auto-retired to `output-error` on disconnect.
- **Citations**: AnythingLLM's per-source-type citation chips (file/link/github/gmail icons)
  + mobile citation modal (MIT — copy); host search results already carry URLs.

---

## 3. Suggested target structure in Blink Chat

**Scope (owner decision, 2026-11-14): Blink Chat stays a chatbot.** The harness is invisible
plumbing, not a product surface — no subagents, no plan mode, no approval-heavy UX. The user
chats; the model transparently calls web search, small built-in tools, and memory; results render
inline in the conversation like any other reply part. MCP is a later, optional add-on. Of the UI
patterns in section 2, only the lightweight ones apply: collapsible tool trace, used-memory
disclosure, citation chips.

```
shared/src/commonMain/kotlin/me/waveio/claudebot/agent/
  AgentRunner.kt          // Flow<AgentRunEvent> loop, rounds/calls/concurrency limits
  AgentRunLimits.kt
  ToolRegistry.kt         // name -> {schema, handler, risk, approval}
  events.kt               // AgentRunEvent model (token, step, tool_call_started/finished, …)
  tools/
    WebSearchTool.kt      // cascade port from tools/search.py + provider adapters
    ReadUrlTool.kt        // SSRF-hardened fetch + untrusted-content nonce wrapper
    CalculateTool.kt, DateTimeTool.kt, …
  mcp/
    McpClientManager.kt   // MCP Kotlin SDK, Streamable HTTP, OAuth 2.1/PKCE
    McpConnectionPool.kt  // single-owner pools, mutex per server, cache-only catalog
  memory/
    MemoryStore.kt        // SQLite + FTS5 (SQLDelight)
    MemoryExtractor.kt    // background reviewer, single-pass ADD-only, JSON ops
    MemoryInjector.kt     // <memory_context> build, char caps, FTS recall
```

UI additions live next to existing chat components: `ToolTraceBlock`, `ToolApprovalPanel`,
memory manager screen (Settings → Personalization → Memory).

Phasing that keeps every step shippable: **1** loop + web_search/read_url → **2** memory →
**3** MCP → **4** approvals + rich UI. Phases 1–2 alone make standalone Blink Chat feel like a
real agent chat; MCP is the differentiator almost no mobile chat app has done well.

---

## 4. Key sources

- Mobile: [gpt_mobile](https://github.com/taewan-p/gpt_mobile) ·
  [agent-tools.md](https://raw.githubusercontent.com/taewan-p/gpt_mobile/main/docs/agent-tools.md) ·
  [knotwork](https://github.com/alexeyw/knotwork) ·
  [pocketpal-ai](https://github.com/a-ghorbani/pocketpal-ai) ·
  [Librechat-Mobile](https://github.com/garfiec/Librechat-Mobile) ·
  [chatmcp](https://github.com/daodao97/chatmcp) ·
  [joey-mcp-client](https://github.com/benkaiser/joey-mcp-client) ·
  [enchanted](https://github.com/gluonfield/enchanted) ·
  [ChatterUI #535](https://github.com/Vali-98/ChatterUI/issues/535) ·
  [flutter_mcp](https://pub.dev/packages/flutter_mcp)
- Desktop/web: [LobeChat](https://github.com/lobehub/lobe-chat) ·
  [Cherry Studio](https://github.com/CherryHQ/cherry-studio) ·
  [Cherry tool-registry doc](https://github.com/CherryHQ/cherry-studio/blob/main/docs/dev/references/ai/tool-registry.md) ·
  [Cherry tool-approval doc](https://github.com/CherryHQ/cherry-studio/blob/main/docs/dev/references/ai/tool-approval.md) ·
  [Jan](https://github.com/janhq/jan) ·
  [Jan memory docs](https://github.com/janhq/jan/blob/main/docs/src/pages/docs/agent/memory.mdx) ·
  [AnythingLLM](https://github.com/Mintplex-Labs/anything-llm) ·
  [LibreChat](https://github.com/LibreChat-AI/LibreChat) ·
  [LibreChat agents docs](https://www.librechat.ai/docs/features/agents) ·
  [Open WebUI](https://github.com/open-webui/open-webui) ·
  [Open WebUI memory docs](https://docs.openwebui.com/features/chat-conversations/memory)
- Memory research: [mem0](https://github.com/mem0ai/mem0) ·
  [mem0 paper](https://arxiv.org/abs/2504.19413) ·
  [Letta memory docs](https://docs.letta.com/guides/agents/memory) ·
  [Graphiti/Zep overview](https://help.getzep.com/graphiti/graphiti/overview) ·
  [Claude Code memory](https://code.claude.com/docs/en/memory) ·
  [Claude API memory tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool) ·
  [ChatGPT memory FAQ](https://help.openai.com/en/articles/8590148-memory-in-chatgpt-faq)
- Local host reference: `Virtual Bot/tools/registry.py`, `Virtual Bot/tools/search.py`,
  `Virtual Bot/memory.py`, `mobile-app/shared/.../ui/ToolLabels.kt`
