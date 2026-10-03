/** Browser-only fixtures verify the quiet header, rotating welcome and single panel menu. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `chat-welcome-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const phrase = () => evaluate('document.querySelector(".welcome-heading__phrase")?.textContent');
const overflow = () => assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'chat must not overflow horizontally');
const bounds = () => evaluate(`['.chat-layout','.welcome-heading','[data-chat-composer-position]','.prompt-bar'].map(selector => {
  const rect = document.querySelector(selector).getBoundingClientRect();
  return { x:rect.x, y:rect.y, width:rect.width, height:rect.height };
})`);
const nearBounds = (before, after) => before.forEach((rect, index) => {
  for (const key of ['x', 'y', 'width', 'height']) {
    assert.ok(Math.abs(rect[key] - after[index][key]) <= 1, `welcome rotation must retain ${index}.${key}`);
  }
});
const settleHeading = () => browser('wait', '--fn', `(() => {
  const heading = document.querySelector('.welcome-heading');
  return heading && heading.querySelectorAll('.welcome-heading__phrase').length === 1
    && heading.getAnimations({ subtree:true }).every(animation => animation.playState !== 'running');
})()`);
const menu = () => {
  browser('click', '[data-right-panel-trigger]');
  browser('wait', '[data-right-panel-menu]');
  browser('wait', '--fn', 'document.querySelector("[data-right-panel-menu]").getAnimations({subtree:true}).every(animation => animation.playState !== "running")');
};
const choose = mode => {
  menu();
  browser('click', `[data-panel-choice="${mode}"]`);
  browser('wait', '--fn', 'document.querySelector("[data-right-panel-menu]") === null');
  browser('wait', '--fn', `document.querySelector('[data-right-panel-trigger]').dataset.panelMode === '${mode}'`);
};
const checkMenu = mode => {
  menu();
  assert.deepEqual(evaluate('[...document.querySelectorAll("[data-panel-choice]")].map(item => ({ mode:item.dataset.panelChoice, role:item.getAttribute("role"), checked:item.getAttribute("aria-checked") }))'),
    ['hidden', 'panels', 'workbench'].map(value => ({ mode:value, role:'menuitemradio', checked:String(value === mode) })));
  browser('press', 'Escape');
  browser('wait', '--fn', 'document.querySelector("[data-right-panel-menu]") === null');
  browser('wait', '--fn', 'document.activeElement.matches("[data-right-panel-trigger]")');
};

let socket;
let cdpSession;
let nextId = 0;
const pending = new Map();
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId;
  pending.set(id, { resolve, reject });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});
const media = (theme, reduced) => cdp('Emulation.setEmulatedMedia', { features: [
  { name:'prefers-color-scheme', value:theme },
  { name:'prefers-reduced-motion', value:reduced ? 'reduce' : 'no-preference' },
] });

// Observe real two-second timers before React mounts. A local EventSource avoids
// retry timers, and the fetch guard ensures even unexpected mutations stay local.
const init = `(() => {
  window.__welcomeTimers = new Map(); window.__welcomeTimerLog = [];
  const timeout = window.setTimeout.bind(window), clear = window.clearTimeout.bind(window);
  window.setTimeout = (callback, delay, ...args) => {
    let id;
    id = timeout(() => { window.__welcomeTimers.delete(id); callback(...args); }, delay);
    if (delay === 2000) {
      window.__welcomeTimers.set(id, performance.now());
      window.__welcomeTimerLog.push({ delay, at:performance.now() });
    }
    return id;
  };
  window.clearTimeout = id => { window.__welcomeTimers.delete(id); clear(id); };
  window.EventSource = class {
    static CONNECTING = 0; static OPEN = 1; static CLOSED = 2;
    readyState = 1;
    constructor() { queueMicrotask(() => this.onopen?.(new Event('open'))); }
    close() { this.readyState = 2; }
  };
  const fetch = window.fetch.bind(window);
  window.__welcomeSends = []; window.__blockedWrites = [];
  const event = (name, data) => 'event: ' + name + '\\ndata: ' + JSON.stringify(data) + '\\n\\n';
  window.__emit = (name, data) => window.__chatStream.enqueue(new TextEncoder().encode(event(name, data)));
  window.fetch = async (url, options) => {
    const pathname = new URL(url instanceof Request ? url.url : String(url), location.href).pathname;
    const method = String(options?.method || (url instanceof Request ? url.method : 'GET')).toUpperCase();
    if (pathname === '/api/chat' && method === 'POST') {
      window.__welcomeSends.push(JSON.parse(options.body));
      if (window.__streaming) return new Response(new ReadableStream({ start(controller) { window.__chatStream = controller; } }), { headers:{ 'Content-Type':'text/event-stream' } });
      return new Response(event('delta', { chunk:'A browser-only reply.' }) + event('done', {
        reply:'A browser-only reply.', session_id:'welcome-fixture', emotion:'idle', mode:'test', model:'test-welcome-model', tool_results:[],
      }), { headers:{ 'Content-Type':'text/event-stream' } });
    }
    if (pathname.startsWith('/api/') && !['GET','HEAD'].includes(method)) {
      window.__blockedWrites.push({ pathname, method });
      return Response.json({ ok:true });
    }
    return fetch(url, options);
  };
})();`;

try {
  execFileSync('osascript', ['-e', 'set volume output muted true']);
  browser('open', 'about:blank');
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled:true });
  route('**/api/setup', { configured:true, profile:{ configured:true, name:'Test', language:'en', persona:'friendly', persona_custom:'', greeting:'', reply_length:'balanced', use_emoji:true, spontaneous:false }, languages:[], personas:[], reply_lengths:[], models:[], selected_model:'', keys_set:{ omni:false, openclaw:false } });
  route('**/api/sessions', { sessions:[{ id:'history', title:'Saved welcome fixture', count:2 }] });
  route('**/api/sessions/history', { id:'history', messages:[{ role:'user', content:'A saved question.' }, { role:'assistant', content:'A saved answer.' }] });
  route('**/api/brain/models', { models:[{ id:'test-welcome-model', label:'Test welcome model', context:200000 }], selected:'test-welcome-model', default:'test-welcome-model', thinking:'high', thinking_levels:['low','high'], available:true });
  route('**/api/models', { models:[{ id:'test-welcome-model', label:'Test welcome model' }], selected:'test-welcome-model', default:'test-welcome-model', active:'test-welcome-model', brain:'test-welcome-model' });
  route('**/api/status', { omni:true, openclaw:true, anthropic:true, chat2api:true, vision:true, display:true, mode:'test' });
  route('**/api/chat/context**', { parts:[], chars:0, dropped:0, history_limit:20 });
  route('**/api/projects', { projects:[] });
  route('**/api/workspace/info**', { root:'/fixture/workspace', session_path:'sessions/welcome-fixture' });
  // Routes match in registration order: this final fallback also intercepts writes.
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

  for (const [language, theme] of [['en','light'], ['uk','dark']]) {
    await media(theme, false);
    evaluate(`localStorage.setItem('claudeBotLang', '${language}'); localStorage.setItem('claudeBotTheme', '${theme}');
      localStorage.setItem('claudeBotConversationList','open'); localStorage.setItem('claudeBotChatPins','["projects","clock"]');
      localStorage.removeItem('claudeBotChatAppearance'); true`);
    browser('reload');
    browser('wait', '.welcome-heading');
    settleHeading();
    assert.equal(evaluate('document.documentElement.lang'), language);
    assert.equal(evaluate('document.documentElement.dataset.theme'), theme);
    const original = evaluate('document.querySelector(".welcome-heading").getAttribute("aria-label")');
    assert.equal(phrase(), original);
    assert.equal(evaluate('new Set([...document.querySelectorAll(".welcome-heading__measure")].map(node => node.textContent)).size'), 4);
    assert.equal(evaluate('window.__welcomeTimers.size'), 1, 'one welcome timer must run');
    assert.equal(evaluate('document.querySelectorAll("[data-chat-toolbar] button").length'), 3);
    assert.equal(evaluate('document.querySelectorAll("[data-right-panel-trigger]").length'), 1);
    const newLabel = evaluate('document.querySelectorAll("[data-chat-toolbar] button")[1].getAttribute("aria-label")');
    assert.equal(evaluate('document.querySelector("[data-global-topbar]").textContent.includes("Test welcome model")'), false);
    assert.equal(evaluate('document.querySelectorAll("[data-global-topbar] .size-2.rounded-full").length'), 0, 'global status dots must be absent');
    assert.doesNotMatch(evaluate('document.querySelector("[data-global-topbar]").textContent'), /local|локально/i);

    browser('fill', '.prompt-bar textarea', 'Keep this welcome draft.');
    evaluate('window.__welcomeTextarea = document.querySelector(".prompt-bar textarea"); true');
    const before = bounds();
    browser('wait', '--fn', `document.querySelector('.welcome-heading__phrase')?.textContent !== ${JSON.stringify(original)}`);
    settleHeading();
    nearBounds(before, bounds());
    assert.equal(evaluate('document.querySelector(".prompt-bar textarea") === window.__welcomeTextarea'), true);
    assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Keep this welcome draft.');
    assert.equal(evaluate('document.querySelector(".welcome-heading").getAttribute("aria-label")'), original, 'assistive text stays quiet during visual rotation');

    evaluate(`window.__frozenPhrase = document.querySelector('.welcome-heading__phrase').textContent;
      window.__hiddenAt = performance.now(); Object.defineProperty(document,'hidden',{ configurable:true,value:true });
      document.dispatchEvent(new Event('visibilitychange')); true`);
    assert.equal(evaluate('window.__welcomeTimers.size'), 0, 'hidden documents cancel welcome timers');
    browser('wait', '--fn', 'performance.now() - window.__hiddenAt >= 2300');
    assert.equal(phrase(), evaluate('window.__frozenPhrase'), 'a hidden document must keep its current phrase');
    evaluate("delete document.hidden; document.dispatchEvent(new Event('visibilitychange')); true");
    browser('wait', '--fn', 'window.__welcomeTimers.size === 1');
    browser('wait', '--fn', 'document.querySelector(".welcome-heading__phrase")?.textContent !== window.__frozenPhrase');
    settleHeading();
    nearBounds(before, bounds());

    checkMenu('panels');
    const pins = evaluate('localStorage.getItem("claudeBotChatPins")');
    choose('hidden');
    assert.equal(evaluate('document.querySelector(".chat-pins") === null && document.querySelector(".workbench") === null'), true);
    browser('reload');
    browser('wait', '[data-right-panel-trigger]');
    assert.equal(evaluate('document.querySelector("[data-right-panel-trigger]").dataset.panelMode'), 'hidden');
    assert.equal(evaluate('localStorage.getItem("claudeBotChatPins")'), pins);
    checkMenu('hidden');
    choose('panels');
    browser('wait', '.chat-pins');
    assert.deepEqual(evaluate('[...document.querySelectorAll(".chat-pins [data-pin]")].map(node => node.dataset.pin)'), ['projects','clock']);
    choose('workbench');
    browser('wait', '.workbench');
    checkMenu('workbench');
    browser('click', '.workbench header button[aria-label]');
    browser('wait', '--fn', 'document.querySelector(".workbench") === null');
    browser('wait', '--fn', 'document.activeElement.matches("[data-right-panel-trigger]")');
    browser('wait', '.chat-pins');
    assert.equal(evaluate('localStorage.getItem("claudeBotChatPins")'), pins);

    // A submitted message and restored history both end rotation; New starts at phrase one.
    browser('fill', '.prompt-bar textarea', 'A browser-only message.');
    browser('press', 'Enter');
    browser('wait', '--text', 'A browser-only reply.');
    assert.equal(evaluate('document.querySelector(".welcome-heading") === null'), true);
    assert.equal(evaluate('window.__welcomeTimers.size'), 0);
    browser('click', `[data-chat-toolbar] button[aria-label=${JSON.stringify(newLabel)}]`);
    browser('wait', '.welcome-heading');
    assert.equal(phrase(), original);
    browser('click', '[data-session-id="history"]');
    browser('wait', '--text', 'A saved question.');
    assert.equal(evaluate('document.querySelector(".welcome-heading") === null'), true);
    assert.equal(evaluate('window.__welcomeTimers.size'), 0);
    browser('click', `[data-chat-toolbar] button[aria-label=${JSON.stringify(newLabel)}]`);
    browser('wait', '.welcome-heading');
    assert.equal(phrase(), original);

    // Explicit panel choices made before a write must win over automatic opening.
    evaluate("window.__streaming = true; window.__vbotSendMessage('A delayed browser-only write.'); true");
    browser('wait', '--fn', 'Boolean(window.__chatStream)');
    for (const mode of ['hidden','panels']) {
      choose(mode);
      const write = { call_id:`write-${mode}`, tool:'workspace_write', detail:`notes/${mode}.md`, input:{ path:`notes/${mode}.md`, content:'# A local fixture' } };
      evaluate(`window.__emit('tool_start', ${JSON.stringify(write)}); true`);
      browser('wait', '--fn', `document.querySelector('.chat-conversation').textContent.includes('notes/${mode}.md')`);
      evaluate(`window.__emit('tool_done', ${JSON.stringify({ ...write, result:{ ok:true, path:`notes/${mode}.md` } })}); true`);
      assert.equal(evaluate('document.querySelector(".workbench") === null'), true, `${mode} must suppress Workbench auto-open during the reply`);
      assert.equal(evaluate('document.querySelector("[data-right-panel-trigger]").dataset.panelMode'), mode);
    }
    evaluate(`window.__emit('done', { reply:'A delayed fixture completed.', session_id:'welcome-fixture', emotion:'idle', mode:'test', model:'test-welcome-model', tool_results:[] }); window.__chatStream.close(); true`);
    browser('wait', '--text', 'A delayed fixture completed.');
    assert.deepEqual(evaluate('window.__blockedWrites'), []);
    overflow();
  }

  for (const [language, theme] of [['en','dark'], ['uk','light']]) {
    await media(theme, true);
    evaluate(`localStorage.setItem('claudeBotLang','${language}'); localStorage.setItem('claudeBotTheme','${theme}'); true`);
    browser('reload');
    browser('wait', '.welcome-heading');
    assert.equal(evaluate('matchMedia("(prefers-reduced-motion: reduce)").matches'), true, 'native CDP media emulation must be active');
    const original = evaluate('document.querySelector(".welcome-heading").getAttribute("aria-label")');
    evaluate('window.__reducedAt = performance.now(); true');
    browser('wait', '--fn', 'performance.now() - window.__reducedAt >= 2300');
    assert.equal(phrase(), original);
    assert.deepEqual(evaluate('window.__welcomeTimerLog'), [], 'reduced motion must not schedule a welcome rotation');
    assert.equal(evaluate('document.querySelector(".welcome-heading").getAnimations({subtree:true}).length'), 0);
    overflow();

    browser('set', 'viewport', '390', '844');
    browser('wait', '.chat-phone-toolbar');
    assert.equal(evaluate('document.querySelector("[data-chat-toolbar]") === null && document.querySelector("[data-right-panel-trigger]") === null'), true);
    assert.equal(evaluate('document.querySelectorAll(".chat-phone-toolbar .brain-choice-trigger").length'), 1, 'the phone keeps its model selector');
    assert.equal(evaluate('document.querySelectorAll(".chat-phone-toolbar button").length'), 4, 'the phone keeps navigation, conversations, model and New');
    overflow();
    browser('set', 'viewport', '1440', '960');
  }
  console.log('PASS: en/uk, light/dark, welcome timing/draft/layout/visibility/reset, native reduced motion, quiet header, panel radio menu/persistence/focus/write suppression, phone toolbar');
} catch (error) {
  console.error(browser('errors'));
  if (process.env.CHAT_WELCOME_SHOTS) browser('screenshot', `${process.env.CHAT_WELCOME_SHOTS}/chat-welcome-failure.png`);
  throw error;
} finally {
  socket?.close();
  browser('close');
}
