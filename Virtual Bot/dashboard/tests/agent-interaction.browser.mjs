/** Mouse input against real Tiptap/SSE playback, with isolated file fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
const session = `agent-interaction-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const emit = (name, data) => evaluate(`window.__emit(${JSON.stringify(name)}, ${JSON.stringify(data)})`);
try {
  browser('open', `${origin}/dash/#/chat`);
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [{ id: 'interaction', title: 'Mouse interaction', count: 1 }] });
  route('**/api/sessions/interaction', { id: 'interaction', messages: [{ role: 'assistant', content: 'Ready to inspect.' }] });
  route('**/api/workspace/info**', { root: '/fixture/workspace', session_path: 'sessions/interaction' });
  route('**/api/brain/models', { models: [{ id: 'test', label: 'Test', context: 200000 }], selected: 'test', thinking: 'high', thinking_levels: ['high'] });
  evaluate("localStorage.setItem('claudeBotLang', 'en'); localStorage.setItem('claudeBotTheme', 'light'); localStorage.setItem('claudeBotConversationList', 'open');");
  browser('reload');
  browser('wait', '[data-session-id="interaction"]');
  browser('click', '[data-session-id="interaction"]');
  browser('wait', '--text', 'Ready to inspect.');
  const note = '# Interactive document\n\nSelect this sentence while the agent works.\n\n![Read-only scene](interactive.drawings/scene.excalidraw)\n\n'
    + Array.from({ length: 55 }, (_, index) => `Paragraph ${index + 1}. This actual file text gives the reader room to scroll while the agent continues writing.\n\n`).join('');
  evaluate(`window.__fileText = ${JSON.stringify(note)}; window.__posts = 0; window.__reads = [];
    const saved = window.fetch;
    window.__emit = (name, data) => window.__stream.enqueue(new TextEncoder().encode('event: ' + name + '\\ndata: ' + JSON.stringify(data) + '\\n\\n'));
    window.fetch = async (url, options) => {
      if (String(url).startsWith('/api/chat')) return new Response(new ReadableStream({ start(controller) { window.__stream = controller; } }), { headers: { 'Content-Type': 'text/event-stream' } });
      if (String(url).startsWith('/api/workspace/file?')) {
        window.__reads.push(String(url));
        return new Response(JSON.stringify({ content: String(url).includes('.excalidraw')
          ? JSON.stringify({ elements: [{ type: 'rectangle', x: 0, y: 0, width: 120, height: 80 }] }) : window.__fileText }), { headers: { 'Content-Type': 'application/json' } });
      }
      if (String(url) === '/api/workspace/file' && options?.method === 'POST') window.__posts++;
      return saved(url, options);
    }; window.__vbotSendMessage('Write an interactive note');`);
  browser('wait', '--fn', 'Boolean(window.__stream)');
  const call = { call_id: 'mouse-write', tool: 'workspace_write', detail: 'session/interactive.md', input: { path: 'session/interactive.md', content: note } };
  emit('tool_start', call);
  browser('wait', '.agent-file-paper .document-drawing');
  const scroller = '.agent-file-paper .note-editor-content';
  // CLI mouse wheel dispatches at (0,0) even after mouse move. Exercise DOM
  // wheel propagation in the real document, then use its targeted scroll API.
  evaluate(`document.querySelector('.agent-file-paper .prose-note').dispatchEvent(new WheelEvent('wheel', { deltaY: -500, bubbles: true }));`);
  browser('scroll', 'up', '5000', '--selector', scroller);
  browser('wait', '.agent-follow-button');
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(scroller)}).scrollTop < 1`);
  evaluate(`window.__scrollSamples = []; window.__watch = setInterval(() => {
    const scroller = document.querySelector(${JSON.stringify(scroller)});
    if (scroller) window.__scrollSamples.push(scroller.scrollTop);
  }, 25);`);
  browser('wait', '--fn', 'window.__scrollSamples.length >= 12');
  assert.ok(evaluate('Math.max(...window.__scrollSamples)') <= 2, 'agent follow must not snap the reader back after a wheel gesture');
  evaluate('clearInterval(window.__watch)');

  browser('click', '.agent-file-paper .document-drawing button');
  browser('wait', '.agent-file-paper .excalidraw canvas');
  assert.equal(evaluate('window.__reads.some(url => url.includes("interactive.md"))'), false, 'the active main file is still never fetched');
  assert.equal(evaluate(`document.querySelector('.agent-file-paper .excalidraw [data-testid="toolbar-rectangle"]') === null`), true, 'live inline drawing is inspectable in view mode');
  browser('click', '.agent-file-paper .document-drawing button');

  // A native mouse drag must retain its actual selection through confirmed
  // content arriving and the tool finishing, until the reader resumes.
  const paragraph = evaluate('document.querySelector(".agent-file-paper .prose-note > p").getBoundingClientRect().toJSON()');
  const y = Math.round(paragraph.top + 10);
  browser('mouse', 'move', String(Math.round(paragraph.left + 2)), String(y));
  browser('mouse', 'down');
  browser('mouse', 'move', String(Math.round(paragraph.left + 195)), String(y));
  browser('mouse', 'up');
  const selected = evaluate('window.getSelection().toString()');
  assert.ok(selected.length > 5, 'read-only playback must allow native text selection');
  evaluate(`window.__selectedSamples = []; window.__watch = setInterval(() => window.__selectedSamples.push(window.getSelection().toString()), 25);
    window.__fileText += '\\nFinal confirmed sentence.\\n';`);
  emit('tool_done', { ...call, result: { ok: true, path: 'sessions/interaction/interactive.md' } });
  emit('delta', { chunk: 'The file is ready.' });
  emit('done', { reply: 'The file is ready.', session_id: 'interaction', emotion: 'idle', mode: 'test', tool_results: [] });
  browser('wait', '--fn', 'window.__selectedSamples.length >= 12 && window.__reads.some(url => url.includes("interactive.md"))');
  assert.equal(evaluate('window.__selectedSamples.every(text => text === window.__selectedSamples[0])'), true, 'confirmation must not clear or replace a reader selection');
  assert.equal(evaluate('window.getSelection().toString()'), selected);
  assert.equal(evaluate('document.querySelector("[data-agent-file-writing]") !== null'), true, 'selected playback remains mounted until inspection ends');
  assert.equal(evaluate('window.__posts'), 0, 'scrolling/selection/inline inspection cannot save a file');
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/agent-mouse-selection.png`);
  evaluate('clearInterval(window.__watch)');
  browser('click', '.agent-follow-button');
  browser('wait', '--fn', '!document.querySelector("[data-agent-file-writing]") && document.querySelector(".workbench .prose-note")?.textContent.includes("Final confirmed sentence.")');
  assert.equal(evaluate('window.__posts'), 0);
  console.log('PASS: wheel handling/scroll without snapping, native mouse selection across completion, read-only inline drawing inspection, explicit resume and exact final prose, no saves');
} catch (error) {
  console.error(browser('errors'));
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/agent-mouse-failure.png`);
  throw error;
} finally { browser('close'); }
