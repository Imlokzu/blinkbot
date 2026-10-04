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

fixture = tempfile.TemporaryDirectory(prefix='claude-bot-audit-2026-10-04-')
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
    from mobile_store import MobileStore

def payload(client_id, **fields):
    return mobile_api.MessageRequest(client_id=client_id, message='Synthetic fixture message', **fields).model_dump()

async def never_called(_job):
    raise AssertionError('The scheduler fixture must not run a provider')
    yield

async def scheduler_failure():
    store = MobileStore(root / 'scheduler.sqlite3')
    job = store.submit('fixture-owner', payload('scheduler-fixture'))
    runtime = mobile_api.MobileRuntime(store, never_called, scan_interval=0.01)
    calls = []
    def fail_claim():
        calls.append(True)
        raise sqlite3.OperationalError('synthetic storage failure')
    try:
        with patch.object(store, 'claim_next', side_effect=fail_claim):
            await runtime.start()
            await asyncio.sleep(0.03)
            task = runtime.scheduler
            done = task.done()
            failure = type(task.exception()).__name__ if done else None
        runtime.notify()
        await runtime.start()
        await asyncio.sleep(0.03)
        return {'scheduler_done': done, 'exception_type': failure,
            'claim_calls': len(calls), 'restart_reuses_dead_task': runtime.scheduler is task,
            'job_state': store.get('fixture-owner', job['id'])['state']}
    finally:
        await runtime.close()

def scheduled_deletion():
    clock = [time.time()]
    store = main._mobile_routes.store
    with ExitStack() as stack:
        stack.enter_context(patch.object(store, 'clock', lambda: clock[0]))
        stack.enter_context(patch.object(chat_store, 'CHATS_DIR', root / 'chats'))
        stack.enter_context(patch.object(chat_store, 'USER_DATA_DIR', root / 'users'))
        stack.enter_context(patch.object(brain_context, 'BRAIN_RUNTIME_DIR', root / 'users'))
        chat_store.append('delete-fixture', 'Synthetic history', 'Synthetic answer')
        history_path = chat_store._path('delete-fixture')
        assert history_path.is_file() and len(chat_store.history('delete-fixture', 20)) == 2
        client = TestClient(main.app)
        # A TestClient context manager would start the prohibited app lifespan.
        stack.callback(client.close)
        request = payload('delete-job', session_id='delete-fixture', scheduled_at=clock[0] + 60)
        submitted = client.post('/api/mobile/messages', json=request)
        assert submitted.status_code == 200, submitted.text
        job_id = submitted.json()['id']
        deleted = client.delete('/api/sessions/delete-fixture')
        empty_history = not history_path.exists() and not chat_store.history('delete-fixture', 20)
        waiting = store.get('', job_id)['state']
        clock[0] += 61
        claimed = store.claim_next()
        return {'delete_status': deleted.status_code, 'delete_ok': deleted.json().get('ok'),
            'history_removed': empty_history, 'state_after_delete': waiting,
            'deleted_conversation_job_claimed': bool(claimed and claimed['id'] == job_id)}

async def blocked_loop():
    store = MobileStore(root / 'lock.sqlite3')
    lock = sqlite3.connect(store.path, isolation_level=None, check_same_thread=False)
    lock.execute('BEGIN IMMEDIATE')
    release = threading.Timer(0.25, lock.rollback)
    release.start()
    timer = asyncio.create_task(asyncio.sleep(0.02))
    await asyncio.sleep(0)
    started = time.monotonic()
    try:
        store.claim_next()
        elapsed = time.monotonic() - started
        delayed = not timer.done()
        await timer
        return {'claim_block_seconds': round(elapsed, 3), 'twenty_ms_timer_unfinished_after_claim': delayed}
    finally:
        release.join()
        lock.close()
        timer.cancel()
        await asyncio.gather(timer, return_exceptions=True)

def retained_history():
    store = MobileStore(root / 'history.sqlite3')
    job = store.submit('fixture-owner', payload('snapshots'))
    store.claim_next()
    for index in range(1, 65):
        store.record(job['id'], 'reply_snapshot', {'text': 'x' * (index * 128)})
    store.record(job['id'], 'done', {'reply': 'x' * 8192})
    store.finish(job['id'], 'completed')
    with store._db() as db:
        summary = db.execute("SELECT COUNT(*), SUM(LENGTH(data)) FROM events WHERE job_id=? AND event='reply_snapshot'", (job['id'],)).fetchone()
    for index in range(40):
        created = store.submit('fixture-owner', payload('terminal-' + str(index)))
        store.claim_next()
        store.finish(created['id'], 'completed')
    listed = store.messages('fixture-owner')
    return {'retained_snapshots': summary[0], 'snapshot_json_characters': summary[1],
        'final_reply_characters': 8192, 'terminal_jobs_returned_by_default': len(listed),
        'all_returned_jobs_terminal': all(item['state'] == 'completed' for item in listed)}

async def audience_capacity():
    with patch.object(events, '_subscribers', set()), \
         patch.object(events, '_latest_todo', {}), \
         patch.object(events, '_shutting_down', False), \
         patch.object(events, 'KEEPALIVE_S', 0.01):
        stream = events.sse_stream(audience='bob-fixture')
        try:
            await anext(stream)
            for index in range(events._QUEUE_MAX):
                events.publish({'type': 'ui', 'fixture': index}, audience='alice-fixture')
            queue_size = next(iter(events._subscribers)).qsize()
            events.publish({'type': 'ui', 'fixture': 'BOB_EXPECTED_EVENT'}, audience='bob-fixture')
            next_frame = await asyncio.wait_for(anext(stream), 0.5)
            return {'bob_queue_size_after_alice_only_events': queue_size,
                'queue_limit': events._QUEUE_MAX,
                'bob_target_event_delivered': 'BOB_EXPECTED_EVENT' in next_frame,
                'next_frame_kind': 'keepalive' if next_frame.startswith(':') else 'data'}
        finally:
            await stream.aclose()

try:
    results = {'scheduler_failure': asyncio.run(scheduler_failure()),
        'scheduled_deletion': scheduled_deletion(),
        'blocked_event_loop': asyncio.run(blocked_loop()),
        'retained_history': retained_history(),
        'audience_capacity': asyncio.run(audience_capacity()),
        'isolation': isolation}
    print(json.dumps(results, indent=2))
finally:
    fixture.cleanup()
