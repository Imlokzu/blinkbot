import assert from 'node:assert/strict';
import test from 'node:test';
import { isFileTransfer, transferredFiles } from '../src/panels/chat/useChatFileDrop.ts';

test('OS file drags show feedback before their protected files become readable', () => {
  const transfer = { types: ['Files'] };
  Object.defineProperty(transfer, 'files', { get() { throw new Error('Protected drag data'); } });
  assert.equal(isFileTransfer(transfer), true);
  assert.equal(isFileTransfer({ types: ['text/plain', 'text/uri-list'] }), false);
  assert.equal(isFileTransfer(null), false);
});

test('dropped files retain order and bytes without following URLs or folders', () => {
  const first = new File(['first'], 'first.md', { type: 'text/markdown' });
  const second = new File(['second'], 'second.pdf', { type: 'application/pdf' });
  const item = file => ({ kind: 'file', getAsFile: () => file, webkitGetAsEntry: () => ({ isDirectory: false }) });
  const transfer = { items: [
    item(first),
    { kind: 'string', getAsFile: () => null },
    { kind: 'file', getAsFile: () => new File([], 'folder'), webkitGetAsEntry: () => ({ isDirectory: true }) },
    item(null), item(second),
  ] };
  assert.deepEqual(transferredFiles(transfer), [first, second]);
});

test('file-only transfer implementations use their file list and empty drops stay empty', () => {
  const file = new File(['draft'], 'draft.txt');
  assert.deepEqual(transferredFiles({ items: [], files: [file] }), [file]);
  assert.deepEqual(transferredFiles({ items: [], files: [] }), []);
});
