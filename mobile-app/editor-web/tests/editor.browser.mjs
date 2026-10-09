/** Exercise the packaged editors, not a development server or an owner's workspace. */
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { request } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { startFixtureServer } from './fixture-server.mjs';

const run = promisify(execFile);
const session = `blink-editor-${process.pid}`;
const rich = '[data-testid="rich-editor"][contenteditable="true"], [data-testid="rich-editor"] [contenteditable="true"]';
const source = '[data-testid="source-editor"] .cm-content';
const drawing = '[data-testid="drawing-editor"]';
const screenshots = process.env.BLINK_EDITOR_SHOTS;
const scenarioFilter = process.env.BLINK_EDITOR_SCENARIO;
const staleExportIds = new Set();
const markdown = '# Project plan\n\nOpening a file must preserve its bytes.\n\n| Item | Quantity |\n| --- | --- |\n| Laptop | 2 |\n\n- [x] Read the brief\n- [ ] Review the table\n\nFinal notes.\n';
let fixture;
let browserStarted = false;

async function browser(...args) {
  const { stdout } = await run('agent-browser', ['--session', session, '--allowed-domains', '127.0.0.1', ...args], {
    encoding: 'utf8', timeout: 45_000, maxBuffer: 8 * 1024 * 1024,
  });
  return stdout;
}
async function evaluate(code) {
  const result = JSON.parse(await browser('--json', 'eval', '-b', Buffer.from(`(async () => { ${code} })()`).toString('base64')));
  assert.equal(result.success, true, result.error);
  return result.data.result;
}
const query = selector => `document.querySelector(${JSON.stringify(selector)})`;
const messages = () => evaluate('return window.__fixture.messages');
const changes = async id => (await messages()).filter(message => message.type === 'change' && message.id === id);
const latestChange = async id => (await changes(id)).at(-1);
const settle = () => evaluate('await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
const clickButton = name => browser('find', 'role', 'button', 'click', '--name', name, '--exact');
const clickTab = name => browser('find', 'role', 'tab', 'click', '--name', name, '--exact');
const screenshot = name => screenshots ? browser('screenshot', `${screenshots}/${name}.png`) : Promise.resolve();

function documentValue(id, kind, content, extra = {}) {
  return { id, kind, path: `notes/${id}.${kind === 'markdown' ? 'md' : kind === 'drawing' ? 'excalidraw' : 'txt'}`, content, readOnly: false, theme: 'light', language: 'en', saveState: 'saved', ...extra };
}
async function openDocument(document, selector) {
  await evaluate(`window.__fixture.document = ${JSON.stringify(document)}; window.BlinkWorkspace.openDocument(window.__fixture.document)`);
  if (selector) await browser('wait', selector);
  await settle();
}
async function flush(id, token) {
  await evaluate(`window.BlinkWorkspace.flush(${JSON.stringify(token)})`);
  await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'flushed' && message.id === ${JSON.stringify(id)} && message.token === ${JSON.stringify(token)})`);
  return messages();
}
async function append(selector, text) {
  // Selection setup is DOM-only; the actual edit uses the browser's keyboard input.
  await evaluate(`const element = ${query(selector)}; element.focus();
    const range = document.createRange(); range.selectNodeContents(element); range.collapse(false);
    const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);`);
  await browser('type', selector, text);
}
async function expectNoRewrite(document) {
  await flush(document.id, `untouched-${document.id}`);
  assert.deepEqual(await changes(document.id), [], `opening ${document.kind} must not normalize or save the source`);
}
async function scenario(name, work) {
  if (scenarioFilter && !new RegExp(scenarioFilter).test(name)) return;
  await work();
  console.log(`PASS: ${name}`);
}
async function rawStatus(path) {
  return new Promise((resolve, reject) => {
    const call = request(`${fixture.origin}`, { path }, response => { response.resume(); resolve(response.statusCode); });
    call.on('error', reject);
    call.end();
  });
}

function pngBytes(message, id) {
  assert.equal(message.type, 'action');
  assert.equal(message.action, 'exportDrawing');
  assert.equal(message.id, id);
  assert.ok(Number.isSafeInteger(message.sequence) && message.sequence > 0);
  assert.ok(Buffer.byteLength(message.content, 'utf8') <= 2_000_000, 'the entire PNG data URL must fit the native bridge budget');
  const encoded = /^data:image\/png;base64,([A-Za-z0-9+/]+={0,2})$/.exec(message.content);
  assert.ok(encoded, 'exports must declare PNG MIME with base64 encoding');
  const bytes = Buffer.from(encoded[1], 'base64');
  assert.ok(bytes.length > 33, 'exported PNG must contain more than its header');
  assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.equal(bytes.readUInt32BE(8), 13);
  assert.equal(bytes.subarray(12, 16).toString('ascii'), 'IHDR');
  for (const dimension of [bytes.readUInt32BE(16), bytes.readUInt32BE(20)]) {
    assert.ok(dimension >= 1 && dimension <= 4096, `PNG dimension ${dimension} is outside the supported range`);
  }
  assert.equal(bytes.subarray(-8, -4).toString('ascii'), 'IEND', 'the PNG must not be truncated');
  return bytes;
}

try {
  if (process.platform === 'darwin') await run('osascript', ['-e', 'set volume output muted true']);
  if (screenshots) await mkdir(screenshots, { recursive: true });
  fixture = await startFixtureServer();
  assert.equal(await rawStatus('/editor/%2e%2e/HANDOFF.md'), 400, 'fixture must reject traversal without normalizing it away');
  assert.equal(await rawStatus('/api/workspace/file'), 404, 'the fixture exposes no backend API');
  browserStarted = true;
  await browser('open', `${fixture.origin}/editor/`);
  await browser('set', 'viewport', '390', '844');
  await browser('wait', '--fn', 'window.__fixture?.messages.some(message => message.type === "ready") && !!window.BlinkWorkspace');

  await scenario('Markdown edits preserve tables and tasks through source switching and reopen', async () => {
    const document = documentValue('markdown-roundtrip', 'markdown', markdown);
    await openDocument(document, rich);
    await expectNoRewrite(document);
    await screenshot('markdown-phone');
    assert.equal(await evaluate('return document.querySelectorAll("[data-testid=rich-editor] table").length'), 1);
    assert.equal(await evaluate('return document.querySelectorAll("[data-testid=rich-editor] input[type=checkbox]").length'), 2);
    await append(rich, ' Reviewed on mobile.');
    await append('[data-testid="rich-editor"] td:first-of-type', ' Pro');
    await browser('click', '[data-testid="rich-editor"] input[type="checkbox"]:not(:checked)');
    await clickTab('Source');
    await browser('wait', source);
    const serialized = await latestChange(document.id);
    assert.match(serialized.content, /Reviewed on mobile\./);
    assert.match(serialized.content, /\|\s*Laptop Pro\s*\|\s*2\s*\|/);
    assert.match(serialized.content, /- \[x\] Read the brief/);
    assert.match(serialized.content, /- \[x\] Review the table/);
    assert.match(await evaluate(`return ${query(source)}.textContent`), /Reviewed on mobile\./);
    await clickTab('Document');
    await browser('wait', rich);
    assert.match(await evaluate(`return ${query(rich)}.textContent`), /Reviewed on mobile\./);
    await openDocument({ ...document, id: 'markdown-reopened', content: serialized.content }, rich);
    assert.equal(await evaluate('return document.querySelectorAll("[data-testid=rich-editor] input[type=checkbox]:checked").length'), 2);
    await expectNoRewrite({ ...document, id: 'markdown-reopened' });
  });

  await scenario('CodeMirror typing, undo, redo and search operate on real source', async () => {
    const initial = 'const answer = 41;\n// Searchable original\n';
    const document = documentValue('source-history', 'code', initial, { path: 'src/answer.ts' });
    await openDocument(document, source);
    await expectNoRewrite(document);
    await append(source, 'const edited = answer + 1;');
    assert.match((await latestChange(document.id)).content, /edited = answer \+ 1/);
    await clickButton('Undo');
    assert.equal((await latestChange(document.id)).content, initial);
    await clickButton('Redo');
    assert.match((await latestChange(document.id)).content, /edited = answer \+ 1/);
    await clickButton('Find');
    await browser('wait', '.cm-search input[name="search"]');
    await browser('fill', '.cm-search input[name="search"]', 'answer');
    await browser('press', 'Enter');
    await browser('wait', '.cm-searchMatch, .cm-selectionMatch');
    await browser('press', 'Escape');
    assert.match((await latestChange(document.id)).content, /Searchable original/);
  });

  await scenario('Acknowledged host echoes preserve selection and do not emit another edit', async () => {
    const document = documentValue('host-echo', 'markdown', 'Keep the cursor here.\n');
    await openDocument(document, rich);
    await append(rich, ' Typed draft.');
    const edit = await latestChange(document.id);
    assert.match(edit.content, /Typed draft\./);
    const before = await evaluate(`const element = ${query(rich)}; const text = element.querySelector('p').firstChild;
      const range = document.createRange(); range.setStart(text, 2); range.setEnd(text, 7);
      const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
      return { text: selection.toString(), anchor: selection.anchorOffset, focus: selection.focusOffset };`);
    const count = (await changes(document.id)).length;
    await evaluate(`window.BlinkWorkspace.updateHost(${JSON.stringify({ id: document.id, saveState: 'saved' })})`);
    await settle();
    const after = await evaluate('const selection = getSelection(); return { text: selection.toString(), anchor: selection.anchorOffset, focus: selection.focusOffset }');
    assert.deepEqual(after, before, 'save acknowledgement must not replace the active editor');
    assert.equal((await changes(document.id)).length, count, 'acknowledgement must not echo back as a new change');
    await evaluate(`window.BlinkWorkspace.updateHost(${JSON.stringify({ id: document.id, saveState: 'failed' })})`);
    await browser('wait', '--text', 'Could not save');
    assert.equal((await changes(document.id)).length, count, 'failed save status must retain the same local draft');
  });

  await scenario('Immediate close flush delivers the last edit before its acknowledgement', async () => {
    const document = documentValue('close-flush', 'text', 'Starting value');
    await openDocument(document, source);
    await append(source, ' final keystroke');
    const events = await flush(document.id, 'close-now');
    const acknowledgement = events.findIndex(message => message.type === 'flushed' && message.id === document.id && message.token === 'close-now');
    const edits = events.flatMap((message, index) => message.type === 'change' && message.id === document.id ? [{ ...message, index }] : []);
    assert.ok(edits.length > 0);
    assert.equal(edits.at(-1).content, 'Starting value final keystroke');
    assert.ok(edits.every(edit => edit.index < acknowledgement), 'native may destroy the view immediately after flushed');
    assert.ok(edits.every((edit, index) => Number.isSafeInteger(edit.sequence) && edit.sequence > (edits[index - 1]?.sequence ?? 0)), 'change sequences must increase');
  });

  await scenario('HTML stays source text inside the privileged editor', async () => {
    const content = '<h1>Untrusted workspace page</h1><script>window.__workspaceHtmlExecuted = true</script><iframe src="https://example.invalid/hostile"></iframe>';
    const document = documentValue('html-source', 'html', content, { path: 'web/index.html' });
    await openDocument(document, source);
    await expectNoRewrite(document);
    assert.equal(await evaluate('return window.__workspaceHtmlExecuted === true'), false);
    assert.equal(await evaluate('return document.querySelectorAll("iframe").length'), 0, 'arbitrary HTML belongs to the separate untrusted native preview');
    assert.equal(await evaluate(`return ${query(source)}.textContent`), content);
  });

  await scenario('A document switch cannot attribute detached editor callbacks to the new file', async () => {
    const oldDocument = documentValue('switch-old', 'markdown', 'Old file.');
    const nextDocument = documentValue('switch-new', 'markdown', 'New file.');
    await openDocument(oldDocument, rich);
    await evaluate(`const old = ${query(rich)}; old.focus();
      const selection = getSelection(); selection.selectAllChildren(old); selection.collapseToEnd();
      document.execCommand('insertText', false, ' pending old edit');
      window.BlinkWorkspace.openDocument(${JSON.stringify(nextDocument)});
      old.textContent = 'Detached stale mutation';
      old.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: 'late' }));`);
    await browser('wait', rich);
    await settle();
    assert.deepEqual(await changes(nextDocument.id), [], 'opening a new ID must not inherit the old mutation');
    assert.equal(await evaluate(`return ${query(rich)}.textContent`), 'New file.');
    await append(rich, ' Current edit.');
    const events = await flush(nextDocument.id, 'switch-flush');
    assert.match((await latestChange(nextDocument.id)).content, /^New file\. Current edit\./);
    assert.ok(events.filter(event => event.type === 'change' && event.id === nextDocument.id).every(event => !/Old|Detached|pending/.test(event.content)));
  });

  await scenario('Nested notes resolve local images and created drawing links relative to their own directory', async () => {
    const document = documentValue('nested-note', 'markdown', '![Nested image](picture.png)\n\n![Folder image](./images/a.png)\n\n![Legacy session image](session/notes/old.png)\n\n![Shared image](notes/shared.png)\n\nTail.\n', { path: 'notes/nested/note.md' });
    await openDocument(document, rich);
    await browser('wait', '--fn', 'document.querySelectorAll("[data-testid=rich-editor] img").length === 4 && [...document.querySelectorAll("[data-testid=rich-editor] img")].every(image => image.complete && image.naturalWidth > 0)');
    assert.ok(fixture.requests.some(entry => entry.path === '/workspace/notes/nested/picture.png'));
    assert.ok(fixture.requests.some(entry => entry.path === '/workspace/notes/nested/images/a.png'));
    assert.ok(fixture.requests.some(entry => entry.path === '/workspace/session/notes/old.png'));
    assert.ok(fixture.requests.some(entry => entry.path === '/workspace/notes/shared.png'));
    assert.equal(fixture.requests.some(entry => entry.path === '/workspace/picture.png'), false, 'an existing root image must not shadow the nested one');
    await expectNoRewrite(document);
    await evaluate(`const element = ${query(rich)}; element.focus(); const selection = getSelection(); selection.selectAllChildren(element); selection.collapseToEnd()`);
    await clickButton('New drawing');
    await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'action' && message.id === ${JSON.stringify(document.id)} && message.action === 'createDrawing')`);
    const action = (await messages()).find(message => message.id === document.id && message.action === 'createDrawing');
    const path = 'notes/nested/note.md.drawings/new.excalidraw';
    await evaluate(`window.BlinkWorkspace.resolveAction(${JSON.stringify({ id: 'stale-document', sequence: action.sequence, path })})`);
    await settle();
    assert.deepEqual(await changes(document.id), [], 'an action response belongs to its originating document');
    await evaluate(`window.BlinkWorkspace.resolveAction(${JSON.stringify({ id: document.id, sequence: action.sequence, path })})`);
    await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'change' && message.id === ${JSON.stringify(document.id)})`);
    assert.match((await latestChange(document.id)).content, /!\[[^\]]*\]\(\.\/note\.md\.drawings\/new\.excalidraw\)/);
    await clickButton('Open drawing');
    await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'action' && message.id === ${JSON.stringify(document.id)} && message.action === 'openWorkspace')`);
    const opened = (await messages()).find(message => message.id === document.id && message.action === 'openWorkspace');
    assert.equal(opened.path, path, 'embedded links return canonical workspace paths to native');
  });

  await scenario('Malformed drawings report an error and never overwrite their source', async () => {
    const document = documentValue('invalid-scene', 'drawing', '{"elements": [broken');
    await openDocument(document);
    await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'error' && message.id === ${JSON.stringify(document.id)} && typeof message.code === 'string')`);
    await expectNoRewrite(document);
    assert.equal(await evaluate('return typeof window.BlinkWorkspace.openDocument'), 'function', 'one bad scene must not crash the editor shell');
    await openDocument(documentValue('after-invalid-scene', 'markdown', 'The next file still opens.'), rich);
  });

  await scenario('Read-only Mermaid renders and exports a drawing without changing the diagram source', async () => {
    const document = documentValue('mermaid-export', 'mermaid', 'flowchart LR\n A[Start] --> B[Finish]', { readOnly: true, path: 'diagrams/flow.mmd' });
    await openDocument(document, `${drawing} canvas`);
    await expectNoRewrite(document);
    await clickButton('Save as drawing');
    await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'action' && message.id === ${JSON.stringify(document.id)} && message.action === 'convertMermaid')`);
    const exported = (await messages()).find(message => message.id === document.id && message.action === 'convertMermaid');
    const scene = JSON.parse(exported.content);
    assert.equal(scene.type, 'excalidraw');
    assert.ok(scene.elements.some(element => element.type === 'text' && element.text.includes('Start')));
    await clickTab('Source');
    await browser('wait', source);
    const visible = await evaluate('return [...document.querySelectorAll("[data-testid=source-editor] .cm-line")].map(line => line.textContent).join("\\n")');
    assert.equal(visible, document.content);
    await expectNoRewrite(document);
  });

  await scenario('Malformed Mermaid retains editable source for recovery', async () => {
    const document = documentValue('invalid-mermaid', 'mermaid', 'flowchart LR\n A[unfinished', { path: 'diagrams/broken.mmd' });
    await openDocument(document);
    await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'error' && message.id === ${JSON.stringify(document.id)} && message.code === 'invalid_mermaid')`);
    await clickButton('Edit source');
    await browser('wait', source);
    assert.equal(await evaluate(`return ${query(source)}.getAttribute('contenteditable')`), 'true');
    await expectNoRewrite(document);
  });

  await scenario('Drawings save interoperable scene JSON and reopen without an automatic save', async () => {
    await browser('set', 'viewport', '900', '800');
    const document = documentValue('drawing-roundtrip', 'drawing', '{"elements":[]}');
    await openDocument(document, `${drawing} canvas`);
    await expectNoRewrite(document);
    const zoomValue = 'return [...document.querySelectorAll(".excalidraw button")].map(button => button.textContent.trim()).find(label => /^\\d+%$/.test(label))';
    const zoomBefore = await evaluate(zoomValue);
    assert.ok(zoomBefore, 'the drawing exposes its zoom control');
    await browser('click', drawing);
    await browser('press', 'r');
    const bounds = await evaluate(`return ${query(`${drawing} canvas`)}.getBoundingClientRect().toJSON()`);
    await browser('mouse', 'move', String(Math.round(bounds.left + bounds.width * 0.45)), String(Math.round(bounds.top + bounds.height * 0.45)));
    await browser('mouse', 'down');
    await browser('mouse', 'move', String(Math.round(bounds.left + bounds.width * 0.65)), String(Math.round(bounds.top + bounds.height * 0.65)));
    await browser('mouse', 'up');
    await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'change' && message.id === ${JSON.stringify(document.id)} && JSON.parse(message.content).elements.some(element => element.type === 'rectangle'))`);
    assert.equal(await evaluate(zoomValue), zoomBefore, 'drawing the first shape must not auto-fit a partially drawn element');
    await flush(document.id, 'drawing-save');
    await screenshot('drawing-desktop');
    const saved = (await latestChange(document.id)).content;
    const scene = JSON.parse(saved);
    assert.equal(scene.type, 'excalidraw');
    assert.ok(scene.elements.some(element => element.type === 'rectangle' && Number.isInteger(element.version)));
    const reopened = { ...document, id: 'drawing-reopened', content: saved };
    await openDocument(reopened, `${drawing} canvas`);
    await expectNoRewrite(reopened);
    await browser('set', 'viewport', '390', '844');
    await clickButton('Fit drawing');
    await settle();
    await expectNoRewrite(reopened);
    await screenshot('drawing-phone');
  });

  await scenario('Drawing text skeletons render using bundled fonts without rewriting their source', async () => {
    const content = JSON.stringify({ elements: [
      { type: 'rectangle', x: 10, y: 10, width: 300, height: 100 },
      { type: 'text', x: 30, y: 45, text: 'Offline drawing label', fontSize: 24, fontFamily: 5 },
    ] });
    const document = documentValue('drawing-local-text', 'drawing', content);
    await openDocument(document, `${drawing} canvas`);
    await evaluate('await document.fonts.ready');
    await expectNoRewrite(document);
    await screenshot('drawing-text-phone');
  });

  await scenario('Editable and read-only drawings and Mermaid export real PNGs without changing source', async () => {
    for (const kind of ['drawing', 'mermaid']) {
      for (const readOnly of [false, true]) {
        const language = readOnly ? 'uk' : 'en';
        const locale = JSON.parse(await readFile(new URL(`../src/locales/${language}.json`, import.meta.url), 'utf8'));
        // Cover both oversized geometry and a stored scale that would exceed the cap on a smaller scene.
        const content = kind === 'drawing' ? JSON.stringify({ elements: [
          { type: 'rectangle', x: 10, y: 10, width: readOnly ? 6000 : 1200, height: 180 },
          { type: 'text', x: 25, y: 40, text: 'PNG export fixture', fontSize: 20, fontFamily: 5 },
        ], appState: readOnly ? {} : { exportScale: 9 } }) : 'flowchart LR\n A[PNG fixture] --> B[Export]';
        const document = documentValue(`png-${kind}-${readOnly ? 'readonly' : 'editable'}`, kind, content, { readOnly, language });
        await openDocument(document, `${drawing} canvas`);
        await expectNoRewrite(document);
        await clickButton(locale.exportPng);
        await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'action' && message.action === 'exportDrawing' && message.id === ${JSON.stringify(document.id)})`);
        const exports = (await messages()).filter(message => message.type === 'action' && message.action === 'exportDrawing' && message.id === document.id);
        assert.equal(exports.length, 1, 'one export press produces one native export action');
        const bytes = pngBytes(exports[0], document.id);
        if (screenshots) await writeFile(`${screenshots}/${document.id}.png`, bytes);
        await expectNoRewrite(document);
        await clickTab(locale.source);
        await browser('wait', source);
        assert.equal(await evaluate('return [...document.querySelectorAll("[data-testid=source-editor] .cm-line")].map(line => line.textContent).join("\\n")'), content);
        await expectNoRewrite(document);
      }
    }
  });

  await scenario('Busy PNG export rejects duplicate presses and discards encoding after a document switch', async () => {
    const document = documentValue('png-stale-document', 'drawing', JSON.stringify({ elements: [
      { type: 'rectangle', x: 0, y: 0, width: 160, height: 80 },
    ] }), { readOnly: true });
    await openDocument(document, `${drawing} canvas`);
    await evaluate(`const original = HTMLCanvasElement.prototype.toBlob;
      const gate = window.__fixture.exportGate = { original, pending: [], calls: 0, completed: 0 };
      window.__fixture.exportButton = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === 'Export PNG');
      HTMLCanvasElement.prototype.toBlob = function(callback, ...args) {
        gate.calls += 1;
        const canvas = this;
        gate.pending.push(() => new Promise(resolve => original.call(canvas, blob => {
          callback(blob); gate.completed += 1; resolve();
        }, ...args)));
      };`);
    try {
      await clickButton('Export PNG');
      await browser('wait', '--fn', 'window.__fixture.exportGate.pending.length === 1');
      assert.equal(await evaluate('return window.__fixture.exportButton.disabled'), true, 'export must show a disabled busy state while encoding');
      await evaluate('window.__fixture.exportButton.click(); window.__fixture.exportButton.click()');
      assert.equal(await evaluate('return window.__fixture.exportGate.calls'), 1, 'repeated presses must not start parallel PNG encoders');
      const next = documentValue('after-png-export', 'markdown', 'The new document stays untouched.');
      await openDocument(next, rich);
      staleExportIds.add(document.id);
      await evaluate('await Promise.all(window.__fixture.exportGate.pending.splice(0).map(release => release()))');
      assert.equal(await evaluate('return window.__fixture.exportGate.completed'), 1, 'the original browser PNG encoder must actually complete');
      await settle();
      await expectNoRewrite(next);
      assert.deepEqual(await changes(document.id), []);
      assert.equal((await messages()).some(message => message.type === 'action' && message.action === 'exportDrawing' && message.id === document.id), false);
    } finally {
      await evaluate('HTMLCanvasElement.prototype.toBlob = window.__fixture.exportGate.original');
    }
  });

  await scenario('PNG failures stay local, preserve flushing and recover on the next real export', async () => {
    for (const failure of [
      { mode: 'oversized', language: 'en', key: 'exportTooLarge' },
      { mode: 'null', language: 'uk', key: 'exportFailed' },
    ]) {
      const locale = JSON.parse(await readFile(new URL(`../src/locales/${failure.language}.json`, import.meta.url), 'utf8'));
      const content = JSON.stringify({ elements: [
        { type: 'rectangle', x: 0, y: 0, width: 260, height: 100 },
        { type: 'text', x: 20, y: 30, text: 'Export failure fixture', fontSize: 20, fontFamily: 5 },
      ] });
      const document = documentValue(`png-failure-${failure.mode}`, 'drawing', content, { language: failure.language });
      await openDocument(document, `${drawing} canvas`);
      await expectNoRewrite(document);
      await evaluate(`window.__fixture.pngFailureOriginal = HTMLCanvasElement.prototype.toBlob;
        window.__fixture.pngFailureCalls = 0;
        HTMLCanvasElement.prototype.toBlob = function(callback) {
          window.__fixture.pngFailureCalls += 1;
          queueMicrotask(() => callback(${failure.mode === 'oversized' ? "new Blob([new Uint8Array(2_000_001)], { type: 'image/png' })" : 'null'}));
        };`);
      try {
        await clickButton(locale.exportPng);
        await browser('wait', '--fn', `document.querySelector('[role=alert]')?.textContent === ${JSON.stringify(locale[failure.key])}`);
        assert.equal(await evaluate('return window.__fixture.pngFailureCalls'), 1, 'the intended encoder fault must be exercised');
        assert.equal(await evaluate(`return [...document.querySelectorAll('button')].find(button => button.textContent.trim() === ${JSON.stringify(locale.exportPng)})?.disabled`), false, 'a failed export must release its busy state');
        assert.equal(await evaluate(`return !!${query(`${drawing} canvas`)}`), true, 'an optional export failure must retain the canvas');
        await flush(document.id, `flush-after-${failure.mode}-export`);
        assert.deepEqual((await messages()).filter(message => message.id === document.id && ['action', 'error', 'change'].includes(message.type)), [], 'encoder faults must not become native actions, fatal errors or document changes');
        await screenshot(`png-failure-${failure.mode}`);
      } finally {
        await evaluate('HTMLCanvasElement.prototype.toBlob = window.__fixture.pngFailureOriginal');
      }
      await clickButton(locale.exportPng);
      await browser('wait', '--fn', `window.__fixture.messages.some(message => message.type === 'action' && message.action === 'exportDrawing' && message.id === ${JSON.stringify(document.id)})`);
      const exports = (await messages()).filter(message => message.id === document.id && message.type === 'action');
      assert.equal(exports.length, 1);
      pngBytes(exports[0], document.id);
      assert.equal(await evaluate('return document.querySelector("[role=alert]") === null'), true, 'a successful retry clears the export alert');
      assert.deepEqual((await messages()).filter(message => message.id === document.id && ['error', 'change'].includes(message.type)), []);
      await expectNoRewrite(document);
      await clickTab(locale.source);
      await browser('wait', source);
      assert.equal(await evaluate('return [...document.querySelectorAll("[data-testid=source-editor] .cm-line")].map(line => line.textContent).join("\\n")'), content);
    }
  });

  await scenario('Phone layout and read-only source retain host data without horizontal overflow', async () => {
    const locale = JSON.parse(await readFile(new URL('../src/locales/uk.json', import.meta.url), 'utf8'));
    const document = documentValue('readonly-phone', 'code', 'const message = "Visible but read only";\n', { readOnly: true, theme: 'dark', language: 'uk', path: 'src/view.ts' });
    await openDocument(document, source);
    assert.equal(await evaluate(`return ${query(source)}.getAttribute('contenteditable')`), 'false');
    await expectNoRewrite(document);
    assert.equal(await evaluate('return document.documentElement.scrollWidth <= innerWidth + 1'), true);
    assert.equal(await evaluate('return document.documentElement.lang'), 'uk');
    assert.equal(await evaluate('return document.documentElement.dataset.theme'), 'dark');
    const text = await evaluate('return document.body.textContent');
    assert.ok(text.includes(locale.readonly), 'the read-only state uses the selected locale');
    assert.equal(await evaluate(`return document.querySelector('button[aria-label=${JSON.stringify(locale.find)}]') !== null`), true);
    await screenshot('source-readonly-dark-uk-phone');
  });

  await scenario('Bundled editors never request external resources or backend APIs', async () => {
    const urls = await evaluate('return [...performance.getEntriesByType("resource").map(entry => entry.name), ...window.__fixture.networkAttempts]');
    const external = urls.filter(url => !['data:', 'blob:'].some(prefix => url.startsWith(prefix)) && new URL(url, fixture.origin).origin !== fixture.origin);
    const violations = await evaluate('return window.__fixture.violations');
    // The host probes listening ports with a headerless GET / even with no browser running.
    // Keep its 404 response; a root request from an editor resource still fails this check.
    const rootLoaded = urls.some(url => new URL(url, fixture.origin).href === `${fixture.origin}/`);
    const infrastructureProbe = entry => entry.path === '/' && !entry.userAgent && !entry.referer && !entry.destination && !rootLoaded;
    const missing = fixture.requests.filter(entry => entry.status >= 400
      && !['/editor/%2e%2e/HANDOFF.md', '/api/workspace/file'].includes(entry.path)
      && !infrastructureProbe(entry));
    const errors = JSON.parse(await browser('--json', 'errors'));
    if (screenshots) await writeFile(`${screenshots}/network.json`, JSON.stringify({ external, violations, missing, errors }, null, 2));
    assert.equal(external.length, 0, `External resource attempts: ${external.slice(0, 3).join(', ')}`);
    assert.equal(violations.length, 0, `CSP must not hide attempted loads: ${JSON.stringify(violations.slice(0, 3))}`);
    assert.deepEqual(missing, [], 'all editor assets must be packaged locally');
    assert.ok(fixture.requests.some(entry => entry.status === 200 && entry.path.includes('/excalidraw/fonts/') && entry.path.endsWith('.woff2')), 'drawing text must exercise at least one bundled font');
    assert.deepEqual(errors.data?.errors ?? [], [], 'editor interactions must not cause uncaught page errors');
    assert.equal((await messages()).some(message => message.type === 'action' && message.action === 'exportDrawing' && staleExportIds.has(message.id)), false, 'late encoders must never export for a disposed document');
  });
} catch (error) {
  if (browserStarted) {
    try {
      const errors = JSON.parse(await browser('--json', 'errors'));
      console.error(JSON.stringify(errors.data?.errors?.slice(0, 5) ?? errors));
      if (screenshots) {
        const diagnostics = await evaluate('return { attempts: window.__fixture.networkAttempts, violations: window.__fixture.violations, resources: performance.getEntriesByType("resource").map(entry => entry.name), resourceDetails: performance.getEntriesByType("resource").map(entry => entry.toJSON()) }');
        await writeFile(`${screenshots}/failure-diagnostics.json`, JSON.stringify({ ...diagnostics, requests: fixture?.requests, errors }, null, 2));
      }
    } catch { /* Keep the original failure if the browser itself failed. */ }
    try { await screenshot('failure'); } catch { /* Screenshot failures must not hide the assertion. */ }
  }
  throw error;
} finally {
  try { if (browserStarted) await browser('close'); } finally { await fixture?.close(); }
}
