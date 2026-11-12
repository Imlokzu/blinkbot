# Blink Chat 0.3.1: effort levels for every endpoint

0.3.0 showed effort levels only when a provider's model catalog published them.
QwenCloud and LiteLLM publish none, so the chat picker said "Supported effort
levels are unavailable for this model" for models that do reason.

Effort levels now come from the endpoint itself. Blink Chat sends each chat
model an effort value no provider accepts; validation rejects it before any
model runs (nothing is billed) and the rejection lists the allowed levels. This
works on Chat Completions (`reasoning_effort`), Responses (`reasoning.effort`)
and Anthropic Messages (`output_config.effort`), and through LiteLLM, which
passes the backend's validation error on. Endpoints that silently ignore
unknown values get `low`/`medium`/`high`.

- **Off** is a stop wherever the provider accepts `none`, so thinking can be
  turned off.
- The slider shows the provider default (`medium`, else `high`) until a level
  is picked, instead of "unavailable".
- Up to 48 models are checked during Connect; other models, and providers saved
  with 0.3.0, are checked the first time they are opened in the chat picker. A
  probe goes only to that model's own provider with its own key.
- LiteLLM's `/model/info` adds context window, output cap and image input.
- Video models are no longer offered as chat models.

## Validation

Verified on 2026-11-12:

- Live against QwenCloud: Connect took 4.2 s and found per-model levels, e.g.
  `qwen3.6-flash` off…xhigh, `glm-5.2` and `qwen3.8-max` off…max,
  `deepseek-v4-pro` low…max (no off), `glm-5.3` low/high/max. Real chats with
  effort `off` and `high` both answered. The temporary key file was removed.
- 57 JVM tests pass, including recorded QwenCloud, OpenAI and Anthropic
  rejection texts, the probe request, LiteLLM `/model/info` and `off` → `none`.
- 25 Android tests pass (the opt-in live test is skipped). A new UI test opens
  the chat picker on a saved provider without catalog levels, sees the probed
  levels with "Medium" shown, picks Off, and checks `none` is sent.
- Lint and the debug APK build pass.

Not verified live: a LiteLLM proxy (none is reachable from this machine) and
OpenAI, Anthropic or OpenRouter (no keys in the agents' vault). Their formats
are covered by fixtures.

## Install

Package `me.waveio.blinkchat`, version `0.3.1`, version code `4`. Updates 0.3.0
in place.

SHA-256 of `standaloneApp-debug.apk`:
`1f879fc2cf18f1eb578654b832731ef776989a3d09c08825cec28d09e859b4e4`
