/** Pairing responses stay in memory, including delayed requests and revocations. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mobileLocales } from '../src/locales/mobile.ts';

const session = `mobile-connection-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5297';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8', timeout: 35000 });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const wait = code => browser('wait', '--fn', code);
const flush = () => evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))).then(() => true)');
const pane = "document.querySelector('#mobile-api-origin').closest('section')";
const qr = `${pane}.querySelector('img')`;
const alerts = () => evaluate(`[...${pane}.querySelectorAll('[role=alert]')].map(node => node.textContent)`);
const click = (key, language = 'en') => browser('find', 'role', 'button', 'click', '--name', mobileLocales[language][key], '--exact');
const plan = (method, response) => evaluate(`window.__mobile.plans[${JSON.stringify(method)}].push(${JSON.stringify(response)}); true`);
const count = method => evaluate(`window.__mobile.requests.filter(request => request.method === ${JSON.stringify(method)}).length`);
const held = method => evaluate(`window.__mobile.requests.findIndex(request => request.method === ${JSON.stringify(method)} && !request.done)`);
const release = (index, response = {}) => evaluate(`window.__mobile.release(${index}, ${JSON.stringify(response)}); true`);
const poll = () => evaluate('window.__mobile.poll(); true');
const phone = { device_id: 'fixture-phone', device_name: 'Pixel 8 · Android 16', platform: 'android', created_at: 1760000000, expires_at: 1767772800, revoked_at: null };
const fixtures = {
  '/api/auth/config': { disabled: true },
  '/api/setup': { configured: true, profile: { configured: true, name: 'Fixture', language: 'en', persona: 'friendly', persona_custom: '', greeting: '', reply_length: 'balanced', use_emoji: true, spontaneous: false }, languages: [], personas: [], reply_lengths: [], models: [], selected_model: '', keys_set: {} },
  '/api/openclaw/settings': { available: true, fields: [] },
  '/api/sessions': { sessions: [] }, '/api/projects': { projects: [] },
  '/api/brain/models': { models: [], selected: '', default: '', thinking: '', thinking_levels: [], available: false },
  '/api/status': { mode: 'fixture', omni: false, openclaw: false, anthropic: false },
  '/api/chat/context': { parts: [], chars: 0, dropped: 0, history_limit: 20 },
};

// Synthetic clocks avoid waiting ten seconds per poll or five minutes per QR.
const init = `(() => {
  const fixtures = ${JSON.stringify(fixtures)}, originalFetch = window.fetch.bind(window);
  const state = window.__mobile = { devices: [], plans: { GET: [], POST: [], DELETE: [] }, requests: [], writes: [], urls: [], revoked: [], intervals: new Map(), qrTimers: new Map(), timerId: 1000000 };
  const setInterval = window.setInterval.bind(window), clearInterval = window.clearInterval.bind(window);
  window.setInterval = (callback, delay, ...args) => {
    if (delay !== 10000) return setInterval(callback, delay, ...args);
    const id = ++state.timerId; state.intervals.set(id, () => callback(...args)); return id;
  };
  window.clearInterval = id => { if (!state.intervals.delete(id)) clearInterval(id); };
  state.poll = () => { for (const callback of state.intervals.values()) callback(); };
  const setTimeout = window.setTimeout.bind(window), clearTimeout = window.clearTimeout.bind(window);
  window.setTimeout = (callback, delay, ...args) => {
    if (delay < 36000 || delay > 37000) return setTimeout(callback, delay, ...args);
    const id = ++state.timerId; state.qrTimers.set(id, () => callback(...args)); return id;
  };
  window.clearTimeout = id => { if (!state.qrTimers.delete(id)) clearTimeout(id); };
  state.expire = () => { for (const callback of state.qrTimers.values()) callback(); state.qrTimers.clear(); };
  const createObjectURL = URL.createObjectURL.bind(URL), revokeObjectURL = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = blob => { const url = createObjectURL(blob); state.urls.push(url); return url; };
  URL.revokeObjectURL = url => { state.revoked.push(url); return revokeObjectURL(url); };
  window.EventSource = class { static OPEN = 1; readyState = 1; constructor() { queueMicrotask(() => this.onopen?.(new Event('open'))); } close() { this.readyState = 2; } };
  state.release = (index, response) => { const request = state.requests[index]; if (!request?.resolve) throw new Error('No held request'); request.resolve(response); };
  window.fetch = async (input, options = {}) => {
    const pathname = new URL(input instanceof Request ? input.url : String(input), location.href).pathname;
    if (!pathname.startsWith('/api/')) return originalFetch(input, options);
    const method = String(options.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
    if (!['GET', 'HEAD'].includes(method)) state.writes.push({ method, pathname });
    if (!pathname.startsWith('/api/mobile/')) return Response.json(fixtures[pathname] || {});
    const request = { method, pathname, body: options.body ? JSON.parse(options.body) : null, done: false };
    state.requests.push(request);
    const fallback = method === 'GET' ? { devices: structuredClone(state.devices) } : method === 'DELETE' ? { ok: true } :
      { qr_svg: '<svg xmlns="http://www.w3.org/2000/svg" width="300" height="300"><rect width="300" height="300" fill="white"/></svg>', expires_at: Date.now() / 1000 + 37 };
    let response = state.plans[method].shift() || {};
    if (response.hold) response = { ...response, ...await new Promise(resolve => { request.resolve = resolve; }) };
    request.done = true;
    if (response.network) throw new TypeError('Failed to fetch');
    const status = response.status || 200;
    if (method === 'DELETE' && status < 400) state.devices = state.devices.filter(device => device.device_id !== pathname.split('/').at(-1));
    return Response.json(response.body || (status >= 400 ? { detail: 'Fixture failure' } : fallback), { status });
  };
})();`;

let socket, cdpSession, nextId = 0;
const pending = new Map();
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId;
  const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 5000);
  pending.set(id, { resolve: value => { clearTimeout(timeout); resolve(value); }, reject: error => { clearTimeout(timeout); reject(error); } });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});
const navigate = tab => {
  evaluate(`location.hash = '#/settings?tab=${tab}'; true`);
  wait(tab === 'devices' ? "Boolean(document.querySelector('#mobile-api-origin'))" : "!document.querySelector('#mobile-api-origin')");
  flush();
};
const remount = (devices = []) => {
  navigate('profile');
  evaluate(`window.__mobile.devices = ${JSON.stringify(devices)}; true`);
  navigate('devices');
  wait(`${pane}.querySelector('[role=status]') === null`);
};

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
  browser('open', 'about:blank');
  // This guard remains active even if the in-page fixture accidentally misses a route.
  browser('network', 'route', '**/api/**', '--body', '{}');
  browser('open', `${origin}${path}#/settings?tab=devices`);
  socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data), request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) request.reject(new Error(JSON.stringify(message.error))); else request.resolve(message.result);
  });
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url.startsWith(`${origin}${path}`));
  assert.ok(target, 'the named browser session needs one isolated page');
  cdpSession = (await cdp('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: init });
  browser('reload');
  browser('wait', '#mobile-api-origin');

  for (const language of ['en', 'uk']) {
    evaluate(`localStorage.setItem('claudeBotLang', '${language}'); true`);
    browser('reload');
    wait("Boolean(document.querySelector('#mobile-api-origin'))");
    wait(`document.documentElement.lang === '${language}'`);
    const labels = mobileLocales[language];
    for (const [response, key] of [
      [{ status: 404 }, 'backendOutdated'], [{ status: 401 }, 'signInRequired'],
      [{ status: 403 }, 'operatorRequired'], [{ status: 400 }, 'invalidOrigin'],
      [{ status: 422 }, 'invalidOrigin'], [{ network: true }, 'networkFailed'],
      [{ status: 503 }, 'failed'],
    ]) {
      plan('POST', response); click('create', language);
      wait(`${pane}.querySelector('[role=alert]')?.textContent === ${JSON.stringify(labels[key])}`);
      assert.deepEqual(alerts(), [labels[key]], `${language}: ${key}`);
      assert.equal(evaluate(`Boolean(${qr})`), false, 'a failed creation cannot leave a previous QR visible');
    }
  }
  evaluate("localStorage.setItem('claudeBotLang', 'en'); true"); browser('reload'); browser('wait', '#mobile-api-origin');
  wait("document.documentElement.lang === 'en'");
  navigate('profile');
  assert.equal(evaluate("Boolean(document.querySelector('#mobile-api-origin'))"), false, 'pairing has left Personality');
  navigate('devices');
  assert.equal(evaluate(`${pane}.querySelector('h2').textContent.trim()`), mobileLocales.en.title);

  // A read failure belongs to the device list; creation may still succeed independently.
  remount(); plan('GET', { status: 503 }); poll();
  wait(`${pane}.querySelector('[role=alert]')?.textContent === ${JSON.stringify(mobileLocales.en.failed)}`);
  click('create'); wait(`Boolean(${qr})`);
  assert.deepEqual(alerts(), [mobileLocales.en.failed]);
  assert.equal(evaluate(`${pane}.querySelector('[role=alert]').previousElementSibling.textContent`), mobileLocales.en.devices);
  assert.equal(evaluate(`${pane}.textContent.includes(${JSON.stringify(mobileLocales.en.empty)})`), false,
    'an unavailable device list must not be presented as confirmed empty');
  plan('GET', { status: 503 }); poll(); flush();
  assert.equal(evaluate(`Boolean(${qr})`), true, 'a later polling failure cannot clear a successful QR');
  const oldUrl = evaluate(`${qr}.src`);
  browser('fill', '#mobile-api-origin', 'https://new-api.example.invalid');
  wait(`!${qr}`);
  assert.equal(evaluate(`window.__mobile.revoked.includes(${JSON.stringify(oldUrl)})`), true, 'editing the origin releases the old QR URL');

  // A request in flight disables both origin changes and repeated creation.
  remount(); plan('POST', { hold: true }); click('create');
  const post = held('POST'), posts = count('POST');
  assert.ok(post >= 0);
  assert.equal(evaluate("document.querySelector('#mobile-api-origin').disabled"), true);
  evaluate(`${pane}.querySelector('button').click(); true`); flush();
  assert.equal(count('POST'), posts, 'a disabled Create QR button cannot send a second request');
  release(post); wait(`Boolean(${qr})`);
  const expiring = evaluate(`${qr}.src`);
  evaluate('window.__mobile.expire(); true');
  wait(`!${qr}`);
  assert.equal(evaluate(`${pane}.textContent.includes(${JSON.stringify(mobileLocales.en.expired)})`), true);
  poll(); flush();
  assert.equal(evaluate(`Boolean(${qr})`), false, 'polling cannot resurrect an expired QR');
  click('create'); wait(`Boolean(${qr})`);
  assert.equal(evaluate(`window.__mobile.revoked.includes(${JSON.stringify(expiring)})`), true);
  click('close'); wait(`!${qr}`);
  assert.equal(evaluate(`${pane}.textContent.includes(${JSON.stringify(mobileLocales.en.expired)})`), false);
  plan('POST', { body: { qr_svg: '<svg/>', expires_at: 1 } }); click('create'); flush();
  assert.equal(evaluate(`Boolean(${qr})`), false, 'a response already past its expiry must never be rendered');

  // Check polls that started both before and during a revoke, not only immediate replies.
  for (const startBeforeRevoke of [true, false]) {
    remount([phone]);
    plan('GET', { hold: true }); plan('DELETE', { hold: true });
    if (startBeforeRevoke) poll();
    click('revoke');
    if (!startBeforeRevoke) poll();
    const read = held('GET'), deletion = held('DELETE'), reads = count('GET');
    assert.ok(read >= 0 && deletion >= 0);
    poll(); poll();
    assert.equal(count('GET'), reads, 'slow polling cannot create overlapping device reads');
    release(deletion); wait(`!${pane}.textContent.includes(${JSON.stringify(phone.device_name)})`);
    release(read, { body: { devices: [phone] } }); flush();
    assert.equal(evaluate(`${pane}.textContent.includes(${JSON.stringify(phone.device_name)})`), false, 'an older read cannot restore a revoked phone');
  }

  remount([phone]);
  assert.equal(evaluate(`${pane}.textContent.includes(${JSON.stringify(mobileLocales.en.pairedViaQr)})`), true, 'device connection method is visible');
  assert.equal(evaluate(`${pane}.textContent.includes(${JSON.stringify(mobileLocales.en.connectedAt)})`), true, 'device connection date is visible');
  assert.equal(evaluate(`${pane}.textContent.includes(${JSON.stringify(mobileLocales.en.expiresAt)})`), true, 'device expiry is visible');
  plan('DELETE', { status: 404 }); click('revoke');
  wait(`${pane}.querySelector('[role=alert]')?.textContent === ${JSON.stringify(mobileLocales.en.deviceMissing)}`);
  assert.deepEqual(alerts(), [mobileLocales.en.deviceMissing], 'a missing device does not tell users to update a working backend');

  // A late completion belongs to the component that initiated it, even after remount.
  for (const response of [{}, { status: 401 }]) {
    remount(); plan('POST', { hold: true }); click('create');
    const request = held('POST');
    navigate('profile');
    assert.equal(evaluate('window.__mobile.intervals.size'), 0, 'unmount stops device polling');
    navigate('devices'); release(request, response); flush();
    assert.equal(evaluate(`Boolean(${qr})`), false, 'late creation cannot put a QR in a new component');
    assert.deepEqual(alerts(), [], 'late failure cannot put an alert in a new component');
    assert.equal(evaluate("document.querySelector('#mobile-api-origin').disabled"), false);
  }
  remount(); plan('GET', { hold: true }); poll();
  const lateRead = held('GET');
  navigate('profile'); navigate('devices');
  release(lateRead, { body: { devices: [phone] } }); flush();
  assert.equal(evaluate(`${pane}.textContent.includes(${JSON.stringify(phone.device_name)})`), false, 'late polling cannot repopulate a new component');
  click('create'); wait(`Boolean(${qr})`);
  const finalUrl = evaluate(`${qr}.src`);
  navigate('profile');
  assert.equal(evaluate(`window.__mobile.revoked.includes(${JSON.stringify(finalUrl)})`), true, 'unmount releases the QR URL');
  assert.equal(evaluate('window.__mobile.qrTimers.size'), 0, 'unmount cancels the expiry timer');
  assert.equal(evaluate("window.__mobile.writes.every(write => write.pathname.startsWith('/api/mobile/'))"), true,
    'the fixture exercises only pairing and device mutations');
  console.log('PASS: localized error remedies, Devices placement, separate read errors, origin edits, busy guard, expiry, polling/revoke races, late requests and cleanup; all API responses and mutations were fixtures');
} catch (error) {
  try { console.error(browser('snapshot')); console.error(browser('errors')); console.error(evaluate("({ url: location.href, fixture: Boolean(window.__mobile), requests: window.__mobile?.requests.length, scripts: [...document.scripts].map(script => script.src) })")); } catch { /* Preserve the original assertion failure. */ }
  throw error;
} finally {
  socket?.close();
  browser('close');
}
