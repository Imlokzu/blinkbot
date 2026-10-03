"""Protocol and process regressions from the independent image review."""
from __future__ import annotations

import asyncio
import base64
import json
import os
from pathlib import Path
import signal
import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

import image_generation as generation

PNG = base64.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X9l8AAAAASUVORK5CYII='
)


class Writer:
    def __init__(self):
        self.messages = []

    def write(self, data):
        self.messages.append(json.loads(data))

    async def drain(self):
        pass


def rpc_session(records):
    reader = asyncio.StreamReader()
    for record in records:
        reader.feed_data((json.dumps(record) + '\n').encode())
    reader.feed_eof()
    return generation.CodexSession(SimpleNamespace(stdin=Writer(), stdout=reader))


def test_rpc_wait_preserves_notifications_across_multiple_calls():
    """An early completion must survive both its RPC and later metadata RPCs."""
    async def scenario():
        early = {'method': 'item/completed', 'params': {'threadId': 'thread'}}
        done = {'method': 'turn/completed', 'params': {'threadId': 'thread'}}
        session = rpc_session([early, {'id': 1, 'result': {}}, done, {'id': 2, 'result': {}}])
        await session.call('turn/start', {})
        await session.call('account/read', {})
        assert await session.read() == early
        assert await session.read() == done
    asyncio.run(scenario())


def test_server_requests_are_denied_without_hiding_notifications():
    async def scenario():
        notice = {'method': 'turn/completed', 'params': {'threadId': 'thread'}}
        session = rpc_session([
            {'id': 17, 'method': 'item/commandExecution/requestApproval', 'params': {}},
            notice, {'id': 1, 'result': {}},
        ])
        await session.call('turn/start', {})
        assert session.process.stdin.messages[-1]['error']['code'] == -32601
        assert await session.read() == notice
    asyncio.run(scenario())


SERVER = r'''
import base64
import json
import os
from pathlib import Path
import signal
import sys
import time

settings = json.loads(sys.argv[1])
if settings.get('ignore_term'):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
initialized = False
def emit(record):
    print(json.dumps(record), flush=True)
for line in sys.stdin:
    request = json.loads(line)
    method = request['method']
    if method == 'initialized':
        initialized = True
        if settings.get('child'):
            pid = os.fork()
            if pid == 0:
                signal.signal(signal.SIGTERM, signal.SIG_IGN)
                os.close(0)
                os.close(1)
                os.close(2)
                Path(settings['child']).write_text(str(os.getpid()))
                while True:
                    time.sleep(1)
        continue
    if method == 'initialize':
        time.sleep(0.01)
        result = {}
    elif not initialized:
        emit({'id': request['id'], 'error': {'code': -32000, 'message': 'Not initialized'}})
        continue
    elif method == 'config/read':
        result = {'config': {'model': 'unrelated-model', 'model_reasoning_effort': 'xhigh',
            'mcp_servers': {'unrelated': {'command': '/usr/bin/false', 'enabled': True}}}}
    elif method == 'model/list':
        result = {'data': [{'model': 'catalog-default', 'isDefault': True,
            'defaultReasoningEffort': 'medium'}], 'nextCursor': None}
    elif method == 'account/read':
        result = {'account': {'type': 'chatgpt', 'planType': 'plus'}}
    elif method == 'thread/start':
        Path(settings['thread']).write_text(json.dumps(request['params']))
        result = {'thread': {'id': 'thread'}}
    elif method == 'turn/start':
        if settings.get('hold'):
            Path(settings['started']).write_text('started')
            emit({'id': request['id'], 'result': {'turn': {'id': 'turn', 'status': 'inProgress'}}})
            continue
        item = {'id': 'image', 'type': 'imageGeneration', 'status': 'completed',
            'result': settings['image']}
        if settings.get('saved'):
            path = Path.cwd() / 'native.png'
            path.write_bytes(base64.b64decode(settings['image']))
            item.update(result='', savedPath=str(path))
        emit({'method': 'item/completed', 'params': {'threadId': 'thread', 'turnId': 'turn', 'item': item}})
        emit({'method': 'turn/completed', 'params': {'threadId': 'thread', 'turn': {'id': 'turn', 'status': 'completed'}}})
        result = {'turn': {'id': 'turn', 'status': 'inProgress'}}
    else:
        result = {}
    emit({'id': request['id'], 'result': result})
'''


def install_server(monkeypatch, tmp_path, **settings):
    """Use real stdio and process groups without invoking Codex or a provider."""
    create = asyncio.create_subprocess_exec
    captured = {}
    settings = {'image': base64.b64encode(PNG).decode(), 'thread': str(tmp_path / 'thread.json'), **settings}

    async def spawn(*args, **kwargs):
        captured.update(command=args, environment=kwargs['env'])
        # A synthetic server never needs any of the inherited credentials.
        kwargs['env'] = {}
        process = await create(sys.executable, '-u', '-c', SERVER, json.dumps(settings), **kwargs)
        captured['process'] = process
        return process

    monkeypatch.setattr(generation.shutil, 'which', lambda name: '/review/codex')
    monkeypatch.setattr(generation.asyncio, 'create_subprocess_exec', spawn)
    monkeypatch.setattr(generation, 'TIMEOUT', 2)
    return captured


@pytest.mark.parametrize('saved', [False, True])
def test_early_native_result_is_published_with_isolated_thread(monkeypatch, tmp_path, saved):
    """Notifications before turn/start's response still produce owned bytes."""
    install_server(monkeypatch, tmp_path, saved=saved)
    uploads = tmp_path / 'uploads'
    monkeypatch.setattr(generation.cfg, 'UPLOADS_DIR', uploads)
    result = asyncio.run(generation.generate('A crab', 'alice'))
    image = result['images'][0]
    published = uploads / image['url'].split('/')[-1]
    assert published.read_bytes() == PNG
    assert published.name.startswith(generation.chat_attachments.owner_prefix('alice'))
    assert published.stat().st_mode & 0o777 == 0o600
    thread = json.loads((tmp_path / 'thread.json').read_text())
    assert thread['model'] == 'catalog-default'
    assert thread['config']['model_reasoning_effort'] == 'medium'
    assert thread['config']['mcp_servers']['unrelated']['enabled'] is False
    assert not Path(thread['cwd']).exists()
    assert not generation._busy


def test_session_uses_chatgpt_only_and_disables_other_tool_runtimes(monkeypatch, tmp_path):
    captured = install_server(monkeypatch, tmp_path)
    monkeypatch.setenv('OPENAI_API_KEY', 'review-only-not-a-key')
    monkeypatch.setenv('CODEX_API_KEY', 'review-only-not-a-key')

    async def scenario():
        async with generation._session(str(tmp_path)):
            pass
    asyncio.run(scenario())
    environment = captured['environment']
    assert 'OPENAI_API_KEY' not in environment and 'CODEX_API_KEY' not in environment
    command = captured['command']
    assert 'forced_login_method="chatgpt"' in command
    for flag in ['js_repl', 'plugins', 'browser_use', 'computer_use']:
        assert f'features.{flag}=false' in command


def pid_alive(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


async def wait_until(predicate):
    async with asyncio.timeout(2):
        while not predicate():
            await asyncio.sleep(0.01)


@pytest.mark.parametrize('leader_exits_first', [False, True])
def test_cleanup_kills_children_even_after_the_leader_exits(monkeypatch, tmp_path, leader_exits_first):
    """A child ignoring TERM must not outlive a completed image request."""
    child_file = tmp_path / 'child.pid'
    install_server(monkeypatch, tmp_path, child=str(child_file))

    async def scenario():
        child_pid = None
        try:
            async with generation._session(str(tmp_path)) as session:
                await wait_until(child_file.exists)
                child_pid = int(child_file.read_text())
                if leader_exits_first:
                    session.process.terminate()
                    await session.process.wait()
            await wait_until(lambda: not pid_alive(child_pid))
        finally:
            if child_pid and pid_alive(child_pid):
                os.kill(child_pid, signal.SIGKILL)
    asyncio.run(scenario())


@pytest.mark.parametrize('cancel', [False, True])
def test_timeout_and_repeated_cancellation_reap_real_process_group(monkeypatch, tmp_path, cancel):
    """Cancellation during shutdown cannot abandon a TERM-resistant group."""
    child_file = tmp_path / 'child.pid'
    started = tmp_path / 'started'
    captured = install_server(monkeypatch, tmp_path, child=str(child_file),
        started=str(started), hold=True, ignore_term=True)
    monkeypatch.setattr(generation.cfg, 'UPLOADS_DIR', tmp_path / 'uploads')
    monkeypatch.setattr(generation, 'TIMEOUT', 30 if cancel else 0.5)

    async def scenario():
        term_sent = asyncio.Event()
        killpg = os.killpg
        def tracked_killpg(pid, sig):
            try:
                return killpg(pid, sig)
            finally:
                if sig == signal.SIGTERM:
                    term_sent.set()
        monkeypatch.setattr(generation.os, 'killpg', tracked_killpg)
        task = asyncio.create_task(generation.generate('A crab', 'alice'))
        try:
            await wait_until(lambda: started.exists() and child_file.exists())
            if cancel:
                task.cancel()
                await term_sent.wait()
                task.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await task
            else:
                with pytest.raises(generation.GenerationError, match='image_timeout'):
                    await task
            assert captured['process'].returncode is not None
            await wait_until(lambda: not pid_alive(int(child_file.read_text())))
            assert not generation._busy and not (tmp_path / 'uploads').exists()
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
    asyncio.run(scenario())


def test_saved_path_rejects_nonregular_files_without_reading(monkeypatch, tmp_path):
    """A FIFO would block the event loop and defeat the generation timeout."""
    fifo = tmp_path / 'native.png'
    os.mkfifo(fifo)
    monkeypatch.setattr(Path, 'read_bytes', lambda path: pytest.fail('A nonregular path must never be read'))
    with pytest.raises(generation.GenerationError, match='image_failed'):
        generation._image_bytes({'status': 'completed', 'result': '', 'savedPath': str(fifo)}, str(tmp_path))


@pytest.mark.parametrize('kind', ['outside', 'symlink', 'missing'])
def test_saved_path_errors_are_bounded_and_normalized(tmp_path, kind):
    request = tmp_path / 'request'
    request.mkdir()
    outside = tmp_path / 'outside.png'
    outside.write_bytes(PNG)
    source = request / 'native.png'
    if kind == 'outside':
        source = outside
    elif kind == 'symlink':
        source.symlink_to(outside)
    with pytest.raises(generation.GenerationError, match='image_failed'):
        generation._image_bytes({'status': 'completed', 'result': '', 'savedPath': str(source)}, str(request))


@pytest.mark.parametrize('disabled,user_id,allowed', [
    ('0', '', False), ('0', 'visitor', False), ('0', 'operator', True),
    ('1', '', True), ('1', 'visitor', False),
])
def test_adapter_requires_operator_identity_when_auth_is_enabled(monkeypatch, disabled, user_id, allowed):
    """A missing ContextVar identity must not become a shared local upload."""
    monkeypatch.setenv('CLERK_DISABLED', disabled)
    monkeypatch.setenv('VBOT_OPERATOR_USER_IDS', ' operator ')
    result = {'provider': 'codex', 'images': []}
    with generation.brain_context.set_clerk_user(user_id), patch.object(
        generation, 'generate', AsyncMock(return_value=result)
    ) as generate:
        response = asyncio.run(generation.image_generate('A crab'))
    if allowed:
        assert response == result
        generate.assert_awaited_once_with('A crab', user_id)
    else:
        assert response == {'error': 'operator_required'}
        generate.assert_not_awaited()
