/** Browser-only fixtures keep every reaction and chat submission off the server. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';

let session = `reaction-actions-${process.pid}-desktop`;
let sessionOpen = false;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding:'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const root = '[data-assistant-message="reaction-fixture-1"]';
const trigger = `${root} [data-reply-actions] button[data-reaction-trigger]`;
const picker = '[data-reaction-picker][role="menu"]';
const item = `${picker} button[role="menuitem"]`;
const badge = (index, emoji) => `${root} [data-assistant-bubble="${index}"] button[aria-label=${JSON.stringify(`Your reaction ${emoji}`)}]`;
const rect = selector => evaluate(`(() => {
  const bounds = document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();
  return { left:bounds.left, right:bounds.right, top:bounds.top, bottom:bounds.bottom, width:bounds.width, height:bounds.height };
})()`);
const activeItem = () => evaluate(`[...document.querySelectorAll(${JSON.stringify(item)})].indexOf(document.activeElement)`);
const shot = name => {
  if (process.env.REACTION_ACTIONS_SHOTS) browser('screenshot', `${process.env.REACTION_ACTIONS_SHOTS}/${name}.png`);
};
const settle = selector => browser('wait', '--fn', `(() => {
  const node = document.querySelector(${JSON.stringify(selector)});
  return node && node.getAnimations({ subtree:true }).every(animation => animation.playState !== 'running');
})()`);
const openPicker = (selector = trigger) => {
  browser('click', selector);
  browser('wait', picker);
  settle(picker);
  browser('wait', '--fn', `document.activeElement?.matches(${JSON.stringify(item)})`);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(selector)}).getAttribute('aria-expanded')`), 'true');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(selector)}).getAttribute('aria-controls') === document.querySelector(${JSON.stringify(picker)}).id`), true);
  assert.equal(evaluate(`[...document.querySelectorAll(${JSON.stringify(item)})].every(node => {
    const bounds = node.getBoundingClientRect();
    return document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2)?.closest('button[role="menuitem"]') === node;
  })`), true, 'every reaction menu choice must have an unobstructed click target');
};
const closedPicker = (selector = trigger) => {
  assert.equal(evaluate('location.origin'), origin, 'the isolated browser must retain its dashboard page while closing the picker');
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(picker)}) === null`);
  browser('wait', '--fn', `document.activeElement?.matches(${JSON.stringify(selector)})`);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(selector)}).getAttribute('aria-expanded')`), 'false');
};
const popupFits = width => {
  const popup = rect(picker);
  assert.ok(popup.left >= -1 && popup.right <= width + 1 && popup.top >= -1 && popup.bottom <= 845, `${width}px reaction menu must fit the viewport`);
  assert.equal(evaluate(`(() => {
    const popup = document.querySelector(${JSON.stringify(picker)}), bounds = popup.getBoundingClientRect();
    for (let ancestor = popup.parentElement; ancestor; ancestor = ancestor.parentElement) {
      const style = getComputedStyle(ancestor), clip = ancestor.getBoundingClientRect();
      if (/(auto|scroll|hidden|clip)/.test(style.overflowY) && (bounds.top < clip.top - 1 || bounds.bottom > clip.bottom + 1)) return false;
      if (/(auto|scroll|hidden|clip)/.test(style.overflowX) && (bounds.left < clip.left - 1 || bounds.right > clip.right + 1)) return false;
    }
    return true;
  })()`), true, 'scroll and layout containers must not clip the phone reaction menu');
};
const loadSaved = (id = 'reaction-fixture') => {
  if (evaluate('Boolean(document.querySelector(".chat-session-drawer"))')) settle('.chat-session-drawer');
  browser('click', `[data-session-id="${id}"]`);
  browser('wait', `[data-assistant-message="${id}-1"]`);
  browser('wait', '--fn', 'document.querySelector(".chat-session-drawer") === null');
  settle(`[data-assistant-message="${id}-1"]`);
};

// Saved text indices include narration. A trailing note must never steal the
// bottom action from the final answer, and an older badge keeps its old index.
const saved = { id:'reaction-fixture', messages:[
  { id:'fixture-question', role:'user', content:'A saved reaction question.' },
  { id:'fixture-answer', role:'assistant', content:'First saved answer.\n\nFinal saved answer.', reactions:{ '1':'👍' }, parts:[
    { type:'text', text:'A note before the answer.', note:true },
    { type:'text', text:'First saved answer.' },
    { type:'text', text:'A note between the answers.', note:true },
    { type:'text', text:'Final saved answer.' },
    { type:'text', text:'A note after the answer.', note:true },
  ] },
] };
const imageStep = { id:'fixture-image', label:'image_generate', detail:'', status:'done', input:{ prompt:'A deterministic local image' },
  result:{ provider:'codex', images:[{ url:'/uploads/reaction-fixture.png', type:'image/png' }] } };
const imageSaved = { id:'reaction-image-fixture', messages:[
  { role:'user', content:'A saved image reaction question.' },
  { id:'fixture-image-answer', role:'assistant', content:'A visible answer after its generated image.', steps:[imageStep], parts:[
    { type:'text', text:'A note before the image.', note:true },
    { type:'steps', ids:[imageStep.id] },
    { type:'text', text:'![A local image](/uploads/reaction-fixture.png)' },
    { type:'text', text:'A visible answer after its generated image.' },
    { type:'text', text:'A note after the image answer.', note:true },
  ] },
] };
const noteSaved = { id:'reaction-notes-fixture', messages:[
  { role:'user', content:'A saved narration question.' },
  { id:'fixture-notes-answer', role:'assistant', content:'', parts:[{ type:'text', text:'Only narration is available.', note:true }] },
] };
const shortSaved = { id:'reaction-short-fixture', messages:[
  { role:'user', content:'A short saved question.' },
  { id:'fixture-short-answer', role:'assistant', content:'A short saved answer.' },
] };
const fixtures = {
  '/api/auth/config': { disabled:true },
  '/api/setup': { configured:true, profile:{ configured:true, name:'Test', language:'en', persona:'friendly', reply_length:'balanced', use_emoji:true, spontaneous:false }, languages:[], personas:[], reply_lengths:[], models:[], selected_model:'', keys_set:{} },
  '/api/sessions': { sessions:[
    { id:saved.id, title:'Saved reaction fixture', count:2 },
    { id:imageSaved.id, title:'Saved image reaction fixture', count:2 },
    { id:noteSaved.id, title:'Saved narration fixture', count:2 },
    { id:shortSaved.id, title:'Short reaction fixture', count:2 },
  ] },
  '/api/sessions/reaction-fixture': saved,
  '/api/sessions/reaction-image-fixture': imageSaved,
  '/api/sessions/reaction-notes-fixture': noteSaved,
  '/api/sessions/reaction-short-fixture': shortSaved,
  '/api/brain/models': { models:[{ id:'reaction-model', label:'Reaction model', context:200000 }], selected:'reaction-model', default:'reaction-model', thinking:'high', thinking_levels:['low','high'], available:true },
  '/api/models': { models:[{ id:'reaction-model', label:'Reaction model' }], selected:'reaction-model', default:'reaction-model', active:'reaction-model', brain:'reaction-model' },
  '/api/status': { mode:'test' },
  '/api/projects': { projects:[] },
  '/api/chat/context': { parts:[], chars:0, dropped:0, history_limit:20 },
  '/api/workspace/info': { root:'/fixture/workspace', session_path:'sessions/reaction-fixture' },
  '/api/tts/status': { enabled:false },
};
// A local 16px PNG avoids image downloads when testing hidden delivery text.
const png = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAGUlEQVR4nGPQq73znxLMMGrAqAGjBgwXAwAX4YYf8tQajgAAAABJRU5ErkJggg==';

let socket;
let cdpSession;
let nextId = 0;
const pending = new Map();
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId;
  const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 10000);
  pending.set(id, {
    resolve:value => { clearTimeout(timeout); resolve(value); },
    reject:error => { clearTimeout(timeout); reject(error); },
  });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});

// Installed on about:blank before the dashboard first mounts. Fixture routes
// answer GET only; every allowed POST is handled here without a network call.
// Browser abort routes also contain requests that bypass window.fetch.
const init = `(() => {
  const fixtures = ${JSON.stringify(fixtures)};
  window.EventSource = class {
    static CONNECTING = 0; static OPEN = 1; static CLOSED = 2;
    readyState = 1;
    constructor() { queueMicrotask(() => this.onopen?.(new Event('open'))); }
    close() { this.readyState = 2; }
  };
  const original = window.fetch.bind(window);
  const newline = String.fromCharCode(10);
  const frame = (type, data) => 'event: ' + type + newline + 'data: ' + JSON.stringify(data) + newline + newline;
  const restore = name => JSON.parse(sessionStorage.getItem(name) || '[]');
  const record = (name, list, value) => { list.push(value); sessionStorage.setItem(name, JSON.stringify(list)); };
  window.__reactionWrites = restore('reaction-fixture-writes');
  window.__blockedWrites = restore('reaction-fixture-blocked-writes');
  window.__chatSends = restore('reaction-fixture-chat-sends');
  window.__flights = [];
  window.__emit = (type, data) => window.__reactionStream.enqueue(new TextEncoder().encode(frame(type, data)));
  window.fetch = async (url, options) => {
    const request = url instanceof Request ? url : null;
    const pathname = new URL(request ? request.url : String(url), location.href).pathname;
    const method = String(options?.method || request?.method || 'GET').toUpperCase();
    if (method === 'GET' && pathname === '/uploads/reaction-fixture.png') {
      return new Response(Uint8Array.from(atob('${png}'), byte => byte.charCodeAt(0)), { headers:{ 'Content-Type':'image/png' } });
    }
    if (method === 'GET' && pathname.startsWith('/api/')) return Response.json(fixtures[pathname] || {});
    if (method === 'POST' && pathname === '/api/sessions/reaction-fixture/reactions') {
      const body = JSON.parse(options?.body ?? await request.clone().text());
      record('reaction-fixture-writes', window.__reactionWrites, { pathname, body });
      return Response.json({ ok:true });
    }
    if (method === 'POST' && pathname === '/api/chat') {
      const body = JSON.parse(options?.body ?? await request.clone().text());
      record('reaction-fixture-chat-sends', window.__chatSends, body);
      return new Response(new ReadableStream({ start(controller) {
        window.__reactionStream = controller;
        (options?.signal || request?.signal)?.addEventListener('abort', () => {
          try { controller.error(new DOMException('Aborted', 'AbortError')); } catch {}
        }, { once:true });
      }, cancel() {} }), { headers:{ 'Content-Type':'text/event-stream' } });
    }
    if (method !== 'GET') {
      record('reaction-fixture-blocked-writes', window.__blockedWrites, { pathname, method });
      return Response.json({ error:'Unexpected browser-fixture write' }, { status:405 });
    }
    return original(url, options);
  };
  const observe = () => new MutationObserver(records => {
    for (const record of records) for (const node of record.addedNodes) {
      if (!(node instanceof HTMLElement) || !node.matches('.chat-emoji-flight')) continue;
      const value = name => Number(node.style.getPropertyValue(name));
      window.__flights.push({ emoji:node.textContent, x:value('--x'), y:value('--y'), dx:value('--dx'), dy:value('--dy') });
    }
  }).observe(document.body, { childList:true });
  if (document.body) observe(); else document.addEventListener('DOMContentLoaded', observe, { once:true });
})();`;

const closeFixture = () => {
  socket?.close();
  socket = undefined;
  cdpSession = undefined;
  if (sessionOpen) browser('close');
  sessionOpen = false;
};
const startFixture = async (name, width = 1440, language = 'en', theme = 'light', touch = false) => {
  session = `reaction-actions-${process.pid}-${name}`;
  execFileSync('osascript', ['-e', 'set volume output muted true']);
  sessionOpen = true;
  browser('open', 'about:blank');
  browser('set', 'viewport', String(width), width < 760 ? '844' : '960');
  browser('network', 'route', '**/api/**', '--abort');
  browser('network', 'route', '**/uploads/**', '--abort');

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
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url === 'about:blank');
  assert.ok(target, 'the isolated blank dashboard target must exist');
  cdpSession = (await cdp('Target.attachToTarget', { targetId:target.targetId, flatten:true })).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source:init });
  await cdp('Emulation.setEmulatedMedia', { features:[{ name:'prefers-reduced-motion', value:'no-preference' }] });
  await cdp('Emulation.setTouchEmulationEnabled', { enabled:touch, maxTouchPoints:1 });
  browser('open', `${origin}${path}#/chat`);
  evaluate(`localStorage.setItem('claudeBotLang','${language}'); localStorage.setItem('claudeBotTheme','${theme}'); localStorage.setItem('claudeBotConversationList','open'); true`);
  browser('reload');
};

try {
  if (process.env.REACTION_ACTIONS_SHOTS) mkdirSync(process.env.REACTION_ACTIONS_SHOTS, { recursive:true });
  await startFixture('desktop');
  browser('wait', '[data-session-id="reaction-fixture"]');
  loadSaved();

  assert.equal(evaluate(`document.querySelectorAll(${JSON.stringify(`${root} [data-reaction-trigger]`)}).length`), 1, 'one bottom action serves the whole reply');
  assert.equal(evaluate(`document.querySelectorAll(${JSON.stringify(`${root} [data-assistant-bubble] [data-reaction-trigger]`)}).length`), 0, 'answer and note bubbles must have no side triggers');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(trigger)}).dataset.reactionBubble`), '3', 'trailing narration must not replace the final answer target');
  assert.deepEqual(evaluate(`[...document.querySelectorAll(${JSON.stringify(`${root} [data-assistant-bubble]`)})].map(node => node.dataset.assistantBubble)`), ['0','1','2','3','4']);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(trigger)}).getAttribute('aria-label')`), 'React');
  assert.equal(evaluate(`Boolean(document.querySelector(${JSON.stringify(badge(1, '👍'))}))`), true, 'the saved older badge remains removable');

  openPicker();
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(picker)}).getAttribute('aria-label')`), 'Pick a reaction');
  assert.equal(evaluate(`document.querySelectorAll(${JSON.stringify(item)}).length`), 8);
  for (const [key, expected] of [['End',7], ['ArrowRight',0], ['ArrowLeft',7], ['Home',0]]) {
    browser('press', key);
    assert.equal(activeItem(), expected, `${key} must navigate the reaction menu`);
  }
  browser('press', 'ArrowDown');
  assert.notEqual(activeItem(), 0, 'ArrowDown must move menu focus');
  browser('press', 'ArrowUp');
  assert.equal(activeItem(), 0, 'ArrowUp must return to the prior menu item');
  browser('press', 'Escape');
  closedPicker();
  openPicker();
  browser('press', 'Tab');
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(picker)}) === null`);
  assert.equal(evaluate(`document.activeElement?.isConnected && Boolean(document.activeElement.closest(${JSON.stringify(`${root} [data-reply-actions]`)}))`), true, 'Tab must close the picker and retain focus in the reply actions');
  openPicker();
  browser('click', '.prompt-bar textarea');
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(picker)}) === null`);
  assert.equal(evaluate('window.__reactionWrites.length'), 0, 'dismissal must never submit a reaction');

  // Selection comes from the menu, but its flight still lands on the answer.
  openPicker();
  browser('focus', `${item}[aria-label="🔥"]`);
  const destination = rect(`${root} [data-assistant-bubble="3"]`);
  const source = rect(`${item}[aria-label="🔥"]`);
  browser('press', 'Enter');
  closedPicker();
  browser('wait', '--fn', 'window.__reactionWrites.length === 1 && window.__flights.length === 1');
  const flight = evaluate('window.__flights[0]');
  assert.equal(flight.emoji, '🔥');
  assert.ok(Math.abs(flight.x - (source.left + source.width / 2)) <= 1, 'the emoji flight starts at the selected menu item');
  assert.ok(Math.abs(flight.y - (source.top + source.height / 2)) <= 1);
  assert.ok(Math.abs(flight.x + flight.dx - (destination.left + 22)) <= 1, 'the flight lands on final answer index 3');
  assert.ok(Math.abs(flight.y + flight.dy - destination.bottom) <= 1);
  browser('wait', badge(3, '🔥'));
  settle(badge(3, '🔥'));
  openPicker();
  browser('find', 'role', 'menuitem', 'click', '--name', 'Remove reaction 🔥', '--exact');
  closedPicker();
  browser('wait', '--fn', 'window.__reactionWrites.length === 2');
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(badge(3, '🔥'))}) === null`);
  browser('click', badge(1, '👍'));
  browser('wait', '--fn', 'window.__reactionWrites.length === 3');
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(badge(1, '👍'))}) === null`);
  assert.deepEqual(evaluate('window.__reactionWrites'), [
    { pathname:'/api/sessions/reaction-fixture/reactions', body:{ message_id:'fixture-answer', bubble:3, emoji:'🔥' } },
    { pathname:'/api/sessions/reaction-fixture/reactions', body:{ message_id:'fixture-answer', bubble:3, emoji:null } },
    { pathname:'/api/sessions/reaction-fixture/reactions', body:{ message_id:'fixture-answer', bubble:1, emoji:null } },
  ]);
  assert.equal(evaluate('window.__flights.length'), 1, 'removal must not launch another emoji flight');

  loadSaved('reaction-image-fixture');
  const imageRoot = '[data-assistant-message="reaction-image-fixture-1"]';
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${imageRoot} [data-assistant-bubble="1"]`)}) === null`), true, 'generated image delivery is hidden');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${imageRoot} [data-reaction-trigger]`)}).dataset.reactionBubble`), '2', 'hidden image text keeps its index in the final answer address');
  loadSaved('reaction-notes-fixture');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify('[data-assistant-message="reaction-notes-fixture-1"] [data-reaction-trigger]')}) === null`), true, 'narration alone has no answer target');
  assert.deepEqual(evaluate('window.__blockedWrites'), [], 'desktop reactions must not make any unexpected server write');
  closeFixture();

  for (const [width, language, theme] of [
    [320,'en','light'],
    [390,'uk','dark'],
  ]) {
    await startFixture(`phone-${width}`, width, language, theme, true);
    browser('wait', '.chat-phone-toolbar');
    browser('click', '.chat-phone-toolbar > button[aria-expanded]:not([data-phone-navigation])');
    browser('wait', '[data-session-id="reaction-fixture"]');
    loadSaved();
    assert.equal(evaluate('document.documentElement.lang'), language);
    const triggerLabel = evaluate(`document.querySelector(${JSON.stringify(trigger)}).getAttribute('aria-label')`);
    assert.ok(triggerLabel, 'the reaction trigger must have a localized accessible label');
    if (language === 'en') assert.equal(triggerLabel, 'React');
    else assert.notEqual(triggerLabel, 'React', 'the translated trigger must use its locale');
    const touch = rect(trigger);
    assert.ok(touch.width >= 44 && touch.height >= 44, `${width}px phone reaction target must be at least 44px`);
    assert.equal(evaluate(`[...document.querySelectorAll(${JSON.stringify(`${root} [data-reply-actions] button`)})].every(node => {
      const bounds = node.getBoundingClientRect(); return bounds.width >= 44 && bounds.height >= 44;
    })`), true, 'every phone reply action must have a 44px hit target');
    openPicker();
    const pickerLabel = evaluate(`document.querySelector(${JSON.stringify(picker)}).getAttribute('aria-label')`);
    assert.ok(pickerLabel, 'the reaction picker must have a localized accessible label');
    if (language === 'en') assert.equal(pickerLabel, 'Pick a reaction');
    else assert.notEqual(pickerLabel, 'Pick a reaction', 'the translated picker must use its locale');
    assert.equal(evaluate(`getComputedStyle(document.querySelector(${JSON.stringify(item)}).parentElement).gridTemplateColumns.split(' ').length`), 4);
    assert.equal(evaluate(`[...document.querySelectorAll(${JSON.stringify(item)})].every(node => {
      const bounds = node.getBoundingClientRect(); return bounds.width >= 44 && bounds.height >= 44;
    })`), true, 'every phone menu item must have a 44px hit target');
    popupFits(width);
    assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'reaction actions must not overflow horizontally');
    shot(`reaction-${width}-${language}-${theme}`);
    browser('press', 'Escape');
    closedPicker();
    browser('click', '.chat-phone-toolbar > button[aria-expanded]:not([data-phone-navigation])');
    browser('wait', '[data-session-id="reaction-short-fixture"]');
    loadSaved('reaction-short-fixture');
    const shortTrigger = '[data-assistant-message="reaction-short-fixture-1"] [data-reply-actions] button[data-reaction-trigger]';
    openPicker(shortTrigger);
    popupFits(width);
    shot(`reaction-short-${width}-${language}-${theme}`);
    browser('press', 'Escape');
    closedPicker(shortTrigger);
    assert.equal(evaluate('window.__reactionWrites.length'), 0, 'phone layout and dismissal must not submit reactions');
    assert.deepEqual(evaluate('window.__blockedWrites'), [], 'phone checks must not make any unexpected server write');
    closeFixture();
  }

  // A live adapter gets no reaction action until the server supplies an address.
  await startFixture('live-coarse', 1440, 'en', 'light', true);
  browser('wait', '[data-session-id="reaction-short-fixture"]');
  loadSaved('reaction-short-fixture');
  assert.equal(evaluate('matchMedia("(pointer: coarse)").matches'), true, 'native touch emulation must exercise coarse-pointer sizing');
  assert.equal(evaluate(`[...document.querySelectorAll('[data-assistant-message="reaction-short-fixture-1"] [data-reply-actions] button')].every(node => {
    const bounds = node.getBoundingClientRect(); return bounds.width >= 44 && bounds.height >= 44;
  })`), true, 'coarse-pointer reply actions must remain at least 44px at a wide viewport');
  // A wide touch screen can still have a narrow chat beside a resized Workbench.
  // Use two menu rows so its last choice retains its complete touch target.
  evaluate("localStorage.setItem('claudeBotWorkbenchWidth','10000'); true");
  browser('reload');
  browser('wait', '[data-session-id="reaction-short-fixture"]');
  loadSaved('reaction-short-fixture');
  browser('click', '[data-right-panel-trigger]');
  browser('wait', '[data-panel-choice="workbench"]');
  browser('click', '[data-panel-choice="workbench"]');
  browser('wait', '[role="separator"]');
  browser('wait', '--fn', 'document.querySelector("[data-right-panel-menu]") === null');
  const coarseTrigger = '[data-assistant-message="reaction-short-fixture-1"] [data-reaction-trigger]';
  openPicker(coarseTrigger);
  assert.equal(evaluate('getComputedStyle(document.querySelector("[data-reaction-picker]")).gridTemplateColumns.split(" ").length'), 4, 'coarse-pointer reaction menus keep four columns even on desktop');
  popupFits(1440);
  browser('press', 'Escape');
  closedPicker(coarseTrigger);
  await cdp('Emulation.setTouchEmulationEnabled', { enabled:false });
  evaluate("localStorage.setItem('claudeBotLang','en'); true");
  browser('reload');
  browser('wait', '.welcome-heading');
  browser('fill', '.prompt-bar textarea', 'A local unaddressed live reply.');
  browser('press', 'Enter');
  browser('wait', '--fn', 'Boolean(window.__reactionStream)');
  evaluate("window.__emit('delta', { chunk:'A streaming fixture answer.' }); true");
  browser('wait', '--text', 'A streaming fixture answer.');
  assert.equal(evaluate('document.querySelectorAll("[data-reaction-trigger]").length'), 0, 'streaming replies have no reaction action');
  evaluate("window.__emit('done', { reply:'A finished unaddressed fixture answer.', session_id:'reaction-live-fixture', emotion:'idle', mode:'test', model:'reaction-model', tool_results:[] }); true");
  browser('wait', '--text', 'A finished unaddressed fixture answer.');
  browser('wait', '[data-reply-actions]');
  assert.equal(evaluate('document.querySelectorAll("[data-reaction-trigger]").length'), 0, 'a done reply without assistant_message_id stays unreactable');
  assert.equal(evaluate('window.__chatSends.length'), 1, 'the only chat submission remains inside the local SSE fixture');
  assert.equal(evaluate('window.__reactionWrites.length'), 0, 'coarse-pointer and live-reply checks must not submit reactions');
  assert.deepEqual(evaluate('window.__blockedWrites'), [], 'no unexpected write, TTS, or setting request may occur');
  console.log('PASS: single bottom reaction target, saved indices/badges, keyboard/focus/dismissal, local POST payloads, emoji flight destination, hidden image and note targeting, 320/390px touch menus/locales, live and unaddressed replies');
} catch (error) {
  try {
    console.error(browser('errors'));
    console.error(evaluate('({ href:location.href, fixture:Array.isArray(window.__reactionWrites), focus:document.activeElement?.outerHTML })'));
    console.error(evaluate(`(() => {
      const menu = document.querySelector(${JSON.stringify(picker)});
      return menu && { columns:getComputedStyle(menu).gridTemplateColumns, width:menu.getBoundingClientRect().width,
        items:[...menu.querySelectorAll('button')].map(node => ({ emoji:node.textContent, x:node.getBoundingClientRect().x,
          y:node.getBoundingClientRect().y, width:node.getBoundingClientRect().width, position:getComputedStyle(node).position })) };
    })()`));
    shot('reaction-actions-failure');
  } catch {}
  throw error;
} finally {
  closeFixture();
}
