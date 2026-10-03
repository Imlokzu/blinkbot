/** Conversation glass must keep titles, selection, pinning and narrow drawers usable. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { t as chatT } from '../src/locales/chat.ts';
import { t as appT } from '../src/lib/i18n.ts';

const session = `session-sidebar-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding:'utf8', timeout:35000 });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const row = id => `[data-session-id="${id}"]`;
const main = id => `${row(id)} .conversation-row__main`;
const current = () => evaluate('document.querySelector(".chat-layout").dataset.chatSession');
const label = (translate, key, language) => {
  const previous = globalThis.document;
  globalThis.document = { documentElement:{ lang:language } };
  try { return translate(key); }
  finally { if (previous === undefined) delete globalThis.document; else globalThis.document = previous; }
};
const toolbar = name => `[data-chat-toolbar] button[aria-label=${JSON.stringify(name)}]`;
const narrowTrigger = language => `.chat-narrow-toolbar button[aria-label=${JSON.stringify(label(appT,'chat.sessions',language))}]`;
const noOverflow = context => {
  assert.equal(evaluate(`document.documentElement.scrollWidth <= innerWidth &&
    [...document.querySelectorAll('.conversation-list')].every(node=>node.scrollWidth<=node.clientWidth+1)`), true,
  `${context}: long titles must not cause horizontal overflow`);
};
const glass = selector => evaluate(`const node=document.querySelector(${JSON.stringify(selector)});
  const rgba=css=>{const canvas=document.createElement('canvas');canvas.width=canvas.height=1;
    const context=canvas.getContext('2d');context.fillStyle=css;context.fillRect(0,0,1,1);
    return [...context.getImageData(0,0,1,1).data];};
  [getComputedStyle(node),getComputedStyle(node,'::before')].map(style=>({
    filter:style.backdropFilter, color:rgba(style.backgroundColor), content:style.content
  }))`);
const assertGlass = (selector, enabled, context) => {
  const surface=glass(selector);
  if(enabled) {
    assert.ok(surface.some(layer=>/blur\(|url\(/.test(layer.filter)), `${context}: the panel actually blurs its backdrop`);
    assert.ok(surface.every(layer=>layer.color[3]<255), `${context}: an opaque layer must not cover the glass`);
  } else {
    assert.ok(surface.every(layer=>layer.filter==='none'), `${context}: solid mode disables backdrop filters`);
    assert.ok(surface.some(layer=>layer.color[3]===255), `${context}: solid mode provides an opaque reading surface`);
  }
};
const assertRows = context => {
  assert.equal(evaluate('document.querySelectorAll(".conversation-list [data-session-id]").length'),18);
  assert.ok(evaluate('document.querySelectorAll(".conversation-list h2").length')>=4, `${context}: all four time groups remain available`);
  assert.equal(evaluate(`const canvas=document.createElement('canvas');canvas.width=canvas.height=1;
    const context=canvas.getContext('2d');[...document.querySelectorAll('.conversation-list h2')].every(node=>{
      const style=getComputedStyle(node);context.clearRect(0,0,1,1);context.fillStyle=style.backgroundColor;
      context.fillRect(0,0,1,1);return context.getImageData(0,0,1,1).data[3]<255&&style.backdropFilter==='none';
    })`),true,`${context}: group titles share the glass without opaque plates or nested filters`);
  assert.equal(evaluate(`[...document.querySelectorAll('.conversation-list .swipe-row')].every(node=>
    node.getAttribute('role')==='group'&&node.getAttribute('aria-label')===window.__sidebarSessions.find(item=>item.id===node.closest('[data-session-id]').dataset.sessionId).title
    &&node.getBoundingClientRect().height>=56)`),true,`${context}: readable rows preserve the swipe action labels`);
  assert.equal(evaluate(`[...document.querySelectorAll('.conversation-list [data-session-id]')].every(node=>{
    const item=window.__sidebarSessions.find(item=>item.id===node.dataset.sessionId),button=node.querySelector('.conversation-row__main');
    return button.ariaLabel.endsWith(item.title)&&getComputedStyle(node.querySelector('.conversation-row__title')).textOverflow==='ellipsis'
      &&node.querySelector('.conversation-row__meta').textContent.trim().length>0&&node.querySelector('.conversation-row__count').textContent.trim()===String(item.count);
  })`),true,`${context}: clipped titles keep full accessible labels, dates and message counts`);
  noOverflow(context);
};
const assertCurrent = id => {
  const active=evaluate(`[...document.querySelectorAll('.conversation-list [data-session-id]')]
    .filter(node=>node.querySelector('.conversation-row__main[aria-current="true"]')).map(node=>node.dataset.sessionId)`);
  assert.deepEqual(active,id?[id]:[], 'only the chosen conversation is marked current for assistive technology');
  if(id) assert.equal(evaluate(`document.querySelector(${JSON.stringify(row(id))}).hasAttribute('data-session-active')`),true);
};
const settleDrawer = () => browser('wait','--fn',`document.querySelector('.chat-session-drawer')?.getAnimations({subtree:true}).every(animation=>animation.playState!=='running')`);
const openDrawer = language => {
  browser('wait','--fn','!document.querySelector(".u-veil")');
  browser('click',narrowTrigger(language));
  browser('wait','.chat-session-drawer'); settleDrawer();
};
const closeDrawer = () => {
  browser('press','Escape');
  browser('wait','--fn','!document.querySelector(".chat-session-drawer") && !document.querySelector(".u-veil")');
  assert.equal(evaluate('document.body.style.overflow'),'','Escape restores page scrolling');
};

const day = new Date(); day.setHours(12,0,0,0);
const sessions=Array.from({length:18},(_,index)=>({
  id:`sidebar-${index}`, title:`Conversation ${index+1}: a deliberately long saved title about planning an interesting project together`,
  count:index+2, pinned:index===0,
  updated:(day.getTime()-[0,3,14,45][Math.floor(index/5)]*86400000)/1000,
}));
const fixtures={
  '/api/auth/config':{disabled:true}, '/api/sessions':{sessions}, '/api/projects':{projects:[]},
  '/api/setup':{configured:true,profile:{configured:true,name:'Fixture',language:'en',persona:'friendly',persona_custom:'',greeting:'',reply_length:'balanced',use_emoji:true,spontaneous:false},languages:[],personas:[],reply_lengths:[],models:[],selected_model:'',keys_set:{omni:false,openclaw:false}},
  '/api/brain/models':{models:[{id:'openai/gpt-fixture',label:'GPT fixture',context:200000}],selected:'openai/gpt-fixture',default:'openai/gpt-fixture',thinking:'high',thinking_levels:['low','high'],available:true},
  '/api/status':{omni:true,openclaw:true,anthropic:true,mode:'test'},
  '/api/chat/context':{parts:[],chars:0,dropped:0,history_limit:20},
  '/api/workspace/info':{root:'/fixture/workspace',session_path:'sessions/default'}, '/api/workspace/files':{files:[]},
};
for(const item of sessions) fixtures[`/api/sessions/${item.id}`]={id:item.id,messages:[
  {role:'user',content:`Saved question for ${item.id}.`}, {role:'assistant',content:`Saved answer for ${item.id}.`},
]};
// Install before navigation: even unknown APIs, writes and SSE remain inside the fixture.
const init=`(() => {
  const fixtures=${JSON.stringify(fixtures)}, original=window.fetch.bind(window);
  window.__sidebarSessions=fixtures['/api/sessions'].sessions;window.__sidebarWrites=[];
  window.EventSource=class{static OPEN=1;readyState=1;constructor(){queueMicrotask(()=>this.onopen?.(new Event('open')));}close(){this.readyState=2;}};
  window.fetch=async(input,options={})=>{
    const pathname=new URL(input instanceof Request?input.url:String(input),location.href).pathname;
    if(!pathname.startsWith('/api/'))return original(input,options);
    const method=String(options.method||(input instanceof Request?input.method:'GET')).toUpperCase();
    if(!['GET','HEAD'].includes(method)){
      const body=JSON.parse(options.body||'{}');window.__sidebarWrites.push({pathname,method,body});
      const pin=/^\\/api\\/sessions\\/([^/]+)\\/pin$/.exec(pathname);
      if(pin){const item=window.__sidebarSessions.find(item=>item.id===decodeURIComponent(pin[1]));if(item)item.pinned=body.pinned;return Response.json({ok:true});}
      return Response.json({error:'Unexpected fixture write'},{status:405});
    }
    return Response.json(fixtures[pathname]||{});
  };
})();`;
let socket, cdpSession, nextId=0;
const pending=new Map();
const cdp=(method,params={},sessionId=cdpSession)=>new Promise((resolve,reject)=>{
  const id=++nextId;const timeout=setTimeout(()=>{pending.delete(id);reject(new Error(`CDP timed out: ${method}`));},5000);
  pending.set(id,{resolve:value=>{clearTimeout(timeout);resolve(value);},reject:error=>{clearTimeout(timeout);reject(error);}});
  socket.send(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})}));
});
const media=(theme,reduced=false)=>cdp('Emulation.setEmulatedMedia',{features:[
  {name:'prefers-color-scheme',value:theme},{name:'prefers-reduced-transparency',value:reduced?'reduce':'no-preference'},
]});
const setup=(language,theme,targets=['chat','navigation','sessions','panels','pages'])=>evaluate(`
  localStorage.setItem('claudeBotLang',${JSON.stringify(language)});localStorage.setItem('claudeBotTheme',${JSON.stringify(theme)});
  localStorage.setItem('claudeBotConversationList','open');localStorage.setItem('claudeBotChatPins','["projects","usage"]');
  localStorage.setItem('claudeBotChatAppearance',JSON.stringify({background:'sky',targets:${JSON.stringify(targets)},material:'glass',glassRecipe:2,opacity:0,blur:0,sidebarVisible:true}));true`);
const shot=name=>{if(process.env.SESSION_SIDEBAR_SHOTS)browser('screenshot',`${process.env.SESSION_SIDEBAR_SHOTS}/${name}.png`);};

try {
  if(process.platform==='darwin')execFileSync('osascript',['-e','set volume output muted true']);
  if(process.env.SESSION_SIDEBAR_SHOTS)mkdirSync(process.env.SESSION_SIDEBAR_SHOTS,{recursive:true});
  browser('open','about:blank');
  for(const [pathname,body] of Object.entries(fixtures).sort(([left],[right])=>right.length-left.length))browser('network','route',`**${pathname}**`,'--body',JSON.stringify(body));
  browser('network','route','**/api/**','--body','{}');
  socket=new WebSocket(browser('get','cdp-url').trim());
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  socket.addEventListener('message',event=>{const message=JSON.parse(event.data),request=pending.get(message.id);if(!request)return;
    pending.delete(message.id);if(message.error)request.reject(new Error(JSON.stringify(message.error)));else request.resolve(message.result);});
  const target=(await cdp('Target.getTargets')).targetInfos.find(info=>info.type==='page'&&info.url==='about:blank');
  assert.ok(target,'the isolated blank browser target must exist');
  cdpSession=(await cdp('Target.attachToTarget',{targetId:target.targetId,flatten:true})).sessionId;
  await cdp('Page.enable');await cdp('Page.addScriptToEvaluateOnNewDocument',{source:init});
  browser('set','viewport','1440','960');browser('open',`${origin}${path}#/chat`);

  for(const [language,theme] of [['en','light'],['uk','dark']]) {
    await media(theme);setup(language,theme);browser('reload');browser('wait',main('sidebar-0'));
    browser('wait','.chat-pins');assertGlass('.chat-sessions',true,`${language}/${theme}/desktop`);assertRows(`${language}/${theme}/desktop`);
    assert.equal(evaluate('document.documentElement.lang'),language);assert.equal(evaluate('document.documentElement.dataset.theme'),theme);
    assert.equal(evaluate(`${JSON.stringify(sessions[0].title)}===document.querySelector(${JSON.stringify(row('sidebar-0')+' .swipe-row')}).getAttribute('aria-label')`),true);
    browser('click',main('sidebar-1'));browser('wait','--text','Saved question for sidebar-1.');assertCurrent('sidebar-1');
    const selectedStyles=evaluate(`const selected=document.querySelector(${JSON.stringify(main('sidebar-1'))}),other=document.querySelector(${JSON.stringify(main('sidebar-2'))});
      const style=node=>{const plate=getComputedStyle(node.parentElement,'::before'),title=getComputedStyle(node.querySelector('.conversation-row__title'));return [plate.backgroundColor,plate.opacity,plate.borderColor,title.color,title.fontWeight].join('|');};[style(selected),style(other)]`);
    assert.notEqual(selectedStyles[0],selectedStyles[1],'the current conversation is visibly distinguished');
    browser('focus',main('sidebar-2'));browser('press','Enter');browser('wait','--text','Saved question for sidebar-2.');assertCurrent('sidebar-2');
    assert.equal(evaluate('document.querySelectorAll(".conversation-list .pulse-heart,.conversation-row__pin").length'),0,'conversation rows keep pinning in their menu');
    browser('focus',main('sidebar-1'));browser('press','Shift+F10');
    browser('wait','[data-session-card-popup="sidebar-1"]');
    browser('find','role','button','click','--name',label(chatT,'sessions.pin',language),'--exact');
    browser('wait','--fn','window.__sidebarSessions.find(item=>item.id==="sidebar-1").pinned');
    assert.equal(current(),'sidebar-2','pinning another row must not open its conversation');
    assert.deepEqual(evaluate('window.__sidebarWrites'),[{pathname:'/api/sessions/sidebar-1/pin',method:'POST',body:{pinned:true}}]);
    browser('press','Escape');browser('wait','--fn','!document.querySelector("[data-session-card-popup]")');
    assert.equal(evaluate(`document.activeElement===document.querySelector(${JSON.stringify(main('sidebar-1'))})`),true,'closing the keyboard menu restores its conversation row');
    const pins=evaluate("localStorage.getItem('claudeBotChatPins')");
    browser('click',toolbar(label(chatT,'sessions.hideList',language)));browser('wait','--fn','!document.querySelector(".chat-sessions")');
    assert.equal(evaluate("localStorage.getItem('claudeBotConversationList')"),'closed');
    browser('click',toolbar(label(chatT,'sessions.showList',language)));browser('wait',main('sidebar-0'));
    assert.equal(evaluate("localStorage.getItem('claudeBotConversationList')"),'open');assert.equal(evaluate("localStorage.getItem('claudeBotChatPins')"),pins);
    assert.equal(evaluate('window.__sidebarSessions.find(item=>item.id==="sidebar-1").pinned'),true);assertCurrent('sidebar-2');
    shot(`desktop-selected-${language}-${theme}`);
    browser('click',`.conversation-list button[aria-label=${JSON.stringify(label(chatT,'chat.newSession',language))}]`);
    browser('wait','.chat-thread[data-empty]');assert.equal(current(),'');assertCurrent('');noOverflow(`${language}/${theme}/new`);shot(`desktop-${language}-${theme}`);
    await media(theme,true);browser('wait','--fn','matchMedia("(prefers-reduced-transparency: reduce)").matches');
    assertGlass('.chat-sessions',false,`${language}/${theme}/reduced transparency`);await media(theme);
  }

  browser('set','viewport','1200','900');browser('wait','[data-chat-toolbar]');assertRows('1200/desktop with right panels');
  assertGlass('.chat-sessions',true,'1200/desktop with right panels');
  for(const [width,language,theme] of [[1100,'en','dark'],[390,'uk','light'],[320,'en','dark']]) {
    browser('set','viewport',String(width),width===1100?'900':'844');await media(theme);setup(language,theme);browser('reload');
    browser('wait','.chat-narrow-toolbar');assert.equal(evaluate('Boolean(document.querySelector(".chat-sessions"))'),false);
    openDrawer(language);assertGlass('.chat-session-drawer',true,`${width}/${language}/${theme}`);assertRows(`${width}/${language}/${theme}`);
    assert.equal(evaluate('document.querySelector(".chat-session-drawer").getAttribute("role")'),'dialog');
    assert.equal(evaluate('document.querySelector(".chat-session-drawer").getAttribute("aria-modal")'),'true');
    assert.equal(evaluate(`[...document.querySelectorAll('.chat-session-drawer > header > span,.chat-session-drawer .conversation-list__header > .u-label')]
      .filter(node=>node.getClientRects().length>0).length`),1,'the drawer presents one visible conversations heading');
    shot(`drawer-${width}-${language}-${theme}`);closeDrawer();openDrawer(language);
    browser('click',main('sidebar-1'));browser('wait','--text','Saved question for sidebar-1.');browser('wait','--fn','!document.querySelector(".chat-session-drawer")');
    assert.equal(current(),'sidebar-1');openDrawer(language);assertCurrent('sidebar-1');
    browser('click',`.chat-session-drawer button[aria-label=${JSON.stringify(label(chatT,'chat.newSession',language))}]`);
    browser('wait','.chat-thread[data-empty]');browser('wait','--fn','!document.querySelector(".chat-session-drawer")');assert.equal(current(),'');
    noOverflow(`${width}/closed`);assert.deepEqual(evaluate('window.__sidebarWrites'),[],'narrow navigation never mutates a session');
  }

  browser('set','viewport','1440','960');await media('light');setup('en','light',['chat','navigation','panels','pages']);browser('reload');
  browser('wait',main('sidebar-0'));assertGlass('.chat-sessions',false,'sessions wallpaper target disabled');noOverflow('solid desktop');
  assert.deepEqual(evaluate('window.__sidebarWrites'),[]);
  console.log('PASS: conversation glass/solid/reduced transparency, en/light and uk/dark rows, keyboard selection, local pinning, preserved panels, four date groups, long titles, 1200 desktop and 1100/390/320 drawers');
} catch(error) {
  console.error(evaluate('({writes:window.__sidebarWrites,current:document.querySelector(".chat-layout")?.dataset.chatSession,pins:window.__sidebarSessions?.filter(item=>item.pinned).map(item=>item.id)})'));
  console.error(browser('snapshot','-i'));shot('session-sidebar-failure');throw error;
} finally { socket?.close();browser('close'); }
