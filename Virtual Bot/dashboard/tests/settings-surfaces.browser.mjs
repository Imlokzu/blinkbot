/** Wallpaper settings and model headings stay readable in an isolated browser. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { t } from '../src/locales/settings.ts';

const session = `settings-surfaces-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding:'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const tabs = ['connectors', 'profile', 'style', 'brain', 'voice', 'look', 'tools', 'mcp', 'skills', 'discover'];
const labels = language => {
  const previous = globalThis.document;
  globalThis.document = { documentElement:{ lang:language } };
  try { return Object.fromEntries(tabs.map(tab => [tab, t(`settings.section.${tab}`)])); }
  finally { if (previous === undefined) delete globalThis.document; else globalThis.document = previous; }
};
const rgba = selector => evaluate(`const canvas=document.createElement('canvas'); canvas.width=canvas.height=1;
  const context=canvas.getContext('2d'); context.fillStyle=getComputedStyle(document.querySelector(${JSON.stringify(selector)})).backgroundColor;
  context.fillRect(0,0,1,1); [...context.getImageData(0,0,1,1).data]`);
const filter = selector => evaluate(`getComputedStyle(document.querySelector(${JSON.stringify(selector)})).backdropFilter`);
const settings = tab => {
  const name = labels(evaluate('document.documentElement.lang'))[tab];
  evaluate(`location.hash='#/settings?tab=${tab}'; true`);
  browser('wait', '.settings-navigation');
  browser('find', 'role', 'button', 'click', '--name', name, '--exact');
  browser('wait', '--fn', `document.querySelector('.settings-content-surface h1')?.textContent===${JSON.stringify(name)}`);
};
const assertSheet = (theme, context) => {
  const color = rgba('.settings-content-surface');
  assert.equal(color[3], 255, `${context}: the content sheet is opaque`);
  assert.ok(theme === 'light' ? color.slice(0,3).every(channel => channel >= 245) : color.slice(0,3).every(channel => channel < 110), `${context}: the sheet follows the theme`);
  assert.equal(evaluate(`const surface=document.querySelector('.settings-content-surface');
    [...document.querySelectorAll('.settings-panel h1,.settings-panel h2,.settings-panel p,.settings-panel section')]
      .filter(node=>!node.closest('.settings-navigation')).every(node=>surface.contains(node))`), true, `${context}: all settings copy belongs to one sheet`);
  const bounds = evaluate(`const surface=document.querySelector('.settings-content-surface'); const rect=surface.getBoundingClientRect();
    ({ left:rect.left, right:rect.right, page:document.documentElement.scrollWidth,
      content:surface.scrollWidth, width:surface.clientWidth, viewport:innerWidth })`);
  assert.ok(bounds.left >= -1 && bounds.right <= bounds.viewport + 1 && bounds.page <= bounds.viewport, `${context}: the sheet fits the page`);
  assert.ok(bounds.content <= bounds.width + 1, `${context}: settings content must not overflow its sheet`);
  assert.equal(evaluate('Boolean(document.querySelector(".app-wallpaper"))'), true, `${context}: the wallpaper remains mounted`);
};
const setStorage = (language, theme) => evaluate(`localStorage.setItem('claudeBotLang',${JSON.stringify(language)});
  localStorage.setItem('claudeBotTheme',${JSON.stringify(theme)});
  localStorage.setItem('claudeBotPopupGlassTargets','["models"]');
  localStorage.setItem('claudeBotChatAppearance',JSON.stringify({ background:'sky', targets:['chat','navigation','sessions','panels','pages'], material:'glass', glassRecipe:2, opacity:0, blur:0 }));
  localStorage.setItem('claudeBotRecentModels','["openai/gpt-second"]');
  localStorage.removeItem('claude-bot:brain-models:v6'); true`);
const openModels = () => {
  evaluate("location.hash='#/chat'; true");
  browser('wait', '.brain-choice-trigger');
  browser('click', '.brain-choice-trigger');
  browser('wait', '.model-effort-menu');
  browser('wait', '--fn', 'document.querySelector(".model-effort-menu").getAnimations({subtree:true}).every(animation=>animation.playState!=="running")');
};
const closeModels = () => { browser('press', 'Escape'); browser('wait', '--fn', '!document.querySelector(".model-effort-menu")'); };

const fixtures = {
  '/api/auth/config':{ disabled:true }, '/api/sessions':{ sessions:[] }, '/api/projects':{ projects:[] },
  '/api/setup':{ configured:true, profile:{ configured:true, name:'Fixture', language:'en', persona:'friendly', persona_custom:'', greeting:'', reply_length:'balanced', use_emoji:true, spontaneous:false },
    languages:[{id:'en',label:'English'},{id:'uk',label:'Ukrainian'}],
    personas:['Friendly','Playful','Concise','Calm','Wise','Energetic','Witty','Business'].map(label=>({id:label.toLowerCase(),label,hint:'A calm fixture'})),
    reply_lengths:[{id:'short',label:'Short'},{id:'balanced',label:'Balanced'},{id:'long',label:'Detailed'}],
    models:[], selected_model:'', keys_set:{ omni:false, openclaw:false } },
  '/api/openclaw/settings':{ available:true, fields:[] },
  '/api/brain/models':{ models:[{id:'openai/gpt-fixture',label:'GPT fixture',context:200000},{id:'openai/gpt-second',label:'GPT second'},{id:'regolo/claude-fixture',label:'Claude fixture'}], selected:'openai/gpt-fixture', default:'openai/gpt-fixture', thinking:'high', thinking_levels:['off','low','high'], available:true },
  '/api/models':{ models:[], selected:'openai/gpt-fixture', default:'openai/gpt-fixture' },
  '/api/status':{ omni:true, openclaw:true, anthropic:true, mode:'test' },
  '/api/tts/status':{ enabled:false, provider:'fixture', voices:[], selected:null, speeds:[1] }, '/api/asr/status':{ enabled:false },
  '/api/tools/catalog':{ profile:'fixture', readable:true, groups:[{server:'tools',tools:[{name:'Fixture tool',qualified:'tools.fixture',description:'A local fixture description.',enabled:true,sensitive:false}]}] },
  '/api/extensions':{ available:true, mcp:[], skills:[], errors:{} },
  '/api/extensions/browse':{ source:'smithery',kind:'mcp',query:'',items:[] },
  '/api/integrations/details':{ integrations:[
    {id:'telegram',label:'Telegram',configured:false,connected:false},
    {id:'google',label:'Google',configured:false,connected:false},
  ] },
  '/api/connectors':{ connectors:[] },
  '/api/connectors/notebooklm/status':{ installed:false,connected:false,profile:'',login_running:false,agent_access:false },
  '/api/chat/context':{ parts:[],chars:0,dropped:0,history_limit:20 },
  '/api/workspace/info':{ root:'/fixture/workspace', session_path:'sessions/default' },
};
// Install before React: every API response, write and SSE connection stays local.
const init = `(() => {
  const fixtures=${JSON.stringify(fixtures)}; const original=window.fetch.bind(window);
  window.__surfaceWrites=[];
  window.EventSource=class { static OPEN=1; readyState=1; constructor(){queueMicrotask(()=>this.onopen?.(new Event('open')));} close(){this.readyState=2;} };
  window.fetch=async(input,options={})=>{
    const pathname=new URL(input instanceof Request?input.url:String(input),location.href).pathname;
    if(!pathname.startsWith('/api/')) return original(input,options);
    const method=String(options.method||(input instanceof Request?input.method:'GET')).toUpperCase();
    if(!['GET','HEAD'].includes(method)) {
      const body=JSON.parse(options.body||'{}'); window.__surfaceWrites.push({pathname,method,body});
      if(pathname==='/api/brain/thinking') fixtures['/api/brain/models'].thinking=body.level;
      return Response.json({ok:true,thinking:fixtures['/api/brain/models'].thinking});
    }
    if(pathname==='/api/setup' && localStorage.getItem('settingsSurfaceFixture')==='loading') await new Promise(resolve=>window.__releaseSetup=resolve);
    if(pathname==='/api/extensions' && localStorage.getItem('settingsSurfaceFixture')==='error') return Response.json({detail:'Settings fixture unavailable'},{status:503});
    return Response.json(fixtures[pathname]||{});
  };
})();`;

let socket, cdpSession, nextId=0;
const pending=new Map();
const cdp=(method,params={},sessionId=cdpSession)=>new Promise((resolve,reject)=>{
  const id=++nextId;
  const timeout=setTimeout(()=>{pending.delete(id);reject(new Error(`CDP timed out: ${method}`));},5000);
  pending.set(id,{resolve:result=>{clearTimeout(timeout);resolve(result);},reject:error=>{clearTimeout(timeout);reject(error);}});
  socket.send(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})}));
});
const media=(theme,reduced=false)=>cdp('Emulation.setEmulatedMedia',{features:[
  {name:'prefers-color-scheme',value:theme},{name:'prefers-reduced-transparency',value:reduced?'reduce':'no-preference'},
]});

try {
  if(process.platform==='darwin') execFileSync('osascript',['-e','set volume output muted true']);
  if(process.env.SETTINGS_SURFACE_SHOTS) mkdirSync(process.env.SETTINGS_SURFACE_SHOTS,{recursive:true});
  browser('open','about:blank');
  for(const [pathname,body] of Object.entries(fixtures).sort(([left],[right])=>right.length-left.length)) {
    browser('network','route',`**${pathname}**`,'--body',JSON.stringify(body));
  }
  browser('network','route','**/api/**','--body','{}');
  browser('open',`${origin}${path}#/settings?tab=profile`);
  socket=new WebSocket(browser('get','cdp-url').trim());
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  socket.addEventListener('message',event=>{
    const message=JSON.parse(event.data); const request=pending.get(message.id); if(!request)return;
    pending.delete(message.id); if(message.error)request.reject(new Error(JSON.stringify(message.error)));else request.resolve(message.result);
  });
  const target=(await cdp('Target.getTargets')).targetInfos.find(info=>info.type==='page'&&info.url.startsWith(`${origin}${path}`));
  assert.ok(target,'the isolated dashboard target must exist');
  cdpSession=(await cdp('Target.attachToTarget',{targetId:target.targetId,flatten:true})).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument',{source:init});

  for(const [language,theme] of [['en','light'],['uk','dark']]) {
    await media(theme); setStorage(language,theme); browser('reload');
    browser('wait','.settings-content-surface input');
    assert.equal(evaluate('document.documentElement.lang'),language);
    assert.equal(evaluate('document.documentElement.dataset.theme'),theme);
    assert.ok(rgba('.settings-navigation')[3]<255,'settings navigation has a translucent wash');
    assert.match(filter('.settings-navigation'),/blur\(/,'settings navigation blurs the wallpaper');
    const names=labels(language);
    // Exercise every mounted tab, including optional sections in another worktree.
    const navigationNames=evaluate('[...document.querySelectorAll(".settings-navigation nav button")].map(button=>button.textContent.trim())');
    assert.ok(tabs.every(tab=>navigationNames.includes(names[tab])),'all standard settings sections must remain available');
    for(const width of [1440,768,390,320]) {
      browser('set','viewport',String(width),width>=768?'960':'844');
      for(const name of navigationNames) {
        browser('find','role','button','click','--name',name,'--exact');
        browser('wait','--fn',`document.querySelector('.settings-content-surface h1')?.textContent===${JSON.stringify(name)}`);
        assertSheet(theme,`${language}/${width}/${name}`);
      }
      if(process.env.SETTINGS_SURFACE_SHOTS && [1440,390].includes(width)) {
        settings('profile'); browser('screenshot',`${process.env.SETTINGS_SURFACE_SHOTS}/settings-${language}-${width}.png`);
      }
    }

    browser('set','viewport','1440','960');
    assert.deepEqual(evaluate('window.__surfaceWrites'),[],'browsing settings never writes a preference or backend field');
    openModels();
    browser('wait','--fn','Boolean(document.querySelector(".model-effort-menu").closest(".popup-lens"))');
    assert.equal(rgba('.brain-picker-group')[3],0,'glass model headings share the menu lens without adding a white plate');
    assert.equal(filter('.brain-picker-group'),'none','headings avoid a nested backdrop root inside the glass lens');
    assert.match(evaluate('getComputedStyle(document.querySelector(".model-effort-menu").closest(".popup-lens")).backdropFilter'),/blur\(|url\(/,'the menu lens retains actual shared blur or refraction');
    const defaultRow='.effort-picker-list [data-level=""]';
    assert.equal(evaluate(`document.querySelector(${JSON.stringify(defaultRow)}).textContent.trim()`),language==='en'?'Automatic':'Автоматично');
    assert.doesNotMatch(evaluate('document.querySelector(".model-effort-menu").textContent'),/As in OpenClaw|Як в OpenClaw/i);
    browser('click',defaultRow);
    browser('wait','--fn',`document.querySelector(${JSON.stringify(defaultRow)}).ariaChecked==='true'`);
    assert.deepEqual(evaluate('window.__surfaceWrites.at(-1)'),{pathname:'/api/brain/thinking',method:'POST',body:{level:''}},'Automatic still clears the explicit effort override');
    if(process.env.SETTINGS_SURFACE_SHOTS) browser('screenshot',`${process.env.SETTINGS_SURFACE_SHOTS}/models-${language}.png`);
    closeModels();
    evaluate("localStorage.setItem('claudeBotPopupGlassTargets','[]'); window.dispatchEvent(new StorageEvent('storage',{key:'claudeBotPopupGlassTargets'})); true");
    openModels();
    assert.equal(rgba('.brain-picker-group')[3],255,'unselected model glass keeps opaque headings');
    assert.equal(filter('.brain-picker-group'),'none');
    closeModels();

    // Real browser media emulation verifies opacity rather than merely matching CSS text.
    await media(theme,true); settings('profile');
    const reduced=evaluate('matchMedia("(prefers-reduced-transparency: reduce)").matches');
    if(reduced) {
      browser('wait','--fn','getComputedStyle(document.querySelector(".settings-navigation")).backdropFilter==="none"');
      assert.equal(rgba('.settings-navigation')[3],255,'reduced transparency makes settings navigation opaque');
      assertSheet(theme,`${language}/reduced transparency`);
    } else {
      assert.equal(evaluate(`const hasRule=rules=>[...rules].some(rule=>
        (rule.conditionText?.includes('prefers-reduced-transparency') && [...rule.cssRules].some(child=>child.selectorText?.includes('.settings-navigation')&&child.style?.backdropFilter==='none'))
        || (rule.cssRules&&hasRule(rule.cssRules))); [...document.styleSheets].some(sheet=>{try{return hasRule(sheet.cssRules);}catch{return false;}})`),true,'unsupported media emulation retains a reduced-transparency fallback');
    }
    await media(theme);

    evaluate("localStorage.setItem('settingsSurfaceFixture','loading'); true"); browser('reload');
    browser('wait','.settings-content-surface .animate-pulse');
    assertSheet(theme,`${language}/loading`);
    evaluate("localStorage.removeItem('settingsSurfaceFixture'); window.__releaseSetup(); true");
    browser('wait','.settings-content-surface input');
    settings('mcp');
    browser('wait','--fn','document.querySelector(".settings-content-surface p")?.textContent.length>0');
    assertSheet(theme,`${language}/empty`);
    evaluate("localStorage.setItem('settingsSurfaceFixture','error'); true"); browser('reload');
    browser('wait','--text','Settings fixture unavailable');
    assertSheet(theme,`${language}/error`);
    evaluate("localStorage.removeItem('settingsSurfaceFixture'); true");
    settings('profile'); browser('reload'); browser('wait','.settings-content-surface input');
  }
  console.log('PASS: every settings tab at 1440/768/390/320, en/light and uk/dark sheets, wallpaper, navigation glass, loading/empty/errors, native reduced transparency, model glass/solid headings and Automatic reset semantics');
} catch(error) {
  console.error(browser('errors'));
  if(process.env.SETTINGS_SURFACE_SHOTS) browser('screenshot',`${process.env.SETTINGS_SURFACE_SHOTS}/settings-surfaces-failure.png`);
  throw error;
} finally { socket?.close(); browser('close'); }
