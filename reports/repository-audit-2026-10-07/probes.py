"""Isolated audit observations; no providers, devices, or application lifespan."""
import asyncio
import atexit
import io
import json
import os
import subprocess
from pathlib import Path
import sys
import tempfile
import time
import types
from unittest.mock import patch

repository = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
if sys.platform == 'darwin':
    subprocess.run(['osascript', '-e', 'set volume output muted true'],
        check=True, timeout=5, capture_output=True)

fixture = tempfile.TemporaryDirectory(prefix='claude-bot-audit-2026-10-07-')
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

# Load the real search/locales sources without tools/__init__.py, whose registry
# imports unrelated application modules. No application startup is needed here.
tool_package = types.ModuleType('tools')
tool_package.__path__ = [str(repository / 'Virtual Bot' / 'tools')]
sys.modules['tools'] = tool_package
with patch('builtins.open', synthetic_config_open):
    from tools import search

import httpx
import textwrap
import fnmatch
import re


def public_dns(*_args, **_kwargs):
    return [(2, 1, 6, '', ('93.184.216.34', 443))]


async def page_size():
    observed = {'chunks': 0, 'transport_bytes': 0}
    class FixtureBody(httpx.AsyncByteStream):
        async def __aiter__(self):
            for _ in range(128):
                chunk = b'x' * 16384
                observed['chunks'] += 1
                observed['transport_bytes'] += len(chunk)
                yield chunk
    def handler(request):
        return httpx.Response(200, headers={'content-type': 'text/html'}, stream=FixtureBody())
    with patch.object(search.socket, 'getaddrinfo', public_dns):
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler), trust_env=False) as client:
            result = await search._fetch_one(client, 'https://page.audit.invalid/')
    return {**observed, 'declared_limit': search._FETCH_MAX_BYTES,
        'reported_bytes': result['bytes'], 'reported_ok': result['ok']}


async def dns_deadline():
    def slow_dns(*_args, **_kwargs):
        time.sleep(0.12)
        return public_dns()
    timer = asyncio.create_task(asyncio.sleep(0.01))
    await asyncio.sleep(0)
    started = time.monotonic()
    with patch.object(search.socket, 'getaddrinfo', slow_dns):
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(
            200, headers={'content-type': 'text/html'}, text='<p>Fixture</p>')), trust_env=False) as client:
            try:
                result = await asyncio.wait_for(search._fetch_one(client, 'https://dns.audit.invalid/'), 0.02)
                outcome = 'ok' if result['ok'] else 'failed'
            except asyncio.TimeoutError:
                outcome = 'timeout'
            elapsed = time.monotonic() - started
            timer_delayed = not timer.done()
    await timer
    return {'outer_timeout_seconds': 0.02, 'elapsed_seconds': round(elapsed, 3),
        'outcome': outcome, 'ten_ms_timer_unfinished_after_fetch': timer_delayed}


def shell_interpolation():
    # Render only the exact NOTES assignment from the workflow, with harmless
    # synthetic input. No GitHub API, token, release action, or config is used.
    workflow = (repository / '.github/workflows/mobile-release.yml').read_text()
    assignment = next(line.strip() for line in workflow.splitlines() if 'NOTES="${{ inputs.changelog }}"' in line)
    marker = root / 'interpolation-marker'
    value = '$(printf fixture > "$AUDIT_MARKER")'
    environment = {**process_environment, 'AUDIT_MARKER': str(marker), 'INPUT_NOTES': value}
    rendered = assignment.replace('${{ inputs.changelog }}', value)
    first = subprocess.run(['bash'], input=rendered + '\nprintf "%s" "$NOTES"\n',
        text=True, cwd=root, env=environment, capture_output=True, timeout=5, check=True)
    executed = marker.exists()
    marker.unlink(missing_ok=True)
    second = subprocess.run(['bash'], input='NOTES="$INPUT_NOTES"\nprintf "%s" "$NOTES"\n',
        text=True, cwd=root, env=environment, capture_output=True, timeout=5, check=True)
    return {'rendered_assignment_executed_input': executed,
        'rendered_notes_empty': first.stdout == '',
        'environment_assignment_preserved_input': second.stdout == value,
        'environment_assignment_executed_input': marker.exists()}


def release_pull():
    # Execute an unchanged COPY of the pull script in an entirely synthetic repo.
    # Both gh and the publisher are fixtures; nothing contacts GitHub, reads a
    # dotenv, touches signing material, publishes, or restarts a service.
    fixture_repo = root / 'release-repo'
    scripts = fixture_repo / 'scripts'
    gradle = fixture_repo / 'mobile-app/androidApp/build.gradle.kts'
    tools = fixture_repo / 'bin'
    scripts.mkdir(parents=True)
    gradle.parent.mkdir(parents=True)
    tools.mkdir()
    pull = scripts / 'mobile_pull_release.sh'
    pull.write_text((repository / 'scripts/mobile_pull_release.sh').read_text())
    pull.chmod(0o700)
    captures = root / 'publish-captures.jsonl'
    publisher = scripts / 'mobile_publish.sh'
    publisher.write_text('#!/usr/bin/env python3\n' + textwrap.dedent('''\
        import hashlib, json, os, sys
        args = sys.argv[1:]
        values = {args[i]: args[i+1] for i in range(0, len(args), 2)}
        values['artifact_sha256'] = hashlib.sha256(open(values['--apk'], 'rb').read()).hexdigest()
        with open(os.environ['AUDIT_CAPTURES'], 'a') as stream:
            stream.write(json.dumps(values) + '\\n')
    '''))
    publisher.chmod(0o700)
    gh = tools / 'gh'
    gh.write_text('#!/usr/bin/env python3\n' + textwrap.dedent('''\
        import fnmatch, os, pathlib, sys
        args = sys.argv[1:]
        if args[:2] == ['repo', 'view']:
            print('fixture/repository')
        elif args[:2] == ['release', 'download']:
            pattern = args[args.index('--pattern') + 1]
            name = os.environ['AUDIT_ASSET_NAME']
            if not fnmatch.fnmatchcase(name, pattern):
                print('Synthetic asset did not match requested pattern', file=sys.stderr)
                raise SystemExit(1)
            destination = pathlib.Path(args[args.index('--dir') + 1]) / name
            destination.write_bytes(b'SYNTHETIC NONINSTALLABLE ARTIFACT')
        elif args[:2] == ['release', 'view']:
            print('Synthetic release notes')
        else:
            raise SystemExit('Unexpected fixture gh call')
    '''))
    gh.chmod(0o700)
    workflow = (repository / '.github/workflows/mobile-release.yml').read_text()
    workflow_asset = Path(re.search(r'APK="([^"]+)"', workflow).group(1)).name
    pattern = re.search(r'--pattern "([^"]+)"', pull.read_text()).group(1)
    environment = {**process_environment, 'PATH': str(tools) + os.pathsep + os.environ.get('PATH', ''),
        'AUDIT_CAPTURES': str(captures), 'TMPDIR': str(root),
        'AUDIT_ASSET_NAME': workflow_asset}
    def execute():
        return subprocess.run([str(pull), '--tag', 'mobile-v0.4.13'],
            cwd=fixture_repo, env=environment, capture_output=True, text=True, timeout=5)
    gradle.write_text('versionCode = 19\n')
    unmatched = execute()
    environment['AUDIT_ASSET_NAME'] = 'ClaudeBot-0.4.13.apk'
    first = execute()
    assert first.returncode == 0, first.stderr
    gradle.write_text('versionCode = 20\n')
    second = execute()
    assert second.returncode == 0, second.stderr
    items = [json.loads(line) for line in captures.read_text().splitlines()]
    return {'workflow_asset_basename': workflow_asset,
        'pull_pattern': pattern,
        'workflow_basename_matches_pull_pattern': fnmatch.fnmatchcase(workflow_asset, pattern),
        'unmatched_asset_exit': unmatched.returncode,
        'captured_codes_for_identical_artifact': [item['--version-code'] for item in items],
        'artifact_digest_unchanged': items[0]['artifact_sha256'] == items[1]['artifact_sha256'],
        'captured_channel': items[0]['--channel']}


try:
    results = {'page_size': asyncio.run(page_size()),
        'dns_deadline': asyncio.run(dns_deadline()),
        'shell_interpolation': shell_interpolation(),
        'release_pull': release_pull(), 'isolation': isolation}
    print(json.dumps(results, indent=2))
finally:
    fixture.cleanup()
