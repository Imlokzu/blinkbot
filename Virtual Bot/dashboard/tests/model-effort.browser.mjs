/** Sequential choices use isolated browser fixtures, never live OpenClaw writes. */
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
const effort = `${menu} .effort-picker-pane [role=slider]`;
const modelRow = id => `${models} [data-model=${JSON.stringify(id)}]`;
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
const levels = ['', ...new Set(catalog.thinking_levels.filter(Boolean))];
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
  assert.equal(evaluate(`document.querySelector('${menu}').dataset.pickerStep`), 'effort', 'each opening starts with effort');
  assert.equal(evaluate(`document.querySelectorAll('${models}').length`), 0, 'the model catalog is a separate step');
  assert.equal(evaluate(`getComputedStyle(document.querySelector('${menu}')).borderRadius`), '12px', 'the effort popup matches the reference corners');
};
const openModels = () => {
  browser('click', '.model-picker-change-model');
  browser('wait', `${menu}[data-picker-step="models"]`);
  settle();
  assert.equal(evaluate(`getComputedStyle(document.querySelector('${menu}')).borderRadius`), '20px', 'the model catalog retains its own popup corners');
};
const backToEffort = () => {
  browser('click', '.model-picker-back');
  browser('wait', `${menu}[data-picker-step="effort"]`);
  settle();
};
const closeMenu = () => {
  browser('press', 'Escape');
  browser('wait', '--fn', `!document.querySelector('${menu}')`);
  assert.equal(evaluate('document.activeElement.matches("[data-picker-test-trigger]")'), true, 'Escape restores the trigger');
};
const waitChoice = (model, level) => {
  const modelLabel = catalog.models.find(choice => choice.id === model)?.label ?? model;
  const expectedTrigger = Object.fromEntries(Object.entries(labels).map(([lang, strings]) =>
    [lang, strings.trigger(modelLabel, strings.levels[level])]));
  // The fixture responds before React observes the mutation. Wait for the
  // shared trigger too: a local slider preview alone does not confirm a save.
  browser('wait', '--fn', `window.__pickerCatalog.selected === ${JSON.stringify(model)}
    && window.__pickerCatalog.thinking === ${JSON.stringify(level)}
    && document.querySelector('[data-picker-test-trigger]')?.getAttribute('aria-label')
      === (${JSON.stringify(expectedTrigger)})[document.documentElement.lang === 'uk' ? 'uk' : 'en']
    && (!document.querySelector(${JSON.stringify(modelRow(model))}) || document.querySelector(${JSON.stringify(modelRow(model))}).ariaChecked === 'true')
    && (!document.querySelector('${effort}') || (document.querySelector('${effort}').dataset.level === ${JSON.stringify(level)}
      && document.querySelector('${effort}').getAttribute('aria-valuenow') === ${JSON.stringify(String(levels.indexOf(level)))}))
    && [...document.querySelectorAll('${menu} [aria-busy]')].every(group => group.ariaBusy !== 'true')`);
};
const effortKey = key => {
  assert.equal(evaluate(`const thumb = document.querySelector('${effort}'); thumb.focus();
    document.activeElement === thumb || thumb.getAttribute('aria-disabled') === 'true'`), true,
  'the enabled slider can receive keyboard focus before a keyboard adjustment');
  browser('press', key);
};
const beginMaximumDrag = () => {
  const drag = evaluate(`const thumb = document.querySelector('${effort}').getBoundingClientRect();
    const track = document.querySelector('.effort-slider').getBoundingClientRect();
    ({x: Math.round(thumb.left + thumb.width / 2), y: Math.round(thumb.top + thumb.height / 2),
      endX: Math.round(track.right - 1), writes: window.__pickerWrites.length});`);
  browser('mouse', 'move', String(drag.x), String(drag.y));
  browser('mouse', 'down');
  browser('mouse', 'move', String(drag.endX), String(drag.y));
  browser('wait', '--fn', `document.querySelector('${effort}').getAttribute('aria-valuenow') === ${JSON.stringify(String(levels.length - 1))}`);
  return drag;
};
const assertThumbEdgeDragStable = (model, level) => {
  for (const edge of ['left', 'right']) {
    const point = evaluate(`const r = document.querySelector('${effort}').getBoundingClientRect();
      ({x:Math.round(${edge === 'left' ? 'r.left + 2' : 'r.right - 2'}),
        y:Math.round(r.top+r.height/2),writes:window.__pickerWrites.length});`);
    browser('mouse', 'move', String(point.x), String(point.y));
    browser('mouse', 'down');
    browser('mouse', 'move', String(point.x + (edge === 'left' ? 1 : -1)), String(point.y));
    assert.equal(evaluate(`document.querySelector('${effort}').dataset.level`), level,
      `grabbing the ${edge} thumb edge preserves its value during a one-pixel move`);
    assert.equal(evaluate('window.__pickerWrites.length'), point.writes, 'an edge grab never writes while dragging');
    browser('mouse', 'up');
    waitChoice(model, level);
    assert.equal(evaluate('window.__pickerWrites.length'), point.writes, 'releasing at the original stop does not rewrite effort');
  }
};
const assertTrigger = (lang, model, level) => {
  const expected = labels[lang].trigger(model, labels[lang].levels[level]);
  assert.equal(evaluate('document.querySelector("[data-picker-test-trigger]").getAttribute("aria-label")'), expected);
};
const assertEffortStructure = lang => {
  const state = evaluate(`({
    radios: document.querySelectorAll('${menu} [role=radio]').length,
    sliders: document.querySelectorAll('${effort}').length,
    level: document.querySelector('${effort}').dataset.level,
    value: document.querySelector('${effort}').getAttribute('aria-valuenow'),
    minimum: document.querySelector('${effort}').getAttribute('aria-valuemin'),
    maximum: document.querySelector('${effort}').getAttribute('aria-valuemax'),
    valueText: document.querySelector('${effort}').getAttribute('aria-valuetext'),
    orientation: document.querySelector('${effort}').getAttribute('aria-orientation'),
    label: document.querySelector('${effort}').getAttribute('aria-label'),
    changeModel: Boolean(document.querySelector('.model-picker-change-model')),
    title: document.querySelector('.effort-slider-value')?.textContent,
    modelText: document.querySelector('.effort-model-link > span')?.textContent,
    expectedModel: window.__pickerCatalog.models.find(model => model.id === window.__pickerCatalog.selected)?.label,
    titleBottom: document.querySelector('.effort-slider-value')?.getBoundingClientRect().bottom,
    modelTop: document.querySelector('.effort-model-link')?.getBoundingClientRect().top,
    titleFont: getComputedStyle(document.querySelector('.effort-slider-value')).fontSize,
    titleWeight: getComputedStyle(document.querySelector('.effort-slider-value')).fontWeight,
    titleLine: getComputedStyle(document.querySelector('.effort-slider-value')).lineHeight,
    modelFont: getComputedStyle(document.querySelector('.effort-model-link')).fontSize,
    modelLine: getComputedStyle(document.querySelector('.effort-model-link')).lineHeight,
    headerHeight: document.querySelector('.effort-slider-heading').getBoundingClientRect().height,
    headerBorder: getComputedStyle(document.querySelector('.model-picker-change-model')).borderBottomWidth,
    headerIcons: document.querySelectorAll('.model-picker-change-model svg[data-brand]').length,
    endpointLabels: document.querySelectorAll('.effort-slider-endpoints').length,
    width: document.querySelector('${menu}').getBoundingClientRect().width,
    height: document.querySelector('${menu}').getBoundingClientRect().height,
  });`);
  assert.equal(state.radios, 0, 'effort uses a slider instead of a list of text choices');
  assert.equal(state.sliders, 1);
  assert.equal(state.minimum, '0');
  assert.equal(state.maximum, String(levels.length - 1), 'every distinct backend level has a snap point');
  assert.equal(state.value, String(levels.indexOf(state.level)));
  assert.equal(state.valueText, labels[lang].levels[state.level], 'assistive technology gets the localized effort name');
  assert.equal(state.orientation, 'horizontal');
  assert.ok(state.label, 'the slider has an accessible name');
  assert.ok(state.changeModel, 'the current model opens the next step');
  assert.equal(state.title, labels[lang].levels[state.level], 'the compact header leads with the current effort');
  assert.equal(state.modelText, state.expectedModel);
  assert.ok(state.titleBottom <= state.modelTop + 1, 'the model name sits below the live effort title');
  assert.deepEqual([state.titleFont, state.titleWeight, state.titleLine], ['14px', '600', '17px']);
  assert.deepEqual([state.modelFont, state.modelLine], ['12px', '15px']);
  assert.equal(state.headerHeight, 48);
  assert.equal(state.headerBorder, '0px', 'the compact header has no dividing line');
  assert.equal(state.headerIcons, 0, 'the compact header has no provider icon');
  assert.equal(state.endpointLabels, 0, 'the reference rail needs no endpoint captions');
  assert.equal(state.width, 256, 'the effort popup matches the reference width');
  assert.ok(state.height < 150, 'the compact effort popup fits its header and rail');
};
const assertEffortGeometry = () => {
  const state = evaluate(`const bounds = node => { const r = node.getBoundingClientRect();
      return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height,center:r.left+r.width/2}; };
    const thumb = document.querySelector('${effort}');
    const track = document.querySelector('.effort-slider-track');
    const range = document.querySelector('.effort-slider-range');
    const ticks = [...document.querySelectorAll('.effort-slider-ticks i')];
    ({thumb:bounds(thumb), track:bounds(track), range:bounds(range), ticks:ticks.map(bounds),
      value:Number(thumb.getAttribute('aria-valuenow')), max:Number(thumb.getAttribute('aria-valuemax')),
      thumbColor:getComputedStyle(thumb).backgroundImage, rangeColor:getComputedStyle(range).backgroundImage});`);
  assert.equal(state.track.height, 28, 'the orange rail has the reference height');
  assert.equal(state.thumb.width, 44, 'the reference handle is a wide pill');
  assert.equal(state.thumb.height, 32, 'the thumb overhangs the rail by 2px on each side');
  assert.ok(state.thumbColor.includes('rgb(255, 255, 255)'), 'the handle stays white in both themes');
  assert.ok(state.rangeColor.includes('rgb(230, 83, 47)'), 'the filled rail uses the exact reference orange');
  assert.equal(state.ticks.length, levels.length);
  assert.ok(state.ticks.every(tick => tick.width === 4 && tick.height === 4), 'each stop is a 4px dot');
  const center = state.track.left + 22 + (state.track.width - 44) * state.value / state.max;
  assert.ok(Math.abs(state.thumb.center - center) <= 1, 'thumb centers use the same inset travel as pointer input');
  assert.ok(Math.abs(state.ticks[state.value].center - center) <= 1, 'the selected dot stays centered under the thumb');
  assert.ok(Math.abs(state.ticks[0].center - state.track.left - 22) <= 1
    && Math.abs(state.ticks.at(-1).center - state.track.right + 22) <= 1, 'endpoint dots follow the 22px center inset');
  assert.ok(Math.abs(state.range.left - state.track.left) <= 1, 'the orange fill starts at the rail edge');
  assert.ok(Math.abs(state.range.right - Math.min(state.track.right, center + 14)) <= 1,
    'the orange fill extends one rail radius under the thumb');
};
const clickEffortEndpoint = endpoint => {
  const point = evaluate(`const ticks = [...document.querySelectorAll('.effort-slider-ticks i')];
    const r = ticks[${endpoint === 'max' ? 'ticks.length - 1' : '0'}].getBoundingClientRect();
    ({x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)});`);
  browser('mouse', 'move', String(point.x), String(point.y));
  browser('mouse', 'down');
  browser('mouse', 'up');
};
const assertModelStructure = lang => {
  const state = evaluate(`({
      headers: [...document.querySelectorAll('${menu} h2')].map(node => node.textContent),
      groups: document.querySelectorAll('${menu} [role=radiogroup]').length,
      modelScroll: document.querySelector('${models}').scrollHeight > document.querySelector('${models}').clientHeight,
      traits: document.querySelectorAll('${models} [role=radio] :is(.lucide-eye, .lucide-zap, .lucide-brain, .lucide-life-buoy)').length,
      intelShown: (() => { try { return localStorage.getItem("claudeBotModelIntel") } catch { return null } })(),
      intelScores: document.querySelectorAll('${models} .model-intel').length,
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
  assert.equal(state.groups, 1, 'models are the only radio group in the second step');
  assert.deepEqual(state.headers, [labels[lang].models]);
  assert.ok(state.modelScroll, 'the large catalog scrolls inside its step');
  assert.equal(state.traits, 0, 'model rows have no capability/status badges');
  assert.equal(state.comparison, false, 'the comparison footer is removed');
  // The benchmark index is off in these fixtures only because they turn it
  // off in localStorage first; the default for everyone else is on.
  assert.equal(state.intelShown, '0');
  assert.equal(state.intelScores, 0, 'rows carry no index while it is hidden');
  assert.equal(state.radios, catalog.models.length, 'recents move models without duplicating radio choices');
  assert.equal(state.checked, 1);
  assert.equal(state.modelRowText, 'GPT-6 Astra', 'rows show a model name without hostname or trait text');
  assert.ok(state.knownLogo && !state.unknownLogo, 'known makers retain their actual SVG; unknown makers are neutral');
  assert.equal(state.unavailableDisabled, 'true');
  assert.ok(state.unavailable.includes(labels[lang].unavailable));
  assert.ok(state.rowHeights.every(height => height >= (state.coarse || state.viewportWidth < 760 ? 44 : 36)), 'compact mouse rows preserve larger touch targets');
  assert.ok(state.popup.width <= 321 && state.popup.height <= 401, 'the sequential picker stays compact');
};
const assertComposerLayout = () => {
  const state = evaluate(`const bar = document.querySelector('.prompt-bar__bar');
    const trigger = bar.querySelector('[data-brain-choice-trigger]');
    const tools = [...bar.querySelectorAll('.prompt-bar__tool:not([aria-pressed])')];
    const microphone = bar.querySelector('.prompt-bar__tool[aria-pressed]');
    const send = bar.querySelector('.prompt-bar__send');
    const bounds = node => { const box = node.getBoundingClientRect(); return { left: box.left, right: box.right, width: box.width }; };
    ({bar: bounds(bar), trigger: bounds(trigger), tools: tools.map(bounds), microphone: bounds(microphone), send: bounds(send),
      micColor: getComputedStyle(microphone).color, sendBackground: getComputedStyle(send).backgroundColor,
      sendColor: getComputedStyle(send).color, sendOpacity: getComputedStyle(send).opacity, sendDisabled: send.disabled,
      fieldRadius: getComputedStyle(document.querySelector('.prompt-bar__field')).borderRadius,
      plateRadius: getComputedStyle(document.querySelector('.prompt-bar-slot > .liquid-glass-plate')).borderRadius});`);
  assert.ok(state.trigger.left > state.bar.left + state.bar.width / 2, 'desktop model and effort sit on the right');
  assert.ok(state.tools.every(tool => tool.right <= state.trigger.left), 'attachment controls stay to the left');
  assert.ok(state.trigger.right <= state.microphone.left + 1 && state.microphone.right <= state.send.left + 1,
    'right-side controls stay ordered model, microphone, send');
  assert.equal(state.micColor, 'rgb(115, 115, 115)', 'the idle microphone uses neutral gray');
  assert.equal(state.sendBackground, 'rgb(48, 48, 48)', 'send uses the reference charcoal');
  assert.equal(state.sendColor, 'rgb(255, 255, 255)', 'the send arrow stays white');
  assert.equal(state.sendOpacity, '1', 'disabled send keeps its charcoal appearance');
  assert.ok(state.sendDisabled, 'the empty composer remains unable to send');
  assert.equal(state.fieldRadius, '28px', 'the composer uses the rounded reference silhouette');
  assert.equal(state.plateRadius, '28px', 'the glass background follows the composer corners');
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
          expression: 'new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))).then(() => { const menu = document.querySelector(".model-effort-menu").getBoundingClientRect(); const trigger = document.querySelector("[data-picker-test-trigger]").getBoundingClientRect(); const pane = document.querySelector(".brain-picker-pane").getBoundingClientRect(); return { left: menu.left, top: menu.top, right: menu.right, bottom: menu.bottom, width: innerWidth, height: innerHeight, scrollWidth: document.documentElement.scrollWidth, triggerWidth: trigger.width, triggerHeight: trigger.height, paneWidth: pane.width, rowOverflow: [...document.querySelectorAll(".brain-picker-row")].some(row => row.scrollWidth > row.clientWidth + 1), coarse: matchMedia("(pointer:coarse)").matches }; })' } }));
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
  evaluate(`localStorage.setItem('claudeBotLang', 'en'); localStorage.removeItem('claudeBotRecentModels'); localStorage.setItem('claudeBotModelIntel', '0');
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

  evaluate('document.documentElement.dataset.theme = "light"');
  assertComposerLayout();
  openMenu();
  assertEffortStructure('en');
  assertEffortGeometry();
  if (process.env.MODEL_EFFORT_SHOTS) browser('screenshot', `${process.env.MODEL_EFFORT_SHOTS}/model-effort-en-reference-1440.png`);
  assertTrigger('en', 'GPT-6 Astra', 'high');
  assertThumbEdgeDragStable(firstModel, 'high');
  assert.equal(evaluate('document.activeElement.dataset.level'), 'high', 'opening focuses the selected effort');
  openModels();
  assertModelStructure('en');
  assert.equal(evaluate('document.activeElement.dataset.model'), firstModel, 'the model step focuses the selected model');
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
  browser('press', 'Escape');
  browser('wait', `${menu}[data-picker-step="effort"]`);
  assert.equal(evaluate('document.activeElement.matches(".model-picker-change-model")'), true, 'Escape returns to the model navigation row');
  closeMenu();

  openMenu();
  openModels();
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
  browser('press', 'Enter');
  assert.equal(evaluate('window.__pickerWrites.length'), 1, 'a pending write cannot be submitted twice');
  evaluate('window.__pickerDelay = false; window.__pickerRelease(); delete window.__pickerRelease');
  browser('wait', `${menu}[data-picker-step="effort"]`);
  waitChoice(nextModel, 'high');
  assert.equal(evaluate('document.activeElement.dataset.level'), 'high', 'successful model selection returns focus to effort');
  assert.deepEqual(evaluate('JSON.parse(localStorage.getItem("claudeBotRecentModels"))'), [nextModel]);
  assertTrigger('en', 'Claude Sonnet 5', 'high');

  effortKey('ArrowLeft');
  waitChoice(nextModel, 'medium');
  assertTrigger('en', 'Claude Sonnet 5', 'medium');
  const canceledDrag = beginMaximumDrag();
  evaluate(`document.querySelector('${effort}').dispatchEvent(new PointerEvent('pointercancel', { bubbles: true, pointerId: 1, pointerType: 'mouse' }))`);
  waitChoice(nextModel, 'medium');
  browser('mouse', 'up');
  assert.equal(evaluate('window.__pickerWrites.length'), canceledDrag.writes, 'a canceled drag restores confirmed effort without saving');
  // A new gesture measures the current track even when cancellation was
  // followed by a layout change; cached coordinates must not shift its stops.
  browser('set', 'viewport', '1380', '900');
  const drag = beginMaximumDrag();
  assert.equal(evaluate('window.__pickerWrites.length'), drag.writes, 'dragging previews effort without writing settings');
  assert.equal(evaluate('window.__pickerCatalog.thinking'), 'medium', 'the committed effort stays unchanged until pointer release');
  assertTrigger('en', 'Claude Sonnet 5', 'medium');
  browser('mouse', 'up');
  waitChoice(nextModel, 'gateway-next');
  assert.equal(evaluate('window.__pickerWrites.length'), drag.writes + 1, 'pointer release commits exactly one snapped level');
  assertEffortGeometry();
  browser('set', 'viewport', '1440', '900');
  effortKey('Home');
  waitChoice(nextModel, '');
  assertEffortGeometry();
  clickEffortEndpoint('max');
  waitChoice(nextModel, 'gateway-next');
  assertEffortGeometry();
  clickEffortEndpoint('min');
  waitChoice(nextModel, '');
  assertEffortGeometry();
  effortKey('ArrowRight');
  waitChoice(nextModel, 'off');
  assert.deepEqual(evaluate('window.__pickerWrites.slice(-2).map(write => write.body)'), [{ level: '' }, { level: 'off' }], 'reset and explicit off stay distinct');
  const writeCount = evaluate('window.__pickerWrites.length');
  browser('click', effort);
  assert.equal(evaluate('window.__pickerWrites.length'), writeCount, 'clicking the current thumb does not rewrite settings');

  evaluate('window.__pickerWriteFails = true');
  effortKey('End');
  browser('wait', '--text', labels.en.effortFailure);
  waitChoice(nextModel, 'off');
  openModels();
  browser('click', modelRow(firstModel));
  browser('wait', '--text', labels.en.modelFailure);
  waitChoice(nextModel, 'off');
  assert.equal(evaluate(`document.querySelector('${menu}').dataset.pickerStep`), 'models', 'a rejected model leaves the catalog available for retry');
  assertTrigger('en', 'Claude Sonnet 5', 'off');
  assert.deepEqual(evaluate('JSON.parse(localStorage.getItem("claudeBotRecentModels"))'), [nextModel], 'rejected models do not enter recents');
  evaluate('window.__pickerWriteFails = false');
  backToEffort();
  effortKey('Home');
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
      assertEffortStructure(lang);
      assertEffortGeometry();
      assertTrigger(lang, 'Claude Sonnet 5', '');
      for (const step of ['effort', 'models']) {
        if (step === 'models') { openModels(); assertModelStructure(lang); }
        const bounds = touchLayout();
        assert.ok(bounds.coarse);
        assert.ok(bounds.triggerWidth >= 44 && bounds.triggerHeight >= 44, 'the single trigger remains a 44px touch target');
        assert.ok(bounds.left >= -1 && bounds.top >= -1 && bounds.right <= bounds.width + 1 && bounds.bottom <= bounds.height + 1, `${step} fits ${lang} ${width}x${height}`);
        assert.ok(bounds.scrollWidth <= bounds.width && !bounds.rowOverflow, 'picker rows never widen the page');
        assert.ok(bounds.paneWidth > 240, 'both steps retain readable full-width rows');
        if (width === 320) {
          const accessibility = JSON.parse(browser('--json', 'a11y', '--selector', menu)).data;
          assert.equal(accessibility.counts.violations, 0, `the ${lang} ${step} step passes its scoped accessibility audit`);
        }
        if (process.env.MODEL_EFFORT_SHOTS && (width === 390 || width === 1440)) {
          browser('screenshot', `${process.env.MODEL_EFFORT_SHOTS}/model-effort-${lang}-${step}-${width}.png`);
        }
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
      backToEffort();
      closeMenu();
    }
  }

  // Navigation during a pending write must stay responsive without allowing a
  // competing effort write, or letting an old acknowledgement steal a new step.
  openMenu();
  openModels();
  evaluate('window.__pickerDelay = true');
  browser('click', modelRow(firstModel));
  browser('wait', '--fn', 'typeof window.__pickerRelease === "function"');
  const pendingWrites = evaluate('window.__pickerWrites.length');
  backToEffort();
  assert.equal(evaluate(`document.querySelector('${effort}').hasAttribute('data-disabled') || document.querySelector('${effort}').ariaDisabled === 'true'`), true);
  effortKey('ArrowRight');
  assert.equal(evaluate('window.__pickerWrites.length'), pendingWrites, 'effort cannot race a pending model write');
  closeMenu();
  openMenu();
  openModels();
  evaluate('window.__pickerDelay = false; window.__pickerRelease(); delete window.__pickerRelease');
  waitChoice(firstModel, '');
  assert.equal(evaluate(`document.querySelector('${menu}').dataset.pickerStep`), 'models', 'an old acknowledgement preserves the newly opened model step');
  assert.deepEqual(evaluate('JSON.parse(localStorage.getItem("claudeBotRecentModels"))'), [firstModel, nextModel]);
  assert.equal(evaluate(`document.querySelectorAll('${models} [aria-checked=true]').length`), 1);
  backToEffort();
  closeMenu();
  openMenu();
  assert.equal(evaluate('document.activeElement.dataset.level'), '', 'reopening always starts at the selected effort');
  closeMenu();
  assert.deepEqual(evaluate('window.__pickerWrites[0]'), { address: '/api/brain/model', body: { model: nextModel } });
  assert.ok(evaluate('window.__pickerWrites.slice(1).some(write => write.address === "/api/brain/thinking")'));
  console.log('PASS: reference header and popup geometry, orange rail with aligned thumb and stops, endpoint clicks, rounded composer, right-aligned neutral controls, release-only writes, cancellation, search/back, serialized writes, late acknowledgements, recents, rollback, reset/off, keyboard focus, touch bounds and both locales');
} catch (error) {
  console.error('Picker failure state:', evaluate(`({step: document.querySelector('${menu}')?.dataset.pickerStep,
    focus: {tag: document.activeElement?.tagName, className: document.activeElement?.className,
      role: document.activeElement?.getAttribute('role'), level: document.activeElement?.dataset.level},
    slider: document.querySelector('${effort}')?.outerHTML,
    busy: document.querySelector('.effort-picker-pane')?.getAttribute('aria-busy')})`));
  if (process.env.MODEL_EFFORT_SHOTS) browser('screenshot', `${process.env.MODEL_EFFORT_SHOTS}/model-effort-failure.png`);
  throw error;
} finally {
  browser('close');
}
