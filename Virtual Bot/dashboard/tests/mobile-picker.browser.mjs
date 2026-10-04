/** The phone picker stays compact and reachable without writing live gateway state. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `mobile-picker-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const menu = '.model-effort-menu';
const selected = 'regolo/gpt-phone-selected';
const catalog = {
  models: [
    ...Array.from({ length: 64 }, (_, index) => ({
      id: `openai/gpt-${index + 1}-phone-fixture`, label: `GPT ${index + 1} phone fixture`,
    })),
    { id: selected, label: 'GPT phone selected' },
  ],
  selected, default: selected, thinking: 'ultra', available: true,
  thinking_levels: ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'adaptive', 'max', 'ultra'],
};

const settle = () => evaluate(`new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
  .then(() => Promise.all(document.querySelector('${menu}')?.getAnimations({ subtree: true })
    .map(animation => animation.finished.catch(() => {})) || []))`);
const visibleTrigger = () => {
  const count = evaluate(`const triggers = [...document.querySelectorAll('[data-brain-choice-trigger]')]
    .filter(node => node.getBoundingClientRect().width > 0 && node.getBoundingClientRect().height > 0);
    document.querySelectorAll('[data-mobile-picker-trigger]').forEach(node => node.removeAttribute('data-mobile-picker-trigger'));
    triggers[0]?.setAttribute('data-mobile-picker-trigger', ''); triggers.length;`);
  assert.equal(count, 1, 'one button opens both phone columns');
  return '[data-mobile-picker-trigger]';
};
const openMenu = () => {
  browser('click', visibleTrigger());
  browser('wait', menu);
  settle();
  assert.equal(evaluate('document.querySelector(".model-picker-search-button").ariaExpanded'), 'false');
};
const closeMenu = () => {
  browser('press', 'Escape');
  browser('wait', '--fn', `!document.querySelector('${menu}')`);
  assert.equal(evaluate('document.activeElement.matches("[data-mobile-picker-trigger]")'), true);
};

// The CLI resets input emulation between commands, so touch and measurements share one CDP session.
const touchMeasure = expression => {
  const { cdpUrl } = JSON.parse(browser('--json', 'get', 'cdp-url')).data;
  const { tabs } = JSON.parse(browser('--json', 'tab', 'list')).data;
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', `
    const socket = new WebSocket(process.argv[1]);
    const pending = new Map(); let nextId = 0;
    socket.addEventListener('message', event => {
      const reply = JSON.parse(event.data); const resolve = pending.get(reply.id);
      if (resolve) { pending.delete(reply.id); resolve(reply); }
    });
    await new Promise((resolve, reject) => {
      socket.addEventListener('open', resolve, { once: true });
      socket.addEventListener('error', reject, { once: true });
    });
    const timeout = setTimeout(() => { console.error('Touch measurement timed out'); socket.close(); process.exit(1); }, 5000);
    const send = async (method, params, sessionId) => {
      const id = ++nextId; const reply = new Promise(resolve => pending.set(id, resolve));
      socket.send(JSON.stringify({ id, method, params, sessionId })); const result = await reply;
      if (result.error) throw new Error(result.error.message); return result.result;
    };
    try {
      const { sessionId } = await send('Target.attachToTarget', { targetId: process.argv[2], flatten: true });
      await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 }, sessionId);
      const result = await send('Runtime.evaluate', { awaitPromise: true, returnByValue: true,
        expression: 'new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))).then(() => (' + process.argv[3] + ')())' }, sessionId);
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
      console.log(JSON.stringify(result.result.value));
    } finally { clearTimeout(timeout); socket.close(); }
  `, cdpUrl, tabs.find(tab => tab.active).targetId, expression], { encoding: 'utf8' }));
};

const measure = () => touchMeasure(`() => {
  const bounds = node => { const r = node.getBoundingClientRect(); return { left:r.left, top:r.top, right:r.right, bottom:r.bottom, width:r.width, height:r.height }; };
  const popup = document.querySelector('${menu}');
  const trigger = document.querySelector('[data-mobile-picker-trigger]');
  const modelList = popup.querySelector('.model-picker-list');
  const effortList = popup.querySelector('.effort-picker-list');
  const currentModel = modelList.querySelector('[aria-checked=true]');
  const currentEffort = effortList.querySelector('[aria-checked=true]');
  const name = popup.querySelector('.model-picker-row .brain-picker-name');
  return {
    popup: bounds(popup), trigger: bounds(trigger), modelList: bounds(modelList), effortList: bounds(effortList),
    currentModel: currentModel && bounds(currentModel), currentEffort: currentEffort && bounds(currentEffort),
    headings: [...popup.querySelectorAll('.brain-picker-heading')].map(node => bounds(node).height),
    rowHeights: [...popup.querySelectorAll('[role=radio]')].map(node => bounds(node).height),
    searchButton: bounds(popup.querySelector('.model-picker-search-button')),
    nameSize: parseFloat(getComputedStyle(name).fontSize), triggerSize: parseFloat(getComputedStyle(trigger).fontSize),
    logo: bounds(trigger.querySelector('svg')), effortHidden: getComputedStyle(trigger.querySelector('.brain-choice-effort')).display === 'none',
    modelScroll: modelList.scrollHeight > modelList.clientHeight, effortScroll: effortList.scrollHeight > effortList.clientHeight,
    modelScrollTop: modelList.scrollTop, effortScrollTop: effortList.scrollTop,
    rowOverflow: [...popup.querySelectorAll('.brain-picker-row')].some(node => node.scrollWidth > node.clientWidth + 1),
    coarse: matchMedia('(pointer: coarse)').matches, width: innerWidth, height: innerHeight,
    scrollWidth: document.documentElement.scrollWidth,
  };
}`);
const inList = (row, list, message) => assert.ok(row && row.top >= list.top - 1 && row.bottom <= list.bottom + 1, message);
const assertPhone = (state, label) => {
  assert.ok(state.coarse, 'measurements use a real coarse pointer media query');
  assert.ok(state.popup.width <= Math.min(360, state.width - 24) + 1, `${label}: width is phone sized`);
  assert.ok(state.popup.height <= Math.min(320, state.height * .52) + 1, `${label}: height stays compact`);
  assert.ok(state.popup.height < Math.min(560, state.height * .74) * .82, `${label}: materially shorter than the desktop popup`);
  assert.ok(state.popup.left >= -1 && state.popup.top >= -1 && state.popup.right <= state.width + 1
    && state.popup.bottom <= state.height + 1, `${label}: popup fits the viewport`);
  assert.ok(state.trigger.width >= 44 && state.trigger.height >= 44);
  assert.ok(state.rowHeights.every(height => height >= 44), 'shrinking the panel preserves every choice hit area');
  assert.ok(state.searchButton.width >= 44 && state.searchButton.height >= 44);
  assert.ok(state.headings.every(height => height <= 45), 'headers use the smaller 44px rail');
  assert.equal(state.triggerSize, 13);
  assert.equal(state.logo.width, 14);
  assert.ok(state.effortHidden);
  assert.ok(state.nameSize >= 11.5 && state.nameSize <= 12.5, 'model data remains readable');
  assert.ok(state.modelList.left < state.effortList.left && state.modelList.right <= state.effortList.left + 2);
  assert.ok(state.modelScroll && state.effortScroll, 'each crowded column scrolls independently');
  assert.ok(state.scrollWidth <= state.width && !state.rowOverflow, `${label}: no horizontal overflow`);
  inList(state.currentModel, state.modelList, `${label}: selected model remains visible`);
  inList(state.currentEffort, state.effortList, `${label}: selected effort remains visible`);
};

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
  browser('open', 'about:blank');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [] });
  route('**/api/projects', { projects: [] });
  route('**/api/workspace/info**', { session_path: 'sessions/default' });
  route('**/api/brain/models', catalog);
  route('**/api/brain/model', { ok: true, selected });
  route('**/api/brain/thinking', { ok: true, thinking: catalog.thinking });
  // Routes use registration order: place the catch-all after the specific fixtures.
  route('**/api/**', {});
  browser('set', 'device', 'iPhone 12');
  browser('open', `${origin}${path}#/chat`);
  browser('wait', '[data-brain-choice-trigger]');
  evaluate(`localStorage.setItem('claudeBotLang', 'en'); localStorage.removeItem('claudeBotRecentModels'); localStorage.setItem('claudeBotModelIntel', '0');
    localStorage.removeItem('claude-bot:brain-models:v6');`);
  browser('open', `${origin}${path}#/chat`);
  browser('wait', '[data-brain-choice-trigger]');
  evaluate(`window.__mobilePickerWrites = []; const savedFetch = window.fetch;
    window.fetch = (input, options = {}) => {
      const method = options.method || (typeof input === 'string' ? 'GET' : input.method) || 'GET';
      if (method !== 'GET' && method !== 'HEAD') {
        window.__mobilePickerWrites.push(method);
        return Promise.resolve(new Response('{}', { status: 405, headers: { 'Content-Type': 'application/json' } }));
      }
      return savedFetch(input, options);
    };`);

  for (const lang of ['en', 'uk']) {
    evaluate(`document.documentElement.lang = ${JSON.stringify(lang)};
      document.documentElement.dataset.theme = ${JSON.stringify(lang === 'en' ? 'dark' : 'light')};`);
    for (const [width, height] of [[320, 568], [390, 844], [430, 932], [667, 375]]) {
      browser('set', 'viewport', String(width), String(height));
      openMenu();
      const state = measure();
      assertPhone(state, `${lang} ${width}x${height}`);
      const effortScroll = state.effortScrollTop;
      evaluate('document.querySelector(".model-picker-list").scrollTop = 0');
      assert.equal(evaluate('document.querySelector(".effort-picker-list").scrollTop'), effortScroll,
        'scrolling models leaves the effort column in place');
      browser('click', '.model-picker-search-button');
      browser('wait', '--fn', 'document.activeElement.matches("[role=searchbox]")');
      browser('fill', '[role=searchbox]', 'selected');
      assert.equal(evaluate('document.querySelectorAll(".model-picker-list [role=radio]").length'), 1);
      assert.equal(evaluate('document.querySelector(".model-picker-search-button").ariaExpanded'), 'true');
      browser('press', 'Escape');
      settle();
      assert.equal(evaluate('document.querySelector(".model-picker-search-button").ariaExpanded'), 'false');
      assert.ok(evaluate('document.querySelector(".model-picker-title").getBoundingClientRect().width > 0'));
      closeMenu();

      openMenu();
      browser('press', 'Shift+Tab');
      assert.equal(evaluate('document.activeElement.matches(".model-picker-search-button")'), true,
        'keyboard focus can reveal the search control');
      browser('press', 'Tab');
      assert.equal(evaluate('document.activeElement.matches("[role=searchbox]")'), true);
      browser('press', 'Escape');
      closeMenu();
    }
  }

  browser('set', 'viewport', '390', '844');
  openMenu();
  // A short visual viewport approximates the room left above an on-screen keyboard.
  browser('set', 'viewport', '390', '320');
  settle();
  assertPhone(measure(), 'keyboard 390x320');
  assert.equal(evaluate('document.activeElement.dataset.model'), selected, 'viewport changes retain selected model focus');
  closeMenu();

  // The phone-only media rule leaves tablet and desktop dimensions intact.
  for (const [width, height] of [[768, 844], [1440, 900]]) {
    browser('set', 'viewport', String(width), String(height));
    openMenu();
    const state = measure();
    assert.equal(state.popup.width, 440, `${width}px uses the compact desktop popup`);
    assert.ok(state.popup.height <= 361, `${width}px keeps the desktop popup short`);
    closeMenu();
  }
  assert.deepEqual(evaluate('window.__mobilePickerWrites'), [], 'all gestures are read-only and dispatch no model or chat writes');

  // A failed refresh retains cached choices; recovery content must not squeeze their touch areas.
  evaluate(`localStorage.setItem('claude-bot:brain-models:v6', JSON.stringify({
    data: ${JSON.stringify(catalog)}, savedAt: Date.now() - 300_000,
  }));`);
  browser('network', 'unroute', '**/api/**');
  browser('network', 'unroute', '**/api/brain/models');
  browser('network', 'route', '**/api/brain/models', '--abort');
  route('**/api/**', {});
  browser('set', 'viewport', '390', '320');
  browser('reload');
  browser('wait', '[data-brain-choice-trigger]');
  openMenu();
  browser('wait', '--fn', 'Boolean(document.querySelector(".brain-picker-status"))');
  settle();
  assertPhone(measure(), 'stale catalog 390x320');
  assert.equal(evaluate('document.querySelector(".brain-picker-status").parentElement.matches(".model-picker-list")'), true,
    'the recovery rail scrolls with the model catalog');
  evaluate('document.querySelector(".brain-picker-status button").focus()');
  settle();
  const retry = evaluate(`const button = document.querySelector('.brain-picker-status button').getBoundingClientRect();
    const list = document.querySelector('.model-picker-list').getBoundingClientRect();
    ({ top: button.top, bottom: button.bottom, height: button.height, listTop: list.top, listBottom: list.bottom });`);
  assert.ok(retry.height >= 44 && retry.top >= retry.listTop - 1 && retry.bottom <= retry.listBottom + 1,
    'keyboard focus exposes a complete 44px Retry control inside its scroll column');
  closeMenu();
  console.log('PASS: compact phone picker, 44px touch areas, both current choices visible, independent columns, search/focus, short keyboard viewport, no overflow, unchanged wide layouts and no writes');
} catch (error) {
  console.error(browser('snapshot'));
  console.error(browser('errors'));
  throw error;
} finally {
  browser('close');
}
