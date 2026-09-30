"""Regressions for unknown usage, partial routing, and bounded private history."""

import asyncio
import datetime as dt
from unittest.mock import AsyncMock, patch

import pytest

import usage_comparison as comparison
import usage_reporting as reporting
import usage_tracking as tracking


STAMP = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
ROW = {'provider': 'test', 'model': 'model', 'timestamp': STAMP, 'status': 'ok',
       'input': 100, 'output': 20, 'cacheRead': 0, 'cacheWrite': 0, 'totalTokens': 120,
       'cost': .1, 'modality': 'voice', 'routed': True, 'request_id': 'one', 'session_id': 'session'}
TURN = {'request_id': 'one', 'routed': True, 'modality': 'voice', 'routing_cost': .01,
        'router_calls': 1, 'started_at': STAMP - 10, 'ended_at': STAMP + 10,
        'session_hash': tracking.session_hash('virtual-bot-v2:test'), 'mode': 'openclaw'}


@pytest.fixture(autouse=True)
def fresh_measurements():
    reporting._cache.clear()
    yield
    reporting._cache.clear()


def test_missing_token_partition_never_becomes_a_free_counterfactual():
    # A known total does not tell us which category each token belongs to.
    summary = reporting.aggregate([{**ROW, 'input': None, 'output': None}])
    assert summary['totals']['totalTokens'] == 120
    assert summary['totals']['input'] is None
    assert summary['totals']['token_breakdown_complete'] is False
    assert comparison.reprice(summary['totals'], comparison.OPUS['claude-opus-5-5']) is None


def test_missing_cost_and_component_rates_are_not_fabricated_as_zero():
    summary = reporting.aggregate([{**ROW, 'cost': None}])
    assert summary['totals']['missingCostEntries'] == 1
    assert summary['daily'][0]['cost'] is None
    assert summary['models'][0]['inputCost'] is None
    with patch.object(comparison.openclaw_config, 'get', return_value={}):
        assert all(row['source'] != 'observed' for row in comparison.prices(summary['models']))


@pytest.mark.parametrize('input_cost', [0, None])
def test_explicit_zero_component_cost_is_a_valid_rate_but_null_is_unknown(input_cost):
    # A genuinely free measured component must remain selectable. A missing
    # price for the same nonzero token count cannot become a free component.
    model = {**ROW, 'inputCost': input_cost, 'outputCost': 0,
             'cacheReadCost': None, 'cacheWriteCost': None, 'missingCostEntries': 0}
    with patch.object(comparison.openclaw_config, 'get', return_value={}):
        option = next(row for row in comparison.prices([model]) if row['id'] == 'test/model')
    assert option['input'] == input_cost
    if input_cost is None:
        assert comparison.reprice(ROW, option) is None
    else:
        assert comparison.reprice(ROW, option) == 0


def test_empty_voice_report_does_not_retain_global_errors_or_tool_counts():
    base = {'available': True, 'providers': [], 'sessions': [], 'errors': 17, 'tool_calls': 42,
            'totals': {}, 'models': [], 'daily': [], 'daily_models': []}
    measured = {'available': True, 'rows': [], 'turns': [], 'limited': False}
    with patch.object(reporting.analytics, 'snapshot', AsyncMock(return_value=base)), \
         patch.object(reporting, 'measured', AsyncMock(return_value=measured)):
        result = asyncio.run(reporting.snapshot(7, 100, False, 'voice', 'owner'))
    assert result['replies'] == 0
    assert result['errors'] == 0
    assert result['tool_calls'] is None


@pytest.mark.parametrize('partial', [False, True])
def test_router_costs_include_daily_overhead_and_reject_partial_provider_turns(partial):
    rows = [ROW, {**ROW, 'provider': 'other'}] if partial else [ROW]
    base = {'available': True, 'models': [], 'providers': [], 'totals': {}, 'replies': len(rows)}
    tracked = {'available': True, 'turns': [TURN], 'rows': rows, 'limited': False}
    with patch.object(comparison.analytics, 'snapshot', AsyncMock(return_value=base)), \
         patch.object(reporting, 'measured', AsyncMock(return_value=tracked)), \
         patch.object(comparison.openclaw_config, 'get', return_value={}):
        result = asyncio.run(comparison.snapshot(7, 'voice', 'test' if partial else '', '',
                                                 'claude-opus-5-5', 'routed', 'owner'))
    if partial:
        assert result['actual_cost'] is None
        assert result['savings_vs_opus'] is None
        assert result['coverage']['partial_router_turns'] == 1
    else:
        assert result['actual_cost'] == pytest.approx(.11)
        assert result['daily'][0]['actual'] == pytest.approx(.11)


def test_unmatched_failed_router_attempt_cannot_disappear_from_savings():
    tracked = {'available': True, 'turns': [TURN, {**TURN, 'request_id': 'failed', 'routing_cost': None}],
               'rows': [ROW], 'limited': True}
    with patch.object(comparison.analytics, 'snapshot', AsyncMock(return_value={'available': True, 'models': []})), \
         patch.object(reporting, 'measured', AsyncMock(return_value=tracked)), \
         patch.object(comparison.openclaw_config, 'get', return_value={}):
        result = asyncio.run(comparison.snapshot(7, 'voice', '', '', 'claude-opus-5-5', 'routed', 'owner'))
    assert result['router_cost'] is None
    assert result['actual_cost'] is None
    assert result['savings_vs_opus'] is None
    assert result['coverage']['unmatched_router_turns'] == 1
    assert result['coverage']['unknown_router_costs'] == 1


def test_unmatched_gateway_groups_are_disclosed_as_limited_history():
    with patch.object(tracking, 'read_turns', return_value=[TURN]), \
         patch.object(reporting.analytics, '_report', AsyncMock(return_value={'sessions': []})):
        result = asyncio.run(reporting.measured(7, 'owner'))
    assert result['rows'] == []
    assert result['limited'] is True
    assert result['unmatched_sessions'] == 1


def test_history_cache_coalesces_filters_and_keeps_owners_separate():
    async def run():
        value = {'available': True, 'limited': False, 'rows': [], 'turns': []}
        with patch.object(reporting, '_measure', AsyncMock(return_value=value)) as load:
            results = await asyncio.gather(reporting.measured(7, 'a'), reporting.measured(7, 'a'))
            assert load.await_count == 1
            results[0]['rows'].append({'private': True})
            assert (await reporting.measured(7, 'a'))['rows'] == []
            await reporting.measured(7, 'b')
            assert load.await_count == 2
            for days in range(1, 12):
                await reporting.measured(days, 'a')
            assert len(reporting._cache) <= reporting._MAX_CACHE
    asyncio.run(run())


def test_only_the_configured_agent_gets_the_stable_gateway_alias():
    stable = 'virtual-bot-v2:test'
    with patch.object(reporting.cfg, 'OPENCLAW_AGENT', 'openclaw/custom'):
        assert tracking.session_hash(stable) in reporting._hashes('agent:custom:' + stable)
        assert tracking.session_hash(stable) not in reporting._hashes('agent:other:' + stable)


def test_back_to_back_turns_have_an_unambiguous_half_open_boundary():
    session = {'key': 'virtual-bot-v2:test'}
    turns = [{**TURN, 'started_at': STAMP - 10, 'ended_at': STAMP},
             {**TURN, 'request_id': 'two', 'started_at': STAMP, 'ended_at': STAMP + 10, 'mode': 'offline'}]
    with patch.object(tracking, 'read_turns', return_value=turns), \
         patch.object(reporting.analytics, '_report', AsyncMock(return_value={'sessions': [session]})), \
         patch.object(reporting.analytics, 'inferences', AsyncMock(return_value={'available': True, 'inferences': [ROW]})):
        result = asyncio.run(reporting.measured(7, 'owner'))
    assert len(result['rows']) == 1
    assert result['rows'][0]['request_id'] == 'two'
