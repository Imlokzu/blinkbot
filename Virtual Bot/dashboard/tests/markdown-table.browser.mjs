/** Verify long assistant tables remain readable instead of clipping cells to an ellipsis. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `markdown-table-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5180';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));

const tableReply = [
  '| Component | Status | Notes | Owner | Details |',
  '| --- | --- | --- | --- | --- |',
  '| Composer | Ready | This is a deliberately long cell with enough detail to wrap across several lines instead of disappearing behind an ellipsis. | Chat | The complete value must remain available to read on a phone and desktop. |',
  '| Table | Fixed | Horizontal scrolling is reserved for the table itself when there are many columns. | UI | Wrapped cells remain visible inside their own column. |',
].join('\n');

const init = `(() => {
  const event = (name, data) => 'event: ' + name + '\\ndata: ' + JSON.stringify(data) + '\\n\\n';
  const original = window.fetch.bind(window);
  window.__tableWrites = [];
  window.fetch = async (url, options) => {
    const request = new URL(url instanceof Request ? url.url : String(url), location.href);
    const method = String(options?.method || (url instanceof Request ? url.method : 'GET')).toUpperCase();
    if (request.pathname === '/api/chat' && method === 'POST') {
      window.__tableWrites.push(JSON.parse(options.body));
      return new Response(event('delta', { chunk: window.__tableReply }) + event('done', {
        reply: window.__tableReply, session_id: 'markdown-table-fixture', emotion: 'idle', mode: 'test',
        model: 'table-fixture', tool_results: [],
      }), { headers: { 'Content-Type': 'text/event-stream' } });
    }
    if (request.pathname.startsWith('/api/') && !['GET', 'HEAD'].includes(method)) return Response.json({ ok: true });
    return original(url, options);
  };
})();`;

try {
  browser('open', 'about:blank');
  browser('set', 'viewport', '390', '844');
  route('**/api/auth/config', { disabled: true });
  route('**/api/setup', { configured: true, profile: { configured: true, name: 'Fixture', language: 'en', persona: 'friendly', reply_length: 'balanced', use_emoji: true, spontaneous: false }, languages: [], personas: [], reply_lengths: [], models: [], selected_model: '', keys_set: {} });
  route('**/api/sessions', { sessions: [] });
  route('**/api/brain/models', { models: [{ id: 'table-fixture', label: 'Table fixture', context: 200000 }], selected: 'table-fixture', default: 'table-fixture', thinking: 'high', thinking_levels: ['high'], available: true });
  route('**/api/models', { models: [{ id: 'table-fixture', label: 'Table fixture' }], selected: 'table-fixture', default: 'table-fixture', active: 'table-fixture' });
  route('**/api/status', { mode: 'test' });
  route('**/api/projects', { projects: [] });
  route('**/api/chat/context**', { parts: [], chars: 0, dropped: 0, history_limit: 20 });
  route('**/api/**', {});
  browser('open', `${origin}${path}#/chat`);
  browser('wait', '.prompt-bar__send');
  evaluate(`${init}; window.__tableReply = ${JSON.stringify(tableReply)}; localStorage.setItem('claudeBotLang', 'en'); localStorage.setItem('claudeBotTheme', 'light'); true`);
  browser('fill', '.prompt-bar textarea', 'Show the long table.');
  browser('press', 'Enter');
  browser('wait', '.markdown-table-scroll');
  browser('wait', '--fn', `[...document.querySelectorAll('[data-horizontal-scroll] span')].some(node => node.textContent.includes('deliberately long cell'))`);

  const result = evaluate(`(() => {
    const scroller = document.querySelector('.markdown-table-scroll');
    const cells = [...document.querySelectorAll('[data-horizontal-scroll] span')];
    return {
      text: cells.map(cell => cell.textContent).join(' '),
      scrollerWidth: scroller.clientWidth,
      scrollerScrollWidth: scroller.scrollWidth,
      cells: cells.map(cell => {
        const style = getComputedStyle(cell);
        return { width: cell.clientWidth, scrollWidth: cell.scrollWidth, height: cell.clientHeight,
          scrollHeight: cell.scrollHeight, whiteSpace: style.whiteSpace, overflow: style.overflow,
          textOverflow: style.textOverflow, overflowWrap: style.overflowWrap };
      }),
      writes: window.__tableWrites.length,
      overflow: document.documentElement.scrollWidth > innerWidth,
    };
  })()`);
  assert.match(result.text, /deliberately long cell/);
  assert.match(result.text, /complete value must remain available/);
  assert.ok(result.scrollerScrollWidth > result.scrollerWidth, 'a multi-column table keeps its horizontal scroll surface');
  assert.equal(result.overflow, false, 'the table must not widen the phone page');
  assert.ok(result.cells.every(cell => cell.whiteSpace === 'normal' && cell.textOverflow === 'clip'), 'cells use readable wrapping');
  assert.ok(result.cells.every(cell => cell.scrollWidth <= cell.width + 1), 'wrapped text is not clipped inside cells');
  assert.ok(result.cells.some(cell => cell.scrollHeight > cell.height || cell.height > 28), 'long content occupies readable lines');
  assert.equal(result.writes, 1, 'the fixture keeps the chat write local');
  console.log('PASS: long assistant tables wrap fully, retain horizontal scrolling and fit a phone');
} catch (error) {
  console.error(browser('snapshot', '-i'));
  console.error(evaluate('({ tableReply: window.__tableReply, writes: window.__tableWrites, body: document.body.innerText.slice(-1000), table: document.querySelector("[data-horizontal-scroll]")?.outerHTML.slice(0, 4000) })'));
  throw error;
} finally {
  browser('close');
}
