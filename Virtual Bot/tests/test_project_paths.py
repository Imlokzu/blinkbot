"""Project metadata must stay within opened code directories, including races."""

from concurrent.futures import ThreadPoolExecutor
import errno
import json
import os
from pathlib import Path
import time
from types import SimpleNamespace
from unittest.mock import patch

from fastapi.testclient import TestClient
import pytest

import main
import projects
from project_locales import STRINGS
import workspace


@pytest.fixture
def registry(tmp_path, monkeypatch):
    root = tmp_path / 'code'
    root.mkdir()
    monkeypatch.setattr(workspace, 'CODE_DIR', root)
    monkeypatch.setattr(workspace, 'USER_DATA_DIR', tmp_path / 'users')
    outside = tmp_path / 'outside'
    outside.mkdir()
    (outside / '.project.json').write_text('{"name":"Outside fixture","created":1}')
    return root, outside


@pytest.mark.parametrize('target_kind', ['outside', 'inside', 'missing'])
def test_directory_links_are_never_projects(registry, target_kind):
    root, outside = registry
    healthy = projects.create_project('Healthy')
    target = {'outside': outside, 'inside': root / healthy['id'],
              'missing': outside / 'absent'}[target_kind]
    alias = root / 'alias'
    alias.symlink_to(target, target_is_directory=True)
    before = (outside / '.project.json').read_bytes()
    assert [entry['id'] for entry in projects.list_projects()] == [healthy['id']]
    assert not projects.exists('alias')
    for action in (lambda: projects.get_project('alias'),
                   lambda: projects.rename_project('alias', 'Changed'),
                   lambda: projects.delete_project('alias')):
        with pytest.raises(projects.ProjectPathError):
            action()
    assert alias.is_symlink()
    assert (outside / '.project.json').read_bytes() == before


@pytest.mark.parametrize('target_kind', ['outside', 'missing'])
def test_metadata_links_are_rejected_and_healthy_projects_stay_visible(registry, target_kind):
    root, outside = registry
    healthy = projects.create_project('Healthy')
    bad = root / 'bad'
    bad.mkdir()
    target = outside / ('.project.json' if target_kind == 'outside' else 'missing.json')
    (bad / '.project.json').symlink_to(target)
    before = (outside / '.project.json').read_bytes()
    assert [entry['id'] for entry in projects.list_projects()] == [healthy['id']]
    assert not projects.exists('bad')
    for action in (lambda: projects.get_project('bad'),
                   lambda: projects.rename_project('bad', 'Changed'),
                   lambda: projects.delete_project('bad')):
        with pytest.raises(projects.ProjectPathError):
            action()
    assert (outside / '.project.json').read_bytes() == before
    assert not (outside / 'missing.json').exists()


@pytest.mark.parametrize('metadata_kind', ['directory', 'fifo'])
def test_nonregular_metadata_is_refused_without_blocking(registry, metadata_kind):
    root, _ = registry
    bad = root / 'bad'
    bad.mkdir()
    metadata = bad / '.project.json'
    if metadata_kind == 'directory':
        metadata.mkdir()
    else:
        os.mkfifo(metadata)
    with pytest.raises(projects.ProjectPathError):
        projects.get_project('bad')
    assert projects.list_projects() == []


def test_legacy_staging_link_is_not_used(registry):
    root, outside = registry
    project = projects.create_project('Normal')
    folder = root / project['id']
    alias = folder / '.project.json.tmp'
    alias.symlink_to(outside / '.project.json')
    before = (outside / '.project.json').read_bytes()
    receipt = projects.rename_project(project['id'], 'Renamed')
    assert receipt['name'] == 'Renamed'
    assert projects.get_project(project['id'])['name'] == 'Renamed'
    assert alias.is_symlink()
    assert (outside / '.project.json').read_bytes() == before
    assert list(folder.glob('.project-*.tmp')) == []


def test_exclusive_staging_never_opens_a_preexisting_link(registry, monkeypatch):
    root, outside = registry
    project = projects.create_project('Normal')
    folder = root / project['id']
    alias = folder / '.project-forced.tmp'
    alias.symlink_to(outside / '.project.json')
    monkeypatch.setattr(projects, 'uuid4', lambda: SimpleNamespace(hex='forced'))
    before = (folder / '.project.json').read_bytes()
    outside_before = (outside / '.project.json').read_bytes()
    with pytest.raises(FileExistsError):
        projects.rename_project(project['id'], 'Blocked')
    assert (folder / '.project.json').read_bytes() == before
    assert (outside / '.project.json').read_bytes() == outside_before
    assert alias.is_symlink()


@pytest.mark.parametrize('replace_at', ['stage_open', 'after_fsync'])
def test_directory_replacement_cannot_redirect_a_write(registry, replace_at):
    root, outside = registry
    project = projects.create_project('Normal')
    folder = root / project['id']
    moved = root / 'moved-original'
    before = (folder / '.project.json').read_bytes()
    outside_before = (outside / '.project.json').read_bytes()
    original_open, original_fsync = os.open, os.fsync
    swapped = False

    def swap():
        nonlocal swapped
        if not swapped:
            folder.rename(moved)
            folder.symlink_to(outside, target_is_directory=True)
            swapped = True

    def open_stage(path, flags, *args, **kwargs):
        if replace_at == 'stage_open' and str(path).startswith('.project-'):
            swap()
        return original_open(path, flags, *args, **kwargs)

    def sync_stage(descriptor):
        original_fsync(descriptor)
        if replace_at == 'after_fsync':
            swap()

    with patch.object(os, 'open', open_stage), patch.object(os, 'fsync', sync_stage):
        with pytest.raises(projects.ProjectPathError):
            projects.rename_project(project['id'], 'Blocked')
    assert swapped
    assert (outside / '.project.json').read_bytes() == outside_before
    assert (moved / '.project.json').read_bytes() == before
    assert list(moved.glob('.project-*.tmp')) == []


def test_metadata_replacement_before_open_is_not_followed(registry):
    root, outside = registry
    project = projects.create_project('Normal')
    metadata = root / project['id'] / '.project.json'
    outside_before = (outside / '.project.json').read_bytes()
    original_open = os.open

    def replace_before_read(path, flags, *args, **kwargs):
        if path == '.project.json':
            metadata.unlink()
            metadata.symlink_to(outside / '.project.json')
        return original_open(path, flags, *args, **kwargs)

    with patch.object(os, 'open', replace_before_read):
        with pytest.raises(projects.ProjectPathError):
            projects.rename_project(project['id'], 'Blocked')
    assert (outside / '.project.json').read_bytes() == outside_before


@pytest.mark.parametrize('failure_at', ['write', 'fsync', 'replace'])
def test_failed_staging_preserves_metadata_and_cleans_only_its_file(registry, failure_at):
    root, _ = registry
    project = projects.create_project('Normal')
    folder = root / project['id']
    sentinel = folder / '.project-other-writer.tmp'
    sentinel.write_bytes(b'other writer')
    before = (folder / '.project.json').read_bytes()
    call = {'write': 'fdopen', 'fsync': 'fsync', 'replace': 'replace'}[failure_at]
    original = getattr(os, call)

    def fail(*args, **kwargs):
        if failure_at == 'write' and args[1] != 'w':
            return original(*args, **kwargs)
        raise OSError(errno.ENOSPC, 'Synthetic failure')

    with patch.object(os, call, side_effect=fail):
        with pytest.raises(OSError):
            projects.rename_project(project['id'], 'Blocked')
    assert (folder / '.project.json').read_bytes() == before
    assert sentinel.read_bytes() == b'other writer'
    assert list(folder.glob('.project-*.tmp')) == [sentinel]


def test_replaced_staging_entry_is_not_promoted(registry):
    root, outside = registry
    project = projects.create_project('Normal')
    folder = root / project['id']
    before = (folder / '.project.json').read_bytes()
    outside_before = (outside / '.project.json').read_bytes()
    original_fsync = os.fsync

    def replace_stage(descriptor):
        original_fsync(descriptor)
        temporary = next(folder.glob('.project-*.tmp'))
        temporary.unlink()
        temporary.symlink_to(outside / '.project.json')

    with patch.object(os, 'fsync', replace_stage):
        with pytest.raises(projects.ProjectPathError):
            projects.rename_project(project['id'], 'Blocked')
    assert (folder / '.project.json').read_bytes() == before
    assert (outside / '.project.json').read_bytes() == outside_before
    replacements = list(folder.glob('.project-*.tmp'))
    assert len(replacements) == 1 and replacements[0].is_symlink()


def test_unowned_staging_directory_does_not_mask_the_path_error(registry):
    root, _ = registry
    project = projects.create_project('Normal')
    folder = root / project['id']
    before = (folder / '.project.json').read_bytes()
    original_fsync = os.fsync

    def replace_stage(descriptor):
        original_fsync(descriptor)
        temporary = next(folder.glob('.project-*.tmp'))
        temporary.unlink()
        temporary.mkdir()

    with patch.object(os, 'fsync', replace_stage):
        with pytest.raises(projects.ProjectPathError):
            projects.rename_project(project['id'], 'Blocked')
    assert (folder / '.project.json').read_bytes() == before
    replacements = list(folder.glob('.project-*.tmp'))
    assert len(replacements) == 1 and replacements[0].is_dir()


def test_symlink_promoted_by_an_external_writer_cannot_receive_a_success_receipt(registry):
    root, outside = registry
    project = projects.create_project('Normal')
    folder = root / project['id']
    before = (outside / '.project.json').read_bytes()
    original_replace = os.replace

    def swap_then_promote(source, destination, **kwargs):
        temporary = folder / source
        temporary.unlink()
        temporary.symlink_to(outside / '.project.json')
        original_replace(source, destination, **kwargs)

    with patch.object(os, 'replace', swap_then_promote):
        with pytest.raises(projects.ProjectPathError):
            projects.rename_project(project['id'], 'Blocked')
    assert (outside / '.project.json').read_bytes() == before
    assert not projects.exists(project['id'])


def test_concurrent_renames_use_separate_staging_and_return_their_own_name(registry):
    root, _ = registry
    project = projects.create_project('Normal')
    with ThreadPoolExecutor(max_workers=8) as executor:
        names = [f'Rename {i}' for i in range(24)]
        receipts = list(executor.map(lambda name: projects.rename_project(project['id'], name), names))
    assert [receipt['name'] for receipt in receipts] == names
    assert projects.get_project(project['id'])['name'] in names
    assert list((root / project['id']).glob('.project-*.tmp')) == []


def test_timestamp_scan_does_not_follow_external_files_or_directories(registry):
    root, outside = registry
    project = projects.create_project('Normal')
    folder = root / project['id']
    (folder / 'outside').symlink_to(outside, target_is_directory=True)
    (folder / 'outside.json').symlink_to(outside / '.project.json')
    future = int(time.time()) + 86400
    os.utime(outside / '.project.json', (future, future))
    assert projects.get_project(project['id'])['updated'] < future


def test_broken_alias_does_not_steal_a_create_slug(registry):
    root, outside = registry
    (root / 'normal').symlink_to(outside / 'missing', target_is_directory=True)
    project = projects.create_project('Normal')
    assert project['id'] == 'normal-2'
    assert (root / 'normal').is_symlink()
    assert not (outside / 'missing').exists()


def test_trash_alias_cannot_redirect_deletion(registry):
    root, outside = registry
    project = projects.create_project('Normal')
    (root / workspace.TRASH_DIRNAME).symlink_to(outside, target_is_directory=True)
    before = sorted(path.name for path in outside.iterdir())
    with pytest.raises(projects.ProjectPathError):
        projects.delete_project(project['id'])
    assert projects.exists(project['id'])
    assert sorted(path.name for path in outside.iterdir()) == before


def test_detected_trash_replacement_does_not_delete_the_project(registry):
    root, outside = registry
    project = projects.create_project('Normal')
    (root / workspace.TRASH_DIRNAME).mkdir()
    moved = outside / 'moved-trash'
    original_mkdir = os.mkdir

    def replace_before_reservation(path, *args, **kwargs):
        if str(path).endswith('-' + project['id']):
            (root / workspace.TRASH_DIRNAME).rename(moved)
            (root / workspace.TRASH_DIRNAME).symlink_to(outside, target_is_directory=True)
        return original_mkdir(path, *args, **kwargs)

    with patch.object(os, 'mkdir', replace_before_reservation):
        with pytest.raises(projects.ProjectPathError):
            projects.delete_project(project['id'])
    assert projects.exists(project['id'])
    assert list(moved.iterdir()) == []


def test_trash_relocation_after_promotion_preserves_data_and_rejects_the_receipt(registry):
    root, outside = registry
    project = projects.create_project('Normal')
    (root / project['id'] / 'note.txt').write_bytes(b'preserve me')
    moved = outside / 'moved-trash'
    original_rename = os.rename

    def relocate_then_rename(source, destination, **kwargs):
        original_rename(root / workspace.TRASH_DIRNAME, moved)
        (root / workspace.TRASH_DIRNAME).symlink_to(outside, target_is_directory=True)
        original_rename(source, destination, **kwargs)

    with patch.object(os, 'rename', relocate_then_rename):
        with pytest.raises(projects.ProjectPathError):
            projects.delete_project(project['id'])
    assert len(list(moved.iterdir())) == 1
    archived = next(moved.iterdir())
    assert (archived / 'note.txt').read_bytes() == b'preserve me'


def test_normal_delete_preserves_project_files_in_local_trash(registry):
    root, _ = registry
    project = projects.create_project('Normal')
    (root / project['id'] / 'note.txt').write_bytes(b'preserve me')
    receipt = projects.delete_project(project['id'])
    assert receipt['ok']
    assert (root / receipt['trashed'] / 'note.txt').read_bytes() == b'preserve me'
    assert projects.list_projects() == []


def test_api_rejects_links_with_400_and_does_not_expose_outside_name(registry):
    root, outside = registry
    (root / 'alias').symlink_to(outside, target_is_directory=True)
    before = (outside / '.project.json').read_bytes()
    # No lifespan or providers are needed to exercise the actual project routes.
    client = TestClient(main.app)
    try:
        assert client.get('/api/projects').json() == {'projects': []}
        assert client.post('/api/projects/alias/rename', json={'name': 'Blocked'}).status_code == 400
        assert client.delete('/api/projects/alias').status_code == 400
    finally:
        client.close()
    assert (outside / '.project.json').read_bytes() == before


def test_project_locale_keys_have_both_languages():
    assert STRINGS['uk'].keys() == STRINGS['en'].keys()
