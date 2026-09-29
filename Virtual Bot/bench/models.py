"""
Who gets benchmarked, where they are served, and what a token costs.

Prices are USD per million tokens (input, output), as published by the host
on 2026-09-29; Regolo bills in EUR and is converted at 1 EUR = 1.17 USD.
"sub" means a flat subscription (Luna through the ChatGPT plan), so one
call has no price of its own; None means the price is not published.

The `openrouter` group is the owner's light-model shortlist. None of those
models is on a host this machine has a key for; they run as soon as
OPENROUTER_API_KEY is in an .env file. Their ids are checked against the
host's /models before the run, and a missing id is reported, not guessed.
"""

from __future__ import annotations

EUR = 1.17

PROVIDERS = {
    # The bot's own key, and a trial with a DAILY token cap: on 2026-09-29 three
    # benchmark runs used it up, and the bot lost speech recognition, its image
    # model and OpenClaw's utility model until the next day. Run Regolo rows
    # sparingly (--only), never in a loop.
    "regolo": {"base": "https://api.regolo.ai/v1", "key": "REGOLO_ASR_API_KEY", "parallel": 4},
    "cloudflare": {
        "base": "https://api.cloudflare.com/client/v4/accounts/{CLOUDFLARE_ACCOUNT_ID}/ai/v1",
        # Its OpenAI-style /models does not list the @cf/ ids, so skip the check.
        "key": "CLOUDFLARE_API_TOKEN", "parallel": 4, "listing": False,
    },
    # Go refuses a request without a session id ("cannot be routed efficiently").
    "opencode-go": {"base": "https://opencode.ai/zen/go/v1", "key": "@opencode-go", "parallel": 3,
                    "session_header": "x-opencode-session"},
    "openrouter": {"base": "https://openrouter.ai/api/v1", "key": "OPENROUTER_API_KEY", "parallel": 4},
    # A pay-as-you-go key on a $0 balance: only the models ZenMux prices at $0
    # answer. Several other $0 models (Ling 3.0 Tiny, Agnes 2.5 Flash, Atria
    # Dawn) still refuse with 402 reject_no_credit until the balance is above
    # zero, as an anti-abuse rule. The owner's Free subscription plan has no
    # API access at all, so this is the only way in.
    # The free GLMs answer 429 "usage limit for the current free model" at
    # two calls in flight, so one at a time with long back-offs.
    "zenmux": {"base": "https://zenmux.ai/api/v1", "key": "ZENMUX_API_KEY", "parallel": 1,
               "retries": 6, "backoff_s": 15},
    # The bot's own path. Luna is only reachable here (a ChatGPT subscription,
    # not an API key), and the gateway adds its agent prompt and overhead, so
    # these rows measure "the model as the bot gets it", not the bare model.
    # One at a time: the gateway also serves the live bot, and two parallel
    # benchmark calls already drew "internal error" 500s from it.
    "openclaw": {"base": "http://127.0.0.1:18789/v1", "key": "OPENCLAW_TOKEN", "parallel": 1,
                 "session_header": "x-openclaw-session-key", "model_header": "x-openclaw-model",
                 "agent": "openclaw/default", "listing": False},
}

# (label, provider, model id, group, (input $, output $) or None)
MODELS = [
    # light models that are reachable today
    ("Qwen3.5 9B", "regolo", "qwen3.5-9b", "light", (0.07 * EUR, 0.35 * EUR)),
    ("GPT-OSS 20B", "regolo", "gpt-oss-20b", "light", (0.10 * EUR, 0.42 * EUR)),
    ("GPT-OSS 120B", "regolo", "gpt-oss-120b", "light", (1.00 * EUR, 4.20 * EUR)),
    ("Gemma 4 26B-A4B", "cloudflare", "@cf/google/gemma-4-26b-a4b-it", "light", (0.10, 0.30)),
    ("Qwen3 30B-A3B", "cloudflare", "@cf/qwen/qwen3-30b-a3b-fp8", "light", (0.0509, 0.335)),
    ("Granite 4 Micro", "cloudflare", "@cf/ibm-granite/granite-4.0-h-micro", "light", (0.017, 0.112)),
    ("Llama 3.2 3B", "cloudflare", "@cf/meta/llama-3.2-3b-instruct", "light", (0.0509, 0.335)),
    ("Llama 3.1 8B", "cloudflare", "@cf/meta/llama-3.1-8b-instruct-fp8", "light", (0.152, 0.287)),
    # the owner's shortlist, via OpenRouter once there is a key
    ("Qwen3.5 4B", "openrouter", "qwen/qwen3.5-4b", "shortlist", (0.04, 0.07)),
    ("Gemma 4 E4B", "openrouter", "google/gemma-4-e4b-it", "shortlist", (0.02, 0.10)),
    ("Gemma 4 E2B", "openrouter", "google/gemma-4-e2b-it", "shortlist", None),
    ("Ministral 3 3B", "openrouter", "mistralai/ministral-3b-2512", "shortlist", (0.10, 0.10)),
    ("Ministral 3 8B", "openrouter", "mistralai/ministral-8b-2512", "shortlist", (0.15, 0.15)),
    ("Ministral 3 14B", "openrouter", "mistralai/ministral-14b-2512", "shortlist", (0.20, 0.20)),
    ("Gemma 3 4B", "openrouter", "google/gemma-3-4b-it", "shortlist", (0.04, 0.08)),
    ("Gemma 3 12B", "openrouter", "google/gemma-3-12b-it", "shortlist", (0.05, 0.10)),
    ("Phi-4 mini", "openrouter", "microsoft/phi-4-mini-instruct", "shortlist", (0.075, 0.30)),
    # free on ZenMux (2026-09-29). All three think before answering: a
    # one-word reply took 100-330 completion tokens and 4-44 s. dots3 note
    # scored IQ 92, basic 100 %, p50 3.5 s. The two GLMs carry a small usage
    # quota: about a dozen calls in, every call is 429 "usage limit for the
    # current free model" for hours, so they cannot finish a run.
    ("GLM 4.7 Flash · ZenMux", "zenmux", "z-ai/glm-4.7-flash-free", "zenmux-free", (0.0, 0.0)),
    ("GLM 4.6V Flash · ZenMux", "zenmux", "z-ai/glm-4.6v-flash-free", "zenmux-free", (0.0, 0.0)),
    ("dots3 note · ZenMux", "zenmux", "dots-studio/dots3-note-prev", "zenmux-free", (0.0, 0.0)),
    # the comparison line. opencode-go (Luna, Qwen 3.8 Flash, DeepSeek, GLM)
    # answered 403 "an active OpenCode Go subscription is required" on
    # 2026-09-29, OpenCode Zen had no funds, and Cloudflare's free plan
    # excludes GLM 5.3 Flash and DeepSeek V4 Flash. So Regolo's GLM 5.2 and
    # Qwen3.8 27B stand in, DeepSeek is out, and OpenClaw serves Luna.
    # Regolo does not publish a GLM 5.2 price, so its cost stays empty.
    ("GPT-6 Luna · OpenClaw", "openclaw", "openai/gpt-6-luna", "reference", "sub"),
    ("GPT-OSS 20B · OpenClaw", "openclaw", "regolo/gpt-oss-20b", "reference", (0.10 * EUR, 0.42 * EUR)),
    ("Qwen3.8 27B", "regolo", "qwen3.8-27b", "reference", (0.50 * EUR, 2.10 * EUR)),
    ("GLM 5.2", "regolo", "glm5.2", "reference", None),
    # The same Qwen3.8 on Cloudflare, so it can be set against Qwen3 30B-A3B
    # on one host (and run while Regolo's daily cap is spent).
    ("Qwen3.8 27B · CF", "cloudflare", "@cf/qwen/qwen3.8-27b", "reference", (0.45, 3.20)),
]

# Asked for and not reachable with the keys on this machine (2026-09-29).
# Shown in every report so a gap is never silent.
UNREACHABLE = [
    "GLM 5.3 Flash, DeepSeek V4 Flash — Cloudflare: not on the Workers Free plan; "
    "opencode-go: subscription inactive (GLM 5.2 on Regolo stands in for GLM)",
    "Qwen 3.8 Flash — opencode-go only (inactive); Qwen3.8 27B on Regolo stands in",
]
