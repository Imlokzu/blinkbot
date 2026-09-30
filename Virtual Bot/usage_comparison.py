"""Price counterfactuals for the same measured LLM tokens; never replay user prompts."""
from __future__ import annotations

import asyncio
import datetime as dt

import app_config as cfg
import openclaw_analytics as analytics
import openclaw_config
import usage_reporting

PRICING = {'checked_at': '2026-09-30', 'opus_source': 'https://platform.claude.com/docs/en/about-claude/pricing',
           'router_source': 'https://docs.typesafe.ai/models'}
OPUS = {
    'claude-opus-5-5': {'id': 'claude-opus-5-5', 'label': 'Claude Opus 5.5', 'input': 4., 'output': 20., 'cacheRead': .2, 'cacheWrite': 5., 'source': PRICING['opus_source']},
    'claude-opus-4-8': {'id': 'claude-opus-4-8', 'label': 'Claude Opus 4.8', 'input': 5., 'output': 25., 'cacheRead': .5, 'cacheWrite': 6.25, 'source': PRICING['opus_source']},
}
_FIELDS = ('input', 'output', 'cacheRead', 'cacheWrite')


def prices(models: list[dict]) -> list[dict]:
    table = dict(OPUS)
    for row in models:
        if row.get('missingCostEntries'): continue
        # A measured component cost can legitimately be zero. Unknown
        # components stay null and cannot establish an effective rate.
        values = {name: row[name + 'Cost'] / row[name] * 1_000_000
                  if analytics._number(row.get(name)) and analytics._number(row.get(name + 'Cost')) is not None else None
                  for name in _FIELDS}
        if not any(value is not None for value in values.values()): continue
        model_id = row['provider'] + '/' + row['model']
        table[model_id] = {'id': model_id, 'label': row['model'], **values, 'source': 'observed'}
    providers = openclaw_config.get('models.providers', {})
    for provider, config in providers.items() if isinstance(providers, dict) else []:
        for model in analytics._rows(analytics._object(config).get('models')):
            cost = analytics._object(model.get('cost'))
            if not cost or not isinstance(model.get('id'), str): continue
            model_id = provider + '/' + model['id']
            table[model_id] = {'id': model_id, 'label': model.get('name') if isinstance(model.get('name'), str) else model['id'],
                               **{name: analytics._number(cost.get(name)) for name in _FIELDS}, 'source': 'openclaw'}
    return list(table.values())


def reprice(tokens: dict | None, rates: dict) -> float | None:
    if not tokens or tokens.get('token_breakdown_complete') is False or not analytics._number(tokens.get('totalTokens')): return None
    if not any(analytics._number(tokens.get(name)) for name in _FIELDS): return None
    total = 0.
    for name in _FIELDS:
        value = analytics._number(tokens.get(name))
        rate = analytics._number(rates.get(name))
        if value is None or value and rate is None: return None
        total += (value or 0) * (rate or 0) / 1_000_000
    return analytics._number(total)


def compare(tokens: dict | None, observed: float | None, router: float | None, baseline: dict, opus: dict) -> dict:
    observed = analytics._number(observed); router = analytics._number(router)
    actual = analytics._number(observed + router) if observed is not None and router is not None else None
    fixed = reprice(tokens, baseline); opus_cost = reprice(tokens, opus)
    def saving(other): return other - actual if other is not None and actual is not None else None
    gain = saving(opus_cost)
    return {'observed_cost': observed, 'router_cost': router, 'actual_cost': actual,
            'without_router_cost': fixed, 'opus_cost': opus_cost,
            'savings_vs_baseline': saving(fixed), 'savings_vs_opus': gain,
            'savings_percent': gain / opus_cost * 100 if gain is not None and opus_cost else None}


async def snapshot(days: int, modality: str, provider: str, baseline_model: str, opus_model: str, population: str, user_id: str) -> dict:
    base = await analytics.snapshot(days, 2000)
    tracked = await usage_reporting.measured(days, user_id)
    rows = [row for row in tracked.get('rows', []) if (modality == 'all' or row['modality'] == modality) and (not provider or row['provider'] == provider)]
    routed_rows = [row for row in rows if row['routed']]
    selected = routed_rows if population == 'routed' else rows
    using_raw = modality == 'all' and population == 'observed'
    provider_row = next((row for row in base.get('providers', []) if row['provider'] == provider), None)
    summary = usage_reporting.aggregate(selected)
    totals = (provider_row if provider else base.get('totals')) if using_raw else summary['totals']
    replies = (provider_row['replies'] if provider_row else 0) if using_raw and provider else base.get('replies', 0) if using_raw else len(selected)
    options = prices(base.get('models', []))
    table = {row['id']: row for row in options}
    if baseline_model and baseline_model not in table: raise ValueError('invalid_baseline')
    default = openclaw_config.get('agents.defaults.model.primary', cfg.JEV_SMART_MODEL)
    baseline_model = baseline_model or (default if isinstance(default, str) and default in table else cfg.JEV_SMART_MODEL)
    if baseline_model not in table:
        baseline_model = next((model['id'] for model in options if model['source'] != PRICING['opus_source']), opus_model)
    if baseline_model not in table or opus_model not in OPUS: raise ValueError('invalid_baseline')
    matched_ids = {row['request_id'] for row in selected}
    eligible_turns = [turn for turn in tracked['turns']
                      if (turn['routed'] or turn.get('router_calls', 0))
                      and (modality == 'all' or turn['modality'] == modality)]
    routed_turns = [turn for turn in eligible_turns if turn['request_id'] in matched_ids]
    unmatched_turns = [turn for turn in eligible_turns if turn['request_id'] not in matched_ids]
    # A provider filter may retain only part of a multi-provider tool/fallback
    # turn. Charging its whole routing decision to each provider is unsound.
    other_provider_ids = {row['request_id'] for row in tracked.get('rows', [])
                          if provider and row['provider'] != provider}
    partial_ids = matched_ids & other_provider_ids
    # Classifier costs are included only when every selected request's usage
    # was matched. Partial assignment cannot charge a whole decision to half a turn.
    unknown_router = sum(turn['routing_cost'] is None for turn in eligible_turns)
    overhead = None if unknown_router or partial_ids else sum(turn['routing_cost'] for turn in eligible_turns)
    observed = analytics._number(totals.get('totalCost')) if totals and totals.get('missingCostEntries') == 0 and totals.get('totalTokens') else None
    if not using_raw and unmatched_turns:
        # Unmatched failed/archived turns cannot disappear from net savings.
        # Their known router subtotal is still shown, but LLM cost is unknown.
        observed = None
    costs = compare(totals, observed, overhead, table[baseline_model], OPUS[opus_model])
    if using_raw:
        # This population predates the ledger and covers the whole Gateway.
        # It is a same-token LLM price comparison, never proof of router gains.
        costs = compare(totals, observed, 0, table[baseline_model], OPUS[opus_model])
        costs['router_cost'] = overhead if replies == len(rows) else None
    # Daily scenario prices require daily token categories, which the gateway
    # aggregate does not supply. Individual tracked responses do supply them.
    days_rows = {}
    for row in selected if not using_raw else []:
        day = dt.datetime.fromtimestamp(row['timestamp'] / 1000, dt.timezone.utc).date().isoformat()
        days_rows.setdefault(day, []).append(row)
    daily = []
    for day, group in sorted(days_rows.items()):
        daily_totals = usage_reporting.aggregate(group)['totals']
        recorded = None if daily_totals['missingCostEntries'] else daily_totals['totalCost']
        day_turns = [turn for turn in eligible_turns
                     if dt.datetime.fromtimestamp(turn['started_at'] / 1000, dt.timezone.utc).date().isoformat() == day]
        day_router = None if any(turn['routing_cost'] is None for turn in day_turns) or partial_ids else sum(turn['routing_cost'] for turn in day_turns)
        if any(turn['request_id'] not in matched_ids for turn in day_turns): recorded = None
        daily_costs = compare(daily_totals, recorded, day_router, table[baseline_model], OPUS[opus_model])
        daily.append({'date': day, 'actual': daily_costs['actual_cost'], 'baseline': daily_costs['without_router_cost'], 'opus': daily_costs['opus_cost']})
    return {'available': base['available'] and tracked['available'], 'days': days, 'modality': modality, 'population': population,
            'start_date': base.get('start_date'), 'end_date': base.get('end_date'), 'totals': totals,
            'replies': replies, 'baseline_model': baseline_model, 'opus_model': opus_model, 'baselines': options,
            **costs, 'daily': daily, 'coverage': {'tracked_turns': len(tracked['turns']), 'matched_inferences': len(rows),
            'router_turns': len(routed_turns), 'unknown_router_costs': unknown_router,
            'unmatched_router_turns': len(unmatched_turns), 'partial_router_turns': len(partial_ids),
            'unmatched_sessions': tracked.get('unmatched_sessions', 0),
            'ambiguous_sessions': tracked.get('ambiguous_sessions', 0),
            'unclassified_inferences': max(0, replies - len(rows)) if using_raw else 0, 'history_limited': tracked['limited']},
            'pricing': PRICING, 'assumption': 'same_recorded_tokens', 'indexing': base.get('indexing', False),
            'actual_cost_scope': 'gateway_llm_only' if using_raw else 'tracked_owner_llm_and_router',
            'history_scope': 'current_transcript', 'match_scope': 'bounded_owner_turns',
            'cache_write_assumption': '5_minutes', 'daily_scope': 'unavailable_global_breakdown' if using_raw else 'matched_turns'}
