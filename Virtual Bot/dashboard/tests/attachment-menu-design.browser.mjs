/** Attachment actions, responsive bounds and focus use browser-only API fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { t as chatT } from '../src/locales/chat.ts';
import { t as connectorT } from '../src/locales/connectors.ts';
import { t as imageT } from '../src/locales/imageGeneration.ts';
import { t as workbenchT } from '../src/locales/workbench.ts';

const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5183';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const shots = process.env.ATTACHMENT_MENU_SHOTS || '/tmp/attachment-menu-design';
const beforeOnly = process.env.ATTACHMENT_MENU_BEFORE_ONLY === '1';
const session = `attachment-menu-design-${process.pid}`;
const menu = '[data-attachment-menu]';
const trigger = '[data-attachment-trigger]';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = source => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(source)}))()`)).data.result;
const localized = (locale, translate, key) => {
  const previous = globalThis.document;
  globalThis.document = { documentElement: { lang: locale } };
  try { return translate(key); } finally {
    if (previous === undefined) delete globalThis.document;
    else globalThis.document = previous;
  }
};
const screenshot = name => browser('screenshot', `${shots}/${name}.png`);

const fixtures = {
  '/api/auth/config': { disabled: true },
  '/api/status': { openclaw: true, anthropic: true, mode: 'fixture' },
  '/api/sessions': { sessions: [] }, '/api/projects': { projects: [] },
  '/api/setup': { configured: true, profile: { configured: true }, keys_set: {}, languages: [], personas: [], reply_lengths: [] },
  '/api/brain/models': { models: [{ id: 'fixture', label: 'Fixture model', context: 200000 }], selected: 'fixture', default: 'fixture', thinking: 'high', thinking_levels: ['high'], available: true },
  '/api/brain/intelligence': { models: [] },
  '/api/chat/context': { parts: [], chars: 0, dropped: 0, history_limit: 20 },
  '/api/asr/status': { enabled: false }, '/api/tts/status': { enabled: false },
  '/api/connectors': { connectors: [{ id: 'notebooklm', name: 'NotebookLM', kind: 'notebooklm', enabled: true, agent_access: true, browse: true }] },
  '/api/images/status': { available: true, provider: 'codex', code: 'ready' },
  '/api/tools/catalog': { profile: 'fixture', readable: true, groups: [{ server: 'tools', tools: [{ name: 'Fixture search', qualified: 'tools.search', description: 'A browser-only fixture tool', enabled: true, sensitive: false }] }] },
  '/api/workspace/info': { root: '/fixture/workspace', session_path: 'sessions/default' },
  '/api/workspace/files': { files: [] },
};
const init = `(() => {
  const fixtures = ${JSON.stringify(fixtures)}, original = window.fetch.bind(window);
  window.__attachmentMenuWrites = []; window.__attachmentMenuUploads = []; window.__attachmentMenuSends = [];
  window.__attachmentMenuErrors = []; window.__attachmentMenuPickers = [];
  window.addEventListener('error', event => window.__attachmentMenuErrors.push(event.message));
  window.addEventListener('unhandledrejection', event => window.__attachmentMenuErrors.push(String(event.reason)));
  window.EventSource = class { static OPEN = 1; readyState = 1; constructor() { queueMicrotask(() => this.onopen?.(new Event('open'))); } close() { this.readyState = 2; } addEventListener() {} removeEventListener() {} };
  const originalClick = HTMLInputElement.prototype.click;
  HTMLInputElement.prototype.click = function() {
    if (this.type !== 'file') return originalClick.call(this);
    window.__attachmentMenuPickers.push({ accept: this.accept, capture: this.getAttribute('capture'), multiple: this.multiple });
  };
  window.fetch = async (input, options = {}) => {
    const request = input instanceof Request ? input : null;
    const pathname = new URL(request ? request.url : String(input), location.href).pathname;
    if (!pathname.startsWith('/api/')) {
      if (pathname.startsWith('/uploads/')) return new Response('A browser-only attachment.');
      return original(input, options);
    }
    const method = String(options.method || request?.method || 'GET').toUpperCase();
    if (method === 'GET' || method === 'HEAD') {
      if (pathname === '/api/sessions/menu-fixture') return Response.json({ id: 'menu-fixture', messages: [{role:'user',content:'A browser-only question.'},{role:'assistant',content:'A browser-only answer.'}] });
      return Response.json(fixtures[pathname] || {});
    }
    if (method === 'POST' && pathname === '/api/chat/upload') {
      const file = options.body.get('file'); window.__attachmentMenuUploads.push({ name: file.name, type: file.type, size: file.size });
      return Response.json({ name: file.name, type: file.type, size: file.size, url: '/uploads/attachment-menu-fixture.txt' });
    }
    if (method === 'POST' && pathname === '/api/chat') {
      window.__attachmentMenuSends.push(JSON.parse(options.body ?? await request.clone().text()));
      const event = (name, data) => 'event: ' + name + '\\ndata: ' + JSON.stringify(data) + '\\n\\n';
      return new Response(event('delta', {chunk:'A browser-only answer.'}) + event('done', {reply:'A browser-only answer.',session_id:'menu-fixture',emotion:'idle',mode:'fixture',model:'fixture',tool_results:[]}), {headers: {'Content-Type': 'text/event-stream'}});
    }
    window.__attachmentMenuWrites.push({ pathname, method });
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

const settle = selector => {
  browser('wait', selector);
  evaluate(`Promise.all([...document.querySelectorAll(${JSON.stringify(selector)})].flatMap(node => node.getAnimations({subtree:true})).filter(animation => Number.isFinite(animation.effect.getComputedTiming().endTime)).map(animation => animation.finished.catch(() => {})))`);
};
const settleComposer = () => browser('wait','--fn','getComputedStyle(document.querySelector("[data-chat-composer-position]")).transform === "none"');
const open = () => {
  browser('focus', trigger); browser('press', 'Space'); settle(menu);
  assert.equal(evaluate(`document.activeElement === document.querySelector('${menu} .attach-media-rows button, ${menu} .attach-media-grid button')`),true,'keyboard opening starts on the first media action');
};
const close = () => {
  browser('press', 'Escape');
  browser('wait', '--fn', '!document.querySelector("[data-attachment-menu]")');
  browser('wait', '--fn', 'document.activeElement.matches("[data-attachment-trigger]")');
};
const bounds = context => {
  const value = evaluate(`const panel=document.querySelector('${menu}'), rect=panel.getBoundingClientRect(), viewport=window.visualViewport;
    const active=document.activeElement.getBoundingClientRect();
    ({left:rect.left,right:rect.right,top:rect.top,bottom:rect.bottom,width:rect.width,height:rect.height,viewportWidth:viewport?.width||innerWidth,viewportHeight:viewport?.height||innerHeight,offsetTop:viewport?.offsetTop||0,scrollWidth:panel.scrollWidth,clientWidth:panel.clientWidth,pageWidth:document.documentElement.scrollWidth,innerWidth,focusInside:panel.contains(document.activeElement),focusTop:active.top,focusBottom:active.bottom});`);
  assert.ok(value.left >= -1 && value.right <= value.innerWidth + 1, `${context}: menu fits horizontally: ${JSON.stringify(value)}`);
  assert.ok(value.top >= value.offsetTop - 1 && value.bottom <= value.offsetTop + value.viewportHeight + 1, `${context}: menu fits visible viewport: ${JSON.stringify(value)}`);
  assert.ok(value.height >= 44, `${context}: constrained menus retain usable action space`);
  assert.ok(value.scrollWidth <= value.clientWidth + 1, `${context}: menu has no horizontal overflow`);
  assert.ok(value.pageWidth <= value.innerWidth + 1, `${context}: page has no horizontal overflow`);
  assert.equal(value.focusInside, true, `${context}: opening focuses a visible menu action`);
  assert.ok(value.focusTop >= value.top - 1 && value.focusBottom <= value.bottom + 1, `${context}: the initially focused action is fully visible`);
};
const setup = (language, theme, width, height, glass = false) => {
  browser('set', 'viewport', String(width), String(height));
  evaluate(`localStorage.setItem('claudeBotLang', '${language}'); localStorage.setItem('claudeBotTheme', '${theme}');
    localStorage.setItem('claudeBotSendBubble', 'off'); localStorage.setItem('claudeBotPopupGlassTargets', ${JSON.stringify(glass ? '["attachments"]' : '[]')});
    localStorage.setItem('claudeBotChatAppearance', JSON.stringify({background:'sky',targets:['chat'],sidebarVisible:false,material:'glass',glassRecipe:2,opacity:0,blur:0})); true`);
  browser('reload'); browser('wait', trigger);
  settleComposer();
  browser('wait', '--fn', `document.documentElement.lang === '${language}' && document.documentElement.dataset.theme === '${theme}'`);
};
const action = (language, translate, key) => {
  const name = localized(language, translate, key);
  evaluate(`const panel=document.querySelector('${menu}'); const button=[...panel.querySelectorAll('button')].find(node => node.textContent.trim()===${JSON.stringify(name)}); if(!button) throw new Error('Missing attachment action: ' + ${JSON.stringify(name)}); button.scrollIntoView({block:'nearest'}); button.click(); true`);
};
const dismissDialog = () => {
  browser('press', 'Escape');
  browser('wait', '--fn', '!document.querySelector("[role=dialog]")');
  browser('wait', '--fn', '!document.querySelector(".u-veil")');
};

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
  mkdirSync(shots, { recursive: true });
  browser('open', 'about:blank');
  // Deny non-fetch API consumers as well, before application code can mount.
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
  assert.ok(target, 'the isolated browser target exists');
  cdpSession = (await cdp('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: init });
  browser('open', `${origin}${path}#/chat`); browser('wait', trigger);

  if (beforeOnly) {
    for (const [width,height] of [[1440,960],[390,844]]) {
      setup('en', 'dark', width, height); open(); screenshot(`before-${width}`); close();
    }
    console.log('PASS: captured the existing desktop and phone attachment menu');
  } else {
    for (const language of ['en','uk']) for (const theme of ['light','dark']) for (const [width,height] of [[1440,960],[390,844],[320,568]]) {
      const context=`${language}/${theme}/${width}`;
      setup(language,theme,width,height); open(); bounds(`${context}/welcome`);
      assert.equal(evaluate(`document.querySelector('${trigger}').ariaExpanded`), 'true');
      assert.equal(evaluate(`document.querySelectorAll('${menu} .attach-media-rows button').length`), width >= 1180 ? 2 : 0, `${context}: compact desktop photo/file actions`);
      assert.equal(evaluate(`document.querySelectorAll('${menu} .attach-media-grid button').length`), width >= 1180 ? 0 : 3, `${context}: phone camera/photo/file actions`);
      assert.equal(evaluate(`document.querySelectorAll('${menu} [data-attachment-action] > svg[data-solar-icon]').length`),width>=1180?6:8,`${context}: every action keeps its Solar glyph`);
      if (width < 760) assert.equal(evaluate(`[...document.querySelectorAll('${menu} button')].every(button => {const r=button.getBoundingClientRect();return r.width>=44&&r.height>=44})`), true, `${context}: every phone action retains a 44px target`);
      screenshot(`${language}-${theme}-${width}-welcome`);
      const draft = evaluate('document.querySelector(".prompt-bar textarea").getBoundingClientRect().top');
      browser('press','Tab'); assert.equal(evaluate(`document.querySelector('${menu}').contains(document.activeElement)`),true, `${context}: Tab reaches the next menu action`);
      close(); assert.ok(Math.abs(evaluate('document.querySelector(".prompt-bar textarea").getBoundingClientRect().top')-draft)<1, `${context}: menu does not shift draft`);
      browser('click',trigger);settle(menu);bounds(`${context}/pointer`);
      assert.equal(evaluate(`document.activeElement === document.querySelector('${menu}')`),true,`${context}: pointer opening focuses the dialog container`);
      assert.equal(evaluate(`[...document.querySelectorAll('${menu} button')].every(button=>!button.matches(':focus-visible'))`),true,`${context}: pointer opening does not show a keyboard-only focus ring on any action`);
      close();
      open(); evaluate('document.querySelector(".prompt-bar textarea").dispatchEvent(new PointerEvent("pointerdown",{bubbles:true})); true');
      browser('wait','--fn','!document.querySelector("[data-attachment-menu]")');
      assert.deepEqual(evaluate('window.__attachmentMenuWrites'),[], `${context}: menu interaction performs no backend writes`);
    }

    for (const [language,width,height] of [['en',1440,960],['uk',390,844]]) {
      setup(language,'dark',width,height);
      for (const key of width >= 1180 ? ['sheet.photos','sheet.files'] : ['sheet.camera','sheet.photos','sheet.files']) {
        open(); action(language,chatT,key);
        const picker=evaluate('window.__attachmentMenuPickers.at(-1)');
        assert.ok(picker.accept.includes('image/jpeg'));
        assert.equal(picker.capture==='environment',key==='sheet.camera');
        assert.equal(picker.accept.includes('.pdf'),key==='sheet.files');
        close();
      }
      // A synthetic OS selection exercises onFiles and the existing upload callback.
      open(); evaluate(`const input=document.querySelector('[data-attachment-picker=files]'), selection=new DataTransfer();selection.items.add(new File(['A browser-only attachment.'],'menu-fixture.txt',{type:'text/plain'}));input.files=selection.files;input.dispatchEvent(new Event('change',{bubbles:true}));true`);
      browser('wait','.prompt-bar .attachment-card');
      browser('wait','--fn','!document.querySelector("[data-attachment-menu]")');
      assert.equal(evaluate('window.__attachmentMenuUploads.at(-1).name'),'menu-fixture.txt');
      assert.equal(evaluate('document.querySelector("[data-attachment-picker=files]").value'),'','input resets so the same file can be picked twice');
      browser('click','.prompt-bar .attachment-remove');

      open(); action(language,imageT,'title'); browser('wait','[role=dialog] textarea'); dismissDialog();
      browser('wait','--fn','document.activeElement.matches("[data-attachment-trigger]")');
      open(); action(language,connectorT,'connectors.title'); browser('wait','--fn','document.querySelector("[role=dialog]")?.textContent.includes("NotebookLM")'); dismissDialog();
      browser('wait','--fn','document.activeElement.matches("[data-attachment-trigger]")');
      open(); action(language,chatT,'sheet.tools'); browser('wait','--text','Fixture search'); dismissDialog();
      browser('wait','--fn','document.activeElement.matches("[data-attachment-trigger]")');
      assert.equal(evaluate('Boolean(document.querySelector(".chat-pins"))'),false,'Panels starts hidden so opening tests its actual callback');
      open(); action(language,chatT,'sheet.panels');browser('wait',width>=1180?'.chat-pins':'[role=dialog] .chat-pins');
      if (width<1180) dismissDialog();
      else browser('wait','--fn','document.activeElement.matches("[data-attachment-trigger]")');
      if (width<1180) {
        open(); action(language,workbenchT,'wb.open'); browser('wait','.workbench'); dismissDialog();
        open();
        evaluate(`const button=document.querySelector('${menu} .attach-context button');button.scrollIntoView({block:'nearest'});button.click();true`);
        browser('wait','[data-popup-kind=context]');
        evaluate('document.querySelector("[data-popup-kind=context]").dispatchEvent(new PointerEvent("pointerdown",{bubbles:true}));true');
        assert.equal(evaluate(`Boolean(document.querySelector('${menu}'))`),true,'the portaled context breakdown belongs to the attachment menu');
        browser('press','Escape');browser('wait','--fn','!document.querySelector("[data-popup-kind=context]")');
        assert.equal(evaluate(`Boolean(document.querySelector('${menu}'))`),true,'first Escape closes only the nested context breakdown');
        close();
      }
      assert.deepEqual(evaluate('window.__attachmentMenuWrites'),[],'all secondary actions open their existing callbacks without writes');
      assert.deepEqual(evaluate('window.__attachmentMenuSends'),[],'opening image creation cannot submit a prompt');
      assert.deepEqual(evaluate('window.__attachmentMenuErrors'),[],'menu callbacks raise no browser errors');
    }

    for (const [width,height] of [[1440,960],[390,844],[320,568],[390,460]]) {
      setup('en','dark',width,height);
      browser('fill','.prompt-bar textarea','A browser-only question.');browser('press','Enter');
      browser('wait','--text','A browser-only answer.');settleComposer();
      open();bounds(`history/${width}x${height}`);close();
      browser('fill','.prompt-bar textarea',Array.from({length:16},(_,index)=>`Draft line ${index+1}`).join('\n'));
      open();bounds(`tall-draft/${width}x${height}`);screenshot(`en-dark-${width}x${height}-tall-draft`);close();
      assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value.split("\\n").length'),16,'the fitted menu preserves all draft lines');
    }

    // Open while the same composer moves out of the welcome center. The menu
    // must continue fitting after its parent's position changes without resizing.
    // Short viewports already pin the welcome composer to the bottom, so use
    // the full-height phone layout for an actual center-to-bottom spring.
    setup('en','dark',390,844);
    browser('fill','.prompt-bar textarea','A browser-only moving-composer question.');
    // Arm the frame probe before the CLI keypress returns, otherwise its own
    // post-input wait may outlast the short layout spring.
    evaluate(`window.__attachmentMenuMovingProbe=new Promise(resolve=>{let frames=0;const next=()=>{const position=document.querySelector('[data-chat-composer-position]');if(getComputedStyle(position).transform!=='none'){document.querySelector('${trigger}').click();resolve(true);}else if(++frames>120)resolve(false);else requestAnimationFrame(next);};next();});true`);
    browser('press','Enter');
    const moving=evaluate('window.__attachmentMenuMovingProbe');
    assert.equal(moving,true,'the regression opens during an actual composer spring');settle(menu);settleComposer();
    bounds('open-during-composer-movement');close();

    setup('en','dark',1440,960);open();
    browser('set','viewport','390','844');browser('wait','.attach-media-grid');settle(menu);bounds('resize-open-desktop-to-phone');
    browser('set','viewport','1440','960');browser('wait','.attach-media-rows');settle(menu);bounds('resize-open-phone-to-desktop');close();

    setup('uk','dark',390,844,true);open();browser('wait',`${menu}.popup-lens`);bounds('selected-glass');screenshot('uk-dark-390-glass');
    const glassAudit=JSON.parse(browser('a11y','--selector',menu,'--json'));
    assert.equal(glassAudit.data.counts.violations,0,JSON.stringify(glassAudit.data.violations));close();
    await cdp('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
    open();assert.equal(evaluate(`document.querySelector('${menu}').getAnimations({subtree:true}).filter(animation=>animation.playState==='running').length`),0,'reduced motion opens immediately without decorative movement');bounds('reduced-motion');close();
    assert.deepEqual(evaluate('window.__attachmentMenuWrites'),[]);
    assert.deepEqual(evaluate('window.__attachmentMenuErrors'),[]);
    console.log('PASS: clean attachment menu; en/uk and light/dark at 1440/390/320; photo/file/camera callbacks; upload, image creation, connectors, tools, panels and workbench; Escape/outside focus; history/tall draft/short viewport bounds; selective glass, accessibility and reduced motion; no real backend writes');
  }
} catch(error) {
  console.error(browser('snapshot','-i'));console.error(browser('errors'));screenshot('failure');throw error;
} finally {socket?.close();browser('close');}
