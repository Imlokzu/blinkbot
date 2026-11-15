# Blink Chat 0.4.0: web search, page reading, persistent memory

Blink Chat stays a chatbot; the new tools run inside the existing bounded
harness (six model rounds, twelve tool calls per turn) and render through the
same activity view.

- **Web search (`web_search`).** Keyless DuckDuckGo search, so it works with no
  account or API key. The result parser ports the paired host server's
  `tools/search.py` (Lite and HTML endpoint variants, attribute orders,
  redirect unwrapping). A challenge or rate-limit page surfaces as an honest
  tool error, and an empty result set is reported as "no hits", not a failure.
- **Page reading (`read_url`).** Opens an https page and hands the model plain
  text. HTTPS only, credentials in the URL rejected, private/loopback/link-local
  and IPv6 unique-local addresses unreachable at every hop, redirects followed
  by hand with per-hop re-checks (both the Ktor and OkHttp layers refuse to
  redirect on their own, so a hop can never bypass the address check), 1 MiB
  download cap, 8k-character text cap. Fetched text reaches the model wrapped
  in a nonce-delimited untrusted envelope, and the literal envelope markers are
  neutralized inside the content, so a page cannot forge tool output
  (PocketPal's injection defense).
- **Persistent memory.** The model can save (`remember`) and delete (`forget`)
  durable facts when the user explicitly asks — LibreChat's strict trigger
  rules. A background reviewer after every fifth user turn (one extra model
  call, Open WebUI's cadence and prompt discipline: durable facts only, no
  secrets or transient details, prefer replace/remove over duplicates) may add,
  revise or drop facts on its own. Every chat injects the saved facts as a
  capped system-prompt block in LibreChat's format. Facts live in a local
  `memories.json` (AtomicFile, same corruption contract as chats); dedup
  ignores case and trailing punctuation. Memory is on by default and
  independent of the tools toggle.
- **Memory screen.** Profile > Memory lists saved facts with per-item forget,
  manual add, "forget everything" behind a confirm dialog, and the memory
  toggle. Back navigation returns to Profile.

## Validation

Verified on 2026-11-15 with JDK 17:

- 90 Blink Chat JVM tests pass, including the new DuckDuckGo parser,
  URL-reader guard (loopback/private/metadata/unique-local hosts, redirect
  downgrade, redirect loops, non-text types, truncation, envelope neutralization)
  and memory store tests (dedup, cap, ops parsing from prose-wrapped reviewer
  JSON, context-block cap and muting).
- Debug APK build passes; the paired Blink app compiles against the shared
  changes (`Screen.Memory`, back navigation, shared tool labels).
- Not verified against a live endpoint in this release: the DuckDuckGo HTML
  shape is covered by fixtures ported from the host server's live-tested
  parser; live search, page fetches and memory extraction on real models
  should be exercised after install.

## Install

Package `me.waveio.blinkchat`, version `0.4.0`, version code `6`; updates
earlier versions in place.

SHA-256 of `standaloneApp-debug.apk`:
`05ba65081ab34b3253b3ff0112144ff3bd95533d12fdaa1181192e532f759c7e`
