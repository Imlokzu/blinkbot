"""Connector setup must reflect real gateway config without exposing credentials."""
from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from fastapi.testclient import TestClient
import connectors
import main
import notebooklm_bridge

NB = '00000000-0000-0000-0000-000000000001'
SOURCE = '00000000-0000-0000-0000-000000000002'


def test_inventory_comes_from_openclaw_and_never_returns_secrets():
    config = {'mcp.servers': {'drive': {'url': 'https://api.example/mcp?token=private', 'headers': {'Authorization': 'secret'}, 'enabled': False}},
        'tools.alsoAllow': ['drive__search']}
    with patch.object(connectors.openclaw_config, 'get', side_effect=lambda path, default=None: config.get(path, default)):
        result = connectors.inventory()
    dumped = json.dumps(result)
    assert 'private' not in dumped and 'secret' not in dumped
    drive = next(item for item in result['connectors'] if item['id'] == 'drive')
    assert drive['enabled'] is False
    assert drive['connected'] is None


def test_status_requires_network_token_verification_not_cookie_presence(tmp_path):
    cli = tmp_path / 'notebooklm'
    cli.write_text('installed')
    for reply, expected in [({'status': 'ok', 'checks': {'token_fetch': None}}, False),
            ({'status': 'ok', 'checks': {'token_fetch': True}}, True),
            ({'status': 'error', 'checks': {'token_fetch': False}, 'details': 'private-cookie'}, False)]:
        with patch.object(connectors, 'cli_path', return_value=cli), \
                patch.object(connectors, '_process', AsyncMock(return_value=reply)):
            result = asyncio.run(connectors.status(check=True))
        assert result['connected'] is expected
        assert 'private-cookie' not in json.dumps(result)


def test_sources_import_actual_content_with_owner_scope_and_private_permissions(tmp_path):
    content = 'The real source says the deadline is October 15.'
    with patch.object(connectors.cfg, 'UPLOADS_DIR', tmp_path), \
            patch.object(connectors, 'sdk', AsyncMock(return_value={'title': 'Source title', 'content': content})) as sdk:
        result = asyncio.run(connectors.attach(NB, SOURCE, 'alice'))
    sdk.assert_awaited_once_with('read', notebook=NB, source=SOURCE)
    file = tmp_path / result['url'].split('/')[-1]
    assert file.read_text() == content
    assert file.name.startswith(connectors.chat_attachments.owner_prefix('alice'))
    assert file.stat().st_mode & 0o777 == 0o600
    assert result['size'] == len(content.encode())


def test_bad_ids_and_profile_paths_never_reach_the_sdk():
    with patch.object(connectors, 'sdk', AsyncMock()) as sdk:
        for bad in ['../private', '--profile', '', 'not-a-uuid']:
            try: asyncio.run(connectors.attach(bad, SOURCE, 'alice'))
            except connectors.ConnectorError as exc: assert exc.code == 'invalid_id'
            else: assert False, bad
        sdk.assert_not_called()
    for bad in ['../default', '--default', 'a/b', 'a.b']:
        try: asyncio.run(connectors.save_profile(bad))
        except connectors.ConnectorError as exc: assert exc.code == 'invalid_profile'
        else: assert False, bad


def test_connector_routes_require_the_server_operator():
    # A dev-mode request forwarded from the network is not a local operator.
    with TestClient(main.app) as client:
        response = client.get('/api/connectors', headers={'X-Forwarded-For': '203.0.113.10'})
    assert response.status_code == 403


def test_source_api_uses_real_sdk_result_and_known_notebook_id():
    async def operator(): return None
    main.app.dependency_overrides[main._require_openclaw_operator] = operator
    try:
        with patch.object(connectors, 'sdk', AsyncMock(return_value={'sources': [{'id': SOURCE, 'title': 'Real source', 'status': 'ready'}]})) as sdk, TestClient(main.app) as client:
            response = client.get(f'/api/connectors/notebooklm/notebooks/{NB}/sources')
            assert response.status_code == 200
            assert response.json()['sources'][0]['title'] == 'Real source'
            sdk.assert_awaited_once_with('sources', notebook=NB)
    finally:
        main.app.dependency_overrides.pop(main._require_openclaw_operator, None)


def test_sdk_bridge_distinguishes_ready_failed_and_unknown_sources():
    sources = [SimpleNamespace(id=str(index), title='Source', is_ready=status == 'READY', is_error=status == 'ERROR',
        is_processing=status == 'PROCESSING', status=SimpleNamespace(name=status))
        for index, status in enumerate(['READY', 'ERROR', 'UNKNOWN', 'PROCESSING'])]
    class Client:
        @classmethod
        def from_storage(cls, **kwargs): return cls()
        async def __aenter__(self):
            self.sources = SimpleNamespace(list=AsyncMock(return_value=sources))
            return self
        async def __aexit__(self, *args): pass
    with patch.dict(sys.modules, {'notebooklm': SimpleNamespace(NotebookLMClient=Client)}):
        result = asyncio.run(notebooklm_bridge.run({'operation': 'sources', 'notebook': NB}))
    assert [item['status'] for item in result['sources']] == ['ready', 'error', 'unknown', 'processing']


def test_nonzero_process_exit_cannot_become_success_and_large_output_is_bounded():
    for script, code in [('import json,sys;print(json.dumps({"ok":True}));sys.exit(1)', 'request_failed'),
            ('print("x" * 10000)', 'file_too_large')]:
        with patch.object(connectors, 'MAX_OUTPUT', 4096):
            try: asyncio.run(connectors._process([sys.executable, '-c', script]))
            except connectors.ConnectorError as exc: assert exc.code == code
            else: assert False, 'child output must not bypass process validation'


def test_default_profile_cache_is_invalidated_when_selected_profile_changes(tmp_path):
    cli = tmp_path / 'notebooklm'
    cli.write_text('installed')
    selected = {'name': 'first'}
    async def run():
        with patch.object(connectors, 'cli_path', return_value=cli), \
                patch.object(connectors, 'profile', side_effect=lambda: selected['name']), \
                patch.object(connectors, '_process', AsyncMock(return_value={'status': 'ok', 'checks': {'token_fetch': True}})):
            first = await connectors.status(check=True)
            assert first['connected'] is True
            selected['name'] = 'second'
            second = await connectors.status()
            assert second['connected'] is None
    asyncio.run(run())


def test_native_agent_setup_uses_guarded_config_and_preserves_existing_tools(tmp_path):
    cli = tmp_path / 'notebooklm'
    cli.write_text('installed')
    cli.with_name('notebooklm-mcp').write_text('installed')
    snapshot = {'tools': {'alsoAllow': ['tools__web_search']}}
    async def run():
        with patch.object(connectors, 'cli_path', return_value=cli), \
                patch.object(connectors, 'status', AsyncMock(return_value={'connected': True})), \
                patch.object(connectors.openclaw_config, 'get', return_value={}), \
                patch.object(connectors.openclaw_config, 'load', return_value=snapshot), \
                patch.object(connectors, '_gateway', AsyncMock()) as gateway:
            await connectors.enable_agent()
            calls = [call.args[0] for call in gateway.await_args_list]
            assert calls[0][2] == 'mcp.servers.notebooklm'
            assert '--expect-current-absent' in calls[0]
            assert calls[1][2] == 'tools.alsoAllow'
            allow = json.loads(calls[1][3])
            assert 'tools__web_search' in allow
            assert all('notebooklm__' + name in allow for name in connectors.READ_TOOLS)
            assert '--expect-current-json' in calls[1]
            assert calls[-1] == ['mcp', 'reload']
    asyncio.run(run())


def test_native_agent_access_requires_every_read_tool_and_enabled_server(tmp_path):
    cli = tmp_path / 'notebooklm'
    server = {'command': str(cli.with_name('notebooklm-mcp')), 'enabled': True, 'toolFilter': {'include': list(connectors.READ_TOOLS)}}
    settings = {'mcp.servers': {'notebooklm': server}, 'tools.alsoAllow': ['notebooklm__' + name for name in connectors.READ_TOOLS]}
    with patch.object(connectors.openclaw_config, 'get', side_effect=lambda path, default=None: settings.get(path, default)), patch.object(connectors, 'cli_path', return_value=cli):
        assert connectors.inventory()['connectors'][0]['agent_access'] is True
        settings['tools.alsoAllow'].pop()
        assert connectors.inventory()['connectors'][0]['agent_access'] is False
        settings['tools.alsoAllow'].append('notebooklm__*')
        server['enabled'] = False
        assert connectors.inventory()['connectors'][0]['agent_access'] is False


def test_explicit_zen_preference_uses_cookie_import_instead_of_chrome():
    with patch.object(connectors.secrets_store, 'load', return_value={'browser': 'zen'}):
        assert connectors.login_browser() == 'zen'


def test_login_pins_the_resolved_profile_and_ignores_metadata_updates(tmp_path):
    home = tmp_path / 'notebooklm'
    home.mkdir()
    configuration = home / 'config.json'
    configuration.write_text(json.dumps({'default_profile': 'zen-account', 'language': 'en'}))
    with patch.dict(os.environ, {'NOTEBOOKLM_HOME': str(home)}, clear=False), \
            patch.object(connectors, 'profile', return_value=''):
        with patch.dict(os.environ, {'NOTEBOOKLM_PROFILE': ''}):
            assert connectors._login_profile() == 'zen-account'
            configuration.write_text(json.dumps({'default_profile': 'zen-account', 'language': 'uk'}))
            assert connectors._login_profile() == 'zen-account'
        with patch.dict(os.environ, {'NOTEBOOKLM_PROFILE': 'explicit-account'}):
            assert connectors._login_profile() == 'explicit-account'


def test_zen_retry_respects_remaining_deadline_and_cleans_only_its_children():
    processes = [SimpleNamespace(pid=1, returncode=0, wait=AsyncMock()),
        SimpleNamespace(pid=2, returncode=1, wait=AsyncMock())]
    async def run():
        with patch.object(connectors.asyncio, 'create_subprocess_exec', AsyncMock(side_effect=processes)), \
                patch.object(connectors, '_command', return_value=['notebooklm']), \
                patch.object(connectors, 'time', SimpleNamespace(monotonic=Mock(side_effect=[0, 299, 299.5, 300]))), \
                patch.object(connectors, '_terminate', AsyncMock()) as stop, \
                patch.object(connectors.asyncio, 'sleep', AsyncMock()) as sleep:
            assert await connectors._zen_login('fixture') is False
            assert [call.args[0].pid for call in stop.await_args_list] == [1, 2]
            sleep.assert_not_awaited()
    asyncio.run(run())


def test_zen_login_uses_verified_cookie_refresh_without_json_or_credentials():
    processes = [SimpleNamespace(pid=1, returncode=0, wait=AsyncMock()),
        SimpleNamespace(pid=2, returncode=0, wait=AsyncMock())]
    async def run():
        with patch.object(connectors.asyncio, 'create_subprocess_exec', AsyncMock(side_effect=processes)) as spawn, \
                patch.object(connectors, '_command', side_effect=lambda *args, **kwargs: ['notebooklm', *args]) as command, \
                patch.object(connectors, '_terminate', AsyncMock()):
            assert await connectors._zen_login('fixture') is True
            assert spawn.await_args_list[0].args == ('open', '-a', 'Zen', 'https://notebook.google.com/')
            args = command.call_args.args
            assert args == ('auth', 'refresh', '--browser-cookies', 'zen', '--verify', '--quiet')
            assert '--json' not in args
            assert command.call_args.kwargs['selected'] == 'fixture'
    asyncio.run(run())
