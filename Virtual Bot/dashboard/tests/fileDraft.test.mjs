import assert from 'node:assert/strict';
import test from 'node:test';
import { editDraft, EMPTY_FILE_DRAFT, receiveFile, saveCompleted } from '../src/panels/files/fileDraft.ts';

test('same-file refresh preserves dirty text and records an external change', () => {
  let state = receiveFile(EMPTY_FILE_DRAFT, 'notes/today.md', 'server v1');
  state = editDraft(state, 'my unsaved edit');
  const refreshed = receiveFile(state, 'notes/today.md', 'server v2');
  assert.equal(refreshed.draft, 'my unsaved edit');
  assert.equal(refreshed.baseline, 'server v2');
  assert.equal(refreshed.dirty, true);
  assert.equal(refreshed.external, true);
});

test('clean refresh follows the server and a different file starts clean', () => {
  let state = receiveFile(EMPTY_FILE_DRAFT, 'a.txt', 'one');
  state = receiveFile(state, 'a.txt', 'two');
  assert.deepEqual(state, { path: 'a.txt', draft: 'two', baseline: 'two', dirty: false, external: false });
  assert.equal(receiveFile(state, 'b.txt', 'other').draft, 'other');
});

test('a delayed save cannot clear newer typing', () => {
  let state = editDraft(receiveFile(EMPTY_FILE_DRAFT, 'notes.md', 'old'), 'first save');
  state = editDraft(state, 'newer edit');
  const after = saveCompleted(state, 'notes.md', 'first save');
  assert.equal(after.draft, 'newer edit');
  assert.equal(after.dirty, true);
  assert.equal(after.baseline, 'first save');
});

test('a delayed save for an old file cannot change the newly selected file', () => {
  let state = receiveFile(EMPTY_FILE_DRAFT, 'old.md', 'old');
  state = receiveFile(state, 'new.md', 'new');
  assert.deepEqual(saveCompleted(state, 'old.md', 'saved old'), state);
});

test('switching away and back keeps an old save visibly dirty until the file is refreshed', () => {
  let state = editDraft(receiveFile(EMPTY_FILE_DRAFT, 'a.md', 'old'), 'saved from A');
  state = receiveFile(state, 'b.md', 'other');
  state = receiveFile(state, 'a.md', 'old');
  const after = saveCompleted(state, 'a.md', 'saved from A');
  assert.equal(after.draft, 'old');
  assert.equal(after.baseline, 'saved from A');
  assert.equal(after.dirty, true);
});
