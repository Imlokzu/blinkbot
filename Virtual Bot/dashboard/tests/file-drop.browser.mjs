/** Synthetic OS-style file events exercise the real draft path; all API writes stay in memory. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { t as uploadT } from '../src/locales/attachments.ts';

const session = `file-drop-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5187';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8', timeout: 35000 });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const cards = '.prompt-bar .attachment-card';
const active = () => evaluate('document.querySelector(".chat-conversation").hasAttribute("data-file-drop-active")');
const names = () => evaluate('[...document.querySelectorAll(".prompt-bar .attachment-card-name")].map(node=>node.textContent)');
const label = (key, language) => {
  const previous = globalThis.document;
  globalThis.document = { documentElement: { lang: language } };
  try { return uploadT(key); } finally { if (previous === undefined) delete globalThis.document; else globalThis.document = previous; }
};
const now = Date.now() / 1000;
const fixtures = {
  '/api/auth/config': { disabled: true },
  '/api/sessions': { sessions: [{ id: 'drop-saved', title: 'Another conversation', count: 1, updated: now }] },
  '/api/sessions/drop-saved': { id: 'drop-saved', messages: [{ role: 'assistant', content: 'A saved conversation.' }] },
  '/api/projects': { projects: [] },
  '/api/setup': { configured: true, profile: { configured: true, name: 'Fixture', language: 'en', persona: 'friendly', persona_custom: '', greeting: '', reply_length: 'balanced', use_emoji: true, spontaneous: false }, languages: [], personas: [], reply_lengths: [], models: [], selected_model: '', keys_set: {} },
  '/api/brain/models': { models: [{ id: 'openai/gpt-fixture', label: 'GPT fixture', context: 200000 }], selected: 'openai/gpt-fixture', default: 'openai/gpt-fixture', thinking: 'high', thinking_levels: ['low', 'high'], available: true },
  '/api/status': { omni: true, openclaw: true, anthropic: true, mode: 'test' },
  '/api/chat/context': { parts: [], chars: 0, dropped: 0, history_limit: 20 },
  '/api/workspace/info': { root: '/fixture/workspace', session_path: 'sessions/default' },
  '/api/workspace/files': { files: [] },
};
// Install before navigation; the network route is a second guard against real writes.
const init = `(() => {
  const fixtures=${JSON.stringify(fixtures)},original=window.fetch.bind(window);
  window.__dropUploads=[];window.__dropWrites=[];window.__holdNext=false;window.__release=null;
  window.EventSource=class{static OPEN=1;readyState=1;constructor(){queueMicrotask(()=>this.onopen?.(new Event('open')));}close(){this.readyState=2;}};
  window.fetch=async(input,options={})=>{
    const url=new URL(input instanceof Request?input.url:String(input),location.href),pathname=url.pathname;
    if(pathname.startsWith('/uploads/'))return new Response(Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5S0AAAAASUVORK5CYII='),char=>char.charCodeAt(0)),{headers:{'Content-Type':'image/png'}});
    if(!pathname.startsWith('/api/'))return original(input,options);
    if(pathname==='/api/chat/upload'){
      const file=options.body.get('file');window.__dropUploads.push({name:file.name,type:file.type,text:await file.text()});
      if(window.__holdNext){window.__holdNext=false;await new Promise(resolve=>{window.__release=resolve;});}
      if(file.name.endsWith('.exe'))return Response.json({detail:'unsupported_type'},{status:400});
      return Response.json({url:'/uploads/drop-'+window.__dropUploads.length+(file.type==='image/png'?'.png':'.md'),name:file.name,type:file.type,size:file.size});
    }
    if(pathname==='/api/chat/attachment-preview')return Response.json({text:'Fixture document preview',truncated:false,type:'text/markdown',size:25});
    const method=String(options.method||(input instanceof Request?input.method:'GET')).toUpperCase();
    if(!['GET','HEAD'].includes(method))window.__dropWrites.push({pathname,method});
    return Response.json(fixtures[pathname]||{});
  };
  window.__drag=(type,target='.chat-conversation',names=['Plan.md'],related=null,kind='files')=>{
    const transfer=new DataTransfer();
    if(kind==='empty')Object.defineProperty(transfer,'types',{value:['Files']});
    else if(kind==='files'||kind==='protected')for(const name of names)transfer.items.add(new File(['fixture bytes'],name,{type:name.endsWith('.png')?'image/png':'text/markdown'}));
    else transfer.setData(kind==='text'?'text/plain':'text/uri-list',kind==='text'?'selected draft text':'https://example.invalid/image.png');
    if(kind==='protected'&&type!=='drop')Object.defineProperty(transfer,'files',{value:[]});
    const node=document.querySelector(target),event=new DragEvent(type,{bubbles:true,cancelable:true,dataTransfer:transfer,relatedTarget:related?document.querySelector(related):null});
    node.dispatchEvent(event);return {prevented:event.defaultPrevented,effect:transfer.dropEffect};
  };
})();`;
let socket, cdpSession, nextId = 0;
const pending = new Map();
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId, timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 5000);
  pending.set(id, { resolve: value => { clearTimeout(timeout); resolve(value); }, reject: error => { clearTimeout(timeout); reject(error); } });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});
const drag = (type, target, files, related, kind) => evaluate(`window.__drag(...${JSON.stringify([type, target || '.chat-conversation', files || ['Plan.md'], related || null, kind || 'files'])})`);
const waitCards = count => browser('wait', '--fn', `document.querySelectorAll(${JSON.stringify(cards)}).length===${count}${count ? " && !document.querySelector('.prompt-bar__send').disabled" : ''}`);
const settle = () => evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
  browser('open', 'about:blank'); browser('network', 'route', '**/api/**', '--body', '{}');
  for (const endpoint of ['/api/auth/config', '/api/setup', '/api/sessions'])
    browser('network', 'route', `**${endpoint}`, '--body', JSON.stringify(fixtures[endpoint]));
  browser('open', `${origin}${path}#/chat`);
  socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data), request = pending.get(message.id); if (!request) return;
    pending.delete(message.id); if (message.error) request.reject(new Error(JSON.stringify(message.error))); else request.resolve(message.result);
  });
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url.startsWith(`${origin}${path}`));
  assert.ok(target, 'attach the actual dashboard target before installing the boot fixture');
  cdpSession = (await cdp('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: init });
  await cdp('Page.reload'); browser('wait', '.prompt-bar textarea');
  for (const [width, language, theme, reduced] of [[1440, 'en', 'light', false], [320, 'uk', 'dark', true]]) {
    browser('set', 'viewport', String(width), '900');
    await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }, { name: 'prefers-reduced-motion', value: reduced ? 'reduce' : 'no-preference' }] });
    evaluate(`localStorage.setItem('claudeBotLang',${JSON.stringify(language)});localStorage.setItem('claudeBotTheme',${JSON.stringify(theme)});
      localStorage.setItem('claudeBotConversationList','open');localStorage.setItem('claudeBotChatPins','[]');
      localStorage.setItem('claudeBotChatAppearance',JSON.stringify({background:'sky',targets:['chat','navigation','sessions','panels','pages'],material:'glass',glassRecipe:2,opacity:0,blur:0,sidebarVisible:true}));true`);
    browser('reload'); browser('wait', '.prompt-bar textarea');
    browser('fill', '.prompt-bar textarea', 'Preserve my unsent draft');
    const location = browser('get', 'url').trim();
    assert.equal(drag('dragenter', undefined, undefined, undefined, 'protected').prevented, true, 'protected file drags are recognized before their bytes are available');
    browser('wait', '[data-chat-file-drop-overlay]'); assert.equal(active(), true);
    const overlay = evaluate(`const node=document.querySelector('[data-chat-file-drop-overlay]'),box=node.getBoundingClientRect(),style=getComputedStyle(node);
      ({text:node.textContent,fit:box.left>=0&&box.right<=innerWidth&&box.top>=0&&box.bottom<=innerHeight,pointer:style.pointerEvents})`);
    assert.ok(overlay.text.trim(), 'the drop invitation is readable'); assert.equal(overlay.fit, true, 'drop invitation fits both layouts');
    assert.equal(overlay.pointer, 'none', 'the overlay does not intercept the drag target');
    assert.equal(evaluate('document.activeElement===document.querySelector(".prompt-bar textarea")'), true, 'drag feedback cannot steal draft focus');
    drag('dragenter', '.prompt-bar textarea'); drag('dragleave', '.chat-conversation', undefined, '.prompt-bar textarea'); settle();
    assert.equal(active(), true, 'moving into nested composer children keeps the invitation visible');
    assert.equal(drag('dragover', '.prompt-bar textarea').prevented, true);
    assert.equal(drag('drop', '.prompt-bar textarea', ['Plan.md', 'Reference.png']).prevented, true, 'the drop event must prevent browser file opening'); waitCards(2);
    assert.deepEqual(names(), ['Plan.md', 'Reference.png']); assert.equal(active(), false);
    assert.deepEqual(evaluate('window.__dropUploads'), [{ name: 'Plan.md', type: 'text/markdown', text: 'fixture bytes' }, { name: 'Reference.png', type: 'image/png', text: 'fixture bytes' }], 'dropped files use the existing multipart upload endpoint');
    assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Preserve my unsent draft');
    assert.equal(browser('get', 'url').trim(), location, 'dropping must keep the app open');
    assert.deepEqual(evaluate('window.__dropWrites'), [], 'dropping files must not send a message');
    browser('wait', '--fn', 'document.querySelector(".attachment-thumbnail img")?.naturalWidth>0');
    browser('wait', '--fn', 'document.querySelector(".attachment-excerpt")?.textContent.includes("Fixture document preview")');
    assert.equal(drag('drop', '.chat-conversation', ['Direct.md']).prevented, true); waitCards(3);
    assert.equal(names().at(-1), 'Direct.md', 'a drop works even when dragenter was missed');
    for (const kind of ['text', 'url']) {
      assert.equal(drag('dragenter', '.prompt-bar textarea', [], null, kind).prevented, false);
      assert.equal(active(), false); assert.equal(drag('drop', '.prompt-bar textarea', [], null, kind).prevented, false);
    }
    assert.equal(evaluate('window.__dropUploads.length'), 3, 'text and internal image links do not upload or fetch URLs');
    drag('dragenter'); drag('drop', '.chat-conversation', [], null, 'empty'); settle();
    assert.equal(active(), false); assert.equal(evaluate('window.__dropUploads.length'), 3, 'empty file lists do not create attachments');
    drag('drop', '.chat-conversation', ['Unsupported.exe']);
    browser('wait', '--fn', `document.querySelector('.toast-stack')?.textContent.includes(${JSON.stringify(label('upload.unsupported_type', language))})`);
    waitCards(3); assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Preserve my unsent draft');
    for (const cancellation of ['leave', 'end', 'escape', 'visibility']) {
      drag('dragenter'); browser('wait', '[data-chat-file-drop-overlay]');
      if (cancellation === 'leave') drag('dragleave');
      else if (cancellation === 'end') drag('dragend');
      else if (cancellation === 'escape') browser('press', 'Escape');
      else evaluate(`Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));delete document.hidden;true`);
      browser('wait', '--fn', '!document.querySelector(".chat-conversation").hasAttribute("data-file-drop-active")');
    }
    if (width === 1440) {
      const before = evaluate('window.__dropUploads.length');
      assert.equal(drag('drop', '.chat-sessions', ['Outside.md']).prevented, true, 'rejected sidebar drops must also prevent browser file opening'); settle();
      assert.equal(evaluate('window.__dropUploads.length'), before, 'the session sidebar is not a draft drop target');
      browser('click', '.prompt-bar .attachment-card-open'); browser('wait', '.attachment-dialog');
      drag('dragenter', '.attachment-dialog'); assert.equal(drag('drop', '.attachment-dialog', ['Modal.md']).prevented, true); settle();
      assert.equal(active(), false); assert.equal(evaluate('window.__dropUploads.length'), before, 'portaled attachment controls do not become conversation drop targets');
      browser('press', 'Escape'); browser('wait', '--fn', '!document.querySelector(".attachment-dialog")');
      drag('drop', '.chat-conversation', ['Four.md', 'Five.md', 'Six.md', 'Seven.md', 'Eight.md', 'Nine.md']); waitCards(8);
      assert.deepEqual(names(), ['Plan.md', 'Reference.png', 'Direct.md', 'Four.md', 'Five.md', 'Six.md', 'Seven.md', 'Eight.md']);
      assert.equal(evaluate('window.__dropUploads.some(file=>file.name==="Nine.md")'), false, 'the existing eight-file limit also guards drops');
      browser('click', '[data-chat-toolbar] button[aria-label="New conversation"]'); waitCards(0);
      browser('fill', '.prompt-bar textarea', 'Previous draft'); evaluate('window.__holdNext=true');
      drag('drop', '.chat-conversation', ['Held.md']); browser('wait', '--fn', 'typeof window.__release==="function"');
      assert.equal(evaluate('document.querySelector(".prompt-bar__send").disabled'), true, 'send waits for upload completion');
      const uploads = evaluate('window.__dropUploads.length'); drag('drop', '.chat-conversation', ['Concurrent.md']); settle();
      assert.equal(evaluate('window.__dropUploads.length'), uploads, 'a second drop does not start concurrent draft uploads');
      browser('click', '[data-session-id="drop-saved"] .conversation-row__main');
      browser('wait', '--fn', 'document.querySelector(".chat-layout").dataset.chatSession==="drop-saved"');
      browser('fill', '.prompt-bar textarea', 'New chat draft'); evaluate('window.__release()'); settle();
      waitCards(0); assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'New chat draft', 'late completion cannot attach files to a different chat');
    }
    if (process.env.FILE_DROP_SHOTS) { drag('dragenter'); browser('wait', '[data-chat-file-drop-overlay]'); browser('screenshot', `${process.env.FILE_DROP_SHOTS}/drop-${width}-${language}.png`); drag('dragleave'); }
  }
  console.log('PASS: file drops across chat/composer, nested hover and cancellation, no focus theft/navigation/autosend, preserved draft/previews, EN/UK desktop/320px, text/URL exclusions, localized failures, cap/order, concurrent guard and session isolation');
} catch (error) {
  console.error(browser('snapshot', '-i')); console.error(browser('errors')); throw error;
} finally { socket?.close(); browser('close'); }
