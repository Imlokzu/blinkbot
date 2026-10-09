import assert from 'node:assert/strict';
import test from 'node:test';

let instance = 0;
const documentValue = (id = 'document-a', extra = {}) => ({
  id, path: 'notes/plan.md', kind: 'markdown', content: 'Original bytes.\n',
  readOnly: false, theme: 'light', language: 'en', saveState: 'saved', ...extra,
});
const pngDataUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';

async function setup(context) {
  const previous = globalThis.window;
  const messages = [];
  globalThis.window = { BlinkNative: { postMessage: raw => messages.push(JSON.parse(raw)) } };
  context.after(() => {
    if (previous === undefined) delete globalThis.window;
    else globalThis.window = previous;
  });
  const bridge = await import(`../src/bridge.ts?test=${++instance}`);
  bridge.installBridge();
  return { bridge, api: window.BlinkWorkspace, messages };
}

test('opening and flushing untouched documents never normalize their content', async context => {
  const { bridge, api, messages } = await setup(context);
  const document = documentValue();
  assert.deepEqual(messages, [{ type: 'ready' }]);
  api.openDocument(document);
  api.flush('close');
  assert.equal(bridge.getSnapshot().document.content, document.content);
  assert.deepEqual(messages.slice(1), [{ type: 'flushed', id: document.id, token: 'close' }]);
});

test('metadata-only host updates preserve current draft, session and change sequence', async context => {
  const { bridge, api, messages } = await setup(context);
  const document = documentValue();
  api.openDocument(document);
  const session = bridge.getSnapshot().session;
  bridge.changeDocument(session, 'Unsaved typing.');
  const count = messages.length;
  api.updateHost({ id: document.id, saveState: 'saving', theme: 'dark', language: 'uk' });
  assert.equal(bridge.getSnapshot().document.content, 'Unsaved typing.');
  assert.equal(bridge.getSnapshot().session, session);
  assert.deepEqual(bridge.getSnapshot().document, { ...document, content: 'Unsaved typing.', saveState: 'saving', theme: 'dark', language: 'uk' });
  api.updateHost({ ...document, content: 'Older acknowledgement.', saveState: 'saved' });
  assert.equal(bridge.getSnapshot().document.content, 'Unsaved typing.');
  api.updateHost({ id: 'another-document', readOnly: true });
  assert.equal(bridge.getSnapshot().document.readOnly, false);
  assert.equal(messages.length, count, 'status acknowledgements must not appear as new user edits');
  bridge.changeDocument(session, 'Next edit.');
  assert.equal(messages.at(-1).sequence, 2);
});

test('an oversized unsent draft blocks close until the user returns to valid content', async context => {
  const { bridge, api, messages } = await setup(context);
  const document = documentValue();
  api.openDocument(document);
  const session = bridge.getSnapshot().session;
  // Four UTF-8 bytes per character catches implementations that count UTF-16 units.
  bridge.changeDocument(session, '\u{1f680}'.repeat(500_001));
  assert.equal(bridge.getSnapshot().document.content, document.content);
  assert.deepEqual(messages.at(-1), { type: 'error', id: document.id, code: 'document_too_large' });
  api.flush('blocked');
  assert.equal(bridge.flushCurrent(session), false, 'source/rich switches must also retain an oversized unsent draft');
  api.updateHost({ id: document.id, saveState: 'saved' });
  api.flush('still-blocked');
  assert.equal(messages.some(message => message.type === 'flushed'), false, 'native must retain the editor while a draft cannot cross the bridge');
  assert.equal(messages.some(message => message.type === 'change'), false);
  bridge.changeDocument(session, document.content);
  assert.equal(bridge.flushCurrent(session), true);
  api.flush('recovered');
  assert.deepEqual(messages.at(-1), { type: 'flushed', id: document.id, token: 'recovered' });
});

test('the 2 MB limit accepts its boundary and rejects oversized document replacement', async context => {
  const { bridge, api, messages } = await setup(context);
  const document = documentValue();
  api.openDocument(document);
  const session = bridge.getSnapshot().session;
  const boundary = 'x'.repeat(2_000_000);
  bridge.changeDocument(session, boundary);
  assert.equal(messages.at(-1).type, 'change');
  assert.equal(messages.at(-1).content.length, 2_000_000);
  api.flush('boundary');
  assert.equal(messages.at(-1).type, 'flushed');
  api.openDocument(documentValue('oversized-open', { content: `${boundary}x` }));
  assert.equal(bridge.getSnapshot().session, session);
  assert.equal(messages.at(-1).code, 'document_too_large');
});

test('detached generations cannot change, flush, report errors or resolve actions into the current document', async context => {
  const { bridge, api, messages } = await setup(context);
  api.openDocument(documentValue('old'));
  const old = bridge.getSnapshot().session;
  const resolved = [];
  bridge.action(old, 'createDrawing', {}, path => resolved.push(path));
  const pending = messages.at(-1);
  let oldFlush = 0;
  const unregisterOld = bridge.registerFlush(old, () => { oldFlush += 1; });
  api.openDocument(documentValue('new'));
  const current = bridge.getSnapshot().session;
  let currentFlush = 0;
  bridge.registerFlush(current, () => { currentFlush += 1; });
  const count = messages.length;
  bridge.changeDocument(old, 'Stale content');
  assert.equal(bridge.flushCurrent(old), false);
  bridge.editorError(old, 'stale_error');
  bridge.action(old, 'createDrawing');
  bridge.registerFlush(old, () => { oldFlush += 1; });
  unregisterOld();
  api.resolveAction({ id: 'old', sequence: pending.sequence, path: 'old.excalidraw' });
  assert.equal(messages.length, count);
  assert.deepEqual(resolved, []);
  api.flush('new-close');
  assert.equal(oldFlush, 0);
  assert.equal(currentFlush, 1, 'old cleanup must not clear the replacement editor flush handler');
  assert.equal(messages.at(-1).id, 'new');
  api.openDocument(documentValue('old'));
  assert.notEqual(bridge.getSnapshot().session.generation, old.generation);
  bridge.changeDocument(old, 'Stale content with the same ID');
  assert.equal(bridge.getSnapshot().document.content, 'Original bytes.\n');
});

test('changes and actions share a monotonic sequence and action replies are consumed once', async context => {
  const { bridge, api, messages } = await setup(context);
  api.openDocument(documentValue());
  const session = bridge.getSnapshot().session;
  const resolved = [];
  bridge.changeDocument(session, 'First edit');
  bridge.action(session, 'createDrawing', {}, path => resolved.push(path));
  const action = messages.at(-1);
  bridge.changeDocument(session, 'Second edit');
  bridge.action(session, 'openWorkspace', { path: 'notes/other.md' });
  assert.deepEqual(messages.filter(message => 'sequence' in message).map(message => message.sequence), [1, 2, 3, 4]);
  api.resolveAction({ id: 'wrong-id', sequence: action.sequence, path: 'wrong.excalidraw' });
  api.resolveAction({ id: session.id, sequence: 99, path: 'wrong.excalidraw' });
  assert.deepEqual(resolved, []);
  api.resolveAction({ id: session.id, sequence: action.sequence, path: 'notes/new.excalidraw' });
  api.resolveAction({ id: session.id, sequence: action.sequence, path: 'notes/duplicate.excalidraw' });
  assert.deepEqual(resolved, ['notes/new.excalidraw']);
});

test('read-only documents deny edits and creation while Mermaid may export a drawing', async context => {
  const { bridge, api, messages } = await setup(context);
  api.openDocument(documentValue('readonly', { readOnly: true }));
  let session = bridge.getSnapshot().session;
  bridge.changeDocument(session, 'Forbidden edit');
  bridge.action(session, 'createDrawing');
  bridge.action(session, 'convertMermaid', { content: '{}' });
  assert.deepEqual(messages, [{ type: 'ready' }]);
  bridge.action(session, 'openExternal', { path: 'http://example.invalid' });
  bridge.action(session, 'openExternal', { path: 'javascript:alert(1)' });
  assert.equal(messages.length, 1);
  bridge.action(session, 'openExternal', { path: 'https://example.invalid/docs' });
  assert.equal(messages.at(-1).action, 'openExternal');
  api.openDocument(documentValue('mermaid', { readOnly: true, kind: 'mermaid', content: 'flowchart LR\n A-->B' }));
  session = bridge.getSnapshot().session;
  bridge.action(session, 'convertMermaid', { content: '{"type":"excalidraw","elements":[]}' });
  assert.equal(messages.at(-1).action, 'convertMermaid');
  assert.equal(messages.at(-1).id, 'mermaid');
});

test('flush exceptions report failure without acknowledging destructive navigation', async context => {
  const { bridge, api, messages } = await setup(context);
  api.openDocument(documentValue());
  bridge.registerFlush(bridge.getSnapshot().session, () => { throw new Error('Synthetic flush failure'); });
  api.flush('close');
  assert.equal(messages.at(-1).code, 'editor_failed');
  assert.equal(messages.some(message => message.type === 'flushed'), false);
});

test('drawing and Mermaid PNG exports are allowed without editing read-only source', async context => {
  const { bridge, api, messages } = await setup(context);
  for (const kind of ['drawing', 'mermaid']) {
    for (const readOnly of [false, true]) {
      const document = documentValue(`export-${kind}-${readOnly}`, { kind, readOnly });
      api.openDocument(document);
      const session = bridge.getSnapshot().session;
      bridge.action(session, 'exportDrawing', { content: pngDataUrl });
      assert.deepEqual(messages.at(-1), { type: 'action', action: 'exportDrawing', id: document.id, sequence: 1, content: pngDataUrl });
      assert.equal(bridge.getSnapshot().document.content, document.content);
    }
  }
  assert.equal(messages.some(message => message.type === 'change'), false);
});

test('ordinary formats and stale documents cannot emit drawing export actions', async context => {
  const { bridge, api, messages } = await setup(context);
  for (const kind of ['markdown', 'code', 'text', 'html']) {
    for (const readOnly of [false, true]) {
      api.openDocument(documentValue(`unsupported-${kind}-${readOnly}`, { kind, readOnly }));
      bridge.action(bridge.getSnapshot().session, 'exportDrawing', { content: pngDataUrl });
    }
  }
  assert.equal(messages.some(message => message.type === 'action'), false);
  api.openDocument(documentValue('export-old', { kind: 'drawing' }));
  const detached = bridge.getSnapshot().session;
  api.openDocument(documentValue('export-new', { kind: 'drawing' }));
  bridge.action(detached, 'exportDrawing', { content: pngDataUrl });
  assert.equal(messages.some(message => message.type === 'action'), false);
  bridge.action(bridge.getSnapshot().session, 'exportDrawing', { content: pngDataUrl });
  assert.equal(messages.at(-1).id, 'export-new');
  assert.equal(messages.at(-1).sequence, 1, 'rejected actions must not consume the current document sequence');
});

test('drawing exports reject destinations and malformed or oversized payloads without failing the editor', async context => {
  const { bridge, api, messages } = await setup(context);
  api.openDocument(documentValue('export-validation', { kind: 'drawing', readOnly: true }));
  const session = bridge.getSnapshot().session;
  for (const extra of [
    {}, { content: '' }, { content: 'data:image/png;base64,' },
    { content: pngDataUrl.replace('image/png', 'image/jpeg') },
    { content: pngDataUrl.replace('image/png', 'image/svg+xml') },
    { content: 'data:image/png;base64,not base64!' },
    { content: 'data:image/png;base64,AAAA=BBB' },
    { content: 'data:image/png;base64,A' },
    { content: `data:image/png;base64,${'A'.repeat(2_000_000)}` },
    { content: pngDataUrl, path: 'export.png' },
  ]) bridge.action(session, 'exportDrawing', extra);
  assert.deepEqual(messages, [{ type: 'ready' }], 'invalid exports are rejected without poisoning document editing');
  bridge.action(session, 'exportDrawing', { content: pngDataUrl });
  assert.equal(messages.at(-1).sequence, 1);
  assert.equal(messages.at(-1).action, 'exportDrawing');
  api.flush('still-usable');
  assert.deepEqual(messages.at(-1), { type: 'flushed', id: session.id, token: 'still-usable' });
});
