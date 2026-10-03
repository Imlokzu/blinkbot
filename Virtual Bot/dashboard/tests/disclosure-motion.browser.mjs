/** Browser-only saved history exercises scrolled logs and interruption-safe folding. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `disclosure-motion-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5183';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
let mediaSocket;
let mediaSession;
let mediaSend;
const emulateReducedMotion = async reduced => {
  if (!mediaSocket) {
    mediaSocket = new WebSocket(browser('get', 'cdp-url').trim());
    await new Promise(resolve => mediaSocket.addEventListener('open', resolve, { once: true }));
    let next = 0;
    const pending = new Map();
    mediaSocket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      const task = pending.get(message.id);
      if (!task) return;
      pending.delete(message.id);
      if (message.error) task.reject(message.error);
      else task.resolve(message.result);
    });
    mediaSend = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
      const id = ++next;
      pending.set(id, { resolve, reject });
      mediaSocket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
    const targets = await mediaSend('Target.getTargets');
    const target = targets.targetInfos.find(item => item.type === 'page' && item.url.startsWith(origin));
    assert.ok(target, 'native emulation must stay in this test browser');
    mediaSession = (await mediaSend('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  }
  await mediaSend('Emulation.setEmulatedMedia', {
    features: [{ name: 'prefers-reduced-motion', value: reduced ? 'reduce' : 'no-preference' }],
  }, mediaSession);
};
const body = '[data-activity-branches]';
const header = '[data-activity-toggle]';
const log = '[data-tool-step="long-log"] [data-tool-details]';
const waitPhase = (selector, phase) => browser('wait', '--fn',
  `document.querySelector(${JSON.stringify(selector)})?.dataset.phase === ${JSON.stringify(phase)}`);
const assertSourceBounds = () => {
  const bounds = evaluate(`(() => {
    const viewport=document.querySelector('.chat-thread-viewport').getBoundingClientRect();
    return ['.chat-source-strip','[data-sources-toggle]','[data-sources-fold]'].map(selector => {
      const box=document.querySelector(selector).getBoundingClientRect();
      return {selector,left:box.left,right:box.right,viewportLeft:viewport.left,viewportRight:viewport.right};
    });
  })()`);
  for (const box of bounds) {
    assert.ok(box.left >= box.viewportLeft - 1 && box.right <= box.viewportRight + 1,
      `${box.selector} must fit the conversation, including its permanently mounted hidden source titles`);
  }
};
const record = (selector, clickSelector, wanted) => {
  evaluate(`window.__disclosureFrames = []; window.__disclosureFinished = false;
    const node = document.querySelector(${JSON.stringify(selector)});
    const started = performance.now();
    const sample = () => {
      const rect = node.getBoundingClientRect();
      const tree = node.closest('.chat-activity-tree');
      const glass = tree && getComputedStyle(tree, '::before');
      window.__disclosureFrames.push({height:rect.height, phase:node.dataset.phase,
        scroll:node.scrollTop, glassHeight:glass && parseFloat(glass.height),
        treeHeight:tree && tree.getBoundingClientRect().height});
      if ((node.dataset.phase === ${JSON.stringify(wanted)} && performance.now() - started > 35) || performance.now() - started > 1400) {
        window.__disclosureFinished = true; return;
      }
      requestAnimationFrame(sample);
    }; requestAnimationFrame(sample); true`);
  browser('click', clickSelector);
  browser('wait', '--fn', 'window.__disclosureFinished === true');
  return evaluate('window.__disclosureFrames');
};

try {
  execFileSync('osascript', ['-e', 'set volume output muted true']);
  browser('open', 'about:blank');
  browser('set', 'viewport', '1280', '850');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [{ id: 'motion-history', title: 'Disclosure motion fixture', count: 2 }] });
  route('**/api/sessions/motion-history', { id: 'motion-history', messages: [
    { role: 'user', content: 'A saved disclosure fixture.' },
    { role: 'assistant', content: 'A controlled answer.', steps: [
      { id: 'long-log', label: 'workspace_read', detail: 'notes/large.txt', status: 'done', input: { path: 'notes/large.txt' },
        result: Array.from({ length: 250 }, (_, index) => `Line ${index}: a real-position scroll fixture`).join('\n') },
      { id: 'source-log', label: 'web_search', detail: 'motion fixture', status: 'done', result: {
        results: Array.from({ length: 5 }, (_, index) => ({
          title: `Fixture page ${index}: ${'A long title with useful source context '.repeat(5)}`,
          url: `https://docs.fixture.test/page-${index}`,
        })),
      } },
    ] },
  ] });
  route('**/api/brain/models', { available: true, selected: 'test', default: 'test', models: [{ id: 'test', label: 'Test', context: 200000 }], thinking: 'high', thinking_levels: ['high'] });
  route('**/api/tts/status', { enabled: false });
  route('**/api/**', {});
  browser('open', `${origin}${path}#/chat`);
  evaluate("localStorage.setItem('claudeBotTheme','dark'); true");
  browser('reload');
  browser('wait', '[data-session-id="motion-history"]');
  evaluate(`window.__disclosureWrites = []; const realFetch = window.fetch;
    window.fetch = (url, options) => {
      const method = String(options?.method || 'GET').toUpperCase();
      if (!['GET','HEAD'].includes(method)) {
        window.__disclosureWrites.push({url:String(url),method});
        return Promise.resolve(Response.json({}, {status:405}));
      } return realFetch(url, options);
    }; true`);
  browser('find', 'text', 'Disclosure motion fixture', 'click', '--exact');
  browser('wait', '--text', 'A controlled answer.');
  waitPhase(body, 'closed');
  browser('click', header);
  waitPhase(body, 'open');
  browser('click', '[data-tool-step="long-log"] [data-tool-row]');
  waitPhase(log, 'open');
  const offset = evaluate(`const pre=[...document.querySelectorAll(${JSON.stringify(`${log} pre`)})].at(-1); pre.scrollTop=480; pre.scrollTop`);
  assert.ok(offset >= 400, 'the regression must actually scroll a long log');
  const frames = record(body, header, 'closed');
  assert.ok(frames.some(frame => frame.phase === 'closing' && frame.height > 1), 'close must have real intermediate height');
  assert.equal(frames.at(-1).height, 0, 'scrolled content must still close fully');
  assert.ok(frames.every(frame => frame.scroll === 0), 'the clipping wrapper must not acquire a scroll offset');
  assert.ok(frames.every(frame => !frame.glassHeight || Math.abs(frame.glassHeight - frame.treeHeight) <= 1), 'one backdrop follows the changing tree');
  browser('click', header);
  waitPhase(body, 'open');
  assert.equal(evaluate(`[...document.querySelectorAll(${JSON.stringify(`${log} pre`)})].at(-1).scrollTop`), offset, 'reopening preserves where the log was read');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(log)}).dataset.phase`), 'open', 'nested disclosure survives outer folding');
  // Some pointer paths retain focus in the log instead of focusing its button.
  // Closing must restore the appropriate header before its content becomes inert.
  evaluate(`document.querySelector(${JSON.stringify(`${log} pre`)}).focus();
    document.querySelector(${JSON.stringify(header)}).click(); true`);
  waitPhase(body, 'closed');
  assert.equal(evaluate(`document.activeElement.matches(${JSON.stringify(header)})`), true, 'manual group folding returns focused logs to its header');
  browser('click', header); waitPhase(body, 'open');
  evaluate(`document.querySelector(${JSON.stringify(`${log} pre`)}).focus();
    document.querySelector('[data-tool-step="long-log"] [data-tool-row]').click(); true`);
  waitPhase(log, 'closed');
  assert.equal(evaluate('document.activeElement.matches("[data-tool-step=\\"long-log\\"] [data-tool-row]")'), true, 'manual log folding returns focused payloads to its own row');
  browser('click', '[data-tool-step="long-log"] [data-tool-row]'); waitPhase(log, 'open');
  assert.equal(evaluate(`[...document.querySelectorAll(${JSON.stringify(`${log} pre`)})].at(-1).scrollTop`), offset, 'focus restoration must preserve the nested read position');
  // Reversal from a genuine moving frame must keep the current height.
  evaluate(`window.__reversal = new Promise(resolve => {
    const fold=document.querySelector(${JSON.stringify(body)}), button=document.querySelector(${JSON.stringify(header)});
    const full=fold.getBoundingClientRect().height; button.click();
    const sample=()=>{ const height=fold.getBoundingClientRect().height;
      if (height > 1 && height < full - 1) { button.click(); resolve({height, immediately:fold.getBoundingClientRect().height}); }
      else requestAnimationFrame(sample);
    }; requestAnimationFrame(sample);
  }); true`);
  const reversal = evaluate('window.__reversal');
  assert.ok(Math.abs(reversal.height - reversal.immediately) <= 1, 'reversal must not snap');
  waitPhase(body, 'open');

  const sources = record('[data-sources-fold]', '[data-sources-toggle]', 'open');
  assert.ok(sources.some(frame => frame.phase === 'opening' && frame.height > 1 && frame.height < sources.at(-1).height - 1), 'sources need a visible opening transition');
  const closingSources = record('[data-sources-fold]', '[data-sources-toggle]', 'closed');
  assert.equal(closingSources.at(-1).height, 0);
  assert.equal(evaluate('document.querySelector("[data-sources-fold]").inert'), true);
  for (const width of [390, 320]) {
    browser('set', 'viewport', String(width), '844');
    assertSourceBounds();
    browser('click', '[data-sources-toggle]'); waitPhase('[data-sources-fold]', 'open');
    assertSourceBounds();
    assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
    browser('click', '[data-sources-toggle]'); waitPhase('[data-sources-fold]', 'closed');
  }
  // The app currently remounts its panel when the native preference changes.
  // Exercise the newly mounted disclosure under the real reduced-motion mode.
  evaluate('window.__beforePreferenceFold = document.querySelector("[data-sources-fold]"); true');
  await emulateReducedMotion(true);
  browser('wait', '--fn', 'matchMedia("(prefers-reduced-motion: reduce)").matches');
  browser('wait', '--fn', 'document.querySelector("[data-sources-fold]") && document.querySelector("[data-sources-fold]") !== window.__beforePreferenceFold');
  waitPhase('[data-sources-fold]', 'closed');
  evaluate(`window.__reducedHeightCalls = 0;
    const fold=document.querySelector('[data-sources-fold]'), animate=fold.animate.bind(fold);
    fold.animate=(frames, options) => {
      if (Array.isArray(frames) ? frames.some(frame => 'height' in frame) : 'height' in (frames || {})) {
        window.__reducedHeightCalls += 1;
      }
      return animate(frames, options);
    }; true`);
  browser('click', '[data-sources-toggle]');
  waitPhase('[data-sources-fold]', 'open');
  // The global reduced-motion rule can create 0.01ms scrollbar/visibility
  // transitions. Check the height motion owned by this disclosure directly.
  const reducedFold = evaluate(`(() => {
    const fold=document.querySelector('[data-sources-fold]'), content=fold.firstElementChild, css=getComputedStyle(content);
    return {
      animatingHeight:fold.getAnimations().some(animation => animation.playState === 'running'
        && animation.effect?.getKeyframes().some(frame => 'height' in frame)),
      height:fold.style.height,
      natural:Math.abs(fold.getBoundingClientRect().height-content.getBoundingClientRect().height
        -(parseFloat(css.marginTop)||0)-(parseFloat(css.marginBottom)||0)) <= 1,
    };
  })()`);
  assert.equal(evaluate('window.__reducedHeightCalls'), 0, 'reduced motion must never request height interpolation');
  assert.equal(reducedFold.animatingHeight, false, 'reduced motion must settle the height without an animation');
  assert.equal(reducedFold.height, 'auto', 'reduced motion must release the explicit height immediately');
  assert.equal(reducedFold.natural, true, 'reduced motion must reveal the full natural source height');
  assert.equal(evaluate('matchMedia("(prefers-reduced-motion: reduce)").matches'), true);
  evaluate('window.__beforePreferenceFold = document.querySelector("[data-sources-fold]"); true');
  await emulateReducedMotion(false);
  browser('wait', '--fn', '!matchMedia("(prefers-reduced-motion: reduce)").matches');
  browser('wait', '--fn', 'document.querySelector("[data-sources-fold]") && document.querySelector("[data-sources-fold]") !== window.__beforePreferenceFold');
  waitPhase('[data-sources-fold]', 'closed');
  browser('click', '[data-sources-toggle]');
  waitPhase('[data-sources-fold]', 'open');
  // An open source list keeps its natural height when the viewport changes.
  browser('set', 'viewport', '390', '844');
  assertSourceBounds();
  assert.ok(evaluate(`(() => {
    const fold=document.querySelector('[data-sources-fold]'), content=fold.firstElementChild, css=getComputedStyle(content);
    return Math.abs(fold.getBoundingClientRect().height-content.getBoundingClientRect().height
      -(parseFloat(css.marginTop)||0)-(parseFloat(css.marginBottom)||0)) <= 1;
  })()`), 'resizing an open list must leave no clipped content or blank height');
  evaluate(`document.querySelector('[data-sources-fold] a').focus();
    document.querySelector('[data-sources-toggle]').click(); true`);
  waitPhase('[data-sources-fold]', 'closed');
  assert.equal(evaluate('document.activeElement === document.querySelector("[data-sources-toggle]")'), true);
  evaluate(`window.__sourceReversal = new Promise(resolve => {
    const fold=document.querySelector('[data-sources-fold]'), button=document.querySelector('[data-sources-toggle]');
    button.click();
    const sample=()=>{const height=fold.getBoundingClientRect().height;
      if (height > 1 && fold.dataset.phase==='opening') {
        button.click(); resolve({height,immediately:fold.getBoundingClientRect().height});
      } else requestAnimationFrame(sample);
    }; requestAnimationFrame(sample);
  }); true`);
  const sourceReversal=evaluate('window.__sourceReversal');
  assert.ok(Math.abs(sourceReversal.height-sourceReversal.immediately) <= 1, 'sources reverse without snapping after a resize');
  waitPhase('[data-sources-fold]', 'closed');
  assert.equal(evaluate('document.querySelector("[data-sources-fold]").getBoundingClientRect().height'), 0);
  assert.equal(evaluate('document.querySelector("[data-sources-fold]").getAttribute("aria-hidden")'), 'true');
  assert.deepEqual(evaluate('window.__disclosureWrites'), []);
  console.log('PASS: scrolled log collapse/reopen, nested state, reversible height, attached blur, animated sources, long-title bounds, native reduced motion, source resize/focus and no server writes');
} finally { mediaSocket?.close(); browser('close'); }
