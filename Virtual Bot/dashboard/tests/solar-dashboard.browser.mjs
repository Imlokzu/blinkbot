/** Solar migration coverage across routes; every API request stays in fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { t as settingsText } from '../src/locales/settings.ts';

const session = `solar-dashboard-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5183';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const screenshots = process.env.SOLAR_DASHBOARD_SHOTS;
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const sections = ['overview', 'chat', 'memory', 'files', 'browser', 'vision', 'services', 'inference', 'agents', 'sessions', 'automation', 'channels', 'logs', 'settings'];
const tabs = ['connectors', 'profile', 'style', 'brain', 'voice', 'look', 'devices', 'integrations', 'tools', 'mcp', 'skills', 'discover'];
const pageInfo = { total: 0, has_more: false, next_offset: null, offset: 0 };
const fixtures = {
  '/api/auth/config': { disabled: true },
  '/api/status': { omni: true, openclaw: true, anthropic: true, mode: 'fixture', vision: false },
  '/api/services': { vision: { online: false, managed: false }, display: { online: false, managed: false } },
  '/api/sessions': { sessions: [] }, '/api/projects': { projects: [] },
  '/api/memory/list': { files: [{ path: 'fixture.md', title: 'Solar toolbar fixture' }] },
  '/api/memory/file': { path: 'fixture.md', content: '# Solar fixture\n\nA small editable note.\n' },
  '/api/workspace/info': { root: '/fixture/workspace', session_path: 'sessions/default' },
  '/api/workspace/list': { path: '', entries: [] },
  '/api/console': { logs: [{ t: 1791120000, level: 'INFO', name: 'fixture', msg: 'Fixture is ready' }] },
  '/api/models': { models: [], selected: 'openai/gpt-fixture', active: '', default: 'openai/gpt-fixture' },
  '/api/brain/models': { models: [{ id: 'openai/gpt-fixture', label: 'GPT fixture', context: 200000 }, { id: 'regolo/claude-fixture', label: 'Claude fixture' }], selected: 'openai/gpt-fixture', default: 'openai/gpt-fixture', thinking: 'high', thinking_levels: ['off', 'low', 'high'], available: true },
  '/api/brain/intelligence': { models: [] },
  '/api/setup': { configured: true, profile: { configured: true, name: 'Fixture', language: 'en', persona: 'friendly', persona_custom: '', greeting: '', reply_length: 'balanced', use_emoji: true, spontaneous: false }, languages: [{ id: 'en', label: 'English' }, { id: 'uk', label: 'Ukrainian' }], personas: [{ id: 'friendly', label: 'Friendly', hint: 'A calm fixture' }], reply_lengths: [{ id: 'balanced', label: 'Balanced' }], models: [], selected_model: '', keys_set: { omni: false, openclaw: false } },
  '/api/openclaw/settings': { available: true, fields: [] },
  '/api/openclaw/control/agents': { agents: [{ id: 'main', name: 'Fixture agent', default: true, model: 'openai/gpt-fixture', fallbacks: [], runtime: 'fixture', thinking: 'high', workspace_configured: true }] },
  '/api/openclaw/control/sessions': { ...pageInfo, sessions: [] },
  '/api/openclaw/control/jobs': { ...pageInfo, jobs: [], scheduler_enabled: true },
  '/api/openclaw/control/channels': { channels: [], updated_at: 1791120000000 },
  '/api/openclaw/analytics': { available: false },
  '/api/tts/status': { enabled: false, provider: 'fixture', voices: [], selected: null, speeds: [1] },
  '/api/asr/status': { enabled: false },
  '/api/tools/catalog': { profile: 'fixture', readable: true, groups: [{ server: 'fixture', tools: [{ name: 'Fixture tool', qualified: 'fixture.tool', description: 'A local fixture', enabled: true, sensitive: false }] }] },
  '/api/extensions': { available: true, mcp: [], skills: [], errors: {} },
  '/api/extensions/browse': { source: 'smithery', kind: 'mcp', query: '', items: [] },
  '/api/integrations/details': { integrations: [{ id: 'telegram', label: 'Telegram', configured: false, connected: false }] },
  '/api/connectors': { connectors: [] },
  '/api/connectors/notebooklm/status': { installed: false, connected: false, profile: '', login_running: false, agent_access: false },
  '/api/mobile/devices': { devices: [] },
  '/api/chat/context': { parts: [], chars: 0, dropped: 0, history_limit: 20 },
  '/api/screen-store/installed': { apps: [], skins: [] },
};
const init = `(() => {
  const fixtures=${JSON.stringify(fixtures)}; const original=window.fetch.bind(window);
  window.__solarWrites=[]; window.__solarRequests=[]; window.__solarErrors=[];
  window.addEventListener('error', event => window.__solarErrors.push(event.message));
  window.addEventListener('unhandledrejection', event => window.__solarErrors.push(String(event.reason)));
  window.EventSource=class { static OPEN=1; readyState=1; constructor(){queueMicrotask(()=>this.onopen?.(new Event('open')));} close(){this.readyState=2;} addEventListener(){} removeEventListener(){} };
  window.fetch=async(input,options={})=>{
    const pathname=new URL(input instanceof Request?input.url:String(input),location.href).pathname;
    if(!pathname.startsWith('/api/')) return original(input,options);
    const method=String(options.method||(input instanceof Request?input.method:'GET')).toUpperCase();
    window.__solarRequests.push({pathname,method});
    if(!['GET','HEAD'].includes(method)) {
      window.__solarWrites.push({pathname,method});
      return Response.json({ok:true});
    }
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
const navigate = section => {
  evaluate(`location.hash='#/${section}'; true`);
  browser('wait', `main[data-section="${section}"]`);
  browser('wait', '--fn', `document.querySelector('main[data-section="${section}"]')?.innerText.length>3 && !document.querySelector('main[data-section="${section}"]')?.innerText.includes('Відкриваю розділ')`);
};
const inspect = context => {
  const result=evaluate(`({
    unmigrated:[...document.querySelectorAll('svg.lucide')].filter(svg=>!svg.closest('.excalidraw')&&!svg.hasAttribute('data-solar-icon')).map(svg=>svg.outerHTML.slice(0,220)),
    invisible:[...document.querySelectorAll('svg[data-solar-icon]')].filter(svg=>{const rect=svg.getBoundingClientRect();return rect.width>0&&rect.height>0;}).filter(svg=>{const rect=svg.getBoundingClientRect();const style=getComputedStyle(svg);return rect.width>96||rect.height>96||style.color==='rgba(0, 0, 0, 0)';}).map(svg=>svg.outerHTML.slice(0,220)),
    count:document.querySelectorAll('svg[data-solar-icon]').length,
    page:document.documentElement.scrollWidth,viewport:innerWidth,
    crashed:document.querySelector('main')?.innerText.includes('Щось зламалось у розділі'),
    errors:window.__solarErrors,
  })`);
  assert.deepEqual(result.unmigrated,[],`${context}: every owned imported icon uses Solar artwork`);
  assert.deepEqual(result.invisible,[],`${context}: Solar icons retain usable size and color`);
  assert.ok(result.count>0,`${context}: Solar icons are mounted`);
  assert.equal(result.crashed,false,`${context}: the route renders without a panel crash`);
  assert.deepEqual(result.errors,[],`${context}: no uncaught browser errors`);
  assert.ok(result.page<=result.viewport+1,`${context}: page overflow ${result.page}/${result.viewport}`);
  return result.count;
};
const settingsLabel=(language,tab)=>{
  const previous=globalThis.document;
  globalThis.document={documentElement:{lang:language}};
  try{return settingsText(`settings.section.${tab}`);}finally{if(previous===undefined)delete globalThis.document;else globalThis.document=previous;}
};
const screenshot = name => { if(screenshots) browser('screenshot',`${screenshots}/${name}.png`); };

try {
  if(process.platform==='darwin')execFileSync('osascript',['-e','set volume output muted true']);
  if(screenshots)mkdirSync(screenshots,{recursive:true});
  browser('open','about:blank');
  // The route also contains non-fetch consumers; deny all unmocked API traffic.
  browser('network','route','**/api/**','--body','{}');
  socket=new WebSocket(browser('get','cdp-url').trim());
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  socket.addEventListener('message',event=>{
    const message=JSON.parse(event.data),request=pending.get(message.id);if(!request)return;
    pending.delete(message.id);if(message.error)request.reject(new Error(JSON.stringify(message.error)));else request.resolve(message.result);
  });
  const target=(await cdp('Target.getTargets')).targetInfos.find(info=>info.type==='page'&&info.url==='about:blank');
  assert.ok(target,'the isolated browser target must exist');
  cdpSession=(await cdp('Target.attachToTarget',{targetId:target.targetId,flatten:true})).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument',{source:init});
  browser('open',`${origin}${path}#/chat`);
  browser('wait','.prompt-bar__send');
  let observations=0;
  for(const language of ['en','uk']) {
    evaluate(`localStorage.setItem('claudeBotLang',${JSON.stringify(language)});localStorage.setItem('claudeBotTheme','light');localStorage.setItem('claudeBotDockSide','bottom');localStorage.removeItem('claude-bot:brain-models:v6');localStorage.removeItem('claudeBotRecentModels');true`);
    browser('reload');browser('wait','.prompt-bar__send');
    assert.equal(evaluate('document.documentElement.lang'),language);
    for(const width of [1440,390]) {
      browser('set','viewport',String(width),width===1440?'960':'844');
      for(const section of sections) {
        navigate(section); observations+=inspect(`${language}/${width}/${section}`);
        if(['overview','chat','settings'].includes(section))screenshot(`${language}-${width}-${section}`);
      }
      for(const tab of tabs) {
        const label=settingsLabel(language,tab);
        browser('find','role','button','click','--name',label,'--exact');
        browser('wait','--fn',`document.querySelector('.settings-content-surface h1')?.textContent===${JSON.stringify(label)}`);
        observations+=inspect(`${language}/${width}/settings/${tab}`);
      }
      navigate('memory');
      browser('find','role','button','click','--name','Solar toolbar fixture fixture.md');
      browser('wait','.note-toolbar');
      observations+=inspect(`${language}/${width}/note-toolbar`);
      const editorIcons=evaluate('[...document.querySelectorAll(".note-toolbar svg")].map(svg=>svg.dataset.solarIcon)');
      for(const name of ['bold','italic','strikethrough','table2','link','image','undo2','redo2'])assert.ok(editorIcons.includes(name),`${name}: editor tool retains a Solar glyph`);
      browser('focus','.note-toolbar button[aria-label]:not(:disabled)');
      assert.equal(evaluate('document.activeElement.closest(".note-toolbar")!==null'),true,'editor buttons remain keyboard focusable');
      screenshot(`${language}-${width}-editor`);
      navigate('chat');browser('wait','[data-brain-choice-trigger]');
      const trigger=evaluate(`const button=[...document.querySelectorAll('[data-brain-choice-trigger]')].find(button=>button.getBoundingClientRect().width>0);button.dataset.solarTestTrigger='';button.getBoundingClientRect().toJSON()`);
      if(width===390)assert.ok(trigger.width>=40&&trigger.height>=40,'phone model trigger retains a touch-sized target');
      browser('click','[data-solar-test-trigger]');browser('wait','.model-effort-menu');
      observations+=inspect(`${language}/${width}/model-menu`);
      browser('focus','.model-picker-search-button');
      browser('wait','--fn','document.querySelector(".model-picker-search-button").ariaExpanded==="true"');
      assert.equal(evaluate('document.activeElement.matches(".model-picker-search-button")'),true,'Solar search keeps the existing keyboard affordance');
      screenshot(`${language}-${width}-model-menu`);
      browser('press','Escape');browser('press','Escape');
      browser('wait','--fn','!document.querySelector(".model-effort-menu")');
      browser('click','.prompt-bar__tool[aria-label]');browser('wait','[role=dialog][data-state=open]');
      observations+=inspect(`${language}/${width}/attachment-menu`);
      screenshot(`${language}-${width}-attachments`);
      browser('press','Escape');browser('wait','--fn','!document.querySelector("[role=dialog][data-state=open]")');
      if(width===1440) {
        const navigation=evaluate('document.querySelectorAll(".dock-item svg[data-solar-icon]").length');
        assert.equal(navigation,14,'desktop navigation retains all 14 Solar section glyphs');
        browser('focus','.dock-item:last-child');browser('press','Enter');
        browser('wait','main[data-section=settings]');
        assert.equal(evaluate('location.hash.startsWith("#/settings")'),true,'keyboard activation still navigates from the Solar dock');
      }
      assert.deepEqual(evaluate('window.__solarWrites'),[],'browsing and opening icon controls never mutates a service');
    }
  }
  if(path.includes('/static/dash/')) {
    const colors=evaluate(`(async()=>{
      const main=await(await fetch('${path}src/main.tsx')).text();
      const version=main.match(/react.js\\?v=([a-z0-9]+)/)[1];
      const [{Search},reactModule,domModule,domBase]=await Promise.all([import('${path}src/vendor/solar-icons/compat.ts'),import('${path}node_modules/.vite/deps/react.js?v='+version),import('${path}node_modules/.vite/deps/react-dom_client.js?v='+version),import('${path}node_modules/.vite/deps/react-dom.js?v='+version)]);
      const React=reactModule.default??reactModule,createRoot=(domModule.default??domModule).createRoot;
      const host=document.createElement('div');document.body.append(host);const root=createRoot(host);
      (domBase.default??domBase).flushSync(()=>root.render(React.createElement(Search,{color:'#123456',size:19,className:'text-ink-3','aria-label':'Fixture search'})));
      const svg=host.querySelector('svg'),shape=svg.querySelector('path');const result={color:getComputedStyle(svg).color,stroke:getComputedStyle(shape).stroke,width:svg.getBoundingClientRect().width,label:svg.getAttribute('aria-label'),solar:svg.dataset.solarIcon};
      root.unmount();host.remove();return result;
    })()`);
    assert.equal(colors.color,'rgb(18, 52, 86)','an explicit caller color beats the dashboard CSS class');
    assert.equal(colors.stroke,'rgb(18, 52, 86)','Solar path strokes inherit the explicit color');
    assert.equal(colors.width,19);assert.equal(colors.label,'Fixture search');assert.equal(colors.solar,'search');
  }
  console.log(`PASS: 14 routes, 12 Settings tabs, chat/model/attachment/editor controls, en/uk at 1440/390, keyboard navigation, touch sizes, no page overflow or uncaught errors, ${observations} Solar icon observations${path.includes('/static/dash/') ? ', explicit color inheritance' : ''}`);
} catch(error) {
  console.error(browser('snapshot','-i'));console.error(browser('errors'));screenshot('failure');throw error;
} finally {socket?.close();browser('close');}
