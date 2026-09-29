import { test } from 'node:test';
import assert from 'node:assert/strict';
import { collectFiles, currentStep, fileKind, shortPath } from '../src/panels/chat/workFiles.ts';

const reply = (...steps) => ({ id: 'a', role: 'assistant', content: '', steps });
const step = (label, path, extra = {}) => ({ id: `${label}-${path}`, label, detail: path, status: 'done', ...extra });

test('files come from both brains, newest first, one tab per path', () => {
  const files = collectFiles([
    reply(step('workspace_write', 'notes/a.md')),
    reply(step('workspace__workspace_write', 'games/snake.html'), step('workspace_write', 'notes/a.md')),
  ]);
  assert.deepEqual(files.map((f) => f.path), ['notes/a.md', 'games/snake.html']);
  // Every write bumps the revision, so an open preview reloads after an edit.
  assert.equal(files[0].revision, 2);
});

test('a write in flight is marked active and reloads only once it lands', () => {
  const writing = collectFiles([reply(step('workspace_write', 'a.html', { status: 'active' }))]);
  assert.deepEqual([writing[0].active, writing[0].revision], [true, 0]);
  const landed = collectFiles([reply(step('workspace_write', 'a.html'))]);
  assert.deepEqual([landed[0].active, landed[0].revision], [false, 1]);
});

test('failed writes and deleted files are not offered as tabs', () => {
  const files = collectFiles([reply(
    step('workspace_write', 'broken.html', { status: 'failed', result: { error: 'Потрібен вхід' } }),
    step('workspace_write', 'gone.md'),
    step('workspace_delete', 'gone.md'),
    step('workspace_read', 'read-only.md'),
  )]);
  assert.deepEqual(files, []);
});

test('the real session folder folds back into the short session/ form', () => {
  // The bot asks for session/plan.md; OpenClaw's MCP result names the real
  // folder inside a text block. Both must be the same tab.
  const mcpResult = { content: [{ type: 'text', text: '{"ok": true, "path": "sessions/abc123/plan.md"}' }] };
  const files = collectFiles([reply(
    step('workspace_write', 'session/plan.md'),
    step('workspace__workspace_write', 'session/plan.md', { result: mcpResult }),
  )], 'sessions/abc123');
  assert.deepEqual(files.map((f) => f.path), ['session/plan.md']);
  assert.equal(shortPath('sessions/other/x.md', 'sessions/abc123'), 'sessions/other/x.md');
  // The workbench folds whatever it holds on every render (a show event names
  // the long form, a tab click the short one), so folding twice must be a no-op.
  assert.equal(shortPath(shortPath('sessions/abc123/plan.md', 'sessions/abc123'), 'sessions/abc123'), 'session/plan.md');
  assert.equal(shortPath('session/plan.md', ''), 'session/plan.md');
});

test('the input path is used when the saved result is empty', () => {
  const files = collectFiles([reply(step('workspace_write', '', { input: { path: 'diagrams/bot.excalidraw', content: '{}' }, result: {} }))]);
  assert.equal(files[0].path, 'diagrams/bot.excalidraw');
  assert.equal(files[0].kind, 'drawing');
});

test('file kinds pick the right view', () => {
  assert.equal(fileKind('a/B.HTML'), 'html');
  assert.equal(fileKind('scene.excalidraw.json'), 'drawing');
  assert.equal(fileKind('flow.mmd'), 'mermaid');
  assert.equal(fileKind('x.json'), 'code');
  assert.equal(fileKind('photo.webp'), 'image');
  assert.equal(fileKind('README'), 'text');
});

test('the live line only follows a reply that is still streaming', () => {
  const active = { id: 's', label: 'web_search', detail: 'crabs', status: 'active' };
  assert.equal(currentStep([{ ...reply(active), id: 'draft' }])?.label, 'web_search');
  assert.equal(currentStep([reply(active)]), null);
});
