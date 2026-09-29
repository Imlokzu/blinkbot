"""
Quick-answer model benchmark: tools, facts, fact checks, everyday questions
and a smarter tier, with speed, price and "did it stay Ukrainian".

    cd "Virtual Bot"
    .venv/bin/python bench/run.py                 # every reachable model
    .venv/bin/python bench/run.py --only "Luna,9B" # labels containing these
    .venv/bin/python bench/run.py --repeat 3       # steadier timings
    .venv/bin/python bench/run.py --openclaw       # also Luna etc. via OpenClaw

Every model gets the same system prompt, tools and temperature 0, and is
called directly on its host rather than through OpenClaw: the gateway adds
its own agent prompt and tools, which would measure OpenClaw, not the model.

Output: a table on stdout, and bench/results/<time>.{jsonl,md}.

Regolo is the bot's production key on a trial with a daily token cap —
see the note in models.py before running its rows more than once a day.

OpenClaw rows are opt-in. Every case there is a new gateway session, and
each session keeps a live MCP runtime; the gateway admits at most 256, and on
2026-09-29 a run that left ~300 behind made it refuse every new chat. So a
run with --openclaw deletes the sessions it opened before it exits.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import subprocess
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

import httpx

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from cases import CASES, SYSTEM, TOOLS, clean, grade_answer, grade_call, ukrainian_ok  # noqa: E402
from cases_v2 import CASES_V2  # noqa: E402

SUITES = {"v1": CASES, "v2": CASES_V2}
from models import MODELS, PROVIDERS, UNREACHABLE  # noqa: E402

CALL_TIMEOUT_S = 60.0
# Reasoning models spend part of this on hidden thinking; too small a cap
# turns a right answer into an empty one.
MAX_TOKENS = 1500


def _load_env() -> dict[str, str]:
    """Keys from both .env files. Values are secrets: nothing here prints them."""
    env: dict[str, str] = {}
    for path in (HERE.parent / ".env", HERE.parent.parent / ".env"):
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except OSError:
            continue
        for line in lines:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.removeprefix("export ").strip()
            value = value.strip()
            if value[:1] in "'\"" and value[-1:] == value[:1]:
                value = value[1:-1]
            env.setdefault(key, value.split(" #")[0].strip())
    env.update({k: v for k, v in os.environ.items() if v})
    try:
        auth = json.loads((Path.home() / ".local/share/opencode/auth.json").read_text())
        env["@opencode-go"] = auth["opencode-go"]["key"]
    except (OSError, KeyError, ValueError):
        pass
    return env


def _parse_calls(message: dict) -> list[dict]:
    calls = []
    for call in message.get("tool_calls") or []:
        fn = call.get("function") or {}
        raw = fn.get("arguments") or "{}"
        try:
            args = raw if isinstance(raw, dict) else json.loads(raw)
            # Some hosts double-encode: the arguments arrive as a JSON string of JSON.
            if isinstance(args, str):
                args = json.loads(args)
        except ValueError:
            args = {"_unparsed": raw}
        if not isinstance(args, dict):
            args = {"_unparsed": raw}
        calls.append({"id": call.get("id") or "call_0", "name": fn.get("name", ""), "args": args,
                      "raw": call})
    return calls


class Host:
    def __init__(self, name: str, env: dict[str, str]):
        spec = PROVIDERS[name]
        self.name = name
        self.key = env.get(spec["key"], "")
        try:
            self.base = spec["base"].format(**env)
        except KeyError:
            self.base, self.key = "", ""
        self.gate = asyncio.Semaphore(spec["parallel"])
        self.session_header = spec.get("session_header", "")
        self.model_header = spec.get("model_header", "")
        self.agent = spec.get("agent", "")
        # Free tiers answer 429 for a while rather than for a moment, so a
        # host can ask for more and longer retries than the default.
        self.retries = spec.get("retries", 3)
        self.backoff_s = spec.get("backoff_s", 2)
        self.client = httpx.AsyncClient(timeout=CALL_TIMEOUT_S)
        # Session ids this run created on the host (only a gateway keeps them).
        self.opened: set[str] = set()

    async def chat(self, model: str, messages: list[dict], session: str = "") -> tuple[dict, dict, float]:
        """(message, usage, seconds on the wire). Waiting for a free slot is not counted."""
        # A gateway takes its agent in `model` and the real model in a header.
        body = {"model": self.agent or model, "messages": messages, "tools": TOOLS,
                "temperature": 0, "max_tokens": MAX_TOKENS}
        headers = {"Authorization": f"Bearer {self.key}"}
        if self.session_header:
            headers[self.session_header] = session
            self.opened.add(session)
        if self.model_header:
            headers[self.model_header] = model
        for attempt in range(self.retries):
            async with self.gate:
                started = time.perf_counter()
                response = await self.client.post(
                    f"{self.base}/chat/completions", json=body, headers=headers,
                )
                spent = time.perf_counter() - started
            if response.status_code in (429, 500, 502, 503) and attempt < self.retries - 1:
                await asyncio.sleep(self.backoff_s + attempt * 3)
                continue
            if response.status_code != 200:
                raise RuntimeError(f"HTTP {response.status_code}: {response.text[:160]}")
            data = response.json()
            return data["choices"][0]["message"], data.get("usage") or {}, spent
        raise RuntimeError("retries exhausted")

    async def listed(self) -> set[str] | None:
        try:
            response = await self.client.get(f"{self.base}/models",
                                             headers={"Authorization": f"Bearer {self.key}"})
            return {m["id"] for m in response.json().get("data", [])}
        except Exception:  # noqa: BLE001 — a host without /models is still usable
            return None


async def run_case(host: Host, model: str, case: dict) -> dict:
    messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": case["prompt"]}]
    tokens = {"in": 0, "out": 0}
    seconds = 0.0
    session = f"bench-{uuid.uuid4().hex[:12]}"
    row = {"case": case["id"], "tier": case["tier"], "cat": case["cat"],
           # Suite 1 has no weights: its basic cases count 1, the smart ones 2.
           "difficulty": case.get("difficulty", 1 if case["tier"] == "basic" else 2),
           "trap": bool(case.get("trap"))}
    try:
        message, usage, spent = await host.chat(model, messages, session)
        seconds += spent
        tokens["in"] += usage.get("prompt_tokens", 0)
        tokens["out"] += usage.get("completion_tokens", 0)
        called = _parse_calls(message)
        text = clean(message.get("content"))
        if case["kind"] == "tool":
            ok, why = grade_call(case, called)
            if ok and case.get("result") is not None:
                # Second step: hand back a fixed result and check the answer uses it.
                messages += [
                    {"role": "assistant", "content": message.get("content") or "",
                     "tool_calls": [c["raw"] for c in called]},
                    *({"role": "tool", "tool_call_id": c["id"],
                       "content": json.dumps(case["result"], ensure_ascii=False)} for c in called),
                ]
                message, usage, spent = await host.chat(model, messages, session)
                seconds += spent
                tokens["in"] += usage.get("prompt_tokens", 0)
                tokens["out"] += usage.get("completion_tokens", 0)
                text = clean(message.get("content"))
                ok, why = grade_answer({**case, "check": None}, text, [])
                why = why and f"after tool: {why}"
        else:
            ok, why = grade_answer(case, text, called)
        row.update(ok=ok, why=why, reply=text[:300],
                   tools=[{"name": c["name"], "args": c["args"]} for c in called],
                   uk=ukrainian_ok(text))
    except Exception as exc:  # noqa: BLE001 — one broken call is a data point, not a crash
        row.update(ok=False, why=f"error: {str(exc)[:160]}", reply="", tools=[], uk=None, error=True)
    row.update(seconds=round(seconds, 2), tokens=tokens)
    return row


def _delete_gateway_sessions(sessions: set[str]) -> None:
    """Remove the OpenClaw sessions a run opened, so their MCP runtimes go too."""
    if not sessions:
        return
    keys = sorted(f"agent:main:{session}" for session in sessions)
    for start in range(0, len(keys), 40):
        batch = keys[start:start + 40]
        done = subprocess.run(["openclaw", "sessions", "delete", *batch, "--yes"],
                              capture_output=True, text=True, timeout=180, check=False)
        if done.returncode != 0:
            print(f"  could not delete {len(batch)} gateway sessions: {done.stderr.strip()[:160]}")
    print(f"  deleted {len(keys)} OpenClaw sessions opened by this run")


def _pct(rows, pred=lambda r: True) -> str:
    chosen = [r for r in rows if pred(r)]
    return f"{100 * sum(r['ok'] for r in chosen) / len(chosen):.0f}" if chosen else "—"


SCORING_NOTE = """How the scores are made:
- IQ: accuracy weighted by difficulty (1 easy, 2 a few steps, 3 a tempting wrong answer), 0–100.
  ± is a 95% interval from the number of cases; two models closer than that are a tie.
- Trap: share of trap questions answered right — the "does it think or pattern-match" number.
- Fast fit: IQ × min(1, 1.5 s / p50) × Ukrainian share. One number for "can the fast tier
  take this model": full marks need the answer under 1.5 s and in Ukrainian.
- Value: IQ points per cent per 1000 questions (higher is better); empty when the price is unknown."""


def intelligence(rows: list[dict]) -> tuple[float, float]:
    """(difficulty-weighted accuracy 0–100, ± half-width of a 95% interval)."""
    weight = sum(r["difficulty"] for r in rows)
    if not weight:
        return 0.0, 0.0
    score = sum(r["difficulty"] for r in rows if r["ok"]) / weight
    # Normal approximation with the effective sample size of weighted cases.
    n_eff = weight ** 2 / sum(r["difficulty"] ** 2 for r in rows)
    return 100 * score, 100 * 1.96 * (score * (1 - score) / n_eff) ** 0.5


def summarize(label: str, price, rows: list[dict]) -> dict:
    seconds = sorted(r["seconds"] for r in rows if not r.get("error"))
    spoken = [r for r in rows if r.get("uk") is not None]
    tin = sum(r["tokens"]["in"] for r in rows)
    tout = sum(r["tokens"]["out"] for r in rows)
    # A gateway that reports no usage has no measurable cost, not a zero one.
    if price == "sub":
        cost = "sub"
    elif price is None or not (tin or tout):
        cost = "—"
    else:
        cost = f"{(tin * price[0] + tout * price[1]) / 1e6 / len(rows) * 1000:.3f}"
    iq, margin = intelligence(rows)
    uk_share = sum(r["uk"] for r in spoken) / len(spoken) if spoken else 0.0
    p50 = statistics.median(seconds) if seconds else None
    fit = iq * min(1.0, 1.5 / p50) * uk_share if p50 else 0.0
    value = "—"
    if cost not in ("sub", "—") and float(cost) > 0:
        value = f"{iq / (float(cost) * 100):.0f}"
    traps = [r for r in rows if r["trap"]]
    return {
        "model": label,
        "iq": f"{iq:.0f} ± {margin:.0f}",
        "_iq": iq,
        "trap": f"{100 * sum(r['ok'] for r in traps) / len(traps):.0f}" if traps else "—",
        "reason": _pct(rows, lambda r: r["cat"] in ("quick_reasoning", "reasoning")),
        "fit": f"{fit:.0f}",
        "value": value,
        "basic": _pct(rows, lambda r: r["tier"] == "basic"),
        "smart": _pct(rows, lambda r: r["tier"] == "smart"),
        "tools": _pct(rows, lambda r: r["cat"] in ("tool_use", "tool_chain")),
        "facts": _pct(rows, lambda r: r["cat"] == "facts"),
        "check": _pct(rows, lambda r: r["cat"] == "fact_check"),
        "daily": _pct(rows, lambda r: r["cat"] == "everyday"),
        "uk": f"{100 * sum(r['uk'] for r in spoken) / len(spoken):.0f}" if spoken else "—",
        "p50": f"{statistics.median(seconds):.1f}" if seconds else "—",
        "p90": f"{seconds[int(0.9 * (len(seconds) - 1))]:.1f}" if seconds else "—",
        "cost": cost,
        "errors": sum(bool(r.get("error")) for r in rows),
    }


COLUMNS = [("model", "Model"), ("iq", "IQ"), ("trap", "Trap %"), ("reason", "Reasoning %"),
           ("basic", "Basic %"), ("tools", "Tools %"), ("facts", "Facts %"),
           ("check", "Fact-check %"), ("daily", "Everyday %"), ("uk", "Ukrainian %"),
           ("p50", "p50 s"), ("p90", "p90 s"), ("cost", "$ / 1k"), ("fit", "Fast fit"),
           ("value", "Value"), ("errors", "Errors")]


def table(summaries: list[dict]) -> str:
    head = "| " + " | ".join(title for _, title in COLUMNS) + " |"
    rule = "|" + "|".join("---" for _ in COLUMNS) + "|"
    body = ["| " + " | ".join(str(s[key]) for key, _ in COLUMNS) + " |" for s in summaries]
    return "\n".join([head, rule, *body])


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="", help="comma-separated label fragments")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--suite", choices=sorted(SUITES), default="v1")
    parser.add_argument("--openclaw", action="store_true",
                        help="include models served through the OpenClaw gateway")
    options = parser.parse_args()
    cases = SUITES[options.suite]

    env = _load_env()
    hosts = {name: Host(name, env) for name in PROVIDERS}
    wanted = [w.strip().casefold() for w in options.only.split(",") if w.strip()]
    roster, skipped = [], []
    listings = {n: await h.listed() for n, h in hosts.items()
                if h.key and PROVIDERS[n].get("listing", True)}
    for label, provider, model, group, price in MODELS:
        if wanted and not any(w in label.casefold() for w in wanted):
            continue
        host = hosts[provider]
        if provider == "openclaw" and not options.openclaw:
            skipped.append(f"{label}: OpenClaw rows need --openclaw")
            continue
        if not host.key:
            skipped.append(f"{label}: no key for {provider} ({PROVIDERS[provider]['key']})")
            continue
        listed = listings.get(provider)
        if listed is not None and model not in listed:
            skipped.append(f"{label}: {provider} does not list {model}")
            continue
        roster.append((label, host, model, group, price))

    print(f"{len(roster)} models × {len(cases)} cases × {options.repeat}")
    for line in skipped:
        print("  skipped —", line)

    async def one_model(label, host, model, group, price):
        rows = []
        for _ in range(options.repeat):
            rows += await asyncio.gather(*(run_case(host, model, c) for c in cases))
        for row in rows:
            row.update(model=label, group=group)
        summary = summarize(label, price, rows)
        print(f"  done {label}: basic {summary['basic']}%, smart {summary['smart']}%, "
              f"p50 {summary['p50']} s, errors {summary['errors']}")
        return group, summary, rows

    try:
        results = await asyncio.gather(*(one_model(*entry) for entry in roster))
    finally:
        for host in hosts.values():
            await host.client.aclose()
        _delete_gateway_sessions(hosts["openclaw"].opened)

    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    out = HERE / "results"
    out.mkdir(exist_ok=True)
    with (out / f"{stamp}-{options.suite}.jsonl").open("w", encoding="utf-8") as file:
        for _group, _summary, rows in results:
            for row in rows:
                file.write(json.dumps(row, ensure_ascii=False) + "\n")

    order = {"light": 0, "shortlist": 1, "zenmux-free": 2, "reference": 3}
    summaries = [s for _g, s, _r in sorted(results, key=lambda r: (order.get(r[0], 9), -r[1]["_iq"]))]
    report = [f"# Quick-answer benchmark {stamp} · suite {options.suite}", "",
              f"{len(cases)} cases ({sum(c['tier'] == 'basic' for c in cases)} basic, "
              f"{sum(c['tier'] == 'smart' for c in cases)} smart), repeat {options.repeat}.", "",
              table(summaries), ""]
    if skipped or UNREACHABLE:
        report += ["Skipped:", *(f"- {s}" for s in [*skipped, *UNREACHABLE]), ""]
    report.append("## Failures")
    for _group, summary, rows in results:
        misses = [r for r in rows if not r["ok"]]
        if misses:
            report.append(f"\n**{summary['model']}**")
            report += [f"- `{r['case']}` — {r['why']} — «{r['reply'][:90]}»" for r in misses]
    report += ["", SCORING_NOTE]
    (out / f"{stamp}-{options.suite}.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print()
    print(table(summaries))
    print(f"\nreport: bench/results/{stamp}-{options.suite}.md")


if __name__ == "__main__":
    asyncio.run(main())
