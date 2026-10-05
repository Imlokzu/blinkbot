"""Isolated audit observations; no providers, devices, or application lifespan."""
import asyncio
import atexit
from contextlib import ExitStack
import io
import json
import os
import subprocess
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
from unittest.mock import patch

repository = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repository / 'Virtual Bot'))
if sys.platform == 'darwin':
    subprocess.run(['osascript', '-e', 'set volume output muted true'],
        check=True, timeout=5, capture_output=True)

fixture = tempfile.TemporaryDirectory(prefix='claude-bot-audit-2026-10-05-')
atexit.register(fixture.cleanup)
root = Path(fixture.name)
# Keep only nonsecret process settings; local credentials cannot affect fixtures.
process_environment = {key: os.environ[key] for key in ('PATH', 'TMPDIR', 'LANG', 'LC_ALL')
    if key in os.environ}
os.environ.clear()
os.environ.update(process_environment)
os.environ.update(CLERK_DISABLED='1', CLERK_ISSUER='https://clerk.audit.invalid',
    CLERK_JWT_ISSUER='https://clerk.audit.invalid', VBOT_OFFLINE='1',
    MOBILE_API_ORIGIN='https://mobile.audit.invalid',
    MOBILE_DATABASE_PATH=str(root / 'mobile.sqlite3'),
    OPENCLAW_CONFIG_PATH=str(root / 'missing.json'),
    VBOT_INTEGRATIONS_DIR=str(root / 'integrations'),
    VBOT_USAGE_DB=str(root / 'usage.sqlite3'),
    VBOT_JEV_STATE_FILE=str(root / 'jev.json'), TYPESAFE_API_KEY='')

isolation = {'dotenv_reads_blocked': 0, 'network_connections_blocked': 0,
    'dns_lookups_blocked': 0, 'socket_binds_blocked': 0, 'synthetic_config_reads': 0}
socket_events = {'socket.connect': 'network_connections_blocked',
    'socket.getaddrinfo': 'dns_lookups_blocked', 'socket.bind': 'socket_binds_blocked'}

def guard_io(event, args):
    # app_config normally reads real dotenv files even in offline mode.
    if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
        name = Path(os.fsdecode(args[0])).name
        if name == '.env' or name.startswith('.env.'):
            isolation['dotenv_reads_blocked'] += 1
            raise FileNotFoundError('Dotenv reads are disabled in this audit fixture')
    if event in socket_events:
        isolation[socket_events[event]] += 1
        raise OSError('Network access is disabled in this audit fixture')

sys.addaudithook(guard_io)
original_open = open
config_path = repository / 'Virtual Bot' / 'config.yaml'

def synthetic_config_open(file, *args, **kwargs):
    if isinstance(file, (str, bytes, os.PathLike)) and Path(os.fsdecode(file)).resolve() == config_path:
        mode = kwargs.get('mode', args[0] if args else 'r')
        if mode not in {'r', 'rt'}:
            raise OSError('Configuration writes are disabled in this audit fixture')
        isolation['synthetic_config_reads'] += 1
        return io.StringIO('{}')
    return original_open(file, *args, **kwargs)

with patch('builtins.open', synthetic_config_open):
    from fastapi.testclient import TestClient
    import brain_context
    import chat_store
    import main
    import mobile_api
    import events
    import image_generation
    import workspace
    import workspace_write_guard
    from mobile_store import MobileStore

from collections import deque
from contextlib import asynccontextmanager
import base64
import hashlib
from fastapi import HTTPException
from starlette.requests import Request
from unittest.mock import AsyncMock


def request(path, token=''):
    headers = [(b'host', b'dashboard.audit.invalid')]
    if token:
        headers.append((b'authorization', ('Bearer ' + token).encode()))
    return Request({'type': 'http', 'method': 'GET', 'path': path,
        'headers': headers, 'query_string': b'', 'client': ('203.0.113.10', 45678),
        'server': ('dashboard.audit.invalid', 80), 'scheme': 'http'})


async def public_stream():
    with ExitStack() as stack:
        stack.enter_context(patch.object(events, '_subscribers', set()))
        stack.enter_context(patch.object(events, '_latest_todo', {}))
        stack.enter_context(patch.object(events, '_LOG_RING', deque(maxlen=400)))
        stack.enter_context(patch.object(events, '_shutting_down', False))
        stack.enter_context(patch.object(main.auth_clerk, 'is_auth_disabled', return_value=False))
        response = await main.api_events(request('/api/events'))
        iterator = response.body_iterator
        try:
            await anext(iterator)
            events.publish_reply('SYNTHETIC_PRIVATE_REPLY', 'idle')
            events.publish_log({'msg': 'SYNTHETIC_PRIVATE_LOG', 'session': 'owner-fixture'})
            frames = []
            for _ in range(12):
                frame = await asyncio.wait_for(anext(iterator), 0.5)
                frames.append(frame.decode() if isinstance(frame, bytes) else frame)
                if any('SYNTHETIC_PRIVATE_REPLY' in item for item in frames) and any('SYNTHETIC_PRIVATE_LOG' in item for item in frames):
                    break
            return {'response_status': response.status_code,
                'anonymous_reply_received': any('SYNTHETIC_PRIVATE_REPLY' in item for item in frames),
                'anonymous_log_received': any('SYNTHETIC_PRIVATE_LOG' in item for item in frames)}
        finally:
            await iterator.aclose()


async def revoked_stream():
    store = MobileStore(root / 'revocation.sqlite3')
    # A deterministic credential exists only in this temporary fixture DB.
    # No pairing exchange, production token, or vault item is used or created.
    token = 'cbm_synthetic_revocation_fixture'
    now = time.time()
    with store._write() as db:
        db.execute('INSERT INTO devices VALUES (?, ?, ?, ?, ?, ?, ?, NULL)',
            ('fixture-device', 'fixture-owner', 'Fixture', 'android',
             hashlib.sha256(token.encode()).hexdigest(), now, now + 3600))
    job = store.submit('fixture-owner', mobile_api.MessageRequest(
        client_id='revoke-job', session_id='revoke-chat', message='Synthetic question').model_dump())
    store.claim_next()
    async def require_user(_request):
        raise HTTPException(401)
    async def require_operator(_request):
        raise HTTPException(403)
    async def unused_runner(_job):
        raise AssertionError('No provider runner may execute')
        yield
    routes = mobile_api.router(require_user, require_operator, unused_runner, store=store)
    endpoint = next(route.endpoint for route in routes.routes
        if route.path == '/api/mobile/messages/{job_id}/events')
    req = request('/api/mobile/messages/' + job['id'] + '/events', token)
    owner = await routes.identity(req)
    response = await endpoint(job['id'], req, after=0, user_id=owner)
    iterator = response.body_iterator
    try:
        for _ in store.events(owner, job['id']):
            await anext(iterator)
        store.revoke(owner, 'fixture-device')
        try:
            await routes.identity(req)
        except HTTPException as exc:
            rejected = exc.status_code
        else:
            rejected = 200
        store.record(job['id'], 'delta', {'chunk': 'SYNTHETIC_AFTER_REVOCATION'})
        frame = await asyncio.wait_for(anext(iterator), 0.5)
        if isinstance(frame, bytes):
            frame = frame.decode()
        return {'fresh_auth_status': rejected, 'existing_stream_received_later_event': 'SYNTHETIC_AFTER_REVOCATION' in frame}
    finally:
        await iterator.aclose()


async def mutation_guard():
    with patch.object(workspace, 'WORKSPACE_DIR', root / 'workspace'), \
         patch.object(workspace, 'USER_DATA_DIR', root / 'users'):
        workspace.write_file('notes.md', 'Synthetic contents')
        def another_context():
            try:
                workspace.write_file('notes.md', 'Concurrent rewrite')
            except ValueError as exc:
                code = str(exc)
            else:
                code = 'not_blocked'
            moved = workspace.rename('notes.md', 'moved.md')
            return {'competing_write_error': code, 'rename_succeeded': moved['ok'],
                'original_exists': (workspace.root() / 'notes.md').exists()}
        async with workspace_write_guard.mobile_reservation('', '', 'notes.md'):
            # run_in_executor does not inherit the holder's ContextVar exemption.
            result = await asyncio.get_running_loop().run_in_executor(None, another_context)
        async with workspace_write_guard.mobile_reservation('', '', 'moved.md'):
            deleted = await asyncio.get_running_loop().run_in_executor(None, workspace.delete, 'moved.md')
            result['delete_reserved_file_succeeded'] = deleted['ok']
        return result


async def cancelled_publication():
    entered = threading.Event()
    release = threading.Event()
    second_entered = threading.Event()
    count_lock = threading.Lock()
    calls = []
    publication_errors = []
    original_publish = image_generation._publish
    png = b'\x89PNG\r\n\x1a\n' + b'synthetic fixture bytes'
    class FixtureCodex:
        async def call(self, method, _params):
            return {'thread': {'id': 'fixture-thread'}} if method == 'thread/start' else {}
        async def read(self):
            return self.frames.pop(0)
        def __init__(self):
            self.frames = [
                {'method': 'item/completed', 'params': {'threadId': 'fixture-thread', 'item': {
                    'type': 'imageGeneration', 'status': 'completed', 'result': base64.b64encode(png).decode()}}},
                {'method': 'turn/completed', 'params': {'threadId': 'fixture-thread', 'turn': {'status': 'completed'}}},
            ]
    @asynccontextmanager
    async def fake_session(_directory):
        yield FixtureCodex()
    def delayed_publish(data, owner):
        completed = threading.Event()
        with count_lock:
            calls.append(completed)
            number = len(calls)
        try:
            entered.set()
            if number == 2:
                second_entered.set()
            if not release.wait(5):
                raise RuntimeError('Fixture publication release timed out')
            return original_publish(data, owner)
        except BaseException as exc:
            with count_lock:
                publication_errors.append(type(exc).__name__)
            raise
        finally:
            # File existence is insufficient: _publish's own finally must finish.
            completed.set()

    async def settle_publishers():
        with count_lock:
            completions = list(calls)
        for completed in completions:
            if not await asyncio.to_thread(completed.wait, 5):
                raise RuntimeError('Fixture publication cleanup timed out')
        return len(completions)

    with patch.object(image_generation, '_session', fake_session), \
         patch.object(image_generation, '_account', AsyncMock()), \
         patch.object(image_generation, '_publish', delayed_publish), \
         patch.object(image_generation, '_busy', False):
        first = asyncio.create_task(image_generation.generate('Synthetic fixture', 'fixture-owner'))
        second = None
        try:
            assert await asyncio.to_thread(entered.wait, 2)
            first.cancel()
            try:
                await first
            except asyncio.CancelledError:
                pass
            busy_after = image_generation._busy
            second = asyncio.create_task(image_generation.generate('Second synthetic fixture', 'fixture-owner'))
            accepted_second = await asyncio.to_thread(second_entered.wait, 2)
            release.set()
            second_result = await asyncio.gather(second, return_exceptions=True)
            completed_count = await settle_publishers()
            return {'busy_after_cancel': busy_after,
                'second_publish_entered_while_first_blocked': accepted_second,
                'second_generation_succeeded': isinstance(second_result[0], dict),
                'files_published_after_first_cancellation': len(list((root / 'uploads').glob('u*.png'))),
                'publication_workers_completed': completed_count,
                'publication_worker_errors': list(publication_errors)}
        finally:
            release.set()
            for task in (first, second):
                if task is not None and not task.done():
                    task.cancel()
            await asyncio.gather(*(task for task in (first, second) if task is not None), return_exceptions=True)
            # Await cancelled tasks and their detached workers on exceptional paths.
            await settle_publishers()


# Keep the upload fixture active through asyncio.run's executor shutdown too.
# Even a failed bounded worker wait must not restore a production path early.
with patch.object(image_generation.cfg, 'UPLOADS_DIR', root / 'uploads'):
    try:
        results = {'public_stream': asyncio.run(public_stream()),
            'revoked_stream': asyncio.run(revoked_stream()),
            'mutation_guard': asyncio.run(mutation_guard()),
            'cancelled_publication': asyncio.run(cancelled_publication()),
            'isolation': isolation}
        print(json.dumps(results, indent=2))
    finally:
        fixture.cleanup()
