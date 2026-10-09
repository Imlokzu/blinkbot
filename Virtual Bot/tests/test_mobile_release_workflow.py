"""Execute the real release steps with synthetic files and a recording gh.

No release, signing material, credential, or network access is needed to prove
that workflow inputs remain data and invalid metadata cannot publish anything.
"""

import json
import os
from pathlib import Path
import subprocess
import sys

import pytest
import yaml


WORKFLOW = Path(__file__).resolve().parents[2] / '.github/workflows/mobile-release.yml'
STEPS = {
    step['name']: step
    for step in yaml.safe_load(WORKFLOW.read_text())['jobs']['release-android']['steps']
    if 'name' in step
}


@pytest.fixture
def release_fixture(tmp_path):
    gradle = tmp_path / 'mobile-app/androidApp/build.gradle.kts'
    gradle.parent.mkdir(parents=True)
    gradle.write_text('android {\n  versionCode = 23\n  versionName = "0.4.16"\n}\n')
    apk = tmp_path / 'mobile-app/androidApp/build/outputs/apk/release/androidApp-release.apk'
    apk.parent.mkdir(parents=True)
    apk.write_bytes(b'SYNTHETIC NONINSTALLABLE APK')
    output = tmp_path / 'step-output'
    output.write_text('')
    calls = tmp_path / 'gh-calls.jsonl'
    notes_tmp = tmp_path / 'notes-tmp'
    notes_tmp.mkdir()
    tools = tmp_path / 'bin'
    tools.mkdir()
    (tools / 'python3').symlink_to(sys.executable)
    gh = tools / 'gh'
    gh.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
record = {'args': args}
if '--notes-file' in args:
    notes_path = Path(args[args.index('--notes-file') + 1])
    record['notes'] = notes_path.read_bytes().decode('utf-8')
with open(os.environ['FIXTURE_CALLS'], 'a') as output:
    output.write(json.dumps(record) + '\\n')
if args[:2] == ['release', 'view']:
    sys.exit(0 if os.environ.get('FIXTURE_RELEASE_EXISTS') == '1' else 1)
elif args[:2] == ['release', 'delete']:
    sys.exit(0)
elif args[:2] == ['release', 'create']:
    sys.exit(int(os.environ.get('FIXTURE_CREATE_EXIT', '0')))
else:
    sys.exit('Unexpected fixture gh invocation')
''')
    gh.chmod(0o700)
    # Only fixture variables and system utilities reach the child shell.
    environment = {
        'PATH': str(tools) + os.pathsep + os.defpath,
        'HOME': str(tmp_path),
        'TMPDIR': str(notes_tmp),
        'LANG': 'en_US.UTF-8',
        'GITHUB_EVENT_NAME': 'workflow_dispatch',
        'GITHUB_REF_NAME': 'main',
        'GITHUB_REF_TYPE': 'branch',
        'GITHUB_REPOSITORY': 'fixture/repository',
        'GITHUB_OUTPUT': str(output),
        'INPUT_VERSION_NAME': '0.4.17',
        'INPUT_VERSION_CODE': '24',
        'INPUT_CHANNEL': 'stable',
        'RELEASE_NAME': '0.4.17',
        'RELEASE_CHANNEL': 'stable',
        'RELEASE_NOTES': '',
        'COMMIT_MESSAGE': '',
        'FIXTURE_CALLS': str(calls),
        'AUDIT_MARKER': str(tmp_path / 'injection-marker'),
    }

    def execute(name, **values):
        step = STEPS[name]
        cwd = tmp_path / step.get('working-directory', '')
        return subprocess.run(
            ['bash', '-e', '-o', 'pipefail', '-c', step['run']],
            cwd=cwd, env={**environment, **values},
            text=True, capture_output=True, timeout=10,
        )

    return tmp_path, gradle, apk, output, calls, notes_tmp, execute


def recorded_calls(path):
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def test_context_expressions_do_not_enter_shell_source():
    # Inspect parsed YAML, so moving an unsafe assignment to another step still fails.
    for step in STEPS.values():
        assert '${{' not in step.get('run', '')
    assert STEPS['Resolve version']['env'] == {
        'INPUT_VERSION_NAME': '${{ inputs.version-name }}',
        'INPUT_VERSION_CODE': '${{ inputs.version-code }}',
        'INPUT_CHANNEL': '${{ inputs.channel }}',
    }
    assert STEPS['Publish GitHub Release']['env'] == {
        'GH_TOKEN': '${{ secrets.GITHUB_TOKEN }}',
        'RELEASE_NAME': '${{ steps.version.outputs.name }}',
        'RELEASE_CHANNEL': '${{ steps.version.outputs.channel }}',
        'RELEASE_NOTES': '${{ inputs.changelog }}',
        'COMMIT_MESSAGE': '${{ github.event.head_commit.message }}',
    }


def test_manual_dispatch_defaults_resolve_without_a_stale_version_code(release_fixture):
    _, _, _, output, _, _, execute = release_fixture
    workflow = yaml.safe_load(WORKFLOW.read_text())
    # PyYAML's YAML 1.1 loader parses the Actions "on" key as True.
    inputs = workflow[True]['workflow_dispatch']['inputs']
    result = execute('Resolve version', **{
        'INPUT_VERSION_NAME': inputs['version-name']['default'],
        'INPUT_VERSION_CODE': inputs['version-code']['default'],
        'INPUT_CHANNEL': inputs['channel']['default'],
    })
    assert result.returncode == 0, result.stderr
    assert 'code=24\n' in output.read_text()


@pytest.mark.parametrize(('values', 'name', 'channel', 'code'), [
    ({}, '0.4.17', 'stable', 24),
    ({'INPUT_CHANNEL': 'beta'}, '0.4.17', 'beta', 24),
    ({'INPUT_VERSION_NAME': '0.4.17-beta'}, '0.4.17', 'beta', 24),
    ({'INPUT_VERSION_CODE': '0'}, '0.4.17', 'stable', 24),
    ({'INPUT_VERSION_CODE': ''}, '0.4.17', 'stable', 24),
    ({'INPUT_VERSION_CODE': '00025'}, '0.4.17', 'stable', 25),
    ({'INPUT_VERSION_CODE': '2100000000'}, '0.4.17', 'stable', 2100000000),
    ({'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF_TYPE': 'tag',
      'GITHUB_REF_NAME': 'mobile-v0.5.0', 'INPUT_VERSION_NAME': '',
      'INPUT_VERSION_CODE': '', 'INPUT_CHANNEL': ''}, '0.5.0', 'stable', 24),
    ({'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF_TYPE': 'tag',
      'GITHUB_REF_NAME': 'mobile-v0.5.0-beta', 'INPUT_VERSION_CODE': '',
      'INPUT_CHANNEL': ''}, '0.5.0', 'beta', 24),
    ({'GITHUB_REF_TYPE': 'tag', 'GITHUB_REF_NAME': 'mobile-v0.5.0-beta'},
     '0.5.0', 'beta', 24),
    ({'GITHUB_REF_NAME': 'mobile-vfeature'}, '0.4.17', 'stable', 24),
])
def test_resolve_valid_versions(release_fixture, values, name, channel, code):
    _, gradle, _, output, _, _, execute = release_fixture
    result = execute('Resolve version', **values)
    assert result.returncode == 0, result.stderr
    assert output.read_text() == f'name={name}\ncode={code}\nchannel={channel}\n'
    assert f'versionCode = {code}\n' in gradle.read_text()
    assert f'versionName = "{name}"\n' in gradle.read_text()


@pytest.mark.parametrize('values', [
    {'INPUT_VERSION_NAME': '$(printf fixture > "$AUDIT_MARKER")'},
    {'INPUT_VERSION_NAME': '0.4.17"; printf fixture > "$AUDIT_MARKER"; #'},
    {'INPUT_VERSION_NAME': '0.4.17\nchannel=beta'},
    {'INPUT_VERSION_NAME': '-beta'},
    {'INPUT_VERSION_NAME': '0.4.17-beta-beta'},
    {'INPUT_VERSION_NAME': '0.4'},
    {'INPUT_VERSION_NAME': ''},
    {'INPUT_CHANNEL': 'stable\nname=forged'},
    {'INPUT_CHANNEL': '$(printf fixture > "$AUDIT_MARKER")'},
    {'INPUT_VERSION_CODE': '$(printf fixture > "$AUDIT_MARKER")'},
    {'INPUT_VERSION_CODE': '24\nchannel=beta'},
    {'INPUT_VERSION_CODE': '24;false'},
    {'INPUT_VERSION_CODE': '-1'},
    {'INPUT_VERSION_CODE': ' 24'},
    {'INPUT_VERSION_CODE': '24.0'},
    {'INPUT_VERSION_CODE': '23'},
    {'INPUT_VERSION_CODE': '22'},
    {'INPUT_VERSION_CODE': '2100000001'},
    {'INPUT_VERSION_CODE': '9' * 100},
    {'GITHUB_EVENT_NAME': 'push'},
    {'GITHUB_EVENT_NAME': 'pull_request'},
    {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF_TYPE': 'tag',
     'GITHUB_REF_NAME': 'mobile-v0.4.17$(printf fixture > "$AUDIT_MARKER")'},
])
def test_invalid_metadata_cannot_mutate_gradle_or_outputs(release_fixture, values):
    root, gradle, _, output, calls, _, execute = release_fixture
    before = gradle.read_bytes()
    result = execute('Resolve version', **values)
    assert result.returncode != 0
    assert gradle.read_bytes() == before
    assert output.read_text() == ''
    assert not (root / 'injection-marker').exists()
    assert recorded_calls(calls) == []


@pytest.mark.parametrize('text', [
    'versionCode = 23\n',
    'versionName = "0.4.16"\n',
    'versionCode = 23\nversionCode = 24\nversionName = "0.4.16"\n',
    'versionCode = 2100000000\nversionName = "0.4.16"\n',
])
def test_unresolvable_gradle_leaves_files_unchanged(release_fixture, text):
    _, gradle, _, output, _, _, execute = release_fixture
    gradle.write_text(text)
    result = execute('Resolve version', INPUT_VERSION_CODE='0')
    assert result.returncode != 0
    assert gradle.read_text() == text
    assert output.read_text() == ''


HOSTILE_NOTES = 'Quotes " and \'\n$(printf fixture > "$AUDIT_MARKER")\n`printf fixture`\n\\literal 😀\n'


@pytest.mark.parametrize(('values', 'notes', 'channel'), [
    ({'RELEASE_NOTES': HOSTILE_NOTES}, HOSTILE_NOTES, 'stable'),
    ({'RELEASE_NOTES': HOSTILE_NOTES, 'RELEASE_CHANNEL': 'beta'}, HOSTILE_NOTES, 'beta'),
    ({'GITHUB_EVENT_NAME': 'push', 'COMMIT_MESSAGE': HOSTILE_NOTES}, HOSTILE_NOTES, 'stable'),
    ({'GITHUB_EVENT_NAME': 'push', 'COMMIT_MESSAGE': 'fallback',
      'RELEASE_NOTES': 'explicit'}, 'explicit', 'stable'),
    ({'COMMIT_MESSAGE': 'ignored on dispatch'}, 'Mobile app update', 'stable'),
    ({'GITHUB_EVENT_NAME': 'push'}, 'Mobile app update', 'stable'),
])
def test_notes_reach_gh_literally_and_temp_file_is_removed(release_fixture, values, notes, channel):
    root, _, _, _, calls, notes_tmp, execute = release_fixture
    result = execute('Publish GitHub Release', **values)
    assert result.returncode == 0, result.stderr
    records = recorded_calls(calls)
    assert [record['args'][:2] for record in records] == [
        ['release', 'view'], ['release', 'create'],
    ]
    create = records[-1]
    assert create['notes'] == notes
    assert create['args'][2] == 'mobile-v0.4.17' + ('-beta' if channel == 'beta' else '')
    assert ('--prerelease' in create['args']) == (channel == 'beta')
    title = create['args'][create['args'].index('--title') + 1]
    assert title == 'Claude Bot 0.4.17' + (' (beta)' if channel == 'beta' else '')
    assert not (root / 'injection-marker').exists()
    assert list(notes_tmp.iterdir()) == []


@pytest.mark.parametrize('values', [
    {'RELEASE_NAME': '0.4.17$(printf fixture > "$AUDIT_MARKER")'},
    {'RELEASE_NAME': '--help'},
    {'RELEASE_NAME': '0.4.17\nname=forged'},
    {'RELEASE_CHANNEL': 'stable;false'},
])
def test_invalid_outputs_cannot_call_gh(release_fixture, values):
    root, _, _, _, calls, _, execute = release_fixture
    result = execute('Publish GitHub Release', FIXTURE_RELEASE_EXISTS='1', **values)
    assert result.returncode != 0
    assert recorded_calls(calls) == []
    assert not (root / 'injection-marker').exists()


@pytest.mark.parametrize('missing', [True, False])
def test_missing_or_empty_apk_cannot_delete_an_existing_release(release_fixture, missing):
    _, _, apk, _, calls, _, execute = release_fixture
    if missing:
        apk.unlink()
    else:
        apk.write_bytes(b'')
    result = execute('Publish GitHub Release', FIXTURE_RELEASE_EXISTS='1')
    assert result.returncode != 0
    assert recorded_calls(calls) == []


def test_existing_release_is_replaced_and_failure_cleans_notes(release_fixture):
    _, _, _, _, calls, notes_tmp, execute = release_fixture
    result = execute('Publish GitHub Release', FIXTURE_RELEASE_EXISTS='1', FIXTURE_CREATE_EXIT='5')
    assert result.returncode == 5
    assert [record['args'][:2] for record in recorded_calls(calls)] == [
        ['release', 'view'], ['release', 'delete'], ['release', 'create'],
    ]
    assert list(notes_tmp.iterdir()) == []
