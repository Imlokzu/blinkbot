/**
 * Opt-in UI check of the chat workbench, with agent-browser against a running
 * server (or `vite` dev server: DASHBOARD_TEST_URL=http://localhost:5180
 * DASHBOARD_TEST_PATH=/static/dash/). Every chat and file below is a browser
 * route fixture: server auth, chats and the workspace stay untouched.
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `workbench-test-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', code)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const shot = process.env.WORKBENCH_SHOTS;
// The dashboard follows the browser's language, so labels are matched in both.
const clickLabel = (...names) => browser('find', 'first', names.map((name) => `button[aria-label="${name}"]`).join(', '), 'click');

const write = (id, file, extra = {}) => ({
  id, label: 'workspace_write', detail: file, status: 'done', input: { path: file }, result: { ok: true, path: file }, ...extra,
});
const files = {
  'notes/plan.md': '# Plan\n\n- first step\n- **second step**\n',
  'diagrams/flow.mmd': 'flowchart LR\n  U["User<br>on a phone"] --> C[Chat]\n  C --> B{Brain}\n  B --> W[Workbench]\n  B --> S[Screen]\n',
  // The short skeleton form a model can write; the view expands it.
  'diagrams/sketch.excalidraw': JSON.stringify({ type: 'excalidraw', elements: [
    { type: 'rectangle', id: 'chat', x: 0, y: 0, width: 180, height: 80, label: { text: 'Chat' } },
    { type: 'ellipse', id: 'brain', x: 320, y: 0, width: 180, height: 80, label: { text: 'Brain' } },
    { type: 'arrow', x: 180, y: 40, start: { id: 'chat' }, end: { id: 'brain' } },
  ] }),
};

try {
  browser('open', `${origin}${path}#/chat`);
  browser('set', 'viewport', '1440', '900');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [{ id: 'wb-fixture', title: 'Workbench check', count: 2 }] });
  route('**/api/sessions/wb-fixture', { id: 'wb-fixture', messages: [
    { role: 'user', content: 'Make a plan and a diagram' },
    { role: 'assistant', content: 'Done — the plan, a flow chart and a sketch.', steps: [
      write('s1', 'games/snake-arena/index.html'),
      write('s2', 'notes/plan.md'),
      write('s3', 'diagrams/sketch.excalidraw'),
      write('s4', 'diagrams/flow.mmd'),
      { id: 's5', label: 'workspace_write', detail: 'broken.html', status: 'failed', result: { error: 'no' } },
    ] },
  ] });
  route('**/api/workspace/info**', { session_path: 'sessions/wb-fixture' });
  for (const [file, content] of Object.entries(files)) {
    route(`**/api/workspace/file?path=${encodeURIComponent(file)}**`, { path: file, size: content.length, content });
  }
  route('**/api/brain/models', { models: [{ id: 'test', label: 'Test', context: 200000 }],
    selected: 'test', default: 'test', thinking: 'high', thinking_levels: ['high'], available: true });
  evaluate("localStorage.setItem('claudeBotWorkbench', 'open')");
  browser('reload');
  browser('wait', '[data-session-id="wb-fixture"]');
  browser('click', '[data-session-id="wb-fixture"]');
  browser('click', '[data-right-panel-trigger]');
  evaluate('Promise.all(document.querySelector("[data-right-panel-menu]").getAnimations({subtree:true}).map(animation => animation.finished.catch(() => {})))');
  browser('click', '[data-panel-choice="workbench"]');
  browser('wait', '--fn', 'document.querySelector("[data-right-panel-menu]") === null');

  // Newest write first; the failed one never becomes a tab.
  browser('wait', '.workbench nav button');
  const tabs = evaluate('[...document.querySelectorAll(".workbench nav button")].map(b => b.title)');
  assert.deepEqual(tabs, ['diagrams/flow.mmd', 'diagrams/sketch.excalidraw', 'notes/plan.md', 'games/snake-arena/index.html']);

  // Mermaid comes out as an Excalidraw sketch, with the converter's elements.
  browser('wait', '.workbench .excalidraw canvas');
  browser('wait', '--fn', 'document.querySelector(".wb-drawing-action") !== null');
  // Save as a drawing writes the converted scene: `<br>` must be a real line break by then.
  route('**/api/workspace/file', { ok: true, path: 'diagrams/flow.excalidraw' });
  browser('wait', '1000');
  browser('click', '.wb-drawing-action');
  browser('wait', '1000');
  const saved = browser('network', 'requests', '--method', 'POST', '--filter', 'workspace/file');
  assert.match(saved, /workspace\/file/);
  browser('network', 'requests', '--clear');
  if (shot) browser('screenshot', `${shot}/workbench-mermaid.png`);

  browser('click', '.workbench nav button[title="diagrams/sketch.excalidraw"]');
  browser('wait', '.workbench .excalidraw canvas');
  if (shot) browser('screenshot', `${shot}/workbench-sketch.png`);
  // Opening, fitting and scrolling a drawing is not an edit: nothing is written back.
  browser('wait', '2000');
  assert.doesNotMatch(browser('network', 'requests', '--method', 'POST', '--filter', 'workspace/file'), /workspace\/file/);

  browser('click', '.workbench nav button[title="notes/plan.md"]');
  browser('wait', '.workbench .prose-note h1');
  assert.equal(evaluate('document.querySelector(".workbench .prose-note h1").textContent'), 'Plan');
  browser('click', '.workbench [role="radio"][aria-checked="false"]');
  browser('wait', '.workbench .cm-editor');

  browser('click', '.workbench nav button[title="games/snake-arena/index.html"]');
  browser('wait', '.workbench iframe');
  const frame = evaluate('document.querySelector(".workbench iframe").getAttribute("src")');
  assert.match(frame, /^\/preview\/games\/snake-arena\/index\.html\?session_id=wb-fixture&r=1$/);
  assert.equal(evaluate('document.querySelector(".workbench iframe").sandbox.contains("allow-same-origin")'), false);
  if (shot) browser('screenshot', `${shot}/workbench-html.png`);

  // Closing brings the pins column back and is remembered.
  clickLabel('Сховати робоче місце', 'Hide the workbench');
  browser('wait', '.chat-pins');
  assert.equal(evaluate('document.querySelector(".workbench") === null'), true);
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);

  // Phone: a sheet, opened from the compact composer's plus menu.
  browser('set', 'viewport', '390', '844');
  browser('wait', '.chat-phone-toolbar');
  evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
  browser('click', '.prompt-bar__tool[aria-controls="chat-attachment-menu"]');
  browser('wait', '[data-attachment-menu]');
  browser('find', 'role', 'button', 'click', '--name',
    evaluate('document.documentElement.lang') === 'uk' ? 'Показати робоче місце' : 'Show the workbench', '--exact');
  browser('wait', '[role="dialog"] .workbench');
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  if (shot) browser('screenshot', `${shot}/workbench-phone.png`);
  console.log('PASS: tabs from steps, mermaid and skeleton drawings, markdown view/code, sandboxed html, close, phone sheet');
} finally {
  browser('close');
}
