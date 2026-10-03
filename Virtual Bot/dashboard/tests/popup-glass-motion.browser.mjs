/** Sample actual popup frames: the lens and its content must share one motion. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const session = `popup-glass-motion-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, data) => browser('network', 'route', url, '--body', JSON.stringify(data));
const setTargets = targets => {
  evaluate(`localStorage.setItem('claudeBotPopupGlassTargets', ${JSON.stringify(JSON.stringify(targets))});
    window.dispatchEvent(new StorageEvent('storage', { key:'claudeBotPopupGlassTargets' })); true`);
  browser('wait', '--fn', `document.documentElement.dataset.popupGlassTargets === ${JSON.stringify(targets.join(' '))}`);
};
const openModels = () => { browser('click', '.brain-choice-trigger'); browser('wait', '.model-effort-menu'); };
const closeModels = () => { browser('press', 'Escape'); browser('wait', '--fn', '!document.querySelector(".model-effort-menu")'); };
const settled = () => browser('wait', '--fn', `(() => {
  const shell = document.querySelector('.model-effort-menu');
  return shell && [shell, shell.parentElement].every(node => node.getAnimations().every(animation => animation.playState === 'finished'));
})()`);
const geometry = () => evaluate(`(() => { const shell=document.querySelector('.model-effort-menu');
  const wrapper=shell.parentElement; return {rect:wrapper.getBoundingClientRect().toJSON(),
    position:wrapper.style.transform, variable:wrapper.style.getPropertyValue('--popup-glass-position'),
    origin:wrapper.style.transformOrigin, motion:wrapper.getAttribute('data-popup-glass-motion')}; })()`);
const nearRects = (a, b, message) => {
  for (const key of ['x', 'y', 'width', 'height']) assert.ok(Math.abs(a[key] - b[key]) <= 1, `${message}: ${key} (${a[key]} vs ${b[key]})`);
};
const traces = {};
let socket;
let cdpSession;
let nextId = 0;
const pending = new Map();
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId;
  pending.set(id, { resolve, reject });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});
const media = (motion = 'no-preference', transparency = 'no-preference') => cdp('Emulation.setEmulatedMedia', {
  features: [{ name:'prefers-reduced-motion', value:motion }, { name:'prefers-reduced-transparency', value:transparency }],
});
const capture = (label, action, duration = 650) => {
  evaluate(`window.__capturePopup(${JSON.stringify(label)}, ${duration}); true`);
  action();
  browser('wait', '--fn', 'window.__popupCapture.done');
  const frames = evaluate('window.__popupCapture.frames');
  traces[label] = frames;
  return frames;
};
const checkSharedFrames = (frames, state) => {
  const present = frames.filter(frame => frame.state === state);
  assert.ok(present.length > 2, `${state} must have genuine intermediate rendered frames`);
  for (const frame of present) {
    assert.equal(frame.motion, state, 'the lens follows the content state');
    nearRects(frame.shell, frame.wrapper, `${state} lens and content bounds`);
    assert.ok(Math.abs(frame.effectiveOpacity - frame.wrapperOpacity) < 0.001, 'content inherits exactly one fade');
    assert.equal(frame.shellOpacity, 1, 'the child must not fade a second time');
    assert.equal(frame.shellTransform, 'none', 'the child must not scale a second time');
    assert.equal(frame.shellFilter, 'none', 'motion must keep the rim and text sharp');
    assert.equal(frame.positionVariable, frame.inlinePosition || 'translate(0px, 0px)', 'Radix retains ownership of its inline position');
    assert.equal(frame.shellAnimation, state === 'open' ? 'u-pop-in' : 'u-pop-out', 'Radix Presence keeps its animation clock');
  }
  return present;
};

try {
  execFileSync('osascript', ['-e', 'set volume output muted true']);
  browser('open', 'about:blank');
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled:true });
  route('**/api/setup', { configured:true, profile:{ configured:true, name:'Test', language:'en', persona:'friendly', persona_custom:'', greeting:'', reply_length:'balanced', use_emoji:true, spontaneous:false }, languages:[], personas:[], reply_lengths:[], models:[], selected_model:'', keys_set:{ omni:false, openclaw:false } });
  route('**/api/sessions', { sessions:[] });
  route('**/api/brain/models', { models:[{ id:'openai/gpt-fixture', label:'GPT fixture', context:200000 }], selected:'openai/gpt-fixture', default:'openai/gpt-fixture', thinking:'high', thinking_levels:['low','high'], available:true });
  route('**/api/chat/context**', { parts:[], chars:0, dropped:0, history_limit:20 });
  // Routes match in registration order. Keep the API catch-all last so the
  // named read fixtures work, while every other request (including writes) is local.
  route('**/api/**', {});
  browser('open', `${origin}${path}#/chat`);
  evaluate(`localStorage.setItem('claudeBotLang','en'); localStorage.setItem('claudeBotTheme','dark');
    localStorage.setItem('claudeBotPopupGlassTargets','["models"]'); true`);
  browser('reload');
  browser('wait', '.chat-thread[data-empty]');

  socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once:true });
    socket.addEventListener('error', reject, { once:true });
  });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (!message.id) return;
    const request = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) request.reject(new Error(JSON.stringify(message.error)));
    else request.resolve(message.result);
  });
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url.startsWith(`${origin}${path}`));
  assert.ok(target, 'the isolated dashboard tab must exist');
  cdpSession = (await cdp('Target.attachToTarget', { targetId:target.targetId, flatten:true })).sessionId;
  await media();
  evaluate(`(() => {
    const rect = node => { const r=node.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; };
    const sample = (capture, source='raf') => {
      const shell=document.querySelector('.model-effort-menu');
      if (!shell) { capture.frames.push({t:performance.now()-capture.start,state:'absent',source}); return; }
      const wrapper=shell.parentElement, content=getComputedStyle(shell), lens=getComputedStyle(wrapper);
      window.__lastPopupWrapper=wrapper;
      let effectiveOpacity=1;
      for(let node=shell;node;node=node.parentElement) effectiveOpacity*=Number(getComputedStyle(node).opacity);
      capture.frames.push({t:performance.now()-capture.start,source,state:shell.dataset.state,
        motion:wrapper.getAttribute('data-popup-glass-motion'),shell:rect(shell),wrapper:rect(wrapper),
        shellOpacity:Number(content.opacity),wrapperOpacity:Number(lens.opacity),effectiveOpacity,
        shellTransform:content.transform,shellFilter:content.filter,shellAnimation:content.animationName,
        wrapperAnimation:lens.animationName,backdrop:lens.backdropFilter,
        inlinePosition:wrapper.style.transform,positionVariable:wrapper.style.getPropertyValue('--popup-glass-position')});
    };
    window.__capturePopup=(label,duration)=>{
      const capture={label,start:performance.now(),frames:[],done:false};window.__popupCapture=capture;
      const tick=()=>{sample(capture);if(performance.now()-capture.start<duration)requestAnimationFrame(tick);else capture.done=true;};
      requestAnimationFrame(tick);
    };
    document.addEventListener('animationend', event=>{
      if(event.target.matches?.('.model-effort-menu') && window.__popupCapture && !window.__popupCapture.done)
        sample(window.__popupCapture,'animationend');
    },true);
    return true;
  })()`);

  const nativeSvg = evaluate('window.Hyalite.supported()');
  const opening = checkSharedFrames(capture('open', openModels), 'open');
  assert.ok(opening.some(frame => frame.wrapperOpacity > 0 && frame.wrapperOpacity < 0.99), 'opening must fade the whole lens');
  assert.ok(Math.max(...opening.map(frame => frame.wrapper.width)) - Math.min(...opening.map(frame => frame.wrapper.width)) > 10, 'opening must scale the whole lens');
  if (nativeSvg) assert.ok(opening.some(frame => frame.backdrop.includes('url(')), 'native SVG refraction remains active during motion');
  const armed = geometry();
  assert.ok(armed.rect.x >= 0 && armed.rect.y >= 0 && armed.rect.x + armed.rect.width <= 1441 && armed.rect.y + armed.rect.height <= 961, 'the settled popup stays within the viewport');

  // Removing the lens must reveal exactly the same final Radix anchor and bounds.
  setTargets([]);
  browser('wait', '--fn', '!document.querySelector(".model-effort-menu").parentElement.classList.contains("popup-lens")');
  nearRects(armed.rect, geometry().rect, 'glass preserves the final solid-popup anchor');
  assert.equal(geometry().position, armed.position, 'arming never overwrites the Radix inline transform');
  assert.equal(geometry().variable, '', 'release removes the hook-owned position variable');
  assert.equal(geometry().origin, '', 'release restores the original inline origin');
  setTargets(['models']);
  settled();
  const beforeResize = geometry();
  browser('set', 'viewport', '1300', '900');
  browser('wait', '--fn', 'document.querySelector(".model-effort-menu").parentElement.style.getPropertyValue("--popup-glass-position") === document.querySelector(".model-effort-menu").parentElement.style.transform');
  const resized = geometry();
  assert.notEqual(resized.position, beforeResize.position, 'Radix repositions the live popup after resizing');
  setTargets([]);
  nearRects(resized.rect, geometry().rect, 'live repositioning preserves the solid-popup anchor');
  setTargets(['models']);
  settled();

  const closing = checkSharedFrames(capture('close', closeModels, 450), 'closed');
  assert.ok(closing.some(frame => frame.wrapperOpacity > 0.02 && frame.wrapperOpacity < 0.98), 'closing must fade the lens before unmounting');
  assert.ok(closing.some(frame => frame.source === 'animationend' && frame.wrapperOpacity <= 0.02), 'the lens reaches zero opacity when Presence ends');
  const end = closing.findIndex(frame => frame.wrapperOpacity <= 0.02);
  assert.ok(end >= 0 && closing.slice(end).every(frame => frame.wrapperOpacity <= 0.02), 'the lens never flashes back after its closing fade');
  assert.deepEqual(evaluate(`(() => {const w=window.__lastPopupWrapper;return {connected:w.isConnected,lens:w.classList.contains('popup-lens'),motion:w.hasAttribute('data-popup-glass-motion'),position:w.style.getPropertyValue('--popup-glass-position')};})()`), {connected:false,lens:false,motion:false,position:''}, 'unmount releases wrapper state');

  // CSS fallback uses the same geometry even when SVG filters are unavailable.
  evaluate('window.__nativePopupSupport=window.Hyalite.supported; window.Hyalite.supported=()=>false; true');
  setTargets([]);
  setTargets(['models']);
  const fallback = checkSharedFrames(capture('css-fallback-open', openModels), 'open');
  assert.ok(fallback.every(frame => frame.backdrop.includes('blur(') && !frame.backdrop.includes('url(')), 'unsupported SVG retains the CSS lens');
  checkSharedFrames(capture('css-fallback-close', closeModels, 450), 'closed');
  evaluate('window.Hyalite.supported=window.__nativePopupSupport; true');
  setTargets([]);
  setTargets(['models']);

  // Live accessibility changes release and rearm without changing Radix placement.
  openModels();
  settled();
  const transparent = geometry();
  await media('no-preference', 'reduce');
  browser('wait', '--fn', '!document.querySelector(".model-effort-menu").parentElement.classList.contains("popup-lens")');
  assert.equal(evaluate('matchMedia("(prefers-reduced-transparency: reduce)").matches'), true);
  nearRects(transparent.rect, geometry().rect, 'reduced transparency restores the solid anchor');
  assert.equal(geometry().motion, null);
  assert.equal(geometry().variable, '');
  await media();
  browser('wait', '--fn', 'document.querySelector(".model-effort-menu").parentElement.getAttribute("data-popup-glass-motion") === "open"');
  settled();
  closeModels();
  await media('reduce');
  const reduced = capture('reduced-motion-open', openModels, 250).filter(frame => frame.state === 'open');
  assert.ok(reduced.length > 2);
  for (const frame of reduced) {
    nearRects(frame.shell, frame.wrapper, 'reduced motion keeps shared bounds');
    assert.equal(frame.wrapperAnimation, 'none');
    assert.equal(frame.wrapperOpacity, 1);
  }
  assert.equal(evaluate('getComputedStyle(document.querySelector(".model-effort-menu")).animationName'), 'u-pop-in');
  assert.ok(parseFloat(evaluate('getComputedStyle(document.querySelector(".model-effort-menu")).animationDuration')) <= 0.001, 'the reduced-motion Presence clock stays short');
  closeModels();
  await media();

  // Hook cleanup must restore pre-existing custom styles, including priorities.
  evaluate(`(() => { const wrapper=document.createElement('div');wrapper.dataset.radixPopperContentWrapper='';
    wrapper.setAttribute('data-popup-glass-motion','previous');
    wrapper.style.cssText='position:fixed;transform:translate(32px,48px);transform-origin:7px 9px!important;--popup-glass-position:translate(1px,2px)!important;border-radius:3px!important';
    const shell=document.createElement('div');shell.className='popup-shell u-pop';shell.dataset.popupKind='models';shell.dataset.state='open';
    shell.style.cssText='width:190px;height:80px;border-radius:12px';wrapper.append(shell);document.body.append(wrapper);window.__motionRestoreFixture=wrapper;return true;})()`);
  browser('wait', '--fn', 'window.__motionRestoreFixture.getAttribute("data-popup-glass-motion") === "open"');
  setTargets([]);
  assert.deepEqual(evaluate(`(() => {const w=window.__motionRestoreFixture;return {motion:w.getAttribute('data-popup-glass-motion'),position:w.style.getPropertyValue('--popup-glass-position'),positionPriority:w.style.getPropertyPriority('--popup-glass-position'),origin:w.style.transformOrigin,originPriority:w.style.getPropertyPriority('transform-origin'),radius:w.style.borderRadius,radiusPriority:w.style.getPropertyPriority('border-radius'),transform:w.style.transform};})()`), {motion:'previous',position:'translate(1px,2px)',positionPriority:'important',origin:'7px 9px',originPriority:'important',radius:'3px',radiusPriority:'important',transform:'translate(32px, 48px)'});
  evaluate('window.__motionRestoreFixture.remove(); true');
  console.log('PASS: shared open/close frame geometry and opacity, Radix Presence, positioning, SVG/CSS fallback, accessibility and cleanup');
} catch (error) {
  console.error(browser('snapshot', '-i'));
  browser('screenshot', `/tmp/${session}-failure.png`);
  throw error;
} finally {
  writeFileSync(`/tmp/${session}-frames.json`, JSON.stringify(traces, null, 2));
  socket?.close();
  browser('close');
}
