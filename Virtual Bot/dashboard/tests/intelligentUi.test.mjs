import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { intelligentUiReceipt, readIntelligentUiSource, sandboxMessage } from '../src/panels/chat/intelligentUiData.ts';
import { intelligentUiDocument } from '../src/panels/chat/intelligentUiSandbox.ts';

const id = 'a'.repeat(32);
const source = JSON.stringify({ version: 1, html: '<output>60</output>', css: '', javascript: '' });
const sha256 = createHash('sha256').update(source).digest('hex');
const result = { ok: true, ui: { version: 1, kind: 'intelligent_ui', id, data: { title: 'Split a bill', summary: 'Change the number of people.', height: 360, path: `sessions/example/interactive/${id}.json`, sha256 } } };
const step = { id: 'call', label: 'tools__render_interactive_ui', detail: '', status: 'done', result };

test('canonical receipts survive the live and saved MCP result forms', () => {
  for (const value of [result, { result }, { structuredContent: result }, { content: [{ type: 'text', text: JSON.stringify({ result }) }] }]) {
    assert.equal(intelligentUiReceipt({ ...step, result: value })?.id, id);
  }
});

test('unconfirmed, malformed and unrelated tool calls never select executable content', () => {
  for (const status of ['active', 'interrupted', 'failed']) assert.equal(intelligentUiReceipt({ ...step, status }), null);
  assert.equal(intelligentUiReceipt({ ...step, label: 'workspace_read' }), null);
  assert.equal(intelligentUiReceipt({ ...step, result: { ...result, ok: false } }), null);
  for (const path of ['../private.json', '/api/status', `sessions/example/interactive/${'b'.repeat(32)}.json`, `sessions/example/interactive/${id}Xjson`]) {
    assert.equal(intelligentUiReceipt({ ...step, result: { ...result, ui: { ...result.ui, data: { ...result.ui.data, path } } } }), null);
  }
});

test('a failed outer transport cannot promote a successful nested receipt', () => {
  for (const wrapped of [
    { isError: true, result }, { ok: false, structuredContent: result },
    { result: { ...result, success: false } },
    { content: [{ type: 'text', text: JSON.stringify({ result: { ...result, ok: false } }) }] },
  ]) assert.equal(intelligentUiReceipt({ ...step, result: wrapped }), null);
});

test('artifact integrity is checked before generated code can execute', async () => {
  assert.equal((await readIntelligentUiSource(source, sha256)).html, '<output>60</output>');
  await assert.rejects(readIntelligentUiSource(source.replace('60', '90'), sha256), /artifact_changed/);
  const invalid = JSON.stringify({ version: 1, html: 42, css: '', javascript: '' });
  await assert.rejects(readIntelligentUiSource(invalid, createHash('sha256').update(invalid).digest('hex')), /invalid_artifact/);
});

test('Unicode artifacts verify on LAN HTTP without SubtleCrypto', async () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
  const unicode = JSON.stringify({ version: 1, html: '<p>Привіт 🌍</p>', css: '', javascript: '' });
  const digest = createHash('sha256').update(unicode).digest('hex');
  try {
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: {} });
    assert.equal((await readIntelligentUiSource(unicode, digest)).html, '<p>Привіт 🌍</p>');
    await assert.rejects(readIntelligentUiSource(unicode + ' ', digest), /artifact_changed/);
  } finally { Object.defineProperty(globalThis, 'crypto', previous); }
});

test('the sandbox bridge is channel-scoped, bounded and cannot submit a chat message', () => {
  assert.equal(sandboxMessage({ channel: 'other', type: 'followup', text: 'hello' }, 'frame'), null);
  assert.equal(sandboxMessage({ channel: 'frame', type: 'send', text: 'hello' }, 'frame'), null);
  assert.equal(sandboxMessage({ channel: 'frame', type: 'followup', text: 'x'.repeat(2001) }, 'frame'), null);
  assert.equal(sandboxMessage({ channel: 'frame', type: 'resize', height: NaN }, 'frame'), null);
  assert.deepEqual(sandboxMessage({ channel: 'frame', type: 'resize', height: 9000 }, 'frame'), { type: 'resize', height: 720 });
  assert.deepEqual(sandboxMessage({ channel: 'frame', type: 'followup', text: '  Explain  ' }, 'frame'), { type: 'followup', text: 'Explain' });
});

test('host CSP and bootstrap cannot be escaped by source or theme values', () => {
  const document = intelligentUiDocument({ version: 1, html: '</script><script>alert(1)</script>', css: '</style>', javascript: '</script>' }, 'safe-channel', { '--text': '</script>' }, 'en');
  assert.equal((document.match(/<script\b/g) || []).length, 1);
  assert.equal((document.match(/<\/script>/g) || []).length, 1);
  assert.match(document, /connect-src 'none'/);
  assert.match(document, /form-action 'none'/);
  assert.match(document, /script-src 'nonce-safe-channel'/);
});
