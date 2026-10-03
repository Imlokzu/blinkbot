"""Audit probes: temporary data only; all provider and publishing calls mocked."""
import asyncio
import json
import logging
import os
import sys
import tempfile
import subprocess
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import AsyncMock, Mock, patch

repository = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repository / 'Virtual Bot'))
if sys.platform == 'darwin':
    subprocess.run(['osascript', '-e', 'set volume output muted true'],
        timeout=5, check=True, capture_output=True)

fixture = tempfile.TemporaryDirectory(prefix='claude-bot-audit-2026-10-03-')
root = Path(fixture.name)
os.environ.update(CLERK_DISABLED='1', OPENCLAW_CONFIG_PATH=str(root / 'missing.json'),
    VBOT_INTEGRATIONS_DIR=str(root / 'integrations'), VBOT_USAGE_DB=str(root / 'usage.sqlite3'),
    VBOT_JEV_STATE_FILE=str(root / 'jev.json'), VBOT_OFFLINE='1', TYPESAFE_API_KEY='')

from fastapi.testclient import TestClient
import brain_context
import chat_store
import coding
import main
import site_share
import workspace

logging.getLogger('virtual_bot').setLevel(logging.CRITICAL)
logging.getLogger('virtual_bot.chat_store').setLevel(logging.CRITICAL)
logging.getLogger('httpx').setLevel(logging.CRITICAL)

async def fake_chat(*args, **kwargs):
    return 'Fixture reply', 'idle', 'fixture', []

results = {}
with ExitStack() as stack:
    for target, field, value in (
        (brain_context, 'BRAIN_RUNTIME_DIR', root / 'users'),
        (chat_store, 'USER_DATA_DIR', root / 'users'),
        (chat_store, 'CHATS_DIR', root / 'chats'),
        (workspace, 'USER_DATA_DIR', root / 'users'),
        (workspace, 'WORKSPACE_DIR', root / 'workspace'),
        (site_share, 'STATE_PATH', root / 'shares.json'),
        (main, '_sessions', {}),
    ):
        stack.enter_context(patch.object(target, field, value))
    stack.enter_context(patch.object(main.brains, 'chat', side_effect=fake_chat))
    stack.enter_context(patch.object(main, '_extract_and_save_facts', Mock()))
    stack.enter_context(patch.object(main, '_autoname_chat', AsyncMock()))
    stack.enter_context(patch.object(main.memory, 'append_chat_log', Mock()))
    stack.enter_context(patch.object(main.display_bridge, 'send_chat_exchange_bg', Mock()))

    with brain_context.set_clerk_user('alice-fixture'):
        main._save_history('same-thread', [], 'ALICE_FIXTURE_TEXT', 'Fixture reply')
    with brain_context.set_clerk_user('bob-fixture'):
        results['cache_owner'] = {
            'bob_disk_history': chat_store.history('same-thread', 20),
            'bob_cache_history': main._get_history('same-thread', []),
        }

    client = TestClient(main.app)
    stack.callback(client.close)
    made = client.post('/api/chat', json={'message': 'Fixture question', 'session_id': 'delete-fixture'})
    before_delete = chat_store.history('delete-fixture', 20)
    deleted = client.delete('/api/sessions/delete-fixture')
    results['delete_cache'] = {'post': made.status_code, 'delete': deleted.json(),
        'disk_messages_before_delete': before_delete,
        'disk_messages': chat_store.history('delete-fixture', 20),
        'cached_messages': main._get_history('delete-fixture', [])}

    invalid = client.post('/api/chat', json={'message': 'Fixture question', 'session_id': 'contains a space'})
    results['invalid_session'] = {'status': invalid.status_code,
        'session_id': invalid.json().get('session_id'),
        'user_message_id': invalid.json().get('user_message_id'),
        'stored': any(item['id'] == 'contains a space' for item in chat_store.list_sessions())}

    original_write_text = Path.write_text
    failed_writes = []

    def fail_fixture_write(path, *args, **kwargs):
        if path == root / 'chats' / 'disk-fixture.tmp':
            failed_writes.append(path.name)
            raise OSError('fixture disk write failure')
        return original_write_text(path, *args, **kwargs)

    with patch.object(Path, 'write_text', fail_fixture_write):
        failed = client.post('/api/chat', json={'message': 'Fixture question', 'session_id': 'disk-fixture'})
    results['disk_failure'] = {'status': failed.status_code, 'reply': failed.json().get('reply'),
        'user_message_id': failed.json().get('user_message_id'),
        'injected_filesystem_write_failures': len(failed_writes),
        'has_persistence_field': 'persistence' in failed.json(),
        'persistence_status': failed.json().get('persistence'),
        'disk_messages': chat_store.history('disk-fixture', 20),
        'cached_messages': main._get_history('disk-fixture', [])}

    with patch.object(site_share, '_put_ingress', AsyncMock()), \
         patch.object(site_share, '_ensure_dns', AsyncMock()), \
         patch.object(site_share, '_restart_cloudflared', Mock()):
        with workspace.set_session('publishing-fixture'):
            workspace.write_file('session/site/index.html', '<!doctype html><p>Session fixture</p>')
            shared = asyncio.run(site_share.share('session/site', 'fixture-site'))
        public = client.get('/site/fixture-site')
        results['share_context'] = {'published': 'url' in shared, 'public_http': public.status_code,
            'stored_relative_path': site_share.list_shares()['shares'][0]['path']}

async def registry_probe():
    with patch.object(coding, '_SESSIONS', {}):
        first = await coding.get_session('fixture-session', str(root))
        second = await coding.get_session('fixture-session', str(root))
        return {'same_session_before_start': first is second,
            'first_registered': any(item is first for item in coding._SESSIONS.values())}

results['coding_registry'] = asyncio.run(registry_probe())

async def stderr_probe():
    code = "import sys; print('STARTED', flush=True); sys.stderr.buffer.write(b'x' * (20 * 1024 * 1024)); sys.stderr.flush(); print('READY', flush=True)"
    process = await asyncio.create_subprocess_exec(sys.executable, '-c', code,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, limit=coding._MAX_FRAME_BYTES)
    read = drained = None

    async def drain_and_count():
        count = 0
        while chunk := await process.stderr.read(64 * 1024):
            count += len(chunk)
        return count

    try:
        started = await asyncio.wait_for(process.stdout.readline(), 5)
        read = asyncio.create_task(process.stdout.readline())
        await asyncio.sleep(0.35)
        blocked = not read.done()
        drained = asyncio.create_task(drain_and_count())
        ready = await asyncio.wait_for(read, 5)
        count = await asyncio.wait_for(drained, 5)
        await asyncio.wait_for(process.wait(), 5)
        return {'child_started': started.decode().strip(),
            'blocked_without_stderr_reader': blocked,
            'ready_after_drain': ready.decode().strip(), 'drained_bytes': count}
    finally:
        if process.returncode is None:
            process.kill()
        readers = [task for task in (read, drained) if task is not None]
        for task in readers:
            if not task.done():
                task.cancel()
        await asyncio.gather(*readers, return_exceptions=True)
        await asyncio.wait_for(process.communicate(), 5)

results['stderr'] = asyncio.run(stderr_probe())
print(json.dumps(results, indent=2))
fixture.cleanup()
