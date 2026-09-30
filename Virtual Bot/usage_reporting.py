"""Modality and router reports over measured gateway usage and private turn metadata."""
from __future__ import annotations

import asyncio
import copy
import datetime as dt
import heapq
import time
import weakref
from collections import Counter, OrderedDict, defaultdict

import app_config as cfg
import openclaw_analytics as analytics
import openclaw_config
import usage_tracking

_MAX_SESSIONS = 32
_MAX_ROWS = 10000
_MAX_CACHE = 8
_cache = OrderedDict()
_pending = weakref.WeakKeyDictionary()
_history_limits = weakref.WeakKeyDictionary()


def _bounds(days: int) -> tuple[dt.date, dt.date, float, float]:
    end = dt.datetime.now(dt.timezone.utc).date()
    start = end - dt.timedelta(days=days - 1)
    return start, end, int(dt.datetime.combine(start, dt.time(), dt.timezone.utc).timestamp() * 1000), int(dt.datetime.combine(end + dt.timedelta(days=1), dt.time(), dt.timezone.utc).timestamp() * 1000)


def _target_agent() -> str | None:
    target = cfg.OPENCLAW_AGENT.strip().lower()
    for prefix in ('openclaw/', 'openclaw:', 'agent:'):
        if target.startswith(prefix) and target[len(prefix):] != 'default':
            return target[len(prefix):]
    agents = analytics._object(openclaw_config.get('agents', {}))
    entries = agents.get('entries')
    roster = ([{**value, 'id': name} for name, value in entries.items() if isinstance(value, dict)]
              if isinstance(entries, dict) else analytics._rows(agents.get('list')))
    defaults = [row for row in roster if row.get('default') is True]
    chosen = defaults if agents.get('ownership') != 'explicit' and len(defaults) == 1 else roster
    if len(chosen) == 1 and isinstance(chosen[0].get('id'), str):
        return chosen[0]['id'].strip().lower()
    return 'main' if 'entries' not in agents and 'list' not in agents else None


def _hashes(key: str, agent_id: str | None = None) -> set[str]:
    values = {usage_tracking.session_hash(key)}
    # OpenClaw canonicalises our stable request key with the agent namespace.
    agent_id = agent_id or _target_agent()
    if agent_id and key.startswith('agent:' + agent_id + ':'):
        values.add(usage_tracking.session_hash(key.split(':', 2)[2]))
    return values


async def _measure(days: int, user_id: str, refresh: bool = False) -> dict:
    start, end, start_ms, end_ms = _bounds(days)
    raw = await analytics._report(days, 2000, refresh)
    # Use the RPC's UTC range if the date changed during the request.
    if raw is not None and analytics._date(raw.get('startDate')) and analytics._date(raw.get('endDate')):
        start, end = analytics._date(raw['startDate']), analytics._date(raw['endDate'])
        start_ms = int(dt.datetime.combine(start, dt.time(), dt.timezone.utc).timestamp() * 1000)
        end_ms = int(dt.datetime.combine(end + dt.timedelta(days=1), dt.time(), dt.timezone.utc).timestamp() * 1000)
    turns = await asyncio.to_thread(usage_tracking.read_turns, start_ms, end_ms, user_id)
    if raw is None:
        return {'available': False, 'rows': [], 'turns': turns, 'limited': False}
    by_hash = defaultdict(list)
    agent_id = _target_agent()
    for row in analytics._rows(raw.get('sessions')):
        if not isinstance(row.get('key'), str):
            continue
        for digest in _hashes(row['key'], agent_id):
            by_hash[digest].append(row)
    grouped = defaultdict(list)
    for turn in turns:
        if turn.get('ended_at') is not None:
            grouped[turn['session_hash']].append(turn)
    eligible = [(digest, group, by_hash[digest][0]) for digest, group in grouped.items() if len(by_hash[digest]) == 1]
    eligible.sort(key=lambda item: max(turn['started_at'] for turn in item[1]), reverse=True)
    ambiguous = sum(len(by_hash[digest]) > 1 for digest in grouped)
    unmatched = sum(not by_hash[digest] for digest in grouped)
    limited = (len(eligible) > _MAX_SESSIONS or len(analytics._rows(raw.get('sessions'))) >= 2000
               or len(turns) >= usage_tracking.MAX_TURNS or bool(ambiguous) or bool(unmatched))
    loop = asyncio.get_running_loop()
    semaphore = _history_limits.setdefault(loop, asyncio.Semaphore(4))

    async def load(digest, group, session):
        async with semaphore:
            journal = await analytics.inferences(analytics._session_id(session), days, 10000)
        if not journal.get('available'):
            return [], True
        output = []
        ordered = sorted(group, key=lambda turn: (turn['started_at'], turn['request_id']))
        active = {}; endings = []; index = 0
        for row in sorted(journal['inferences'], key=lambda row: row['timestamp']):
            stamp = row['timestamp']
            while index < len(ordered) and ordered[index]['started_at'] <= stamp:
                turn = ordered[index]; index += 1
                active[turn['request_id']] = turn
                heapq.heappush(endings, (turn['ended_at'], turn['request_id']))
            while endings and endings[0][0] <= stamp:
                _, request_id = heapq.heappop(endings)
                active.pop(request_id, None)
            # Overlapping turns cannot safely attribute usage to one mode/user.
            if len(active) != 1:
                continue
            turn = next(iter(active.values()))
            output.append({**row, 'request_id': turn['request_id'], 'modality': turn['modality'],
                           'routed': turn['routed'], 'session_id': analytics._session_id(session)})
        return output, bool(journal.get('limited') or journal.get('pricing_limited'))
    values = await asyncio.gather(*(load(*item) for item in eligible[:_MAX_SESSIONS]), return_exceptions=True)
    rows = []
    for value in values:
        if isinstance(value, BaseException):
            limited = True
        else:
            rows.extend(value[0]); limited = limited or value[1]
    rows.sort(key=lambda row: row['timestamp'], reverse=True)
    limited = limited or len(rows) > _MAX_ROWS
    rows = rows[:_MAX_ROWS]
    return {'available': True, 'rows': rows, 'turns': turns, 'limited': limited,
            'start_date': str(start), 'end_date': str(end), 'ambiguous_sessions': ambiguous,
            'unmatched_sessions': unmatched,
            'history_scope': 'current_transcript', 'match_scope': 'bounded_owner_turns',
            'range_totals': raw.get('totals'),
            'range_providers': analytics._object(raw.get('aggregates')).get('byProvider')}


async def measured(days: int, user_id: str = '', refresh: bool = False) -> dict:
    """Share bounded history scans across filters, without sharing owners' rows."""
    analytics._validate_range(days, 2000, 2000)
    key = (dt.datetime.now(dt.timezone.utc).date(), days, usage_tracking._owner_hash(user_id), _target_agent())
    hit = _cache.get(key)
    if hit and not refresh and time.monotonic() - hit[0] < (5 if hit[1]['limited'] else 30):
        _cache.move_to_end(key)
        return copy.deepcopy(hit[1])
    pending = _pending.setdefault(asyncio.get_running_loop(), {})
    if key not in pending:
        async def load():
            try:
                result = await _measure(days, user_id, refresh)
                if result['available']:
                    _cache[key] = (time.monotonic(), result)
                    _cache.move_to_end(key)
                    while len(_cache) > _MAX_CACHE:
                        _cache.popitem(last=False)
                return result
            finally:
                pending.pop(key, None)
        pending[key] = asyncio.create_task(load())
    return copy.deepcopy(await asyncio.shield(pending[key]))


def aggregate(rows: list[dict]) -> dict:
    totals = analytics._costs(None)
    models = {}
    daily = {}; daily_models = {}
    for row in rows:
        identity = (row['provider'], row['model'])
        model = models.setdefault(identity, {'provider': identity[0], 'model': identity[1], 'replies': 0, 'rates_available': False, **analytics._costs(None)})
        model['replies'] += 1
        for name in ('input', 'output', 'cacheRead', 'cacheWrite', 'totalTokens'):
            value = analytics._number(row.get(name))
            for bucket in (model, totals):
                bucket[name] = bucket[name] + value if value is not None and bucket[name] is not None else None
        for name in ('input', 'output', 'cacheRead', 'cacheWrite'):
            value = analytics._number(row.get(name + 'Cost'))
            if value is None and row.get(name) == 0:
                value = 0
            for bucket in (model, totals):
                field = name + 'Cost'
                bucket[field] = bucket[field] + value if value is not None and bucket[field] is not None else None
        cost = analytics._number(row.get('cost'))
        if cost is None:
            model['missingCostEntries'] += 1; totals['missingCostEntries'] += 1
        else:
            model['totalCost'] += cost; totals['totalCost'] += cost
        date = dt.datetime.fromtimestamp(row['timestamp'] / 1000, dt.timezone.utc).date().isoformat()
        for key, target in ((date, daily), ((date, *identity), daily_models)):
            day = target.setdefault(key, {'date': date, 'tokens': 0, 'cost': 0})
            tokens = analytics._number(row.get('totalTokens'))
            day['tokens'] = day['tokens'] + tokens if tokens is not None and day['tokens'] is not None else None
            day['cost'] = day['cost'] + cost if cost is not None and day['cost'] is not None else None
            if target is daily_models: day.update(provider=identity[0], model=identity[1])
    providers = {}
    for model in models.values():
        provider = providers.setdefault(model['provider'], {'provider': model['provider'], 'replies': 0,
                                                           'auth': '', 'quota': None, **analytics._costs(None)})
        provider['replies'] += model['replies']
        for field in analytics.usage._COST_FIELDS:
            value = model[field]
            provider[field] = provider[field] + value if value is not None and provider[field] is not None else None
    for bucket in [totals, *models.values(), *providers.values()]:
        bucket['token_breakdown_complete'] = all(bucket[name] is not None for name in ('input', 'output', 'cacheRead', 'cacheWrite'))
    return {'totals': totals, 'replies': len(rows), 'models': list(models.values()),
            'providers': list(providers.values()), 'daily': list(daily.values()), 'daily_models': list(daily_models.values()),
            'errors': sum(row.get('status') == 'error' for row in rows), 'tool_calls': None}


async def snapshot(days: int, limit: int, refresh: bool, modality: str, user_id: str) -> dict:
    base = await analytics.snapshot(days, limit, refresh)
    if modality == 'all' or not base['available']:
        start, end, start_ms, end_ms = _bounds(days)
        turns = await asyncio.to_thread(usage_tracking.read_turns, start_ms, end_ms, user_id)
        return {**base, 'modality': modality, 'coverage': {'tracked_turns': len(turns), 'unclassified': True,
                'traffic_scope': 'gateway', 'history_limited': len(turns) >= usage_tracking.MAX_TURNS}}
    data = await measured(days, user_id, refresh)
    if not data['available']:
        return {**base, 'available': False, 'totals': None, 'modality': modality}
    rows = [row for row in data['rows'] if row['modality'] == modality]
    summary = aggregate(rows)
    known = {row['provider']: row for row in base['providers']}
    for provider in summary['providers']:
        prior = known.get(provider['provider'], {})
        provider.update(auth=prior.get('auth', ''), quota=prior.get('quota'))
    session_rows = []
    by_session = defaultdict(list)
    for row in rows:
        by_session[row['session_id']].append(row)
    for session in base['sessions']:
        matching = by_session[session['id']]
        if not matching: continue
        usage = aggregate(matching)
        session_rows.append({**session, 'replies': usage['replies'], 'errors': usage['errors'], 'usage': usage['totals'],
                             'models': [{'model': model['model'], 'provider': model['provider']} for model in usage['models']]})
    return {**base, **summary, 'sessions': session_rows, 'modality': modality,
            'indexing': base.get('indexing', False), 'sessions_limited': data['limited'],
            'coverage': {'tracked_turns': len(data['turns']), 'matched_inferences': len(rows),
                         'unclassified': True, 'history_limited': data['limited'], 'traffic_scope': 'tracked_owner',
                         'unmatched_sessions': data.get('unmatched_sessions', 0),
                         'ambiguous_sessions': data.get('ambiguous_sessions', 0),
                         'history_scope': 'current_transcript', 'match_scope': 'bounded_owner_turns'}}


async def journal(session_id: str, days: int, limit: int, modality: str, user_id: str) -> dict:
    data = await analytics.inferences(session_id, days, limit)
    if modality == 'all' or not data.get('available'): return data
    measured_data = await measured(days, user_id)
    if not measured_data['available']:
        return {**data, 'available': False, 'inferences': [], 'limited': True}
    def identity(row):
        return tuple(row.get(name) for name in ('timestamp', 'model', 'provider', 'status', 'input', 'output',
                                                'cacheRead', 'cacheWrite', 'totalTokens', 'cost'))
    ids = Counter(identity(row) for row in measured_data['rows']
                  if row['session_id'] == session_id and row['modality'] == modality)
    filtered = []
    for row in data['inferences']:
        key = identity(row)
        if ids[key]:
            filtered.append(row); ids[key] -= 1
    return {**data, 'inferences': filtered, 'modality': modality,
            'limited': bool(data.get('limited') or measured_data['limited'])}
