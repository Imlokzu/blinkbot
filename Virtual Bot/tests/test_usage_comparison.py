import asyncio
import unittest
from unittest.mock import AsyncMock, patch
from fastapi.testclient import TestClient
import main
import usage_comparison as comparison
import usage_reporting as reporting
import usage_tracking

TOKENS = {'input': 1000000, 'output': 100000, 'cacheRead': 2000000, 'cacheWrite': 100000, 'totalTokens': 3200000, 'totalCost': 1., 'missingCostEntries': 0}
class ComparisonTests(unittest.TestCase):
    def test_opus_prices_use_real_input_output_cache_read_and_write(self):
        self.assertAlmostEqual(comparison.reprice(TOKENS, comparison.OPUS['claude-opus-5-5']), 6.9)
        self.assertAlmostEqual(comparison.reprice(TOKENS, comparison.OPUS['claude-opus-4-8']), 9.125)
    def test_paid_router_and_negative_savings_are_preserved(self):
        values = comparison.compare(TOKENS, 8., .1, comparison.OPUS['claude-opus-4-8'], comparison.OPUS['claude-opus-5-5'])
        self.assertAlmostEqual(values['actual_cost'], 8.1); self.assertAlmostEqual(values['savings_vs_opus'], -1.2)
        self.assertLess(values['savings_percent'], 0)
        self.assertIsNone(comparison.compare(TOKENS, 1., None, comparison.OPUS['claude-opus-4-8'], comparison.OPUS['claude-opus-5-5'])['savings_vs_opus'])
    def test_missing_usage_or_rates_is_not_a_free_scenario(self):
        self.assertIsNone(comparison.reprice(None, comparison.OPUS['claude-opus-5-5']))
        missing_partition = {**TOKENS, 'input': 0, 'output': 0, 'cacheRead': 0, 'cacheWrite': 0}
        self.assertIsNone(comparison.reprice(missing_partition, comparison.OPUS['claude-opus-5-5']))
        self.assertIsNone(comparison.reprice({**TOKENS, 'output': None}, comparison.OPUS['claude-opus-5-5']))
        self.assertIsNone(comparison.reprice(TOKENS, {'input': 1, 'output': 1, 'cacheRead': None, 'cacheWrite': 1}))
    def test_filtered_usage_never_claims_zero_component_prices(self):
        summary = reporting.aggregate([{'provider': 'a', 'model': 'm', 'timestamp': 1790000000000, **TOKENS, 'cost': None}])
        self.assertEqual(summary['totals']['missingCostEntries'], 1); self.assertFalse(summary['models'][0]['rates_available'])
    def test_historical_scenario_is_separate_from_verified_router_population(self):
        base = {'available': True, 'totals': TOKENS, 'replies': 10, 'models': [], 'providers': [], 'start_date': '2026-09-24', 'end_date': '2026-09-30'}
        tracked = {'available': True, 'turns': [], 'rows': [], 'limited': False}
        with patch.object(comparison.analytics, 'snapshot', AsyncMock(return_value=base)), patch.object(reporting, 'measured', AsyncMock(return_value=tracked)), patch.object(comparison.openclaw_config, 'get', return_value={}):
            actual = asyncio.run(comparison.snapshot(7, 'all', '', '', 'claude-opus-5-5', 'observed', ''))
            verified = asyncio.run(comparison.snapshot(7, 'all', '', '', 'claude-opus-5-5', 'routed', ''))
            with self.assertRaises(ValueError): asyncio.run(comparison.snapshot(7, 'all', '', '../secret', 'claude-opus-5-5', 'observed', ''))
        self.assertEqual(actual['replies'], 10); self.assertEqual(actual['coverage']['unclassified_inferences'], 10)
        self.assertEqual(verified['replies'], 0); self.assertIsNone(verified['actual_cost'])
    def test_api_validates_parameters_and_authenticates(self):
        client = TestClient(main.app)
        self.assertEqual(client.get('/api/openclaw/analytics?modality=bad').status_code, 422)
        self.assertEqual(client.get('/api/openclaw/analytics/comparison?days=999').status_code, 422)
        self.assertEqual(client.get('/api/openclaw/analytics/comparison?opus_model=bad').status_code, 422)
        with patch.object(main.auth_clerk, 'is_auth_disabled', return_value=False): self.assertEqual(client.get('/api/openclaw/analytics/comparison').status_code, 401)
    def test_integer_utc_range_and_opaque_gateway_session_alias(self):
        bounds = reporting._bounds(7); self.assertIsInstance(bounds[2], int); self.assertEqual(bounds[3] - bounds[2], 7 * 86400000)
        stable = 'virtual-bot-v2:' + 'a' * 32
        self.assertIn(usage_tracking.session_hash(stable), reporting._hashes('agent:main:' + stable))
