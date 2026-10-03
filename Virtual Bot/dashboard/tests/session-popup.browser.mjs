/** Conversation actions must stay above replies, wallpaper and narrow drawers. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { t as chatT } from '../src/locales/chat.ts';
import { t as appT } from '../src/lib/i18n.ts';

const session = `session-popup-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const popup = '[data-session-card-popup]';
const row = id => `[data-session-id="${id}"] .conversation-row__main`;
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8', timeout: 35000 });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const label = (translate, key, language) => {
  const previous = globalThis.document;
  globalThis.document = { documentElement: { lang: language } };
  try { return translate(key); }
  finally { if (previous === undefined) delete globalThis.document; else globalThis.document = previous; }
};
const current = () => evaluate('document.querySelector(".chat-layout").dataset.chatSession');
const shot = name => {
  if (process.env.SESSION_POPUP_SHOTS) browser('screenshot', `${process.env.SESSION_POPUP_SHOTS}/${name}.png`);
};
const settle = () => browser('wait', '--fn', `document.querySelector('${popup}')?.getAnimations({subtree:true}).every(animation=>animation.playState!=='running')`);
const closePopup = () => {
  browser('press', 'Escape');
  browser('wait', '--fn', `!document.querySelector('${popup}')`);
};
const bounds = selector => evaluate(`const box=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();
  ({left:box.left,right:box.right,top:box.top,bottom:box.bottom,width:box.width,height:box.height})`);
const assertPopup = (context, id, glass, overlapsReply = false) => {
  const result = evaluate(`const node=document.querySelector('${popup}'),box=node.getBoundingClientRect();
    const wrapper=node.closest('[data-radix-popper-content-wrapper]');
    const anchor=document.querySelector(${JSON.stringify(row(id))}).getBoundingClientRect();
    const hits=[...node.querySelectorAll('button')].map(button=>{const box=button.getBoundingClientRect();
      const hit=document.elementFromPoint(box.left+box.width/2,box.top+box.height/2);
      return Boolean(hit&&button.contains(hit));});
    let overlap=null;
    for(const reply of document.querySelectorAll('[data-assistant-reply]')){
      const answer=reply.getBoundingClientRect(),left=Math.max(box.left,answer.left),right=Math.min(box.right,answer.right),
        top=Math.max(box.top,answer.top),bottom=Math.min(box.bottom,answer.bottom);
      if(right-left>4&&bottom-top>4){const hit=document.elementFromPoint((left+right)/2,(top+bottom)/2);
        overlap={width:right-left,height:bottom-top,onTop:Boolean(hit&&(node.contains(hit)||hit===wrapper))};break;}
    }
    ({id:node.dataset.sessionCardPopup,count:document.querySelectorAll('${popup}').length,portaled:!node.closest('.conversation-list,.chat-sessions,.chat-session-drawer'),
      inViewport:box.left>=11&&box.top>=11&&box.right<=innerWidth-11&&box.bottom<=innerHeight-11,
      nearAnchor:Math.abs(box.top-anchor.top)<=box.height+anchor.height+12,
      glass:wrapper.classList.contains('popup-lens'),hits,overlap})`);
  assert.equal(result.count, 1, `${context}: only one conversation card opens`);
  assert.equal(result.id, id, `${context}: the opened card belongs to the intended conversation`);
  assert.equal(result.portaled, true, `${context}: the card escapes sidebar and drawer stacking contexts`);
  assert.equal(result.inViewport, true, `${context}: collision handling keeps the entire card visible`);
  assert.equal(result.nearAnchor, true, `${context}: the portaled card remains anchored to its row`);
  assert.equal(result.glass, glass, `${context}: the selected popup material survives portaling`);
  assert.ok(result.hits.length > 0 && result.hits.every(Boolean), `${context}: every action receives pointer input`);
  if (overlapsReply) {
    assert.ok(result.overlap, `${context}: the fixture must reproduce a real card/reply overlap`);
    assert.equal(result.overlap.onTop, true, `${context}: reply content cannot cover the overlapping card`);
  }
};

const now = Date.now() / 1000;
const sessions = Array.from({ length: 24 }, (_, index) => ({
  id: `popup-${index}`, title: `Saved conversation ${index + 1} about a detailed project`, count: 2,
  updated: now - index * 60, pinned: false,
}));
const answer = Array.from({ length: 9 }, (_, index) =>
  `- Detail ${index + 1}: This restored answer fills a wide message bubble so conversation actions overlap real text, just as they do beside a saved chat.`).join('\n');
const fixtures = {
  '/api/auth/config': { disabled: true }, '/api/sessions': { sessions },
  '/api/projects': { projects: [{ id: 'project-fixture', name: 'Popup fixture project' }] },
  '/api/setup': { configured: true, profile: { configured: true, name: 'Fixture', language: 'en', persona: 'friendly', persona_custom: '', greeting: '', reply_length: 'balanced', use_emoji: true, spontaneous: false }, languages: [], personas: [], reply_lengths: [], models: [], selected_model: '', keys_set: { omni: false, openclaw: false } },
  '/api/brain/models': { models: [{ id: 'openai/gpt-fixture', label: 'GPT fixture', context: 200000 }], selected: 'openai/gpt-fixture', default: 'openai/gpt-fixture', thinking: 'high', thinking_levels: ['low', 'high'], available: true },
  '/api/status': { omni: true, openclaw: true, anthropic: true, mode: 'test' },
  '/api/chat/context': { parts: [], chars: 0, dropped: 0, history_limit: 20 },
  '/api/workspace/info': { root: '/fixture/workspace', session_path: 'sessions/default' },
  '/api/workspace/files': { files: [] },
};
for (const item of sessions) fixtures[`/api/sessions/${item.id}`] = { id: item.id, messages: [
  { role: 'assistant', content: answer },
] };
// Install on the blank target before navigation; every API write stays in memory.
const init = `(() => {
  const fixtures=${JSON.stringify(fixtures)},original=window.fetch.bind(window);
  window.__popupSessions=fixtures['/api/sessions'].sessions;window.__popupWrites=[];
  window.EventSource=class{static OPEN=1;readyState=1;constructor(){queueMicrotask(()=>this.onopen?.(new Event('open')));}close(){this.readyState=2;}};
  window.fetch=async(input,options={})=>{
    const pathname=new URL(input instanceof Request?input.url:String(input),location.href).pathname;
    if(!pathname.startsWith('/api/'))return original(input,options);
    const method=String(options.method||(input instanceof Request?input.method:'GET')).toUpperCase();
    if(['GET','HEAD'].includes(method))return Response.json(fixtures[pathname]||{});
    const body=JSON.parse(options.body||'{}');window.__popupWrites.push({pathname,method,body});
    const match=/^\\/api\\/sessions\\/([^/]+)(?:\\/(pin|project))?$/.exec(pathname);
    const item=match&&window.__popupSessions.find(item=>item.id===decodeURIComponent(match[1]));
    if(item&&method==='POST'&&match[2]==='pin'){item.pinned=body.pinned;return Response.json({ok:true});}
    if(item&&method==='POST'&&match[2]==='project'){item.project=body.project;return Response.json({ok:true});}
    if(item&&method==='DELETE'&&!match[2]){window.__popupSessions.splice(window.__popupSessions.indexOf(item),1);return Response.json({ok:true});}
    return Response.json({error:'Unexpected fixture write'},{status:405});
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
const nativeEvaluate = async code => {
  const result = await cdp('Runtime.evaluate', { expression: `(() => { ${code} })()`, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
};
const nativeWait = expression => nativeEvaluate(`return (async()=>{
  const deadline=performance.now()+5000;
  while(!(${expression})){
    if(performance.now()>deadline)throw new Error('Native gesture condition timed out');
    await new Promise(resolve=>setTimeout(resolve,30));
  }return true;
})()`);
const mouse = (x, y) => cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
const moveTo = selector => {
  const box = bounds(selector);
  return mouse(box.left + box.width / 2, box.top + box.height / 2);
};
const hover = async id => {
  await moveTo(row(id)); browser('wait', popup); settle();
};
const action = (key, language) => {
  const text = label(chatT, key, language);
  return evaluate(`const buttons=[...document.querySelector('${popup}').querySelectorAll('button')];
    const index=buttons.findIndex(button=>button.textContent.trim()===${JSON.stringify(text)});
    if(index<0)throw new Error('Missing fixture action');index`);
};
const clickAction = (key, language) => browser('click', `${popup} button:nth-of-type(${action(key, language) + 1})`);
const setup = (language, theme, glass) => evaluate(`
  localStorage.setItem('claudeBotLang',${JSON.stringify(language)});localStorage.setItem('claudeBotTheme',${JSON.stringify(theme)});
  localStorage.setItem('claudeBotConversationList','open');localStorage.setItem('claudeBotChatPins','["projects","usage"]');
  localStorage.setItem('claudeBotPopupGlassTargets',${JSON.stringify(JSON.stringify(glass ? ['menus'] : []))});
  localStorage.setItem('claudeBotChatAppearance',JSON.stringify({background:'sky',targets:['chat','navigation','sessions','panels','pages'],material:'glass',glassRecipe:2,opacity:0,blur:0,sidebarVisible:true}));true`);
const openDrawer = language => {
  browser('click', `.chat-narrow-toolbar button[aria-label=${JSON.stringify(label(appT, 'chat.sessions', language))}]`);
  browser('wait', '.chat-session-drawer');
  browser('wait', '--fn', `document.querySelector('.chat-session-drawer').getAnimations({subtree:true}).every(animation=>animation.playState!=='running')`);
};

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
  if (process.env.SESSION_POPUP_SHOTS) mkdirSync(process.env.SESSION_POPUP_SHOTS, { recursive: true });
  browser('open', 'about:blank');
  browser('network', 'route', '**/api/**', '--body', '{}');
  socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data), request = pending.get(message.id); if (!request) return;
    pending.delete(message.id); if (message.error) request.reject(new Error(JSON.stringify(message.error))); else request.resolve(message.result);
  });
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url === 'about:blank');
  assert.ok(target, 'the isolated blank browser target must exist');
  cdpSession = (await cdp('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  await cdp('Page.enable'); await cdp('Page.addScriptToEvaluateOnNewDocument', { source: init });
  browser('set', 'viewport', '1440', '900'); browser('open', `${origin}${path}#/chat`);

  const desktopCases = process.env.SESSION_POPUP_PHONE_ONLY ? [] : [['en', 'light', true], ['uk', 'dark', true], ['en', 'light', false]];
  for (const [language, theme, glass] of desktopCases) {
    await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }] });
    setup(language, theme, glass); browser('reload'); browser('wait', row('popup-0')); browser('wait', '.chat-pins');
    assert.equal(evaluate('document.querySelectorAll(".conversation-list .pulse-heart,.conversation-row__pin").length'), 0,
      'conversation rows leave pinning to the action card');
    browser('click', row('popup-0')); browser('wait', '[data-assistant-reply]');
    await moveTo('.prompt-bar textarea'); browser('focus', '.prompt-bar textarea');
    browser('wait', '--fn', `!document.querySelector('${popup}')`);
    await hover('popup-1');
    assertPopup(`${language}/${theme}/${glass ? 'glass' : 'solid'}`, 'popup-1', glass, true);
    assert.equal(evaluate('document.activeElement===document.querySelector(".prompt-bar textarea")'), true, 'hover does not steal the draft caret');
    await moveTo(popup);
    evaluate('new Promise(resolve=>setTimeout(resolve,220))');
    assert.equal(evaluate(`Boolean(document.querySelector('${popup}'))`), true, 'moving from the row into its portal cancels delayed closing');
    shot(`desktop-${language}-${theme}-${glass ? 'glass' : 'solid'}`);
    closePopup();
    assert.equal(evaluate('document.activeElement===document.querySelector(".prompt-bar textarea")'), true, 'Escape preserves composer focus');
    assert.equal(current(), 'popup-0', 'hover never changes the selected conversation');

    // A pending mouse hover must not open a second card over keyboard actions.
    await moveTo(row('popup-2'));
    browser('focus', row('popup-3'));
    browser('press', 'Shift+F10');
    browser('wait', '[data-session-card-popup="popup-3"]'); settle();
    evaluate('new Promise(resolve=>setTimeout(resolve,500))');
    assert.deepEqual(evaluate('[...document.querySelectorAll("[data-session-card-popup][data-state=open]")].map(node=>node.dataset.sessionCardPopup)'), ['popup-3']);
    assert.equal(evaluate('Boolean(document.querySelector("[data-session-card-popup] svg[class*=lucide-pin]"))'), true, 'the menu uses a pin glyph');
    closePopup();
    assert.equal(evaluate(`document.activeElement===document.querySelector(${JSON.stringify(row('popup-3'))})`), true);
    browser('focus', '.prompt-bar textarea');

    await hover('popup-1'); clickAction('sessions.pin', language);
    browser('wait', '--fn', 'window.__popupSessions.find(item=>item.id==="popup-1").pinned');
    assert.equal(current(), 'popup-0', 'pinning the hovered row does not open it');
    closePopup(); await hover('popup-1'); clickAction('sessions.toProject', language);
    browser('wait', '--text', 'Popup fixture project');
    browser('find', 'role', 'button', 'click', '--name', 'Popup fixture project', '--exact');
    browser('wait', '--fn', `!document.querySelector('${popup}')`);
    assert.equal(evaluate('window.__popupSessions.find(item=>item.id==="popup-1").project'), 'project-fixture');
    await hover('popup-1'); clickAction('sessions.delete', language);
    assert.equal(evaluate('window.__popupWrites.filter(write=>write.method==="DELETE").length'), 0, 'the first delete click only asks for confirmation');
    clickAction('sessions.deleteSure', language);
    browser('wait', '--fn', `!document.querySelector('${popup}') && !document.querySelector('[data-session-id="popup-1"]')`);
    assert.equal(current(), 'popup-0');
    assert.deepEqual(evaluate('window.__popupWrites'), [
      { pathname: '/api/sessions/popup-1/pin', method: 'POST', body: { pinned: true } },
      { pathname: '/api/sessions/popup-1/project', method: 'POST', body: { project: 'project-fixture' } },
      { pathname: '/api/sessions/popup-1', method: 'DELETE', body: {} },
    ], 'all action writes remain in the isolated fixture');

    await moveTo('.prompt-bar textarea');
    evaluate('document.querySelector(".conversation-list__scroll").scrollTop=10000;true');
    browser('wait', '--fn', 'document.querySelector(".conversation-list__scroll").scrollTop>100');
    await hover('popup-23'); assertPopup(`${language}/${theme}/bottom row`, 'popup-23', glass); closePopup();
    await moveTo(row('popup-22'));
    evaluate("window.location.hash='#/settings?tab=look';true");
    browser('wait', '.chat-appearance-settings'); evaluate('new Promise(resolve=>setTimeout(resolve,500))');
    assert.equal(evaluate(`Boolean(document.querySelector('${popup}'))`), false, 'unmounting a row clears its pending hover and leaves no orphan portal');
    evaluate("window.location.hash='#/chat';true"); browser('wait', row('popup-0'));
  }

  for (const [width, language, theme] of [[390, 'uk', 'light'], [320, 'en', 'dark']]) {
    browser('set', 'viewport', String(width), '844'); setup(language, theme, true); browser('reload');
    browser('wait', '.chat-narrow-toolbar'); openDrawer(language); browser('click', row('popup-0'));
    browser('wait', '[data-assistant-reply]'); browser('wait', '--fn', '!document.querySelector(".chat-session-drawer")');
    openDrawer(language);
    const box = bounds(row('popup-1'));
    await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
    await cdp('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: box.left + box.width / 2, y: box.top + box.height / 2 }] });
    // CLI commands can reset touch emulation; keep each physical gesture on CDP.
    await nativeWait(`document.querySelector('${popup}')?.getAnimations({subtree:true}).every(animation=>animation.playState!=='running')`);
    await cdp('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await nativeEvaluate('return new Promise(resolve=>setTimeout(resolve,220))');
    assert.equal(current(), 'popup-0', 'releasing a long press does not open the hovered conversation');
    assert.equal(evaluate('Boolean(document.querySelector(".chat-session-drawer"))'), true, 'releasing a long press keeps its drawer open');
    assertPopup(`${width}/${language}/${theme}/long press`, 'popup-1', true);
    const drawerBefore = bounds('.chat-session-drawer');
    const title = bounds(`${popup} > p`);
    const x = title.left + title.width / 2, y = title.top + title.height / 2;
    await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
    await cdp('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    await cdp('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x - 50, y }] });
    const drawerDuring = await nativeEvaluate(`const box=document.querySelector('.chat-session-drawer').getBoundingClientRect();return {left:box.left,right:box.right}`);
    assert.equal(drawerDuring.left, drawerBefore.left, 'dragging a portaled card title cannot move the underlying drawer');
    await cdp('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x - 110, y }] });
    await cdp('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await nativeEvaluate('return new Promise(resolve=>setTimeout(resolve,220))');
    assert.equal(evaluate('Boolean(document.querySelector(".chat-session-drawer"))'), true, 'a card gesture cannot dismiss the drawer beneath it');
    assert.equal(current(), 'popup-0');
    assertPopup(`${width}/${language}/${theme}/card drag`, 'popup-1', true);
    shot(`phone-${width}-${language}-${theme}`); closePopup();
    if (evaluate('Boolean(document.querySelector(".chat-session-drawer"))')) browser('press', 'Escape');
    browser('wait', '--fn', '!document.querySelector(".chat-session-drawer")');
    assert.deepEqual(evaluate('window.__popupWrites'), [], 'touch discovery never mutates a conversation');

    // Cancellation can omit the release click; it must not consume a later row tap.
    openDrawer(language);
    const cancelBox = bounds(row('popup-1'));
    await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
    await cdp('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: cancelBox.left + cancelBox.width / 2, y: cancelBox.top + cancelBox.height / 2 }] });
    await nativeWait(`Boolean(document.querySelector('${popup}'))`);
    await cdp('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] });
    closePopup();
    if (evaluate('Boolean(document.querySelector(".chat-session-drawer"))')) browser('press', 'Escape');
    browser('wait', '--fn', '!document.querySelector(".chat-session-drawer")');
    openDrawer(language);
    const freshRow = bounds(row('popup-1'));
    await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
    await cdp('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: freshRow.left + freshRow.width / 2, y: freshRow.top + freshRow.height / 2 }] });
    await cdp('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await nativeWait('document.querySelector(".chat-layout").dataset.chatSession==="popup-1"');
    browser('wait', '--fn', '!document.querySelector(".chat-session-drawer")');
    assert.equal(current(), 'popup-1', 'the first fresh row tap after cancellation opens its conversation');
    assert.deepEqual(evaluate('window.__popupWrites'), [], 'opening a row does not mutate a conversation');

    openDrawer(language);
    const pinRow = bounds(row('popup-2'));
    await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
    await cdp('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: pinRow.left + pinRow.width / 2, y: pinRow.top + pinRow.height / 2 }] });
    await nativeWait(`document.querySelector('${popup}')?.getAnimations({subtree:true}).every(animation=>animation.playState!=='running')`);
    await cdp('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await nativeEvaluate('return new Promise(resolve=>setTimeout(resolve,220))');
    assertPopup(`${width}/${language}/${theme}/menu pin`, 'popup-2', true);
    clickAction('sessions.pin', language);
    browser('wait', '--fn', 'window.__popupSessions.find(item=>item.id==="popup-2").pinned');
    assert.equal(current(), 'popup-1', 'pinning another conversation through its card leaves the current chat selected');
    assert.deepEqual(evaluate('window.__popupWrites'), [{ pathname: '/api/sessions/popup-2/pin', method: 'POST', body: { pinned: true } }]);
    browser('press', 'Escape'); browser('wait', '--fn', '!document.querySelector(".chat-session-drawer")');
    await cdp('Emulation.setTouchEmulationEnabled', { enabled: false });
  }
  console.log(process.env.SESSION_POPUP_PHONE_ONLY
    ? 'PASS: 390/320 long presses, release without selection, isolated card gestures, fresh selection after cancellation and menu pinning'
    : 'PASS: conversation cards above overlapping replies and drawers, glass/solid menus, hover transfer, focus/Escape, pin/project/confirmed delete, bottom collisions, pending-hover unmount and 390/320 long presses/card gestures/cancellation');
} catch (error) {
  console.error(browser('snapshot', '-i')); shot('failure'); throw error;
} finally { socket?.close(); browser('close'); }
