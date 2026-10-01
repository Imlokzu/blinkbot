/** Exercise document editing and navigation with isolated browser route fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `document-editor-test-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5180';
const page = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const clickLabel = (...names) => browser('find', 'first', names.map((name) => `button[aria-label="${name}"]`).join(', '), 'click');
const content = '# Project plan\n\nWrite together with the agent.\n\n| Item | Quantity | Price |\n| --- | --- | --- |\n| Laptop | 2 | 1200 |\n\n- [x] Read the brief\n- [ ] Review the table\n\n![Diagram](notes/flow.excalidraw)\n\nFinal notes.\n';

try {
  browser('open', `${origin}${page}#/chat`);
  browser('set', 'viewport', '1600', '1000');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [{ id: 'doc-fixture', title: 'Document editor check', count: 2 }] });
  route('**/api/sessions/doc-fixture', { id: 'doc-fixture', messages: [
    { role: 'user', content: 'Create a document' },
    { role: 'assistant', content: 'Open [the plan](/Users/fixture/workspace/notes/plan.md). See [OpenAI](https://openai.com).',
      steps: ['notes/other.md', 'notes/plan.md'].map((path, index) => ({
        id: `write-${index}`, label: 'workspace_write', status: 'done', detail: path, input: { path }, result: { path },
      })) },
  ] });
  route('**/api/workspace/info**', { root: '/Users/fixture/workspace', session_path: 'sessions/doc-fixture', reveal_available: true });
  route('**/api/workspace/file?path=notes%2Fplan.md**', { path: 'notes/plan.md', content });
  route('**/api/workspace/file?path=notes%2Fother.md**', { path: 'notes/other.md', content: '# Other document\n' });
  route('**/api/workspace/file?path=notes%2Fflow.excalidraw**', { content: JSON.stringify({ elements: [] }) });
  route('**/api/workspace/file', { ok: true });
  route('**/api/workspace/reveal', { ok: true });
  route('**/api/brain/models', { models: [{ id: 'test', label: 'Test', context: 200000 }],
    selected: 'test', default: 'test', thinking: 'high', thinking_levels: ['high'], available: true });
  evaluate("localStorage.setItem('claudeBotWorkbench', 'open')");
  browser('reload');
  browser('wait', '[data-session-id="doc-fixture"]');
  browser('click', '[data-session-id="doc-fixture"]');
  clickLabel('Показати робоче місце', 'Show the workbench');
  browser('wait', '.workbench .note-toolbar');
  evaluate(`window.__documentRequests = []; window.__drawingFile = { elements: [] }; const originalFetch = window.fetch; window.fetch = async (url, options) => {
    if (String(url).includes('path=notes%2Fflow.excalidraw')) {
      return new Response(JSON.stringify({ content: JSON.stringify(window.__drawingFile) }), { headers: { 'Content-Type': 'application/json' } });
    }
    if (options?.method === 'POST' && String(url).includes('/api/workspace/')) {
      window.__documentRequests.push({ url: String(url), body: JSON.parse(options.body) });
      if (window.__pauseSave && String(url).endsWith('/file')) await new Promise(resolve => { window.__releaseSave = resolve; });
      if (JSON.parse(options.body).path === 'notes/flow.excalidraw') {
        if (window.__pauseDrawing) await new Promise(resolve => { window.__releaseDrawing = resolve; });
        window.__drawingFile = JSON.parse(JSON.parse(options.body).content);
      }
    }
    return originalFetch(url, options);
  }`);
  assert.equal(evaluate('document.querySelector(".workbench .prose-note").getAttribute("contenteditable")'), 'true');
  assert.equal(evaluate('document.querySelector(".workbench .prose-note h1").textContent'), 'Project plan');
  assert.equal(evaluate('document.querySelectorAll(".workbench .prose-note table").length'), 1);
  assert.equal(evaluate('document.querySelectorAll(".workbench input[type=checkbox]").length'), 2);
  assert.equal(evaluate('window.__documentRequests.length'), 0, 'opening a document must not rewrite it');

  // Existing absolute links are repaired at rendering time, including old chats.
  browser('wait', '.chat-conversation a[href*="file="]');
  assert.equal(evaluate('document.querySelector(".chat-conversation a[href*=file]").hasAttribute("target")'), false);
  clickLabel('Сховати робоче місце', 'Hide the workbench');
  browser('click', '.chat-conversation a[href*="file="]');
  browser('wait', '.workbench .note-toolbar');
  assert.match(browser('get', 'url').trim(), /#\/chat$/);
  assert.equal(evaluate(`document.querySelector('.chat-conversation a[href="https://openai.com"]').target`), '_blank');
  clickLabel('Показати у Finder', 'Show in Finder');
  browser('wait', '--fn', 'window.__documentRequests.some(r => r.url.endsWith("/reveal"))');
  assert.deepEqual(evaluate('window.__documentRequests.find(r => r.url.endsWith("/reveal")).body'), { path: 'notes/plan.md', session_id: 'doc-fixture' });

  evaluate(`const element = document.querySelector('.workbench .prose-note'); element.focus();
    const range = document.createRange(); range.selectNodeContents(element); range.collapse(false);
    const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);`);
  browser('type', '.workbench .prose-note', ' Reviewed by owner.');
  browser('wait', 'button[aria-label="Insert table"], button[aria-label="Вставити таблицю"]');
  browser('click', '.workbench [role="radio"][aria-checked="false"]');
  browser('wait', '.workbench .cm-editor');
  assert.match(evaluate('document.querySelector(".workbench .cm-content").textContent'), /Reviewed by owner/);
  browser('click', '.workbench [role="radio"][aria-checked="false"]');
  browser('wait', '.workbench .note-toolbar');
  browser('click', '.workbench nav button[title="notes/other.md"]');
  browser('click', '.workbench nav button[title="notes/plan.md"]');
  browser('wait', '.workbench .prose-note');
  assert.match(evaluate('document.querySelector(".workbench .prose-note").textContent'), /Reviewed by owner/);
  browser('press', 'Meta+s');
  browser('wait', '--fn', 'window.__documentRequests.some(r => r.url.endsWith("/file"))');
  const saved = evaluate('window.__documentRequests.filter(r => r.url.endsWith("/file")).at(-1).body');
  assert.equal(saved.path, 'notes/plan.md');
  assert.equal(saved.session_id, 'doc-fixture');
  assert.match(saved.content, /Reviewed by owner/);
  assert.match(saved.content, /\| Laptop/);
  assert.match(saved.content, /- \[x\] Read the brief/);
  assert.match(saved.content, /!\[Diagram\]\(notes\/flow\.excalidraw\)/);

  // A pending save belongs to the document, not the view that starts it.
  const append = (text) => {
    evaluate(`const element = document.querySelector('.workbench .prose-note'); element.focus();
      const range = document.createRange(); range.selectNodeContents(element); range.collapse(false);
      const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);`);
    browser('type', '.workbench .prose-note', text);
  };
  append(' First edit.');
  evaluate('window.__pauseSave = true');
  browser('press', 'Meta+s');
  browser('wait', '--fn', 'typeof window.__releaseSave === "function"');
  browser('click', '.workbench nav button[title="notes/other.md"]');
  browser('click', '.workbench nav button[title="notes/plan.md"]');
  browser('wait', '.workbench .prose-note');
  append(' Second edit.');
  evaluate('window.__pauseSave = false; window.__releaseSave(); delete window.__releaseSave');
  browser('wait', '--fn', '[...document.querySelectorAll(".workbench button")].some(b => /^(Save|Зберегти)$/.test(b.textContent.trim()) && !b.disabled)');
  assert.match(evaluate('document.querySelector(".workbench .prose-note").textContent'), /First edit\. Second edit\./);
  browser('press', 'Meta+s');
  browser('wait', '--fn', '!document.querySelector(".workbench button:disabled")?.textContent.includes("Saving")');

  // A drawing is an inline component, with the surrounding document retained.
  browser('click', '.document-drawing header button');
  browser('wait', '.document-drawing .excalidraw canvas');
  assert.match(evaluate('document.querySelector(".workbench .prose-note").textContent'), /Final notes/);
  const draw = (shortcut, offset = 0) => {
    browser('scrollintoview', '.document-drawing .excalidraw');
    browser('click', '.document-drawing .excalidraw');
    browser('press', shortcut);
    const rect = evaluate('document.querySelector(".document-drawing .excalidraw canvas").getBoundingClientRect().toJSON()');
    browser('mouse', 'move', String(Math.round(rect.left + 100 + offset)), String(Math.round(rect.top + 130)));
    browser('mouse', 'down');
    browser('mouse', 'move', String(Math.round(rect.left + 180 + offset)), String(Math.round(rect.top + 210)));
    browser('mouse', 'up');
  };
  draw('r');
  browser('wait', '--fn', 'window.__drawingFile.elements.some(e => e.type === "rectangle")');
  browser('click', '.document-drawing header button');
  browser('click', '.document-drawing header button');
  browser('wait', '.document-drawing .excalidraw canvas');
  evaluate('window.__pauseDrawing = true');
  draw('d', 110);
  browser('wait', '--fn', 'typeof window.__releaseDrawing === "function"');
  browser('click', '.document-drawing header button');
  browser('click', '.document-drawing header button');
  assert.equal(evaluate('document.querySelectorAll(".document-drawing .excalidraw canvas").length'), 0, 'reopen must wait for the pending scene save');
  evaluate('window.__pauseDrawing = false; window.__releaseDrawing(); delete window.__releaseDrawing');
  browser('wait', '.document-drawing .excalidraw canvas');
  draw('o', 210);
  browser('wait', '--fn', 'window.__drawingFile.elements.some(e => e.type === "ellipse")');
  assert.deepEqual(evaluate('window.__drawingFile.elements.filter(e => !e.isDeleted).map(e => e.type).sort()'), ['diamond', 'ellipse', 'rectangle']);
  browser('click', '.document-drawing header button');
  clickLabel('Вставити Excalidraw', 'Insert Excalidraw');
  browser('wait', '--fn', 'document.querySelectorAll(".document-drawing").length === 2');
  assert.equal(evaluate('window.__documentRequests.filter(r => r.url.endsWith("/file")).at(-1).body.content.includes("excalidraw")'), true);
  if (process.env.WORKBENCH_SHOTS) browser('screenshot', `${process.env.WORKBENCH_SHOTS}/document-editor.png`);

  browser('set', 'viewport', '390', '844');
  clickLabel('Показати робоче місце', 'Show the workbench');
  browser('wait', '[role="dialog"] .note-toolbar');
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  if (process.env.WORKBENCH_SHOTS) browser('screenshot', `${process.env.WORKBENCH_SHOTS}/document-editor-phone.png`);
  console.log('PASS: rich document/table/task round-trip, delayed-save draft retention, Cmd+S, internal links, Finder, inline drawing keyboard/reopen preservation, phone layout');
} catch (error) {
  console.error(browser('errors'));
  if (process.env.WORKBENCH_SHOTS) browser('screenshot', `${process.env.WORKBENCH_SHOTS}/document-editor-failure.png`);
  throw error;
} finally { browser('close'); }
