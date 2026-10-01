"""NotebookLM sources and the connector inventory already owned by OpenClaw."""
from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import re
import shutil
import signal
import tempfile
import time
import uuid

import app_config as cfg
import chat_attachments
import openclaw_config
from openclaw_store import OpenClawStoreError, _run
from integrations import secrets_store

READ_TOOLS = ('notebook_list', 'notebook_describe', 'source_list', 'source_read', 'chat_ask')
_sdk_lock = asyncio.Lock()
_config_lock = asyncio.Lock()
_login_task: asyncio.Task | None = None
_login_error = ''
_verified: tuple[float, tuple, dict] | None = None
MAX_OUTPUT = 32 * 1024 * 1024
ERROR_CODES = {'needs_login', 'not_installed', 'request_failed', 'timeout', 'file_too_large',
    'source_not_ready', 'source_error', 'source_not_found', 'unreadable_document', 'invalid_request'}


class ConnectorError(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def cli_path() -> Path:
    name = os.environ.get('VBOT_NOTEBOOKLM_CLI') or shutil.which('notebooklm')
    path = Path(name) if name else Path.home() / '.local/bin/notebooklm'
    if not path.is_file() or not os.access(path, os.X_OK):
        raise ConnectorError('not_installed')
    return path.resolve()


def profile() -> str:
    value = secrets_store.load('notebooklm').get('profile') or ''
    if not isinstance(value, str):
        raise ConnectorError('invalid_profile')
    if value and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', value):
        raise ConnectorError('invalid_profile')
    return value


def _command(*args: str, selected: str | None = None) -> list[str]:
    selected = profile() if selected is None else selected
    return [str(cli_path()), *(['--profile', selected] if selected else []), *args]


def _profile_key() -> tuple:
    home = Path(os.environ.get('NOTEBOOKLM_HOME') or Path.home() / '.notebooklm').expanduser()
    try:
        changed = (home / 'config.json').stat().st_mtime_ns
    except OSError:
        changed = None
    # The CLI's default profile can change outside the dashboard.
    return profile(), str(cli_path()), str(home), os.environ.get('NOTEBOOKLM_PROFILE'), changed


async def _terminate(process) -> None:
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            pass
        if process.returncode is not None:
            continue
        try:
            await asyncio.wait_for(process.wait(), 2)
        except asyncio.TimeoutError:
            continue


async def _process(command: list[str], body: bytes | None = None, timeout: int = 35,
        auth_check: bool = False) -> dict:
    try:
        process = await asyncio.create_subprocess_exec(*command, stdin=asyncio.subprocess.PIPE if body is not None else asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL, start_new_session=True)
    except FileNotFoundError:
        raise ConnectorError('not_installed') from None
    except OSError:
        raise ConnectorError('request_failed') from None

    async def communicate():
        if body is not None:
            process.stdin.write(body)
            try:
                await process.stdin.drain()
            except (BrokenPipeError, ConnectionResetError):
                pass
            process.stdin.close()
        output = bytearray()
        while chunk := await process.stdout.read(64 * 1024):
            output.extend(chunk)
            if len(output) > MAX_OUTPUT:
                raise ConnectorError('file_too_large')
        await process.wait()
        return output

    try:
        output = await asyncio.wait_for(communicate(), timeout)
    except asyncio.TimeoutError:
        await _terminate(process)
        raise ConnectorError('timeout') from None
    except BaseException:
        await _terminate(process)
        raise
    else:
        await _terminate(process)
    try:
        data = json.loads(output)
    except (ValueError, UnicodeError):
        raise ConnectorError('request_failed') from None
    if not isinstance(data, dict):
        raise ConnectorError('request_failed')
    if process.returncode != 0:
        # Auth-check exit 1 is its documented negative readiness result. It
        # cannot become a positive connection claim, even with valid JSON.
        if auth_check and process.returncode == 1 and data.get('status') != 'ok':
            raise ConnectorError('needs_login')
        raise ConnectorError('request_failed')
    return data


async def sdk(operation: str, **fields: str) -> dict:
    global _verified
    interpreter = cli_path().parent / 'python'
    if not interpreter.is_file():
        raise ConnectorError('not_installed')
    async with _sdk_lock:
        if _login_task and not _login_task.done():
            raise ConnectorError('login_running')
        if operation not in {'notebooks', 'sources', 'read'}:
            raise ConnectorError('invalid_request')
        key = _profile_key()
        payload = {'operation': operation, 'profile': key[0]}
        if operation in {'sources', 'read'}:
            payload['notebook'] = valid_id(fields.get('notebook', ''))
        if operation == 'read':
            payload['source'] = valid_id(fields.get('source', ''))
        data = await _process([str(interpreter), str(Path(__file__).with_name('notebooklm_bridge.py'))], json.dumps(payload).encode())
    if data.get('error'):
        error = data['error']
        code = error if isinstance(error, str) and error in ERROR_CODES else 'request_failed'
        if code == 'needs_login':
            _verified = None
            if _profile_key() == key:
                _verified = (time.monotonic(), key, {'connected': False, 'code': code})
        raise ConnectorError(code)
    return data


def inventory() -> dict:
    servers = openclaw_config.get('mcp.servers', {})
    allowed = openclaw_config.get('tools.alsoAllow', [])
    allowed = {item for item in allowed if isinstance(item, str)} if isinstance(allowed, list) else set()
    items = []
    for name, server in (servers.items() if isinstance(servers, dict) else []):
        if not isinstance(server, dict):
            continue
        enabled = server.get('enabled') is not False
        access = enabled and any(tool.startswith(name + '__') or tool == '*' for tool in allowed)
        if name == 'notebooklm':
            include = (server.get('toolFilter') or {}).get('include', []) if isinstance(server.get('toolFilter'), dict) else []
            include = {item for item in include if isinstance(item, str)} if isinstance(include, list) else set()
            selected = profile()
            arguments = ['--profile', selected] if selected else []
            exclude = server['toolFilter'].get('exclude', []) if isinstance(server.get('toolFilter'), dict) else []
            try:
                command_matches = server.get('command') == str(cli_path().with_name('notebooklm-mcp'))
            except ConnectorError:
                command_matches = False
            access = enabled and command_matches and not exclude and server.get('args', []) == arguments and set(READ_TOOLS).issubset(include) and all(
                'notebooklm__' + tool in allowed or 'notebooklm__*' in allowed or '*' in allowed for tool in READ_TOOLS)
        items.append({'id': name, 'name': name, 'kind': 'notebooklm' if name == 'notebooklm' else 'mcp',
            'enabled': enabled, 'agent_access': access,
            'connected': None, 'browse': name == 'notebooklm'})
    if not any(item['id'] == 'notebooklm' for item in items):
        items.insert(0, {'id': 'notebooklm', 'name': 'NotebookLM', 'kind': 'notebooklm',
            'enabled': False, 'agent_access': False, 'connected': None, 'browse': True})
    return {'connectors': items, 'available': openclaw_config.config_path().is_file()}


async def status(check: bool = False) -> dict:
    global _verified
    result = {'installed': False, 'profile': '', 'connected': None,
        'login_running': bool(_login_task and not _login_task.done()), 'agent_access': False}
    key = None
    try:
        result['profile'] = profile()
        cli_path()
        result['installed'] = True
        result['agent_access'] = next(item['agent_access'] for item in inventory()['connectors'] if item['id'] == 'notebooklm')
        if check:
            async with _sdk_lock:
                key = _profile_key()
                result['profile'] = key[0]
                data = await _process(_command('auth', 'check', '--test', '--passive', '--json', selected=key[0]), auth_check=True)
                checks = data.get('checks')
                connected = data.get('status') == 'ok' and isinstance(checks, dict) and checks.get('token_fetch') is True
                result.update(connected=connected, code='' if connected else 'needs_login')
                if _profile_key() == key:
                    _verified = (time.monotonic(), key, {'connected': connected, 'code': result['code']})
        elif _verified and time.monotonic() - _verified[0] < 300 and _verified[1] == _profile_key():
            result.update(_verified[2])
        elif _login_error and not result['login_running']:
            result['code'] = _login_error
    except ConnectorError as exc:
        if check:
            _verified = None
            if exc.code == 'needs_login':
                result['connected'] = False
                if key and _profile_key() == key:
                    _verified = (time.monotonic(), key, {'connected': False, 'code': exc.code})
        if exc.code == 'not_installed':
            result['installed'] = False
        result['code'] = exc.code
    except asyncio.TimeoutError:
        result['code'] = 'timeout'
    return result


async def save_profile(value: str) -> dict:
    global _verified, _login_error
    if value and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', value):
        raise ConnectorError('invalid_profile')
    async with _config_lock, _sdk_lock:
        if _login_task and not _login_task.done():
            raise ConnectorError('login_running')
        try:
            await _blocking(secrets_store.update, 'notebooklm', profile=value)
        except OSError:
            raise ConnectorError('request_failed') from None
        _verified = None
        _login_error = ''
    return await status()


async def login() -> dict:
    global _login_task, _verified, _login_error

    async def authenticate():
        global _login_error
        process = None
        try:
            if shutil.which('osascript'):
                mute = await asyncio.create_subprocess_exec('osascript', '-e', 'set volume output muted true',
                    stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, start_new_session=True)
                try:
                    await asyncio.wait_for(mute.wait(), 5)
                finally:
                    if mute.returncode is None:
                        await _terminate(mute)
            process = await asyncio.create_subprocess_exec(*command, stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL, start_new_session=True)
            await asyncio.wait_for(process.wait(), 330)
            if process.returncode != 0:
                _login_error = 'request_failed'
            else:
                checked = await status(check=True)
                _login_error = '' if checked.get('connected') else str(checked.get('code') or 'needs_login')
        except asyncio.TimeoutError:
            _login_error = 'timeout'
        except (OSError, ConnectorError):
            _login_error = 'request_failed'
        finally:
            # A successful CLI exit can still leave its browser descendants.
            if process is not None:
                await _terminate(process)

    async with _config_lock, _sdk_lock:
        if not _login_task or _login_task.done():
            command = _command('login', '--browser', 'chrome', '--browser-timeout', '300')
            _verified = None
            _login_error = ''
            _login_task = asyncio.create_task(authenticate())
    return await status()


async def stop() -> None:
    if _login_task and not _login_task.done():
        _login_task.cancel()
        await asyncio.gather(_login_task, return_exceptions=True)


async def enable_agent() -> dict:
    async with _config_lock:
        return await _enable_agent()


async def _gateway(args: list[str], timeout: int = 35) -> None:
    try:
        await _blocking(_run, args, timeout=timeout)
    except OpenClawStoreError as exc:
        code = 'config_conflict' if 'expect' in exc.output.lower() or 'precondition' in exc.output.lower() else 'gateway_unavailable'
        raise ConnectorError(code) from None


async def _blocking(function, *args, **kwargs):
    task = asyncio.create_task(asyncio.to_thread(function, *args, **kwargs))
    try:
        return await asyncio.shield(task)
    except asyncio.CancelledError:
        # A CLI/thread keeps running after the awaiter is cancelled. Retain the
        # mutation lock until it settles, rather than racing a later config save.
        try:
            await task
        except Exception:
            pass
        raise


async def _enable_agent() -> dict:
    if _login_task and not _login_task.done():
        raise ConnectorError('login_running')
    current = await status(check=True)
    if not current.get('connected'):
        raise ConnectorError(str(current.get('code') or 'needs_login'))
    executable = cli_path().with_name('notebooklm-mcp')
    if not executable.is_file():
        raise ConnectorError('not_installed')
    servers = openclaw_config.get('mcp.servers', {})
    existing = servers.get('notebooklm') if isinstance(servers, dict) else None
    if existing is not None and (not isinstance(existing, dict) or existing.get('command') != str(executable)):
        raise ConnectorError('name_in_use')
    server = {'command': str(executable), 'args': ['--profile', profile()] if profile() else [],
        'enabled': True, 'toolFilter': {'include': list(READ_TOOLS)}}
    # Conditional writes reject stale snapshots instead of replacing a changed
    # server or allowlist. The CLI does not support guarded batch operations.
    expected = ['--expect-current-json', json.dumps(existing)] if existing is not None else ['--expect-current-absent']
    await _gateway(['config', 'set', 'mcp.servers.notebooklm', json.dumps(server), '--strict-json', *expected])
    snapshot = openclaw_config.load()
    missing = object()
    allowed = openclaw_config.lookup(snapshot, 'tools.alsoAllow', missing)
    if allowed is not missing and (not isinstance(allowed, list) or not all(isinstance(item, str) for item in allowed)):
        raise ConnectorError('gateway_unavailable')
    allow = list(allowed) if allowed is not missing else []
    for name in READ_TOOLS:
        if 'notebooklm__' + name not in allow:
            allow.append('notebooklm__' + name)
    expected = ['--expect-current-json', json.dumps(allowed)] if allowed is not missing else ['--expect-current-absent']
    await _gateway(['config', 'set', 'tools.alsoAllow', json.dumps(allow), '--strict-json', *expected], timeout=30)
    await _gateway(['mcp', 'reload'], timeout=20)
    return await status()


def valid_id(value: str) -> str:
    try: return str(uuid.UUID(value))
    except (ValueError, AttributeError, TypeError): raise ConnectorError('invalid_id') from None


async def attach(notebook: str, source: str, user_id: str) -> dict:
    data = await sdk('read', notebook=valid_id(notebook), source=valid_id(source))
    content = str(data.get('content') or '')
    if not content.strip():
        raise ConnectorError('unreadable_document')
    if '\0' in content or not content[:chat_attachments.MAX_TEXT].strip():
        raise ConnectorError('unreadable_document')
    title = str(data.get('title') or 'NotebookLM source')[:150] + '.txt'
    filename, display = chat_attachments.upload_name(title, user_id)
    root = cfg.UPLOADS_DIR
    try:
        root.mkdir(parents=True, exist_ok=True)
    except OSError:
        raise ConnectorError('request_failed') from None
    encoded = content.encode('utf-8')
    if len(encoded) > chat_attachments.MAX_BYTES:
        raise ConnectorError('file_too_large')
    def store():
        descriptor, temporary = tempfile.mkstemp(prefix='.notebooklm-', dir=root)
        try:
            with os.fdopen(descriptor, 'wb') as output:
                output.write(encoded)
            # Publish a complete 0600 file, without replacing an existing upload.
            os.link(temporary, root / filename)
        finally:
            Path(temporary).unlink(missing_ok=True)

    worker = asyncio.create_task(asyncio.to_thread(store))
    try:
        await asyncio.shield(worker)
    except asyncio.CancelledError:
        try:
            await worker
        except OSError:
            pass
        else:
            (root / filename).unlink(missing_ok=True)
        raise
    except OSError:
        raise ConnectorError('request_failed') from None
    return {'url': '/uploads/' + filename, 'name': display, 'type': 'text/plain', 'size': len(encoded),
        'truncated': len(content) > chat_attachments.MAX_TEXT,
        'connector': 'notebooklm', 'notebook_id': valid_id(notebook), 'source_id': valid_id(source)}
