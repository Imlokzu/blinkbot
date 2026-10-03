/** Exercise shared thinking settings through browser-only fixtures, never OpenClaw. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { t } from '../src/locales/chat.ts';

const session = `effort-menu-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const touchLayout = () => {
  const { cdpUrl } = JSON.parse(browser('--json', 'get', 'cdp-url')).data;
  const { tabs } = JSON.parse(browser('--json', 'tab', 'list')).data;
  // The CLI reapplies its input settings per command, so measure in the CDP touch session.
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', `
    const socket = new WebSocket(process.argv[1]);
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { socket.close(); reject(new Error('Touch emulation timed out')); }, 5000);
      socket.addEventListener('error', event => { clearTimeout(timeout); socket.close(); reject(event); });
      socket.addEventListener('open', () => socket.send(JSON.stringify({ id: 1,
        method: 'Target.attachToTarget', params: { targetId: process.argv[2], flatten: true } })));
      socket.addEventListener('message', event => {
        const reply = JSON.parse(event.data);
        if (reply.error) { clearTimeout(timeout); socket.close(); reject(new Error(reply.error.message)); return; }
        if (reply.id === 1) socket.send(JSON.stringify({ id: 2, sessionId: reply.result.sessionId,
          method: 'Emulation.setTouchEmulationEnabled', params: { enabled: true, maxTouchPoints: 1 } }));
        if (reply.id === 2) socket.send(JSON.stringify({ id: 3, sessionId: reply.sessionId,
          method: 'Runtime.evaluate', params: { awaitPromise: true, returnByValue: true,
            expression: 'new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))).then(() => { const menu = document.querySelector(".effort-menu").getBoundingClientRect(); const trigger = document.querySelector("[data-effort-test-trigger]").getBoundingClientRect(); return { left: menu.left, top: menu.top, right: menu.right, bottom: menu.bottom, width: innerWidth, height: innerHeight, scrollWidth: document.documentElement.scrollWidth, triggerWidth: trigger.width, triggerHeight: trigger.height, coarse: matchMedia("(pointer:coarse)").matches }; })' } }));
        if (reply.id === 3) { clearTimeout(timeout); socket.close();
          if (reply.result.exceptionDetails) reject(new Error(reply.result.exceptionDetails.text));
          else { console.log(JSON.stringify(reply.result.result.value)); resolve(); } }
      });
    });`, cdpUrl, tabs.find(tab => tab.active).targetId], { encoding: 'utf8' }));
};
const groupSelector = '.effort-menu [role=radiogroup]';
const radioSelector = level => `${groupSelector} [role=radio][data-level=${JSON.stringify(level)}]`;
const catalog = {
  models: [{ id: 'openai/fixture', label: 'Fixture model', context: 200000 }],
  selected: 'openai/fixture', default: 'openai/fixture', thinking: 'high', available: true,
  // Unknown future gateway levels must remain selectable, with their reported name.
  thinking_levels: ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'adaptive', 'max', 'ultra', 'gateway-next'],
};

// Read the actual locale keys so Ukrainian copy exists only in the locale file.
const labelsFor = lang => {
  const previous = globalThis.document;
  globalThis.document = { documentElement: { lang } };
  try {
    const levels = Object.fromEntries(catalog.thinking_levels.map(level => [level,
      level === 'gateway-next' ? level : t(`effort.${level}`),
    ]));
    levels[''] = t('composer.asConfigured');
    const triggers = Object.fromEntries(Object.entries(levels).map(([level, label]) =>
      [level, t('composer.chooseEffortCurrent', { level: label })],
    ));
    return {
      levels,
      trigger: level => triggers[level],
      group: t('composer.chooseEffort'),
      hint: t('composer.effortHintShort'),
      failure: t('composer.effortFailed'),
      model: t('composer.chooseModel'),
    };
  } finally {
    if (previous === undefined) delete globalThis.document;
    else globalThis.document = previous;
  }
};
const labels = { en: labelsFor('en'), uk: labelsFor('uk') };

const visibleTrigger = () => {
  const found = evaluate(`const triggers = [...document.querySelectorAll('.effort-trigger')]
    .filter(button => button.getBoundingClientRect().width > 0 && button.getBoundingClientRect().height > 0);
    document.querySelectorAll('[data-effort-test-trigger]').forEach(button => button.removeAttribute('data-effort-test-trigger'));
    triggers[0]?.setAttribute('data-effort-test-trigger', '');
    triggers.length;`);
  assert.equal(found, 1, 'each chat layout exposes one effort control');
  return '[data-effort-test-trigger]';
};
const openMenu = () => {
  browser('click', visibleTrigger());
  browser('wait', groupSelector);
  // The opening spring scales every hit target until the popup settles.
  evaluate('Promise.all(document.querySelector(".effort-menu").getAnimations({ subtree: true }).map(animation => animation.finished.catch(() => {})))');
};
const rows = () => evaluate(`[...document.querySelectorAll('${groupSelector} [role=radio]')].map(row => ({
  level: row.dataset.level, label: row.getAttribute('aria-label') || row.textContent.trim(),
  checked: row.getAttribute('aria-checked'), disabled: row.getAttribute('aria-disabled'),
  focused: row === document.activeElement, tabIndex: row.tabIndex,
  height: row.getBoundingClientRect().height,
}))`);
const writeCount = () => evaluate('window.__effortWrites.length');
const waitForChoice = level => {
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(radioSelector(level))})?.getAttribute('aria-checked') === 'true'
    && [...document.querySelectorAll('${groupSelector} [role=radio]')].every(row => row.getAttribute('aria-disabled') !== 'true')`);
  assert.deepEqual(rows().filter(row => row.checked === 'true').map(row => row.level), [level]);
};
const assertTrigger = (lang, level) => {
  const state = evaluate(`const button = document.querySelector('[data-effort-test-trigger]');
    ({ label: button.getAttribute('aria-label'), title: button.title, text: button.textContent.trim(), icon: Boolean(button.querySelector('svg')) });`);
  assert.equal(state.label, labels[lang].trigger(level));
  assert.equal(state.title, state.label, 'the tooltip describes the same current level as the accessible name');
  assert.equal(state.text, '', 'the effort trigger stays icon-only');
  assert.equal(state.icon, true);
};
const assertMenu = (lang, level) => {
  waitForChoice(level);
  const state = evaluate(`const group = document.querySelector('${groupSelector}');
    const ids = (group.getAttribute('aria-describedby') || '').split(/\\s+/).filter(Boolean);
    ({ label: group.getAttribute('aria-label'), descriptions: ids.map(id => document.getElementById(id)?.textContent),
      connected: ids.every(id => Boolean(document.getElementById(id))) });`);
  assert.equal(state.label, labels[lang].group);
  assert.ok(state.connected && state.descriptions.length > 0, 'the radios reference a rendered explanatory hint');
  assert.ok(state.descriptions.includes(labels[lang].hint));
  assert.deepEqual(rows().map(row => row.level), ['', ...catalog.thinking_levels], 'only server levels and the reset option are offered');
  assert.deepEqual(rows().map(row => row.label), ['', ...catalog.thinking_levels].map(value => labels[lang].levels[value]));
  assert.ok(rows().every(row => row.height >= 44), 'every effort choice has a 44px hit target');
  assertTrigger(lang, level);
};

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
  browser('open', 'about:blank');
  browser('set', 'viewport', '1440', '900');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [] });
  route('**/api/projects', { projects: [] });
  route('**/api/workspace/info**', { session_path: 'sessions/default' });
  route('**/api/brain/models', catalog);
  // These guards protect against a fixture override being lost or bypassed.
  route('**/api/brain/thinking', { ok: true, thinking: catalog.thinking });
  route('**/api/brain/model', { ok: true, selected: catalog.selected });
  browser('open', `${origin}/docs`);
  evaluate(`localStorage.setItem('claudeBotLang', 'en');
    localStorage.removeItem('claude-bot:brain-models:v6');`);
  browser('open', `${origin}${path}#/chat`);
  browser('wait', '.effort-trigger');
  evaluate(`window.__effortCatalog = ${JSON.stringify(catalog)};
    window.__effortWrites = [];
    const savedFetch = window.fetch;
    window.fetch = async (input, options = {}) => {
      const address = new URL(typeof input === 'string' ? input : input.url, location.href).pathname;
      if (address === '/api/brain/models') {
        return new Response(JSON.stringify(window.__effortCatalog), { headers: { 'Content-Type': 'application/json' } });
      }
      if (address === '/api/brain/model' || address === '/api/brain/thinking') {
        const body = JSON.parse(options.body || await input.clone().text());
        window.__effortWrites.push({ address, body });
        if (window.__effortDelay) await new Promise(resolve => { window.__effortRelease = resolve; });
        if (window.__effortWriteFails) return new Response(JSON.stringify({ detail: 'fixture rejected' }), {
          status: 502, headers: { 'Content-Type': 'application/json' },
        });
        if (address === '/api/brain/thinking') window.__effortCatalog.thinking = body.level;
        else window.__effortCatalog.selected = body.model;
        return new Response(JSON.stringify({ ok: true, thinking: window.__effortCatalog.thinking, selected: window.__effortCatalog.selected }), {
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return savedFetch(input, options);
    };`);

  openMenu();
  assertMenu('en', 'high');
  assert.deepEqual(rows().filter(row => row.focused).map(row => row.level), ['high'], 'opening focuses the current radio');
  assert.deepEqual(rows().filter(row => row.tabIndex === 0).map(row => row.level), ['high']);

  // A second click or key cannot overlap a delayed write, even on a different row.
  evaluate('window.__effortDelay = true');
  browser('find', 'role', 'radio', 'click', '--name', labels.en.levels.medium, '--exact');
  browser('wait', '--fn', 'typeof window.__effortRelease === "function"');
  assert.deepEqual(evaluate('window.__effortWrites'), [{ address: '/api/brain/thinking', body: { level: 'medium' } }]);
  assert.ok(rows().every(row => row.disabled === 'true'), 'pending writes disable every radio through ARIA');
  browser('press', 'ArrowDown');
  evaluate(`document.querySelector(${JSON.stringify(radioSelector('low'))}).click()`);
  assert.equal(writeCount(), 1, 'pending keyboard and mouse choices cannot dispatch another write');
  evaluate('window.__effortDelay = false; window.__effortRelease(); delete window.__effortRelease');
  waitForChoice('medium');
  assertTrigger('en', 'medium');
  assert.equal(evaluate('Boolean(document.querySelector(".effort-menu"))'), true, 'successful choices leave the popover open');

  // A rejection preserves the acknowledged setting and lets the user retry in place.
  evaluate('window.__effortWriteFails = true');
  browser('find', 'role', 'radio', 'click', '--name', labels.en.levels.high, '--exact');
  browser('wait', '--text', labels.en.failure);
  waitForChoice('medium');
  assert.equal(writeCount(), 2);
  assertTrigger('en', 'medium');
  assert.equal(evaluate('window.__effortCatalog.thinking'), 'medium');
  assert.equal(evaluate('Boolean(document.querySelector(".effort-menu"))'), true);
  evaluate('window.__effortWriteFails = false');

  // Clearing the configured value is a different API operation from explicit "off".
  browser('find', 'role', 'radio', 'click', '--name', labels.en.levels[''], '--exact');
  waitForChoice('');
  assertTrigger('en', '');
  browser('find', 'role', 'radio', 'click', '--name', labels.en.levels.off, '--exact');
  waitForChoice('off');
  assertTrigger('en', 'off');
  assert.deepEqual(evaluate('window.__effortWrites.slice(-2).map(write => write.body)'), [{ level: '' }, { level: 'off' }]);
  const unchangedWrites = writeCount();
  browser('find', 'role', 'radio', 'click', '--name', labels.en.levels.off, '--exact');
  assert.equal(writeCount(), unchangedWrites, 'choosing the checked level does not rewrite settings');

  // Arrow keys both move focus and select; Home/End include the reset and future levels.
  for (const key of ['ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft', 'Home', 'ArrowUp', 'End', 'Home']) {
    const before = rows();
    const active = before.findIndex(row => row.focused);
    assert.ok(active >= 0, `${key} begins on a radio`);
    const next = key === 'Home' ? 0 : key === 'End' ? before.length - 1
      : (active + (key === 'ArrowDown' || key === 'ArrowRight' ? 1 : before.length - 1)) % before.length;
    const expectedLevel = before[next].level;
    const count = writeCount();
    browser('press', key);
    waitForChoice(expectedLevel);
    assert.deepEqual(rows().filter(row => row.focused).map(row => row.level), [expectedLevel], `${key} moves radio focus`);
    assert.deepEqual(rows().filter(row => row.tabIndex === 0).map(row => row.level), [expectedLevel], 'the checked radio is the only tab stop');
    assert.equal(writeCount(), count + (before[next].checked === 'true' ? 0 : 1));
    assertTrigger('en', expectedLevel);
  }
  browser('press', 'Escape');
  browser('wait', '--fn', '!document.querySelector(".effort-menu")');
  assert.equal(evaluate('document.activeElement.matches("[data-effort-test-trigger]")'), true, 'Escape restores trigger focus');

  // Model search stays a separate surface without an embedded thinking picker.
  browser('find', 'role', 'button', 'click', '--name', labels.en.model, '--exact');
  browser('wait', '[role=combobox]');
  assert.equal(evaluate(`document.querySelector('[role=radiogroup][aria-label=${JSON.stringify(labels.en.group)}]') === null`), true);
  assert.equal(evaluate('document.querySelector(".effort-menu") === null'), true);
  browser('press', 'Escape');
  browser('wait', '--fn', 'document.querySelector("[role=combobox]") === null');

  // Device emulation exercises coarse-pointer sizing, both placements, themes and locales.
  browser('set', 'device', 'iPhone 12');
  for (const lang of ['en', 'uk']) {
    evaluate(`document.documentElement.lang = ${JSON.stringify(lang)};
      document.documentElement.dataset.theme = ${JSON.stringify(lang === 'en' ? 'dark' : 'light')};`);
    for (const [width, height] of [[320, 568], [390, 844], [768, 844], [1440, 900]]) {
      browser('set', 'viewport', String(width), String(height));
      evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
      openMenu();
      assertMenu(lang, '');
      const layout = touchLayout();
      assert.equal(layout.coarse, true, 'device emulation uses touch hit targets');
      assert.ok(layout.triggerWidth >= 44 && layout.triggerHeight >= 44, 'both effort trigger placements expand to 44px on touch');
      assert.ok(layout.left >= -1 && layout.top >= -1 && layout.right <= layout.width + 1 && layout.bottom <= layout.height + 1,
        `the ${lang} popover fits ${width}x${height}`);
      assert.ok(layout.scrollWidth <= layout.width, 'the popup never widens the page');
      browser('press', 'Escape');
      browser('wait', '--fn', '!document.querySelector(".effort-menu")');
      assert.equal(evaluate('document.activeElement.matches("[data-effort-test-trigger]")'), true);
    }
  }
  assert.ok(evaluate('window.__effortWrites.every(write => write.address === "/api/brain/thinking")'), 'effort choices cannot mutate the model');
  console.log('PASS: effort mouse/keyboard selection, serialized writes, rollback, reset vs off, accessible hint/focus, 44px touch targets, viewport fit and locale');
} finally {
  browser('close');
}
