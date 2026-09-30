from __future__ import annotations

import asyncio
import copy
import datetime as dt
import json
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

import main
import openclaw_analytics as a
import openclaw_usage as u

TODAY = dt.datetime.now(dt.timezone.utc).date()
STAMP = dt.datetime.combine(TODAY, dt.time(12), dt.timezone.utc).timestamp() * 1000
COST = {'input': 100, 'output': 20, 'cacheRead': 400, 'cacheWrite': 0, 'totalTokens': 520,
        'inputCost': .01, 'outputCost': .02, 'cacheReadCost': .004, 'totalCost': .034}
REPORT = {'startDate': str(TODAY - dt.timedelta(days=29)), 'endDate': str(TODAY),
          'updatedAt': STAMP, 'totals': COST, 'cacheStatus': {'status': 'refreshing'},
          'sessions': [{'key': 'agent:main:telegram:someone@example.com', 'modelProvider': 'primary',
                        'model': 'chosen', 'updatedAt': STAMP, 'label': 'private chat title',
                        'usage': {**COST, 'messageCounts': {'assistant': 2, 'errors': 1},
                                  'modelUsage': [{'provider': 'fallback', 'model': 'actual'}]}}],
          'aggregates': {'byProvider': [{'provider': 'fallback', 'count': 2, 'totals': COST}],
                         'byModel': [{'provider': 'fallback', 'model': 'actual', 'count': 2, 'totals': COST}],
                         'modelDaily': [{'date': str(TODAY), 'provider': 'fallback', 'model': 'actual',
                                         'tokens': 520, 'cost': .034, 'content': 'private chart text'}],
                         'daily': [{'date': str(TODAY), 'tokens': 520, 'cost': .034, 'errors': 1}],
                         'messages': {'errors': 1}, 'tools': {'totalCalls': 3}}}


class AnalyticsTests(unittest.TestCase):
    def setUp(self):
        a._cache.clear()
        u._cache.clear()

    def test_totals_cover_report_even_when_session_list_is_limited(self):
        # The RPC's range aggregates include sessions outside the visible list.
        with patch.object(u, '_call', AsyncMock(return_value=copy.deepcopy(REPORT))) as call, \
             patch.object(u, 'quota', AsyncMock(return_value=[])), \
             patch.object(u, '_configured_providers', AsyncMock(return_value={'unused': 'oauth'})):
            report = asyncio.run(a.snapshot(30, 1))
        self.assertEqual(report['replies'], 2)
        self.assertEqual(report['totals']['totalTokens'], 520)
        self.assertAlmostEqual(report['totals']['noCacheCost'], .07)
        self.assertAlmostEqual(report['providers'][0]['noCacheCost'], .07)
        self.assertEqual(report['providers'][1]['noCacheCost'], 0)
        self.assertTrue(report['sessions_limited'])
        self.assertTrue(report['indexing'])
        params = call.await_args.args[1]
        self.assertEqual(params['startDate'], str(TODAY - dt.timedelta(days=29)))
        self.assertEqual(params['agentScope'], 'all')
        self.assertNotIn('example.com', json.dumps(report))
        self.assertNotIn('private chat title', json.dumps(report))
        self.assertEqual(report['sessions'][0]['models'][0]['provider'], 'fallback')
        self.assertEqual((report['sessions'][0]['provider'], report['sessions'][0]['model']), ('fallback', 'actual'))
        self.assertEqual(report['daily_models'], [{'date': str(TODAY), 'provider': 'fallback', 'model': 'actual',
                                                   'tokens': 520, 'cost': .034}])
        self.assertNotIn('private chart text', json.dumps(report))

    def test_failure_and_missing_usage_never_become_zero_traffic(self):
        with patch.object(u, '_call', AsyncMock(return_value=None)), \
             patch.object(u, 'quota', AsyncMock(return_value=[])), \
             patch.object(u, '_configured_providers', AsyncMock(return_value={})): 
            report = asyncio.run(a.snapshot())
        self.assertFalse(report['available'])
        self.assertIsNone(report['totals'])

    def test_cache_is_bounded_and_refresh_retries(self):
        async def ranged_report(method, params, **kwargs):
            return {**copy.deepcopy(REPORT), 'startDate': params['startDate'], 'endDate': params['endDate']}
        with patch.object(u, '_call', AsyncMock(side_effect=ranged_report)) as call:
            for days in range(1, 21):
                asyncio.run(a._report(days, 500))
            self.assertEqual(len(a._cache), a._MAX_CACHE)
            asyncio.run(a._report(20, 500))
            self.assertEqual(call.await_count, 20)
            asyncio.run(a._report(20, 500, True))
            self.assertEqual(call.await_count, 21)

    def snapshot(self, report, quota=None, configured=None, limit=500):
        with patch.object(u, '_call', AsyncMock(return_value=copy.deepcopy(report))), \
             patch.object(u, 'quota', AsyncMock(return_value=quota or [])), \
             patch.object(u, '_configured_providers', AsyncMock(return_value=configured or {})):
            return asyncio.run(a.snapshot(30, limit))

    def inferences(self, messages, logs, report=None, limit=1000):
        report = copy.deepcopy(REPORT if report is None else report)
        responses = {'sessions.usage': report, 'sessions.get': messages, 'sessions.usage.logs': logs}
        async def rpc(method, params, **kwargs):
            response = responses[method]
            if isinstance(response, Exception):
                raise response
            return copy.deepcopy(response)
        with patch.object(u, '_call', rpc):
            return asyncio.run(a.inferences(a._session_id(report['sessions'][0]), 30, limit))

    def test_provider_no_cache_cost_uses_matching_models_and_keeps_unclassified_cost(self):
        # Averaging prices across models overprices the cheap model's cache.
        report = copy.deepcopy(REPORT)
        cheap = {'input': 100, 'inputCost': .1, 'cacheRead': 1000, 'cacheReadCost': .1, 'totalCost': .3}
        dear = {'input': 100, 'inputCost': 1, 'cacheRead': 100, 'cacheReadCost': .1, 'totalCost': 1.5}
        unpriced = {'input': 200, 'cacheRead': 50, 'totalCost': .2, 'missingCostEntries': 1}
        report['totals']['totalCost'] = 2.5
        report['aggregates']['byModel'] = [
            {'provider': 'mixed', 'model': 'cheap', 'totals': cheap},
            {'provider': 'mixed', 'model': 'dear', 'totals': dear},
            {'provider': 'unpriced', 'model': 'other', 'totals': unpriced},
        ]
        report['aggregates']['byProvider'] = [
            {'provider': 'mixed', 'totals': {'totalCost': 1.8}},
            {'provider': 'unpriced', 'totals': unpriced},
        ]
        snap = self.snapshot(report)
        providers = {p['provider']: p for p in snap['providers']}
        self.assertAlmostEqual(providers['mixed']['noCacheCost'], 3.6)
        self.assertAlmostEqual(providers['unpriced']['noCacheCost'], .2)
        # The extra .5 has no model breakdown and must not disappear.
        self.assertAlmostEqual(snap['totals']['noCacheCost'], 4.3)
        a._cache.clear()
        report['aggregates']['byModel'] = []
        snap = self.snapshot(report)
        self.assertEqual(snap['totals']['noCacheCost'], 2.5)
        self.assertEqual(snap['providers'][0]['noCacheCost'], 1.8)

    def test_model_only_provider_does_not_lose_traffic(self):
        report = copy.deepcopy(REPORT)
        report['aggregates']['byProvider'] = []
        snap = self.snapshot(report)
        self.assertEqual(snap['providers'][0]['totalTokens'], 520)
        self.assertAlmostEqual(snap['providers'][0]['noCacheCost'], .07)
        self.assertEqual(snap['providers'][0]['replies'], 2)

    def test_daily_models_are_allowlisted_numeric_and_in_range(self):
        report = copy.deepcopy(REPORT)
        report['aggregates']['modelDaily'] += [
            {'date': str(TODAY), 'provider': 'bad', 'model': 'odd', 'tokens': True, 'cost': float('nan'),
             'key': 'private recipient', 'content': 'private reply'},
            {'date': str(TODAY), 'provider': 'openclaw', 'tokens': 99, 'cost': 1},
            {'date': str(TODAY + dt.timedelta(days=1)), 'provider': 'future', 'tokens': 99},
            {'date': '2026-02-30', 'provider': 'invalid', 'tokens': 99},
            {'date': {'private': 'metadata'}, 'provider': 'invalid'}, None,
        ]
        rows = self.snapshot(report)['daily_models']
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0], {'date': str(TODAY), 'provider': 'bad', 'model': 'odd', 'tokens': 0, 'cost': 0})
        self.assertNotIn('private', json.dumps(rows, allow_nan=False))

    def test_malformed_metadata_cannot_leak_objects_or_break_serialization(self):
        report = copy.deepcopy(REPORT)
        private = {'private': 'recipient@example.com'}
        report['updatedAt'] = private
        report['cacheStatus'] = {'status': private}
        report['sessions'][0]['usage']['messageCounts'] = {'assistant': private, 'errors': float('inf')}
        report['sessions'][0]['usage']['modelUsage'][0]['model'] = private
        report['aggregates']['messages'] = 'private text'
        report['aggregates']['tools'] = {'totalCalls': private}
        report['aggregates']['byModel'][0]['model'] = private
        report['aggregates']['byModel'][0]['totals']['input'] = 10 ** 400
        quota = [{'provider': 'fallback', 'accountEmail': 'private@example.com', 'windows': [{'label': '5h', 'used_percent': float('nan')}]}]
        snap = self.snapshot(report, quota, {'fallback': private})
        dumped = json.dumps(snap, allow_nan=False)
        self.assertNotIn('private', dumped)
        self.assertNotIn('example.com', dumped)
        self.assertEqual(snap['errors'], 0)
        self.assertIsNone(snap['updated_at'])
        self.assertEqual(snap['providers'][0]['auth'], '')
        # A malformed cache status also remains safe on the cached read.
        self.snapshot(report)

    def test_partial_report_is_unavailable_and_is_not_cached(self):
        for report in ({}, {'sessions': []}, {**REPORT, 'startDate': str(TODAY)}, None):
            with self.subTest(report=type(report).__name__):
                snap = self.snapshot(report)
                self.assertFalse(snap['available'])
                self.assertIsNone(snap['totals'])
                self.assertEqual(snap['daily_models'], [])
                self.assertFalse(a._cache)

    def test_reply_total_includes_unmetered_replies(self):
        report = copy.deepcopy(REPORT)
        report['aggregates']['messages']['assistant'] = 9
        report['aggregates']['byProvider'].append({'provider': 'openclaw', 'count': 2, 'totals': {}})
        self.assertEqual(self.snapshot(report)['replies'], 7)

    def test_multiple_models_do_not_claim_the_configured_primary(self):
        report = copy.deepcopy(REPORT)
        report['sessions'][0]['usage']['modelUsage'] += [{'provider': 'second', 'model': 'also-used'}]
        session = self.snapshot(report)['sessions'][0]
        self.assertEqual((session['provider'], session['model']), ('', ''))
        self.assertEqual(len(session['models']), 2)

    def test_snapshot_honors_row_limit_even_if_gateway_returns_more(self):
        report = copy.deepcopy(REPORT)
        report['sessions'].append({**report['sessions'][0], 'key': 'agent:other:new'})
        snap = self.snapshot(report, limit=1)
        self.assertEqual(len(snap['sessions']), 1)
        self.assertEqual(snap['totals']['totalTokens'], 520)
        self.assertTrue(snap['sessions_limited'])

    def test_known_empty_sessions_are_hidden_but_unknown_usage_remains(self):
        # Empty indexed transcripts are noise; null usage must remain visible
        # because indexing may still discover actual replies in that session.
        report = copy.deepcopy(REPORT)
        empty = {'messageCounts': {'assistant': 0}, 'modelUsage': [], 'totalTokens': 0}
        active = report['sessions'][0]
        report['sessions'] = [
            {'key': 'agent:main:empty-one', 'usage': empty},
            {'key': 'agent:main:empty-two', 'usage': empty},
            {'key': 'agent:main:unindexed', 'usage': None},
            active,
        ]
        limited = self.snapshot(report, limit=3)
        self.assertEqual(len(limited['sessions']), 1)
        self.assertIsNone(limited['sessions'][0]['usage'])
        self.assertTrue(limited['sessions_limited'])
        self.assertEqual(limited['totals']['totalTokens'], 520)
        complete = self.snapshot(report, limit=5)
        self.assertEqual(len(complete['sessions']), 2)
        self.assertFalse(complete['sessions_limited'])

    def test_empty_filter_preserves_incomplete_and_inconsistent_usage(self):
        report = copy.deepcopy(REPORT)
        report['sessions'] = [
            {'key': 'agent:main:unknown', 'usage': {}},
            {'key': 'agent:main:cached', 'usage': {'messageCounts': {'assistant': 0}, 'modelUsage': [],
                                                 'totalTokens': 0, 'cacheRead': 100}},
        ]
        self.assertEqual(len(self.snapshot(report)['sessions']), 2)

    def test_sessions_limited_uses_raw_count_including_filtered_rows(self):
        report = copy.deepcopy(REPORT)
        report['sessions'] = [{'key': 'agent:main:empty', 'usage': {
            'messageCounts': {'assistant': 0}, 'modelUsage': [], 'totalTokens': 0}}, None]
        snap = self.snapshot(report, limit=2)
        self.assertEqual(snap['sessions'], [])
        self.assertTrue(snap['sessions_limited'])

    def test_inferences_use_actual_provider_resolved_cost_and_strip_content(self):
        sid = a._session_id(REPORT['sessions'][0])
        messages = {'messages': [
            {'role': 'user', 'timestamp': STAMP, 'content': 'private prompt'},
            {'role': 'assistant', 'timestamp': STAMP, 'provider': 'fallback', 'model': 'actual',
             'content': 'private reply', 'usage': {**COST, 'cost': {'total': 0}}, 'stopReason': 'toolUse'},
            {'role': 'assistant', 'timestamp': STAMP + 1, 'provider': 'unmetered', 'model': 'other',
             'usage': {'totalTokens': 0}, 'stopReason': 'stop'},
            {'role': 'assistant', 'timestamp': STAMP - 100 * 86400000, 'provider': 'old', 'model': 'old'},
            {'role': 'assistant', 'timestamp': STAMP + 2, 'provider': 'openclaw', 'content': 'delivery'},
        ]}
        async def rpc(method, params, **kwargs):
            return copy.deepcopy({'sessions.usage': REPORT, 'sessions.get': messages,
                                  'sessions.usage.logs': {'logs': [{'role': 'assistant', 'timestamp': STAMP, 'cost': .034}]}}[method])
        with patch.object(u, '_call', rpc):
            result = asyncio.run(a.inferences(sid, 30))
        self.assertEqual(len(result['inferences']), 2)
        row = result['inferences'][1]
        self.assertEqual((row['provider'], row['model'], row['cost'], row['status']), ('fallback', 'actual', .034, 'tool'))
        self.assertIsNone(result['inferences'][0]['totalTokens'])
        self.assertIsNone(result['inferences'][0]['cost'])
        self.assertNotIn('private', json.dumps(result))
        self.assertEqual(result['history_scope'], 'current_transcript')

    def test_ambiguous_timestamps_never_assign_another_models_cost(self):
        message = {'role': 'assistant', 'timestamp': STAMP, 'provider': 'fallback', 'model': 'actual',
                   'usage': {**COST, 'cost': {'total': 0}}}
        for messages, logs in (
            ([message], [{'role': 'assistant', 'timestamp': STAMP, 'cost': .03},
                         {'role': 'assistant', 'timestamp': STAMP, 'cost': .50}]),
            ([message, {**message, 'provider': 'other', 'model': 'different'}],
             [{'role': 'assistant', 'timestamp': STAMP, 'cost': .50}]),
            ([message], [{'role': 'assistant', 'timestamp': STAMP, 'tokens': 999, 'cost': .50}]),
        ):
            with self.subTest(messages=len(messages), logs=len(logs)):
                a._cache.clear()
                result = self.inferences({'messages': messages}, {'logs': logs})
                self.assertTrue(all(row['cost'] is None for row in result['inferences']))

    def test_recorded_billing_survives_missing_tokens_or_logs(self):
        messages = {'messages': [
            {'role': 'assistant', 'timestamp': STAMP, 'provider': 'billed', 'model': 'aggregate',
             'usage': {'cost': {'total': .5, 'totalOrigin': 'provider-billed'}}},
            {'role': 'assistant', 'timestamp': STAMP + 1, 'provider': 'free', 'model': 'billed-zero',
             'usage': {'totalTokens': 0, 'cost': {'total': 0, 'totalOrigin': 'provider-billed'}}},
            {'role': 'assistant', 'timestamp': STAMP + 2, 'provider': 'unknown', 'model': 'synthetic-zero',
             'usage': {'totalTokens': 0, 'cost': {'total': 0}}, 'stopReason': 'aborted'},
            {'role': 'assistant', 'timestamp': STAMP + 3, 'provider': 'priced', 'model': 'observed',
             'usage': {**COST, 'cost': {'total': .034}}},
        ]}
        result = self.inferences(messages, OSError('private gateway detail'))
        by_model = {row['model']: row for row in result['inferences']}
        self.assertEqual(by_model['aggregate']['cost'], .5)
        self.assertIsNone(by_model['aggregate']['totalTokens'])
        self.assertEqual(by_model['billed-zero']['cost'], 0)
        self.assertIsNone(by_model['synthetic-zero']['cost'])
        self.assertEqual(by_model['synthetic-zero']['status'], 'error')
        self.assertAlmostEqual(by_model['observed']['cost'], .034)
        self.assertNotIn('private', json.dumps(result))

    def test_token_components_supply_total_when_aggregate_is_absent(self):
        result = self.inferences({'messages': [
            {'role': 'assistant', 'timestamp': STAMP, 'provider': 'fallback', 'model': 'actual',
             'usage': {'input': 100, 'output': 20, 'cacheRead': 400, 'cacheWrite': 0}},
        ]}, {'logs': [{'role': 'assistant', 'timestamp': STAMP, 'tokens': 520, 'cost': .034}]})
        self.assertEqual(result['inferences'][0]['totalTokens'], 520)
        self.assertAlmostEqual(result['inferences'][0]['cost'], .034)

    def test_empty_current_history_does_not_erase_archival_usage(self):
        result = self.inferences({'messages': []}, {'logs': []})
        self.assertFalse(result['available'])
        self.assertTrue(result['limited'])
        self.assertEqual(result['history_scope'], 'current_transcript')
        self.assertEqual(result['inferences'], [])

    def test_transcript_failure_is_unavailable_even_if_pricing_succeeds(self):
        result = self.inferences(OSError('private path'), {'logs': []})
        self.assertFalse(result['available'])
        self.assertNotIn('private', json.dumps(result))

    def test_inference_window_is_utc_and_end_exclusive(self):
        start = dt.datetime.combine(TODAY - dt.timedelta(days=29), dt.time(), dt.timezone.utc).timestamp() * 1000
        end = dt.datetime.combine(TODAY + dt.timedelta(days=1), dt.time(), dt.timezone.utc).timestamp() * 1000
        messages = {'messages': [{'role': 'assistant', 'timestamp': stamp, 'provider': 'fallback',
                                  'model': 'actual', 'usage': COST, 'content': 'private text'}
                                 for stamp in (start - 1, start, end - 1, end)]}
        result = self.inferences(messages, {'logs': []})
        self.assertEqual([row['timestamp'] for row in result['inferences']], [end - 1, start])
        self.assertNotIn('private', json.dumps(result))

    def test_current_history_and_pricing_limits_are_explicit(self):
        messages = {'messages': [{'role': 'assistant', 'timestamp': STAMP + i, 'provider': 'fallback',
                                  'model': 'actual', 'usage': COST} for i in range(5)]}
        logs = {'logs': [{'role': 'user', 'timestamp': STAMP + i} for i in range(1000)]}
        result = self.inferences(messages, logs, limit=2)
        self.assertEqual(len(result['inferences']), 2)
        self.assertEqual(result['message_limit'], 2)
        self.assertTrue(result['limited'])
        self.assertTrue(result['pricing_limited'])

    def test_transcript_instance_id_prevents_retargeting_after_reset(self):
        first = {**REPORT['sessions'][0], 'sessionId': 'before-reset'}
        second = {**first, 'sessionId': 'after-reset'}
        self.assertNotEqual(a._session_id(first), a._session_id(second))
        report = {**REPORT, 'sessions': [second]}
        with patch.object(u, '_call', AsyncMock(return_value=report)) as call:
            with self.assertRaises(LookupError):
                asyncio.run(a.inferences(a._session_id(first), 30))
        self.assertEqual(call.await_count, 1)

    def test_cached_instance_is_verified_against_current_transcript(self):
        # A reset after the report was cached must not return the new chat's
        # metadata under the old instance's id, even if it uses the same key.
        report = copy.deepcopy(REPORT)
        report['sessions'][0]['sessionId'] = 'reported-instance'
        messages = {'messages': [{'role': 'assistant', 'timestamp': STAMP, 'provider': 'new',
                                  'model': 'after-reset', 'usage': COST, 'content': 'private reply'}]}
        for described in ('reported-instance', 'new-instance', None):
            with self.subTest(described=described):
                a._cache.clear()
                responses = {'sessions.usage': report, 'sessions.get': messages, 'sessions.usage.logs': {'logs': []},
                             'sessions.describe': {'session': {'sessionId': described}}}
                async def rpc(method, params, **kwargs):
                    return copy.deepcopy(responses[method])
                with patch.object(u, '_call', AsyncMock(side_effect=rpc)) as call:
                    asyncio.run(a._report(30, 2000))
                    result = asyncio.run(a.inferences(a._session_id(report['sessions'][0]), 30))
                self.assertEqual(result['available'], described == 'reported-instance')
                if described != 'reported-instance':
                    self.assertEqual(result['inferences'], [])
                self.assertNotIn('private', json.dumps(result))
                self.assertEqual(call.await_count, 4)

    def test_invalid_backend_ranges_never_start_gateway_calls(self):
        with patch.object(u, '_call', AsyncMock()) as call:
            for days, limit in ((0, 1), (91, 1), (True, 1), (30, 0), (30, 2001)):
                with self.subTest(days=days, limit=limit), self.assertRaises(ValueError):
                    asyncio.run(a.snapshot(days, limit))
            with self.assertRaises(ValueError):
                asyncio.run(a.inferences('a' * 64, 30, 10001))
            call.assert_not_awaited()

    def test_gateway_timeout_flag_is_in_milliseconds_with_startup_grace(self):
        with patch.object(u.openclaw_models, '_run_cli', AsyncMock(return_value=(0, '{}', ''))) as run:
            asyncio.run(u._call('sessions.usage', {'limit': 1}, timeout=60.5))
        args = run.await_args.args
        self.assertEqual(args[args.index('--timeout') + 1], '60500')
        self.assertEqual(run.await_args.kwargs['timeout'], 65.5)
        self.assertEqual(json.loads(args[args.index('--params') + 1]), {'limit': 1})

    def test_unknown_session_and_traversal_never_query_transcripts(self):
        with patch.object(u, '_call', AsyncMock(return_value=copy.deepcopy(REPORT))) as call:
            with self.assertRaises(ValueError):
                asyncio.run(a.inferences('../secret', 30))
            call.assert_not_awaited()
            with self.assertRaises(LookupError):
                asyncio.run(a.inferences('f' * 64, 30))
            self.assertEqual(call.await_count, 1)

    def test_endpoint_validates_range_and_requires_auth(self):
        client = TestClient(main.app)
        with patch.object(a, 'snapshot', AsyncMock()) as snap:
            for query in ('days=0', 'days=999', 'limit=0', 'limit=2001'):
                self.assertEqual(client.get('/api/openclaw/analytics?' + query).status_code, 422)
            snap.assert_not_awaited()
        with patch.object(a, 'inferences', AsyncMock()) as inf:
            for query in ('days=0', 'days=91', 'limit=0', 'limit=10001'):
                self.assertEqual(client.get('/api/openclaw/analytics/inferences?session_id=' + 'a' * 64 + '&' + query).status_code, 422)
            inf.assert_not_awaited()
        self.assertEqual(client.get('/api/openclaw/analytics/inferences?session_id=../secret').status_code, 400)
        with patch.object(main.auth_clerk, 'is_auth_disabled', return_value=False):
            self.assertEqual(client.get('/api/openclaw/analytics').status_code, 401)
            self.assertEqual(client.get('/api/openclaw/analytics/inferences?session_id=' + 'a' * 64).status_code, 401)

    def test_successful_endpoints_disable_browser_caching(self):
        client = TestClient(main.app)
        with patch.object(a, 'snapshot', AsyncMock(return_value={'available': False})), \
             patch.object(a, 'inferences', AsyncMock(return_value={'available': False, 'inferences': []})):
            for path in ('/api/openclaw/analytics', '/api/openclaw/analytics/inferences?session_id=' + 'a' * 64):
                response = client.get(path)
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.headers['cache-control'], 'no-store')


if __name__ == '__main__':
    unittest.main()
