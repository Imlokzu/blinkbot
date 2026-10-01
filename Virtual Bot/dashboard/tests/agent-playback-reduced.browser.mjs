/** Real reduced-motion emulation, isolated from the history/focus fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
const session = `agent-playback-reduced-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
try {
  browser('open', 'about:blank');
  browser('set', 'media', 'dark', 'reduced-motion');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [] });
  route('**/api/workspace/info**', { root: '/fixture/workspace', session_path: 'sessions/quiet' });
  route('**/api/brain/models', { models: [{ id: 'test', label: 'Test', context: 200000 }], selected: 'test', thinking: 'high', thinking_levels: ['high'] });
  browser('open', `${origin}/dash/#/chat`);
  evaluate("localStorage.setItem('claudeBotLang', 'en'); localStorage.setItem('claudeBotTheme', 'dark');");
  browser('reload');
  browser('wait', 'textarea');
  evaluate(`window.__posts = 0; const saved = window.fetch;
    window.__emit = (name, data) => window.__stream.enqueue(new TextEncoder().encode('event: ' + name + '\\ndata: ' + JSON.stringify(data) + '\\n\\n'));
    window.fetch = async (url, options) => {
      if (String(url).startsWith('/api/chat')) return new Response(new ReadableStream({ start(controller) { window.__stream = controller; } }), { headers: { 'Content-Type': 'text/event-stream' } });
      if (String(url).startsWith('/api/workspace/file?')) return new Response(JSON.stringify({ content: '# Quiet note\\n\\nVisible immediately.' }), { headers: { 'Content-Type': 'application/json' } });
      if (String(url) === '/api/workspace/file' && options?.method === 'POST') window.__posts++;
      return saved(url, options);
    }; window.__vbotSendMessage('A quiet note');`);
  browser('wait', '--fn', 'Boolean(window.__stream)');
  const call = { call_id: 'quiet', tool: 'workspace_write', detail: 'session/quiet.md', input: { path: 'session/quiet.md', content: '# Quiet note\n\nVisible immediately.' } };
  evaluate(`window.__emit('tool_start', ${JSON.stringify(call)})`);
  browser('wait', '--fn', 'document.querySelector(".agent-file-paper .prose-note")?.textContent.includes("Visible immediately.")');
  assert.equal(evaluate('window.matchMedia("(prefers-reduced-motion: reduce)").matches'), true);
  assert.equal(evaluate('document.querySelector(".agent-editor-caret") === null'), true);
  evaluate(`window.__emit('tool_done', ${JSON.stringify({ ...call, result: { ok: true, path: 'sessions/quiet/quiet.md' } })});
    window.__emit('done', ${JSON.stringify({ reply: 'Saved', session_id: 'quiet', emotion: 'idle', mode: 'test', tool_results: [] })});`);
  browser('wait', '--fn', '!document.querySelector("[data-agent-file-writing]") && document.querySelector(".workbench .prose-note h1")?.textContent === "Quiet note"');
  assert.equal(evaluate('window.__posts'), 0);
  console.log('PASS: native reduced motion renders complete prose immediately, no cursor animation or writes, confirmed document releases in dark theme');
} finally { browser('close'); }
