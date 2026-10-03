/** Combined choices use isolated browser fixtures, never live OpenClaw writes. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { t } from '../src/locales/chat.ts';

const session = `model-effort-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const menu = '.model-effort-menu';
const models = `${menu} .model-picker-list`;
const effort = `${menu} .effort-picker-list`;
const modelRow = id => `${models} [data-model=${JSON.stringify(id)}]`;
const effortRow = level => `${effort} [data-level=${JSON.stringify(level)}]`;
const firstModel = 'openai/gpt-6-astra';
const nextModel = 'regolo/claude-sonnet-5';
const catalog = {
  models: [
    { id: firstModel, label: 'GPT-6 Astra', context: 200000, vision: true, fast: true, is_default: true },
    { id: nextModel, label: 'Claude Sonnet 5', vision: true, fallback: true },
    ...Array.from({ length: 72 }, (_, index) => ({
      id: `${index % 2 ? 'regolo/claude' : 'openai/gpt'}-fixture-${index}`,
      label: `${index % 2 ? 'Claude' : 'GPT'} fixture ${index}`, context: 32000 + index,
    })),
    { id: 'regolo/closed', label: 'Unavailable fixture', available: false },
    { id: 'regolo/future', label: 'Future fixture' },
  ],
  selected: firstModel, default: firstModel, thinking: 'high', available: true,
  thinking_levels: ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'adaptive', 'max', 'ultra', 'gateway-next'],
};
const labelsFor = lang => {
  const previous = globalThis.document;
  globalThis.document = { documentElement: { lang } };
  try {
    const trigger = t('composer.chooseModelEffort');
    return {
      models: t('composer.models'), effort: t('composer.effort'), search: t('models.openSearch'),
      levels: Object.fromEntries(['', ...catalog.thinking_levels].map(level => [level,
        !level ? t('composer.asConfigured') : level === 'gateway-next' ? level : t(`effort.${level}`),
      ])),
      trigger: (model, level) => trigger.replace('{model}', model).replace('{level}', level),
      modelFailure: t('composer.modelFailed'), effortFailure: t('composer.effortFailed'),
      unavailable: t('models.unavailable'),
    };
  } finally {
    if (previous === undefined) delete globalThis.document;
    else globalThis.document = previous;
  }
};
const labels = { en: labelsFor('en'), uk: labelsFor('uk') };
const visibleTrigger = () => {
  const count = evaluate(`const triggers = [...document.querySelectorAll('[data-brain-choice-trigger]')]
    .filter(button => button.getBoundingClientRect().width > 0 && button.getBoundingClientRect().height > 0);
    document.querySelectorAll('[data-picker-test-trigger]').forEach(button => button.removeAttribute('data-picker-test-trigger'));
    triggers[0]?.setAttribute('data-picker-test-trigger', ''); triggers.length;`);
  assert.equal(count, 1, 'each layout exposes one combined trigger');
  assert.equal(evaluate('document.querySelectorAll(".effort-trigger").length'), 0, 'the separate effort trigger is removed');
  return '[data-picker-test-trigger]';
};
const settle = () => evaluate(`Promise.all(document.querySelector('${menu}').getAnimations({ subtree: true }).map(animation => animation.finished.catch(() => {})))`);
const openMenu = () => {
  evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
  browser('click', visibleTrigger());
  browser('wait', menu);
  settle();
  assert.equal(evaluate('document.querySelector(".model-picker-search-button").ariaExpanded'), 'false', 'each opening begins with collapsed search');
};
const closeMenu = () => {
  browser('press', 'Escape');
  browser('wait', '--fn', `!document.querySelector('${menu}')`);
  assert.equal(evaluate('document.activeElement.matches("[data-picker-test-trigger]")'), true, 'Escape restores the trigger');
};
const waitChoice = (model, level) => browser('wait', '--fn', `document.querySelector(${JSON.stringify(modelRow(model))})?.ariaChecked === 'true'
  && document.querySelector(${JSON.stringify(effortRow(level))})?.ariaChecked === 'true'
  && [...document.querySelectorAll('${menu} [role=radiogroup]')].every(group => group.ariaBusy !== 'true')`);
const assertTrigger = (lang, model, level) => {
  const expected = labels[lang].trigger(model, labels[lang].levels[level]);
  assert.equal(evaluate('document.querySelector("[data-picker-test-trigger]").getAttribute("aria-label")'), expected);
};
const assertStructure = lang => {
  const state = evaluate(`const modelPane = document.querySelector('.model-picker-pane');
    const effortPane = document.querySelector('.effort-picker-pane');
    const left = modelPane.getBoundingClientRect(); const right = effortPane.getBoundingClientRect();
    ({ left: left.left, leftRight: left.right, right: right.left,
      headers: [...document.querySelectorAll('${menu} h2')].map(node => node.textContent),
      groups: document.querySelectorAll('${menu} [role=radiogroup]').length,
      modelScroll: document.querySelector('${models}').scrollHeight > document.querySelector('${models}').clientHeight,
      effortScroll: getComputedStyle(document.querySelector('${effort}')).overflowY,
      traits: document.querySelectorAll('${menu} .lucide-eye, ${menu} .lucide-zap, ${menu} .lucide-brain, ${menu} .lucide-life-buoy').length,
      comparison: Boolean(document.querySelector('${menu} a')),
      radios: document.querySelectorAll('${models} [role=radio]').length,
      checked: document.querySelectorAll('${models} [role=radio][aria-checked=true]').length,
      modelRowText: document.querySelector(${JSON.stringify(modelRow(firstModel))})?.textContent,
      knownLogo: Boolean(document.querySelector(${JSON.stringify(modelRow(firstModel))})?.querySelector('svg[data-brand=openai]')),
      unknownLogo: Boolean(document.querySelector(${JSON.stringify(modelRow('regolo/future'))})?.querySelector('svg[data-brand]')),
      unavailable: document.querySelector(${JSON.stringify(modelRow('regolo/closed'))})?.textContent,
      unavailableDisabled: document.querySelector(${JSON.stringify(modelRow('regolo/closed'))})?.ariaDisabled,
      coarse: matchMedia('(pointer: coarse)').matches,
      viewportWidth: innerWidth,
      popup: { width: document.querySelector('${menu}').getBoundingClientRect().width, height: document.querySelector('${menu}').getBoundingClientRect().height },
      rowHeights: [...document.querySelectorAll('${menu} [role=radio]')].map(row => row.getBoundingClientRect().height),
    });`);
  assert.equal(state.groups, 2);
  assert.deepEqual(state.headers, [labels[lang].models, labels[lang].effort]);
  assert.ok(state.left < state.right && Math.abs(state.leftRight - state.right) < 2, 'models stay left and effort stays right');
  assert.ok(state.modelScroll, 'the large catalog scrolls inside its column');
  assert.equal(state.effortScroll, 'auto');
  assert.equal(state.traits, 0, 'model rows have no capability/status badges');
  assert.equal(state.comparison, false, 'the comparison footer is removed');
  assert.equal(state.radios, catalog.models.length, 'recents move models without duplicating radio choices');
  assert.equal(state.checked, 1);
  assert.equal(state.modelRowText, 'GPT-6 Astra', 'rows show a model name without hostname or trait text');
  assert.ok(state.knownLogo && !state.unknownLogo, 'known makers retain their actual SVG; unknown makers are neutral');
  assert.equal(state.unavailableDisabled, 'true');
  assert.ok(state.unavailable.includes(labels[lang].unavailable));
  assert.ok(state.rowHeights.every(height => height >= (state.coarse || state.viewportWidth < 760 ? 44 : 36)), 'compact mouse rows preserve larger touch targets');
  assert.ok(state.popup.width <= 441 && state.popup.height <= 361, 'the combined picker stays within its compact size');
};
// Keep touch emulation and measurements within one CDP session: the CLI reapplies input settings.
const touchLayout = () => {
  const { cdpUrl } = JSON.parse(browser('--json', 'get', 'cdp-url')).data;
  const { tabs } = JSON.parse(browser('--json', 'tab', 'list')).data;
  return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', `
    const socket = new WebSocket(process.argv[1]);
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { socket.close(); reject(new Error('Touch emulation timed out')); }, 5000);
      socket.addEventListener('error', event => { clearTimeout(timeout); socket.close(); reject(event); });
      socket.addEventListener('open', () => socket.send(JSON.stringify({ id: 1, method: 'Target.attachToTarget', params: { targetId: process.argv[2], flatten: true } })));
      socket.addEventListener('message', event => {
        const reply = JSON.parse(event.data);
        if (reply.error) { clearTimeout(timeout); socket.close(); reject(new Error(reply.error.message)); return; }
        if (reply.id === 1) socket.send(JSON.stringify({ id: 2, sessionId: reply.result.sessionId, method: 'Emulation.setTouchEmulationEnabled', params: { enabled: true, maxTouchPoints: 1 } }));
        if (reply.id === 2) socket.send(JSON.stringify({ id: 3, sessionId: reply.sessionId, method: 'Runtime.evaluate', params: { awaitPromise: true, returnByValue: true,
          expression: 'new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))).then(() => { const menu = document.querySelector(".model-effort-menu").getBoundingClientRect(); const trigger = document.querySelector("[data-picker-test-trigger]").getBoundingClientRect(); const left = document.querySelector(".model-picker-pane").getBoundingClientRect(); const right = document.querySelector(".effort-picker-pane").getBoundingClientRect(); return { left: menu.left, top: menu.top, right: menu.right, bottom: menu.bottom, width: innerWidth, height: innerHeight, scrollWidth: document.documentElement.scrollWidth, triggerWidth: trigger.width, triggerHeight: trigger.height, modelWidth: left.width, effortWidth: right.width, rowOverflow: [...document.querySelectorAll(".brain-picker-row")].some(row => row.scrollWidth > row.clientWidth + 1), coarse: matchMedia("(pointer:coarse)").matches }; })' } }));
        if (reply.id === 3) { clearTimeout(timeout); socket.close(); if (reply.result.exceptionDetails) reject(new Error(reply.result.exceptionDetails.text)); else { console.log(JSON.stringify(reply.result.result.value)); resolve(); } }
      });
    });`, cdpUrl, tabs.find(tab => tab.active).targetId], { encoding: 'utf8' }));
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
  // Guards remain active even if the in-page fixture is accidentally bypassed.
  route('**/api/brain/model', { ok: true, selected: catalog.selected });
  route('**/api/brain/thinking', { ok: true, thinking: catalog.thinking });
  browser('open', `${origin}/docs`);
  evaluate(`localStorage.setItem('claudeBotLang', 'en'); localStorage.removeItem('claudeBotRecentModels');
    localStorage.removeItem('claude-bot:brain-models:v6');`);
  browser('open', `${origin}${path}#/chat`);
  browser('wait', '[data-brain-choice-trigger]');
  evaluate(`window.__pickerCatalog = ${JSON.stringify(catalog)}; window.__pickerWrites = [];
    const savedFetch = window.fetch;
    window.fetch = async (input, options = {}) => {
      const address = new URL(typeof input === 'string' ? input : input.url, location.href).pathname;
      if (address === '/api/brain/models') return new Response(JSON.stringify(window.__pickerCatalog), { headers: { 'Content-Type': 'application/json' } });
      if (address === '/api/brain/model' || address === '/api/brain/thinking') {
        const body = JSON.parse(options.body || await input.clone().text()); window.__pickerWrites.push({ address, body });
        if (window.__pickerDelay) await new Promise(resolve => { window.__pickerRelease = resolve; });
        if (window.__pickerWriteFails) return new Response(JSON.stringify({ detail: 'fixture rejected' }), { status: 502, headers: { 'Content-Type': 'application/json' } });
        if (address === '/api/brain/model') window.__pickerCatalog.selected = body.model;
        else window.__pickerCatalog.thinking = body.level;
        return new Response(JSON.stringify({ ok: true, selected: window.__pickerCatalog.selected, thinking: window.__pickerCatalog.thinking }), { headers: { 'Content-Type': 'application/json' } });
      }
      return savedFetch(input, options);
    };`);

  openMenu();
  assertStructure('en');
  assertTrigger('en', 'GPT-6 Astra', 'high');
  assert.equal(evaluate('document.activeElement.dataset.model'), firstModel, 'opening focuses the current model without revealing search');
  const currentRowBounds = evaluate(`const row=document.querySelector(${JSON.stringify(modelRow(firstModel))}).getBoundingClientRect();
    const list=document.querySelector('${models}').getBoundingClientRect(); ({top:row.top,bottom:row.bottom,listTop:list.top,listBottom:list.bottom});`);
  assert.ok(currentRowBounds.top >= currentRowBounds.listTop - 1 && currentRowBounds.bottom <= currentRowBounds.listBottom + 1,
    'the current model remains visible after Radix constrains a large catalog');
  assert.equal(evaluate('document.querySelector("[role=searchbox]").ariaHidden'), 'true');
  browser('hover', '.model-picker-search-button');
  browser('wait', '--fn', 'document.querySelector(".model-picker-search-button").ariaExpanded === "true"');
  settle();
  assert.ok(evaluate('document.querySelector("[role=searchbox]").getBoundingClientRect().width > 100'));
  browser('press', 'Escape');
  assert.equal(evaluate('document.querySelector(".model-picker-search-button").ariaExpanded'), 'false', 'Escape first collapses search');
  closeMenu();

  openMenu();
  browser('press', 'Shift+Tab');
  assert.equal(evaluate('document.activeElement.matches(".model-picker-search-button")'), true);
  assert.equal(evaluate('document.querySelector(".model-picker-search-button").ariaExpanded'), 'true', 'keyboard focus reveals search');
  browser('press', 'Tab');
  assert.equal(evaluate('document.activeElement.matches("[role=searchbox]")'), true);
  browser('fill', '[role=searchbox]', 'Sonnet');
  assert.equal(evaluate(`document.querySelectorAll('${models} [role=radio]').length`), 1);
  evaluate('window.__pickerDelay = true');
  browser('press', 'Enter');
  browser('wait', '--fn', 'typeof window.__pickerRelease === "function"');
  browser('wait', '--fn', `[...document.querySelectorAll('${menu} [role=radio]')].every(row => row.ariaDisabled === 'true')`);
  assert.equal(evaluate('window.__pickerWrites.length'), 1);
  assert.equal(evaluate('localStorage.getItem("claudeBotRecentModels")'), null, 'pending models do not enter recents');
  evaluate(`document.querySelector(${JSON.stringify(effortRow('low'))}).click()`);
  browser('press', 'Enter');
  assert.equal(evaluate('window.__pickerWrites.length'), 1, 'writes cannot overlap across the two columns');
  browser('press', 'Tab');
  browser('press', 'Tab');
  browser('press', 'Tab');
  assert.equal(evaluate('document.activeElement.dataset.level'), 'high', 'a pending write allows moving to the effort column');
  evaluate('window.__pickerDelay = false; window.__pickerRelease(); delete window.__pickerRelease');
  waitChoice(nextModel, 'high');
  assert.equal(evaluate('document.activeElement.dataset.level'), 'high', 'model acknowledgements preserve subsequent keyboard focus');
  assert.deepEqual(evaluate('JSON.parse(localStorage.getItem("claudeBotRecentModels"))'), [nextModel]);
  assert.equal(evaluate(`Boolean(document.querySelector('${menu}'))`), true, 'model acknowledgements keep effort available in the same popover');
  assertTrigger('en', 'Claude Sonnet 5', 'high');
  browser('press', 'Escape');
  assert.equal(evaluate('document.querySelector("[role=searchbox]").value'), '');
  assert.equal(evaluate('document.querySelector(".model-picker-search-button").ariaExpanded'), 'false');

  browser('click', effortRow('medium'));
  waitChoice(nextModel, 'medium');
  assertTrigger('en', 'Claude Sonnet 5', 'medium');
  browser('click', effortRow(''));
  waitChoice(nextModel, '');
  browser('click', effortRow('off'));
  waitChoice(nextModel, 'off');
  assert.deepEqual(evaluate('window.__pickerWrites.slice(-2).map(write => write.body)'), [{ level: '' }, { level: 'off' }], 'reset and explicit off stay distinct');
  const writeCount = evaluate('window.__pickerWrites.length');
  browser('click', effortRow('off'));
  assert.equal(evaluate('window.__pickerWrites.length'), writeCount, 'the checked level does not rewrite settings');

  evaluate('window.__pickerWriteFails = true');
  browser('click', effortRow('high'));
  browser('wait', '--text', labels.en.effortFailure);
  waitChoice(nextModel, 'off');
  browser('click', modelRow(firstModel));
  browser('wait', '--text', labels.en.modelFailure);
  waitChoice(nextModel, 'off');
  assertTrigger('en', 'Claude Sonnet 5', 'off');
  assert.deepEqual(evaluate('JSON.parse(localStorage.getItem("claudeBotRecentModels"))'), [nextModel], 'rejected models do not enter recents');
  evaluate('window.__pickerWriteFails = false');
  browser('click', effortRow('off'));
  browser('press', 'Home');
  waitChoice(nextModel, '');
  browser('press', 'End');
  waitChoice(nextModel, 'gateway-next');
  assert.equal(evaluate('document.activeElement.dataset.level'), 'gateway-next', 'effort keyboard selection moves focus and uses reported future levels');
  browser('press', 'Home');
  waitChoice(nextModel, '');
  closeMenu();

  // Click is also a complete search path, including on a touch-only device.
  browser('wait', '--fn', 'document.querySelector(".toast-stack")?.childElementCount === 0');
  browser('set', 'device', 'iPhone 12');
  for (const lang of ['en', 'uk']) {
    evaluate(`document.documentElement.lang = ${JSON.stringify(lang)}; document.documentElement.dataset.theme = ${JSON.stringify(lang === 'en' ? 'dark' : 'light')};`);
    for (const [width, height] of [[320, 568], [390, 844], [768, 844], [1440, 900]]) {
      browser('set', 'viewport', String(width), String(height));
      openMenu();
      assertStructure(lang);
      assertTrigger(lang, 'Claude Sonnet 5', '');
      const bounds = touchLayout();
      assert.ok(bounds.coarse);
      assert.ok(bounds.triggerWidth >= 44 && bounds.triggerHeight >= 44, 'the single trigger remains a 44px touch target');
      assert.ok(bounds.left >= -1 && bounds.top >= -1 && bounds.right <= bounds.width + 1 && bounds.bottom <= bounds.height + 1, `popover fits ${lang} ${width}x${height}`);
      assert.ok(bounds.scrollWidth <= bounds.width && !bounds.rowOverflow, 'columns and rows never widen the page');
      assert.ok(bounds.modelWidth > bounds.effortWidth && bounds.modelWidth / (bounds.modelWidth + bounds.effortWidth) < .66, 'thinking retains usable width beside the model list');
      if (width === 320) {
        const accessibility = JSON.parse(browser('--json', 'a11y', '--selector', menu)).data;
        assert.equal(accessibility.counts.violations, 0, `the combined ${lang} menu passes its scoped accessibility audit`);
      }
      browser('click', '.model-picker-search-button');
      browser('wait', '--fn', 'document.activeElement.matches("[role=searchbox]")');
      browser('fill', '[role=searchbox]', 'Unavailable');
      const before = evaluate('window.__pickerWrites.length');
      browser('press', 'Enter');
      assert.equal(evaluate('window.__pickerWrites.length'), before, 'unavailable model feedback cannot dispatch a write');
      browser('press', 'Escape');
      assert.equal(evaluate('document.querySelector(".model-picker-search-button").ariaExpanded'), 'false');
      settle();
      assert.ok(evaluate('document.querySelector(".model-picker-title").getBoundingClientRect().width > 0'), 'the models heading returns after search collapses');
      if (process.env.MODEL_EFFORT_SHOTS && width === 390) browser('screenshot', `${process.env.MODEL_EFFORT_SHOTS}/model-effort-${lang}.png`);
      closeMenu();
    }
  }
  openMenu();
  browser('click', modelRow(firstModel));
  waitChoice(firstModel, '');
  assert.equal(evaluate('document.activeElement.dataset.model'), firstModel, 'recents retain the initiating model row focus after moving it');
  assert.equal(evaluate('document.activeElement.tabIndex'), 0, 'the moved row remains the model group tab stop');
  assert.equal(evaluate(`document.querySelectorAll('${models} [aria-checked=true]').length`), 1);
  closeMenu();
  assert.deepEqual(evaluate('window.__pickerWrites[0]'), { address: '/api/brain/model', body: { model: nextModel } });
  assert.ok(evaluate('window.__pickerWrites.slice(1).some(write => write.address === "/api/brain/thinking")'));
  console.log('PASS: one combined trigger, clean two-column catalog, search hover/focus/click, serialized model-to-effort writes, acknowledged recents, rollback, reset/off, keyboard focus, touch bounds and both locales');
} finally {
  browser('close');
}
