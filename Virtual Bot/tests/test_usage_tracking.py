import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import brain_context
import usage_tracking as tracking

class TrackingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        env = patch.dict(os.environ, {'VBOT_USAGE_DB': str(Path(self.tmp.name) / 'usage.sqlite3')})
        env.start(); self.addCleanup(env.stop)
    def rows(self, user=''):
        return tracking.read_turns(0, 2**63 - 1, user)
    def test_durable_private_modality_and_owner_scoped_route_cost(self):
        @tracking.record_chat
        async def chat(message, *, session_key=None, voice=False, spoken=False):
            call = tracking.note_classifier(); tracking.note_classifier(180, call_id=call)
            tracking.note_route('fast', 'openai/test', 'jev 0.95')
            return 'private reply', 'happy', 'openclaw', []
        with brain_context.set_clerk_user('private@example.com'):
            result = asyncio.run(chat('secret prompt', session_key='virtual-bot-v2:secret', spoken=True))
        self.assertEqual(result[0], 'private reply'); self.assertEqual(self.rows(), [])
        row = self.rows('private@example.com')[0]
        self.assertEqual(row['modality'], 'voice'); self.assertTrue(row['routed'])
        self.assertAlmostEqual(row['routing_cost'], 180 * .042 / 1000000)
        self.assertNotIn('secret', json.dumps(row)); self.assertNotIn('example.com', json.dumps(row))
        self.assertEqual(tracking.read_turns(row['started_at'], row['started_at'] + 1, 'private@example.com'), [row])
        self.assertEqual(tracking.read_turns(0, row['started_at'], 'private@example.com'), [])
    def test_classifier_failure_is_unknown_while_local_rules_cost_zero(self):
        @tracking.record_chat
        async def chat(*, session_key=None, failed=False):
            if failed: tracking.note_classifier()
            tracking.note_route('smart', 'openai/test', 'keywords')
            return '', '', 'openclaw', []
        asyncio.run(chat(session_key='one', failed=True)); asyncio.run(chat(session_key='two'))
        rows = self.rows(); self.assertIsNone(rows[0]['routing_cost']); self.assertEqual(rows[1]['routing_cost'], 0)
    def test_concurrent_turns_and_background_calls_do_not_mix(self):
        @tracking.record_chat
        async def chat(*, session_key=None, voice=False):
            await asyncio.sleep(.001); tracking.note_route('fast' if voice else 'build', 'provider/model')
            return '', '', 'openclaw', []
        async def run(): await asyncio.gather(chat(session_key='a', voice=True), chat(session_key='b'), chat())
        asyncio.run(run()); rows = self.rows(); self.assertEqual(len(rows), 2)
        self.assertEqual({r['modality']: r['tier'] for r in rows}, {'voice': 'fast', 'text': 'build'})
    def test_errors_and_cancellation_are_preserved(self):
        @tracking.record_chat
        async def chat(*, session_key=None, cancel=False):
            if cancel: raise asyncio.CancelledError()
            raise RuntimeError('failed')
        with self.assertRaises(RuntimeError): asyncio.run(chat(session_key='a'))
        with self.assertRaises(asyncio.CancelledError): asyncio.run(chat(session_key='b', cancel=True))
        self.assertEqual({r['mode'] for r in self.rows()}, {'error', 'cancelled'})
    def test_broken_database_cannot_break_reply(self):
        @tracking.record_chat
        async def chat(*, session_key=None): return 'ok', '', 'openclaw', []
        with patch.dict(os.environ, {'VBOT_USAGE_DB': self.tmp.name}): self.assertEqual(asyncio.run(chat(session_key='a'))[0], 'ok')
