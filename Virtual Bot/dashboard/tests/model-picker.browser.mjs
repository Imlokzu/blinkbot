/** Model choices use browser-only fixtures; no OpenClaw settings are changed. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `night-model-picker-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const openMenu = () => browser('find', 'first', 'button[aria-label="Choose model"], button[aria-label="Обрати модель"]', 'click');
const rows = () => evaluate('[...document.querySelectorAll("[role=listbox] [role=option]")].map(row => row.textContent)');
const catalog = {
  models: [
    { id: 'jev', label: 'Jev', auto: true },
    { id: 'openai/vision', label: 'Vision model', context: 200000, vision: true },
    { id: 'regolo/fast', label: 'Fast model', context: 32000, fast: true },
    { id: 'openai/unavailable', label: 'Unavailable model', available: false },
  ], selected: 'openai/vision', default: 'openai/vision', thinking: 'high',
  thinking_levels: ['low', 'high'], available: true,
};

try {
  browser('open', 'about:blank');
  browser('set', 'viewport', '1440', '900');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [] });
  route('**/api/projects', { projects: [] });
  route('**/api/workspace/info**', { session_path: 'sessions/default' });
  route('**/api/brain/models', catalog);
  // Prepare origin storage on a page that never starts a brain query.
  browser('open', `${origin}/docs`);
  // Parsed JSON can have the wrong type. Cached settings are a second boundary.
  evaluate(`localStorage.setItem('claudeBotLang', 'en');
    localStorage.setItem('claudeBotRecentModels', 'null');
    localStorage.setItem('claudeBotModelSort', '{}');
    localStorage.setItem('claude-bot:brain-models:v6', JSON.stringify({
      data: { ...${JSON.stringify(catalog)}, thinking_levels: 'high' }, savedAt: Date.now()
    }));`);
  browser('open', `${origin}${path}#/chat`);
  browser('wait', 'button[aria-label="Choose model"]');
  openMenu();
  browser('wait', '[role=listbox] [role=option]');
  assert.equal(rows().length, 4);
  assert.equal(evaluate('document.querySelector("[role=combobox]") === document.activeElement'), true);

  // Filters compose with search, and Jev cannot claim a fixed capability.
  browser('find', 'role', 'radio', 'click', '--name', 'sees images', '--exact');
  browser('wait', '--fn', 'document.querySelectorAll("[role=listbox] [role=option]").length === 1');
  assert.deepEqual(rows().map(text => text.startsWith('Vision model')), [true]);
  browser('fill', '[role=combobox]', 'regolo');
  assert.equal(rows().length, 0);
  browser('find', 'role', 'radio', 'click', '--name', 'fast', '--exact');
  browser('wait', '--fn', 'document.querySelector("[role=listbox] [role=option]")?.textContent.includes("Fast model")');
  assert.equal(rows().length, 1);
  assert.match(rows()[0], /Fast model/);
  browser('press', 'Escape');
  assert.equal(evaluate('document.querySelector("[role=combobox]").value'), '');
  browser('press', 'Escape');
  browser('wait', '--fn', 'document.querySelector("[role=listbox]") === null');

  // Delay and fail individual requests through fetch, retaining the actual app flow.
  evaluate(`window.__pickerCatalog = ${JSON.stringify(catalog)};
    window.__pickerWrites = []; window.__pickerReads = [];
    const savedFetch = window.fetch;
    window.fetch = async (url, options = {}) => {
      const address = String(url);
      if (address === '/api/brain/models') {
        window.__pickerReads.push(options.cache);
        return new Response(JSON.stringify(window.__pickerReadFails ? { detail: 'fixture unavailable' } : window.__pickerCatalog), {
          status: window.__pickerReadFails ? 502 : 200, headers: { 'Content-Type': 'application/json' }
        });
      }
      if (address === '/api/brain/model' || address === '/api/brain/thinking') {
        const body = JSON.parse(options.body);
        window.__pickerWrites.push({ address, body });
        if (window.__pickerDelay) await new Promise(resolve => { window.__pickerRelease = resolve; });
        if (window.__pickerWriteFails) return new Response(JSON.stringify({ detail: 'fixture rejected' }), {
          status: 502, headers: { 'Content-Type': 'application/json' }
        });
        if (address.endsWith('/model')) window.__pickerCatalog.selected = body.model;
        else window.__pickerCatalog.thinking = body.level;
        return new Response(JSON.stringify({ ok: true, selected: window.__pickerCatalog.selected, thinking: window.__pickerCatalog.thinking }), {
          headers: { 'Content-Type': 'application/json' }
        });
      }
      return savedFetch(url, options);
    };`);
  openMenu();
  browser('fill', '[role=combobox]', 'Unavailable');
  browser('press', 'Enter');
  assert.equal(evaluate('window.__pickerWrites.length'), 0, 'known unavailable rows must not mutate settings');
  browser('fill', '[role=combobox]', 'Fast');
  evaluate('window.__pickerDelay = true');
  browser('press', 'Enter');
  browser('wait', '--fn', 'typeof window.__pickerRelease === "function"');
  browser('press', 'Enter');
  assert.equal(evaluate('window.__pickerWrites.length'), 1, 'repeat picks cannot overlap');
  assert.equal(evaluate('[...document.querySelectorAll("[role=option]")].every(row => row.ariaDisabled === "true")'), true);
  assert.equal(evaluate(`[...document.querySelectorAll('[role=radiogroup][aria-label="Thinking level"] button')].every(button => button.disabled)`), true);
  assert.equal(evaluate('localStorage.getItem("claudeBotRecentModels")'), 'null', 'unacknowledged choices do not enter recents');
  evaluate('window.__pickerDelay = false; window.__pickerRelease()');
  browser('wait', '--fn', 'document.querySelector("[role=listbox]") === null');
  assert.match(evaluate(`document.querySelector('button[aria-label="Choose model"]').textContent`), /Fast model/);
  assert.deepEqual(evaluate('JSON.parse(localStorage.getItem("claudeBotRecentModels"))'), ['regolo/fast']);
  assert.ok(evaluate('window.__pickerReads.length > 0 && window.__pickerReads.every(cache => cache === "no-store")'));

  // A failed choice keeps the old selection and menu, and does not poison recents.
  openMenu();
  browser('fill', '[role=combobox]', 'Vision');
  evaluate('window.__pickerWriteFails = true');
  browser('press', 'Enter');
  browser('wait', '--text', 'Model was not accepted');
  assert.equal(rows().length, 1);
  assert.deepEqual(evaluate('JSON.parse(localStorage.getItem("claudeBotRecentModels"))'), ['regolo/fast']);
  evaluate('window.__pickerWriteFails = false; window.__pickerReadFails = true');
  browser('press', 'Enter');
  browser('wait', '--fn', 'document.querySelector("[role=listbox]") === null');
  openMenu();
  browser('wait', '--text', 'Could not refresh models. Showing the last catalog.');
  assert.match(evaluate(`document.querySelector('button[aria-label="Choose model"]').textContent`), /Vision model/,
    'confirmed writes survive a failed catalog refresh');
  assert.equal(evaluate('JSON.parse(localStorage.getItem("claude-bot:brain-models:v6")).data.selected'), 'openai/vision',
    'a failed refresh cannot leave the previous choice in the reload cache');
  evaluate('window.__pickerReadFails = false');
  browser('find', 'role', 'button', 'click', '--name', 'Retry', '--exact');
  browser('wait', '--fn', '!document.body.textContent.includes("Could not refresh models.")');

  // Phone dimensions, both locales and themes, empty and disconnected catalogs.
  browser('press', 'Escape');
  for (const width of [320, 390, 768, 1440]) {
    browser('set', 'viewport', String(width), '844');
    evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    openMenu();
    browser('wait', '[role=combobox]');
    assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
    assert.ok(evaluate('document.querySelector("[role=combobox]").getBoundingClientRect().left >= 0'));
    assert.ok(evaluate('document.querySelector("[role=listbox]").clientHeight > 0'));
    if (width === 390 && process.env.MODEL_PICKER_SHOTS) browser('screenshot', `${process.env.MODEL_PICKER_SHOTS}/model-picker-phone.png`);
    browser('press', 'Escape');
  }
  evaluate("document.documentElement.lang = 'uk'; document.documentElement.dataset.theme = 'light'");
  openMenu();
  browser('wait', 'input[aria-label="Пошук моделі…"]');
  assert.ok(evaluate('document.body.textContent.includes("Усі")'));
  browser('press', 'Escape');
  evaluate("document.documentElement.lang = 'en'");
  browser('network', 'unroute', '**/api/brain/models');
  route('**/api/brain/models', { ...catalog, models: [], available: false });
  evaluate("localStorage.removeItem('claude-bot:brain-models:v6')");
  browser('reload');
  browser('wait', 'button[aria-label="Choose model"]');
  openMenu();
  browser('wait', '--text', 'OpenClaw is unavailable. Check the connection and retry.');
  browser('wait', 'button');
  assert.equal(rows().length, 0);
  assert.ok(evaluate('[...document.querySelectorAll("button")].some(button => button.textContent === "Retry")'));
  console.log('PASS: malformed preferences/cache, capability search, unavailable models, serialized writes, acknowledged recents, fresh reads, failed-write/refetch recovery, phone layouts and locale');
} finally {
  browser('close');
}
