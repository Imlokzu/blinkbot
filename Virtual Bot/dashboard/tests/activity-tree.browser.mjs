/** Browser-only SSE fixtures exercise the real chat adapter without server writes. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';

const session = `activity-tree-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding:'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const emit = (type, data) => evaluate(`window.__emit(${JSON.stringify(type)}, ${JSON.stringify(data)}); true`);
const row = id => `[data-tool-step=${JSON.stringify(id)}]`;
const state = id => evaluate(`document.querySelector(${JSON.stringify(row(id))})?.dataset.toolStatus`);
const finish = reply => emit('done', { reply, session_id:'activity-fixture', emotion:'idle', mode:'test', model:'activity-model', tool_results:[] });
const send = message => {
  evaluate('window.__activityStream = null; true');
  browser('fill', '.prompt-bar textarea', message);
  browser('press', 'Enter');
  browser('wait', '--fn', 'Boolean(window.__activityStream)');
};
const overflow = () => {
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'activity must fit the viewport');
  assert.equal(evaluate(`[...document.querySelectorAll('.chat-activity-tree, [data-tool-row]')].every(node => {
    const bounds = node.getBoundingClientRect();
    return !bounds.width || (bounds.left >= -1 && bounds.right <= innerWidth + 1);
  })`), true, 'long paths and open logs must not push rows outside the phone');
};
const readLabelFits = () => {
  const label = evaluate(`(() => {
    const name = document.querySelector('[data-tool-step="read-a"] .chat-activity-tool-name');
    return { text:name.textContent, width:name.clientWidth, contentWidth:name.scrollWidth, viewport:innerWidth };
  })()`);
  assert.equal(label.text, 'Read file');
  assert.ok(label.width >= label.contentWidth - 1,
    `a known read operation must stay readable beside a long path at ${label.viewport}px (${label.width}/${label.contentWidth}px)`);
};
const shot = name => {
  if (process.env.ACTIVITY_TREE_SHOTS) browser('screenshot', `${process.env.ACTIVITY_TREE_SHOTS}/${name}.png`);
};
const controls = selector => evaluate(`(() => {
  const control = document.querySelector(${JSON.stringify(selector)});
  const id = control?.getAttribute('aria-controls');
  return { expanded:control?.getAttribute('aria-expanded'), linked:Boolean(id && document.getElementById(id)) };
})()`);

let socket;
let cdpSession;
let nextId = 0;
const pending = new Map();
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId;
  const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 10000);
  pending.set(id, { resolve:value => { clearTimeout(timeout); resolve(value); }, reject:error => { clearTimeout(timeout); reject(error); } });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});

// Install before React mounts, including after history/locale reloads. The
// catch-all browser route protects initial startup; this guard records and
// rejects every unexpected mutation throughout the exercised interactions.
const init = `(() => {
  window.EventSource = class {
    static CONNECTING = 0; static OPEN = 1; static CLOSED = 2;
    readyState = 1;
    constructor() { queueMicrotask(() => this.onopen?.(new Event('open'))); }
    close() { this.readyState = 2; }
  };
  const original = window.fetch.bind(window);
  const frame = (type, data) => 'event: ' + type + '\\ndata: ' + JSON.stringify(data) + '\\n\\n';
  window.__activitySends = []; window.__activityAborts = 0;
  window.__blockedWrites = JSON.parse(sessionStorage.getItem('activity-fixture-blocked-writes') || '[]');
  window.__emit = (type, data) => window.__activityStream.enqueue(new TextEncoder().encode(frame(type, data)));
  window.fetch = async (url, options) => {
    const pathname = new URL(url instanceof Request ? url.url : String(url), location.href).pathname;
    const method = String(options?.method || (url instanceof Request ? url.method : 'GET')).toUpperCase();
    if (pathname === '/api/chat' && method === 'POST') {
      window.__activitySends.push(JSON.parse(options.body));
      return new Response(new ReadableStream({ start(controller) {
        window.__activityStream = controller;
        options.signal?.addEventListener('abort', () => {
          window.__activityAborts++;
          try { controller.error(new DOMException('Aborted', 'AbortError')); } catch {}
        }, { once:true });
      }, cancel() {} }), { headers:{ 'Content-Type':'text/event-stream' } });
    }
    if (pathname.startsWith('/api/') && !['GET','HEAD'].includes(method)) {
      window.__blockedWrites.push({ pathname, method });
      sessionStorage.setItem('activity-fixture-blocked-writes', JSON.stringify(window.__blockedWrites));
      return Response.json({ error:'Unexpected browser-fixture write' }, { status:405 });
    }
    return original(url, options);
  };
})();`;

const longPath = `session/research/${'a-very-long-directory-name/'.repeat(12)}reference.ts`;
const first = { call_id:'read-a', tool:'workspace__workspace_read', detail:longPath, input:{ path:longPath } };
const second = { call_id:'read-b', tool:'workspace__workspace_read', detail:'session/second.md', input:{ path:'session/second.md' } };
const search = { call_id:'search', tool:'web_search', detail:'reference animation', input:{ query:'reference animation' } };
const untrusted = '<img src=x onerror="window.__activityInjected=true">';
const saved = { id:'activity-history', messages:[{ role:'assistant', content:'Saved activity fixture.', steps:[
  { id:'saved-ok', label:'workspace_read', detail:longPath, status:'done', input:{ path:longPath }, result:{ content:untrusted } },
  { id:'saved-error', label:'web_search', detail:'offline query', status:'failed', result:{ error:'Fixture unavailable' } },
  { id:'saved-stop', label:'workspace_read', detail:'session/unfinished.md', status:'interrupted' },
  { id:'saved-legacy', tool:'legacy_inspector', args:{ path:'session/legacy.md' } },
  { id:'saved-active', label:'memory_search', detail:'old unfinished search', status:'active' },
] }] };

try {
  execFileSync('osascript', ['-e', 'set volume output muted true']);
  if (process.env.ACTIVITY_TREE_SHOTS) mkdirSync(process.env.ACTIVITY_TREE_SHOTS, { recursive:true });
  browser('open', 'about:blank');
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled:true });
  route('**/api/setup', { configured:true, profile:{ configured:true, name:'Test', language:'en', persona:'friendly', reply_length:'balanced', use_emoji:true, spontaneous:false }, languages:[], personas:[], reply_lengths:[], models:[], selected_model:'', keys_set:{} });
  route('**/api/sessions', { sessions:[{ id:'activity-history', title:'Saved activity fixture', count:1 }] });
  route('**/api/sessions/activity-history', saved);
  route('**/api/brain/models', { models:[{ id:'activity-model', label:'Activity model', context:200000 }], selected:'activity-model', default:'activity-model', thinking:'high', thinking_levels:['low','high'], available:true });
  route('**/api/models', { models:[{ id:'activity-model', label:'Activity model' }], selected:'activity-model', default:'activity-model', active:'activity-model', brain:'activity-model' });
  route('**/api/status', { mode:'test' });
  route('**/api/projects', { projects:[] });
  route('**/api/chat/context**', { parts:[], chars:0, dropped:0, history_limit:20 });
  route('**/api/workspace/info**', { root:'/fixture/workspace', session_path:'sessions/activity-fixture' });
  route('**/api/**', {});
  browser('open', `${origin}${path}#/chat`);

  socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once:true });
    socket.addEventListener('error', reject, { once:true });
  });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) request.reject(new Error(JSON.stringify(message.error)));
    else request.resolve(message.result);
  });
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url.startsWith(`${origin}${path}`));
  assert.ok(target, 'the isolated dashboard target must exist');
  cdpSession = (await cdp('Target.attachToTarget', { targetId:target.targetId, flatten:true })).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source:init });
  evaluate("localStorage.setItem('claudeBotLang','en'); localStorage.setItem('claudeBotTheme','light'); localStorage.setItem('claudeBotConversationList','open'); true");
  browser('reload');
  browser('wait', '.prompt-bar__send');

  send('A controlled activity fixture.');
  assert.equal(evaluate('document.querySelectorAll("[data-agent-activity]").length'), 0, 'waiting for a reply must not fabricate tool actions');
  for (const step of [first, second, search]) emit('tool_start', step);
  browser('wait', row('read-a'));
  browser('wait', row('read-b'));
  browser('wait', row('search'));
  assert.equal(evaluate('document.querySelectorAll(".chat-activity-tree").length'), 1);
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step]").length'), 3, 'same-named concurrent calls keep separate rows');
  assert.equal(evaluate('document.querySelector(".chat-activity-tree").hasAttribute("data-running")'), true);
  assert.equal(evaluate('document.querySelectorAll("[data-activity-branches] ol > li[data-tool-step]").length'), 3, 'the branch body is an ordered list');
  assert.deepEqual(controls('[data-activity-toggle]'), { expanded:'true', linked:true });
  assert.ok(evaluate('document.querySelectorAll(".chat-activity-branch").length') >= 3, 'real actions form connected branches');
  assert.equal(evaluate(`[...document.querySelectorAll('.chat-activity-branch')].every(node => node.getAttribute('aria-hidden') !== 'true')`), true, 'connected action rows remain available to assistive technology');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('read-a')} .chat-activity-detail`)}).textContent`), longPath, 'paths remain actual tool metadata');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('search')} .chat-activity-detail`)}).textContent`), search.detail);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('read-a')} [data-tool-state]`)}).textContent`), 'Running');

  const readToggle = `${row('read-a')} button[data-tool-row]`;
  browser('focus', readToggle);
  browser('press', 'Enter');
  browser('wait', `${row('read-a')} [data-tool-details]`);
  assert.deepEqual(controls(readToggle), { expanded:'true', linked:true });
  emit('tool_progress', { ...first, result:{ content:'A partial read' } });
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(`${row('read-a')} [data-tool-details]`)})?.textContent.includes('A partial read')`);
  assert.match(evaluate(`document.querySelector(${JSON.stringify(`${row('read-a')} [data-tool-details]`)}).textContent`), /Parameters.*Partial result/s);
  // A backend snapshot replaces the matching call, never appends another row.
  emit('tool_progress', { step:{ id:'read-a', label:first.tool, detail:longPath, status:'active', input:first.input, result:{ content:'Replacement snapshot' } } });
  browser('wait', '--text', 'Replacement snapshot');
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step]").length'), 3);
  assert.equal(controls(readToggle).expanded, 'true', 'progress replacement preserves an open row');

  browser('focus', '[data-activity-toggle]');
  browser('press', 'Space');
  browser('wait', '--fn', 'document.querySelector("[data-activity-toggle]")?.getAttribute("aria-expanded") === "false"');
  assert.deepEqual(controls('[data-activity-toggle]'), { expanded:'false', linked:true });
  browser('wait', '--fn', 'document.querySelector("[data-activity-branches]").getAnimations().every(animation => animation.playState !== "running") && document.querySelector("[data-activity-branches]").getBoundingClientRect().height === 0');
  assert.equal(evaluate('document.querySelector("[data-activity-branches]").getBoundingClientRect().height'), 0);
  browser('press', 'Enter');
  browser('wait', '--fn', 'document.querySelector("[data-activity-toggle]")?.getAttribute("aria-expanded") === "true"');
  assert.equal(controls(readToggle).expanded, 'true', 'reopening the group preserves row logs');
  assert.equal(evaluate('document.activeElement.matches("[data-activity-toggle]")'), true, 'group toggling retains keyboard focus');

  emit('tool_done', { ...first, result:{ content:'Confirmed read' } });
  emit('tool_error', { ...second, result:{ error:'Fixture read failed' } });
  emit('tool_done', { ...search, result:{ hits:2 } });
  browser('wait', '--fn', 'document.querySelectorAll("[data-tool-status=active]").length === 0');
  assert.deepEqual(['read-a','read-b','search'].map(state), ['done','failed','done']);
  assert.equal(evaluate('document.querySelector(".chat-activity-tree").hasAttribute("data-running")'), false, 'reply streaming alone must not mark completed tools as running');
  assert.match(evaluate(`document.querySelector(${JSON.stringify(`${row('read-a')} [data-tool-details]`)}).textContent`), /Result.*Confirmed read/s);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('read-b')} [data-tool-state]`)}).textContent`), 'Failed');
  const connector = evaluate(`(() => {
    const branch = document.querySelector('[data-tool-step="read-a"]');
    const trunk = getComputedStyle(branch, '::before'), elbow = getComputedStyle(branch, '::after');
    return { trunk:trunk.backgroundColor, width:elbow.borderLeftWidth, style:elbow.borderLeftStyle, color:elbow.borderLeftColor };
  })()`);
  assert.notEqual(connector.trunk, 'rgba(0, 0, 0, 0)', 'the tree trunk resolves to a visible theme color');
  assert.notEqual(connector.trunk, 'transparent');
  assert.deepEqual({ width:connector.width, style:connector.style }, { width:'1px', style:'solid' }, 'each row has a drawn elbow');
  assert.notEqual(connector.color, 'rgba(0, 0, 0, 0)');
  const audit = JSON.parse(browser('a11y', '--selector', '.chat-activity-tree', '--json'));
  assert.equal(audit.data.counts.violations, 0, JSON.stringify(audit.data.violations));
  // Decorative wallpaper may stop automated contrast inference. Exercise the
  // same expanded live tree against a plain surface to catch muted-label drift.
  evaluate(`window.__activityAppearance = localStorage.getItem('claudeBotChatAppearance');
    localStorage.setItem('claudeBotChatAppearance', JSON.stringify({ ...JSON.parse(window.__activityAppearance || '{}'), background:'none' }));
    window.dispatchEvent(new StorageEvent('storage', { key:'claudeBotChatAppearance' })); true`);
  browser('wait', '.chat-conversation[data-wallpaper="none"]');
  const plainAudit = JSON.parse(browser('a11y', '--selector', '.chat-activity-tree', '--json'));
  assert.equal(plainAudit.data.counts.violations, 0, JSON.stringify(plainAudit.data.violations));
  evaluate(`if (window.__activityAppearance === null) localStorage.removeItem('claudeBotChatAppearance');
    else localStorage.setItem('claudeBotChatAppearance', window.__activityAppearance);
    window.dispatchEvent(new StorageEvent('storage', { key:'claudeBotChatAppearance' })); true`);
  browser('wait', '.chat-conversation[data-wallpaper="sky"]');
  readLabelFits();
  shot('activity-desktop');
  for (const width of [390,320]) {
    browser('set', 'viewport', String(width), '844');
    browser('wait', '.chat-phone-toolbar');
    overflow();
    readLabelFits();
    shot(`activity-${width}`);
  }
  finish('The controlled calls finished.');
  browser('wait', '--text', 'The controlled calls finished.');
  assert.deepEqual(['read-a','read-b','search'].map(state), ['done','failed','done']);

  send('A controlled interruption.');
  const pendingRead = { call_id:'stop-active', tool:'workspace_read', detail:longPath, input:{ path:longPath } };
  const completedRead = { call_id:'stop-done', tool:'workspace_read', detail:'session/complete.md', input:{ path:'session/complete.md' } };
  emit('tool_start', pendingRead);
  emit('tool_start', completedRead);
  emit('tool_done', { ...completedRead, result:{ content:'Already confirmed' } });
  browser('wait', row('stop-active'));
  evaluate('const stop = document.querySelector(".prompt-bar__send"); stop.click(); stop.click(); true');
  browser('wait', `${row('stop-active')}[data-tool-status=interrupted]`);
  assert.equal(state('stop-done'), 'done', 'Stop preserves confirmed outcomes');
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step=stop-active]").length'), 1, 'repeated Stop cannot duplicate activity');
  assert.equal(evaluate('document.querySelectorAll("[data-agent-activity][data-running]").length'), 0);
  assert.equal(evaluate('window.__activityAborts'), 1);

  // Image generation keeps its own honest pending surface, alongside ordinary
  // activity, rather than gaining a duplicate generic branch row.
  send('A mixed generation fixture.');
  emit('tool_start', { step:{ id:'paint', label:'image_generate', status:'active', detail:'', input:{ prompt:'A local fixture image' } } });
  emit('tool_start', { call_id:'mixed-search', tool:'web_search', detail:'fixture query', input:{ query:'fixture query' } });
  browser('wait', '[data-image-generation][data-state=generating]');
  browser('wait', row('mixed-search'));
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step=paint]").length'), 0);
  assert.equal(evaluate('document.querySelectorAll("[data-image-generation]").length'), 1);
  browser('click', '.prompt-bar__send');
  browser('wait', '[data-image-generation][data-state=interrupted]');
  browser('wait', `${row('mixed-search')}[data-tool-status=interrupted]`);
  assert.equal(evaluate('window.__activitySends.length'), 3);
  assert.deepEqual(evaluate('window.__blockedWrites'), []);

  // Reloaded legacy activity cannot turn unknown or unfinished outcomes into
  // success. Native reduced-motion emulation also checks the live tree path.
  await cdp('Emulation.setEmulatedMedia', { features:[
    { name:'prefers-color-scheme', value:'dark' }, { name:'prefers-reduced-motion', value:'reduce' },
  ] });
  evaluate("localStorage.setItem('claudeBotLang','uk'); localStorage.setItem('claudeBotTheme','dark'); true");
  browser('reload');
  browser('wait', '.chat-phone-toolbar');
  assert.equal(evaluate('matchMedia("(prefers-reduced-motion: reduce)").matches'), true);
  browser('click', '.chat-narrow-toolbar button[aria-label="Розмови"]');
  browser('wait', '[data-session-id="activity-history"]');
  evaluate('document.querySelector("[data-session-id=activity-history] .truncate").click(); true');
  browser('wait', '--text', 'Saved activity fixture.');
  browser('wait', row('saved-legacy'));
  assert.deepEqual(['saved-ok','saved-error','saved-stop','saved-legacy','saved-active'].map(state), ['done','failed','interrupted','interrupted','interrupted']);
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step]").length'), 5);
  assert.equal(evaluate('document.querySelectorAll("[data-agent-activity][data-running]").length'), 0);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('saved-stop')} [data-tool-state]`)}).textContent`), 'Перервано · завершення не підтверджено');
  browser('focus', `${row('saved-ok')} button[data-tool-row]`);
  browser('press', 'Enter');
  browser('wait', `${row('saved-ok')} [data-tool-details]`);
  assert.match(evaluate(`document.querySelector(${JSON.stringify(`${row('saved-ok')} [data-tool-details]`)}).textContent`), /Параметри.*Результат/s);
  assert.equal(evaluate(`JSON.parse([...document.querySelectorAll(${JSON.stringify(`${row('saved-ok')} [data-tool-details] pre`)})].at(-1).textContent).content`), untrusted);
  assert.equal(evaluate('window.__activityInjected === undefined'), true, 'tool payloads remain inert text');
  assert.equal(evaluate('document.querySelectorAll("[data-tool-details] img").length'), 0);
  assert.notEqual(evaluate(`document.querySelector(${JSON.stringify(`${row('saved-stop')} [data-tool-row]`)}).tagName`), 'BUTTON', 'rows without input/result expose no empty log button');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('saved-stop')} [data-tool-row]`)}).getAttribute('aria-expanded')`), null);
  overflow();
  shot('activity-history-320-reduced');
  assert.equal(evaluate('window.__activitySends.length'), 0, 'restoring history must not send a new request');
  assert.deepEqual(evaluate('window.__blockedWrites'), []);

  send('A reduced-motion fixture.');
  emit('tool_start', { call_id:'reduced-read', tool:'workspace_read', detail:longPath, input:{ path:longPath } });
  browser('wait', row('reduced-read'));
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(row('reduced-read'))}).getAnimations({ subtree:true }).filter(animation => animation.playState === 'running').length`), 0, 'reduced motion suppresses entry and ongoing branch animation');
  overflow();
  browser('click', '.prompt-bar__send');
  browser('wait', `${row('reduced-read')}[data-tool-status=interrupted]`);
  assert.deepEqual(evaluate('window.__blockedWrites'), []);
  console.log('PASS: real SSE concurrency/progress/snapshots/outcomes, keyboard toggles/log state, no fabricated activity, Stop, separate images, saved legacy outcomes, inert payloads, en/uk, desktop/390/320px and native reduced motion; no server mutations');
} catch (error) {
  try {
    console.error(evaluate('({ text:document.body.innerText.slice(-2200), states:[...document.querySelectorAll("[data-tool-step]")].map(node => ({ id:node.dataset.toolStep, status:node.dataset.toolStatus })), blockedWrites:window.__blockedWrites })'));
    shot('activity-failure');
  } catch { /* Preserve the original failure if the browser itself has closed. */ }
  throw error;
} finally {
  socket?.close();
  browser('close');
}
