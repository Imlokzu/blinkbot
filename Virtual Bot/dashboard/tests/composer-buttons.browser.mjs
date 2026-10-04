/** Composer button appearance and behavior; every API request stays in fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { t } from '../src/locales/chat.ts';

const session = `composer-buttons-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5183';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const shots = process.env.COMPOSER_BUTTON_SHOTS;
const send = '.prompt-bar__send';
const attachment = '.prompt-bar__tool[aria-controls="chat-attachment-menu"]';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const text = (language, key) => {
  const previous = globalThis.document;
  globalThis.document = { documentElement: { lang: language } };
  try { return t(key); } finally {
    if (previous === undefined) delete globalThis.document;
    else globalThis.document = previous;
  }
};
const screenshot = name => { if (shots) browser('screenshot', `${shots}/${name}.png`); };
const settleButton = () => {
  evaluate('window.__composerStableButton = null; true');
  // Sending moves the composer from the welcome center to the thread footer.
  // Wait for its actual geometry so a pointer click cannot miss a moving Stop.
  browser('wait', '--fn', `(() => {
    const button = document.querySelector('${send}'); if (!button) return false;
    const rect = button.getBoundingClientRect(), value = [rect.x, rect.y, rect.width, rect.height];
    const last = window.__composerStableButton;
    const count = last && value.every((part, index) => Math.abs(part - last.value[index]) < 0.5) ? last.count + 1 : 0;
    window.__composerStableButton = { value, count };
    for (let ancestor = button; ancestor; ancestor = ancestor.parentElement) {
      if (ancestor.getAnimations().some(animation => animation.playState === 'running' && Number.isFinite(animation.effect.getComputedTiming().endTime))) return false;
    }
    return count >= 2 && document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)?.closest('${send}') === button;
  })()`);
};
const fixtures = {
  '/api/auth/config': { disabled: true },
  '/api/status': { openclaw: true, anthropic: true, mode: 'fixture' },
  '/api/sessions': { sessions: [] }, '/api/projects': { projects: [] },
  '/api/setup': { configured: true, profile: { configured: true }, keys_set: {}, languages: [], personas: [], reply_lengths: [] },
  '/api/brain/models': { models: [{ id: 'test', label: 'Test', context: 200000 }], selected: 'test', default: 'test', thinking: 'high', thinking_levels: ['high'], available: true },
  '/api/brain/intelligence': { models: [] },
  '/api/chat/context': { parts: [], chars: 0, dropped: 0, history_limit: 20 },
  '/api/asr/status': { enabled: false }, '/api/tts/status': { enabled: false },
  '/api/connectors': { connectors: [] },
};
const init = `(() => {
  const fixtures = ${JSON.stringify(fixtures)}, original = window.fetch.bind(window);
  window.__composerSends = []; window.__composerAborts = 0; window.__composerWrites = []; window.__composerErrors = [];
  window.addEventListener('error', event => window.__composerErrors.push(event.message));
  window.addEventListener('unhandledrejection', event => window.__composerErrors.push(String(event.reason)));
  window.EventSource = class { static OPEN = 1; readyState = 1; constructor() { queueMicrotask(() => this.onopen?.(new Event('open'))); } close() { this.readyState = 2; } addEventListener() {} removeEventListener() {} };
  window.fetch = async (input, options = {}) => {
    const request = input instanceof Request ? input : null;
    const pathname = new URL(request ? request.url : String(input), location.href).pathname;
    if (!pathname.startsWith('/api/')) return original(input, options);
    const method = String(options.method || request?.method || 'GET').toUpperCase();
    if (method === 'GET' || method === 'HEAD') return Response.json(fixtures[pathname] || {});
    if (method === 'POST' && pathname === '/api/chat') {
      window.__composerSends.push(JSON.parse(options.body ?? await request.clone().text()));
      return new Response(new ReadableStream({ start(controller) {
        window.__composerStream = controller;
        (options.signal || request?.signal)?.addEventListener('abort', () => {
          window.__composerAborts++;
          try { controller.error(new DOMException('Aborted', 'AbortError')); } catch {}
        }, { once: true });
      } }), { headers: { 'Content-Type': 'text/event-stream' } });
    }
    window.__composerWrites.push({ pathname, method });
    return Response.json({ error: 'Unexpected browser-fixture write' }, { status: 405 });
  };
})();`;

let socket, cdpSession, nextId = 0;
const pending = new Map();
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId;
  const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 5000);
  pending.set(id, { resolve: result => { clearTimeout(timeout); resolve(result); }, reject: error => { clearTimeout(timeout); reject(error); } });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});
const inspectCircle = (context, touch) => {
  const result = evaluate(`const button = document.querySelector('${send}'), style = getComputedStyle(button), rect = button.getBoundingClientRect();
    ({ width: rect.width, height: rect.height, radius: style.borderRadius, background: style.backgroundColor,
      disabled: button.disabled, count: document.querySelectorAll('${send}').length });`);
  assert.equal(result.count, 1, `${context}: there is only one send/stop button`);
  assert.ok(Math.abs(result.width - result.height) < 1, `${context}: the send surface is circular`);
  assert.ok(parseFloat(result.radius) >= result.width / 2, `${context}: circular rounding survives every state`);
  assert.notEqual(result.background, 'rgba(0, 0, 0, 0)', `${context}: the circular surface is filled`);
  assert.notEqual(result.background, 'transparent', `${context}: the circular surface is filled`);
  if (touch) assert.ok(result.width >= 44 && result.height >= 44, `${context}: phone send target is at least 44px`);
  return result;
};

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
  if (shots) mkdirSync(shots, { recursive: true });
  browser('open', 'about:blank');
  // Deny API consumers outside fetch too, before the application can mount.
  browser('network', 'route', '**/api/**', '--body', '{}');
  socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data), request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) request.reject(new Error(JSON.stringify(message.error)));
    else request.resolve(message.result);
  });
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url === 'about:blank');
  assert.ok(target, 'the isolated browser target must exist');
  cdpSession = (await cdp('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: init });
  browser('open', `${origin}${path}#/chat`);
  browser('wait', send);

  for (const [language, theme, width, height] of [['en', 'light', 1440, 960], ['uk', 'dark', 390, 844]]) {
    const context = `${language}/${theme}/${width}`, touch = width === 390;
    evaluate(`localStorage.setItem('claudeBotLang', '${language}'); localStorage.setItem('claudeBotTheme', '${theme}'); localStorage.setItem('claudeBotSendBubble', 'off'); true`);
    browser('set', 'viewport', String(width), String(height));
    browser('reload'); browser('wait', send);
    browser('wait', '--fn', `document.querySelector('${send}').ariaLabel === ${JSON.stringify(text(language, 'composer.send'))}`);
    settleButton();
    assert.equal(evaluate('document.documentElement.lang'), language);
    assert.equal(evaluate('document.documentElement.dataset.theme'), theme);
    assert.equal(inspectCircle(`${context}/idle`, touch).disabled, true, 'an empty draft retains native disabled behavior');
    screenshot(`${language}-${width}-idle`);
    browser('fill', '.prompt-bar__input', '   ');
    browser('press', 'Enter');
    assert.equal(evaluate('window.__composerSends.length'), 0, 'whitespace cannot send');
    assert.equal(evaluate(`document.querySelector('${attachment} svg').dataset.solarIcon`), 'paperclip', 'attachments use the Solar paperclip');
    if (touch) assert.equal(evaluate(`const r = document.querySelector('${attachment}').getBoundingClientRect(); r.width >= 44 && r.height >= 44`), true, 'phone attachment target remains at least 44px');

    browser('focus', attachment); browser('press', 'Space');
    browser('wait', '[data-attachment-menu]');
    assert.equal(evaluate(`document.querySelector('${attachment}').ariaExpanded`), 'true');
    assert.equal(evaluate('Boolean(document.activeElement.closest("[data-attachment-menu]"))'), true, 'keyboard opening moves focus into attachment actions');
    browser('press', 'Escape');
    browser('wait', '--fn', '!document.querySelector("[data-attachment-menu]")');
    assert.equal(evaluate(`document.activeElement.matches('${attachment}')`), true, 'Escape returns focus to the paperclip');

    browser('fill', '.prompt-bar__input', 'Browser-only composer fixture');
    browser('wait', '--fn', `!document.querySelector('${send}').disabled`);
    settleButton();
    assert.equal(inspectCircle(`${context}/ready`, touch).disabled, false);
    screenshot(`${language}-${width}-ready`);
    browser('click', send);
    browser('wait', '--fn', `Boolean(window.__composerStream) && document.querySelector('${send}').ariaLabel === ${JSON.stringify(text(language, 'composer.stop'))}`);
    settleButton();
    assert.equal(inspectCircle(`${context}/stop`, touch).disabled, false, 'Stop stays usable with the now-empty draft');
    assert.equal(evaluate('window.__composerSends.length'), 1, 'sending makes one mocked request');
    screenshot(`${language}-${width}-stop`);
    browser('click', send);
    browser('wait', '--fn', `window.__composerAborts === 1 && document.querySelector('${send}').ariaLabel === ${JSON.stringify(text(language, 'composer.send'))}`);
    assert.equal(inspectCircle(`${context}/stopped`, touch).disabled, true);
    assert.equal(evaluate('window.__composerAborts'), 1, 'Stop calls the stream cancellation callback');
    assert.deepEqual(evaluate('window.__composerWrites'), [], 'all non-chat writes stay blocked');
    assert.deepEqual(evaluate('window.__composerErrors'), [], 'button interaction raises no uncaught browser errors');
    assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'the composer causes no page overflow');
  }
  console.log('PASS: filled circular idle/ready/Stop, Solar paperclip, native disabled/whitespace behavior, attachment keyboard/Escape focus, mocked send and Stop cancellation, 44px phone targets, en/light desktop and uk/dark phone');
} catch (error) {
  console.error(browser('snapshot', '-i')); console.error(browser('errors')); screenshot('failure'); throw error;
} finally { socket?.close(); browser('close'); }
