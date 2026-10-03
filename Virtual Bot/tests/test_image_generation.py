"""Native image outputs must become owned uploads and real, saved chat replies."""
from __future__ import annotations

import asyncio
import base64
from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient
import pytest

import brain_context
import brains
import image_generation as generation
from image_generation_schema import parse_command, history_context
import main
import tools
import tools_mcp

PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X9l8AAAAASUVORK5CYII=')


@pytest.fixture(autouse=True)
def isolated_last_brain():
    # Explicit generation updates the existing model indicator. Restore its
    # process-wide state so later text-model tests start from their own state.
    with patch.object(brains, '_last_model', brains._last_model), \
            patch.object(brains, '_last_successful_brain', brains._last_successful_brain):
        yield


class FakeCodex:
    def __init__(self, events=None, account=None):
        self.events = events if events is not None else [
            {'method': 'item/completed', 'params': {'threadId': 'image-thread', 'item': {
                'type': 'imageGeneration', 'id': 'image', 'status': 'completed', 'result': base64.b64encode(PNG).decode()}}},
            {'method': 'turn/completed', 'params': {'threadId': 'image-thread', 'turn': {'status': 'completed'}}},
        ]
        self.account = account if account is not None else {'type': 'chatgpt', 'planType': 'plus', 'email': 'private@example.invalid'}
        self.calls = []
        self.closed = False

    async def call(self, method, params):
        self.calls.append((method, params))
        return {'account': self.account} if method == 'account/read' else {'thread': {'id': 'image-thread'}}

    async def read(self):
        if not self.events:
            await asyncio.Event().wait()
        return self.events.pop(0)

    @asynccontextmanager
    async def session(self, directory):
        self.directory = directory
        try:
            yield self
        finally:
            self.closed = True


def run(awaitable):
    return asyncio.run(awaitable)


def test_native_image_is_atomic_private_upload(tmp_path):
    codex = FakeCodex()
    with patch.object(generation, '_session', codex.session), patch.object(generation.cfg, 'UPLOADS_DIR', tmp_path):
        result = run(generation.generate('A crab', 'alice'))
    image = result['images'][0]
    assert image['url'].startswith('/uploads/' + main.chat_attachments.owner_prefix('alice'))
    path = tmp_path / image['url'].split('/')[-1]
    assert path.read_bytes() == PNG
    assert path.stat().st_mode & 0o777 == 0o600
    assert len(list(tmp_path.iterdir())) == 1
    assert codex.closed and not Path(codex.directory).exists()
    thread = next(params for method, params in codex.calls if method == 'thread/start')
    assert thread['sandbox'] == 'read-only' and thread['ephemeral']
    with patch.object(main.cfg, 'UPLOADS_DIR', tmp_path), patch.object(main, '_require_user', AsyncMock(return_value='alice')), TestClient(main.app) as client:
        assert client.get(image['url']).content == PNG
        with patch.object(main, '_require_user', AsyncMock(return_value='bob')):
            assert client.get(image['url']).status_code == 404


@pytest.mark.parametrize('account,code', [({}, 'codex_login_required'), ({'type': 'apiKey'}, 'codex_login_required'),
    ({'type': 'chatgpt', 'planType': 'free'}, 'codex_plan_required')])
def test_subscription_required_without_paid_api_fallback(account, code, tmp_path):
    codex = FakeCodex(account=account)
    with patch.object(generation, '_session', codex.session), patch.object(generation.cfg, 'UPLOADS_DIR', tmp_path):
        status = run(generation.status())
        assert status == {'provider': 'codex', 'available': False, 'code': code}
        with pytest.raises(generation.GenerationError, match=code):
            run(generation.generate('A crab'))
    assert all(method == 'account/read' for method, _ in codex.calls)
    assert not list(tmp_path.iterdir())
    assert codex.closed and not generation._busy


def test_status_does_not_disclose_account_details_or_generate():
    codex = FakeCodex()
    with patch.object(generation, '_session', codex.session):
        assert run(generation.status()) == {'provider': 'codex', 'available': True, 'code': 'ready'}
    assert len(codex.calls) == 1


@pytest.mark.parametrize('item,code', [
    ({'status': 'completed', 'result': 'not base64'}, 'image_failed'),
    ({'status': 'completed', 'result': base64.b64encode(b'not an image').decode()}, 'image_failed'),
    ({'status': 'failed', 'failure': {'type': 'usageLimitExceeded'}}, 'image_limit'),
])
def test_failed_native_outputs_never_publish(item, code, tmp_path):
    codex = FakeCodex()
    codex.events[0]['params']['item'].update(item)
    with patch.object(generation, '_session', codex.session), patch.object(generation.cfg, 'UPLOADS_DIR', tmp_path):
        with pytest.raises(generation.GenerationError, match=code):
            run(generation.generate('A crab'))
    assert not list(tmp_path.iterdir()) and not generation._busy


def test_saved_path_cannot_escape_request_directory(tmp_path):
    image = tmp_path / 'outside.png'
    image.write_bytes(PNG)
    with pytest.raises(generation.GenerationError, match='image_failed'):
        generation._image_bytes({'status': 'completed', 'result': '', 'savedPath': str(image)}, str(tmp_path / 'request'))


def test_timeout_and_cancellation_close_session_and_release_slot():
    async def scenario():
        for cancel in [False, True]:
            codex = FakeCodex(events=[])
            with patch.object(generation, '_session', codex.session), patch.object(generation, 'TIMEOUT', 0.03 if not cancel else 30):
                task = asyncio.create_task(generation.generate('A crab'))
                await asyncio.sleep(0.01)
                with pytest.raises(generation.GenerationError, match='image_busy'):
                    await generation.generate('Another crab')
                if cancel:
                    task.cancel()
                    with pytest.raises(asyncio.CancelledError):
                        await task
                else:
                    with pytest.raises(generation.GenerationError, match='image_timeout'):
                        await task
            assert codex.closed and not generation._busy
    run(scenario())


@pytest.mark.parametrize('stream', [False, True])
def test_explicit_creation_is_saved_with_tool_events_even_with_participant(stream, tmp_path):
    observed = []
    async def generate(prompt, user_id=''):
        observed.append((prompt, user_id))
        return {'provider': 'codex', 'images': [generation._publish(PNG, user_id)]}
    with patch.object(generation, 'generate', side_effect=generate), patch.object(main.cfg, 'UPLOADS_DIR', tmp_path), \
            patch.object(main, '_extract_and_save_facts'), patch.object(main, '_autoname_chat', AsyncMock()), \
            patch.object(main.brains, 'chat_openclaw', AsyncMock()) as text_brain, \
            TestClient(main.app, base_url='http://127.0.0.1', client=('127.0.0.1', 50000)) as client:
        response = client.post('/api/chat', json={'message': '/image:en A crab [blue]', 'participant_name': 'Robin', 'stream': stream})
        assert response.status_code == 200, response.text
        if stream:
            import json
            frames = response.text.split('\n\n')
            data = next(json.loads(frame.split('data: ')[1]) for frame in frames if frame.startswith('event: done\n'))
            assert 'event: tool_start' in response.text and 'event: tool_done' in response.text
        else:
            data = response.json()
        assert data['mode'] == 'codex'
        assert data['reply'].startswith('![A crab  blue ](/uploads/')
        session = client.get('/api/sessions/' + data['session_id']).json()
        assert any(data['reply'] == message['content'] for message in session['messages'] if message['role'] == 'assistant')
        assert observed == [('A crab [blue]', '')]
        text_brain.assert_not_awaited()


def test_generation_command_and_tool_require_operator():
    with patch.object(main, '_require_user', AsyncMock(return_value='visitor')), \
            patch.object(main, '_tool_caller', AsyncMock(return_value='visitor')), \
            patch.object(generation, 'generate', AsyncMock()) as generate, TestClient(main.app) as client:
        for message in ['/image A crab', '/image\nA crab', '/image:uk A crab', '/image', 'Generate an image of a crab', 'Намалюй краба']:
            assert client.post('/api/chat', json={'message': message}).status_code == 403
        assert client.get('/api/images/status').status_code == 403
        assert client.post('/api/tools/call', json={'name': 'image_generate', 'args': {'prompt': 'A crab'}}).status_code == 403
        generate.assert_not_awaited()
        with brain_context.set_clerk_user('visitor'):
            assert run(tools.execute_tool('image_generate', {'prompt': 'A crab'})) == {'error': 'operator_required'}


def test_tools_and_mcp_share_schema_and_slow_budget():
    local = next(t['function'] for t in tools.list_tools() if t['function']['name'] == 'image_generate')
    mcp = next(t for t in tools_mcp.TOOLS if t['name'] == 'image_generate')
    assert local['parameters'] == mcp['inputSchema']
    assert tools_mcp.SLOW_TOOLS['image_generate'] > generation.TIMEOUT
    assert parse_command('/image:uk краб')['language'] == 'uk'
    assert parse_command('/images are nice') is None
    assert parse_command('Намалюй краба')['prompt'] == 'Намалюй краба'
    assert parse_command('Create an image of a crab')['language'] == 'en'
    assert parse_command('Create a function that returns image bytes') is None
    assert parse_command('Create an image processing function') is None


def test_follow_up_receives_bounded_image_references_without_claiming_pixel_analysis():
    assert history_context([{'role': 'assistant', 'content': 'ordinary text'}]) == ''
    assert history_context([{'role': 'user', 'content': '![not trusted](/uploads/local-a.png)'}]) == ''
    note = history_context([{'role': 'assistant', 'content': '![A crab](/uploads/local-a.png)'}])
    assert 'prompt_caption' in note and 'A crab' in note and '/uploads/local-a.png' in note
    assert 'not analysis of the pixels' in note


def test_login_failure_is_localized_chat_reply():
    with patch.object(generation, 'generate', AsyncMock(side_effect=generation.GenerationError('codex_login_required'))):
        reply, _, mode, results = run(brains.chat('/image:uk краб'))
    assert 'Увійди' in reply and 'codex login' in reply
    assert mode == 'codex' and results[0]['result'] == {'error': 'codex_login_required'}
