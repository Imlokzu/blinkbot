/** Glass and popup choices use isolated browser storage and local API fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `glass-popup-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, data) => browser('network', 'route', url, '--body', JSON.stringify(data));
const settings = () => {
  evaluate("window.location.hash = '#/settings?tab=look'; true");
  browser('wait', '.popup-glass-settings');
};
const chat = () => {
  evaluate("window.location.hash = '#/chat'; true");
  browser('wait', '.chat-thread');
  browser('wait', '--text', 'A fixture answer.');
};
const selected = () => evaluate('document.documentElement.dataset.popupGlassTargets');
const openModels = () => { browser('click', '.brain-choice-trigger'); browser('wait', '.model-effort-menu'); };
const closeModels = () => { browser('press', 'Escape'); browser('wait', '--fn', '!document.querySelector(".model-effort-menu")'); };
const modelLens = () => evaluate('document.querySelector(".model-effort-menu")?.closest("[data-radix-popper-content-wrapper]").classList.contains("popup-lens")');
const setTargets = targets => evaluate(`localStorage.setItem('claudeBotPopupGlassTargets', ${JSON.stringify(JSON.stringify(targets))});
  window.dispatchEvent(new StorageEvent('storage', { key:'claudeBotPopupGlassTargets' })); true`);
const clearPlate = () => {
  const plate = evaluate(`(() => { const node=document.querySelector('.liquid-glass-plate'); const css=getComputedStyle(node);
    const canvas=document.createElement('canvas'); canvas.width=canvas.height=1; const paint=canvas.getContext('2d');
    paint.fillStyle=css.backgroundColor; paint.fillRect(0,0,1,1);
    return { alpha:paint.getImageData(0,0,1,1).data[3], filter:css.backdropFilter, opacity:document.documentElement.style.getPropertyValue('--chat-opacity'), blur:document.documentElement.style.getPropertyValue('--chat-blur') }; })()`);
  assert.equal(plate.alpha, 0, 'the composer centre must be clear rather than a frosted surface wash');
  assert.equal(plate.opacity, '0%');
  assert.equal(plate.blur, '0px');
  assert.notEqual(plate.filter, 'none', 'the rim must retain its refraction or supported blur fallback');
};

try {
  browser('open', 'about:blank');
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled:true });
  route('**/api/setup', { configured:true, profile:{ configured:true, name:'Test', language:'en', persona:'friendly', persona_custom:'', greeting:'', reply_length:'balanced', use_emoji:true, spontaneous:false }, languages:[], personas:[], reply_lengths:[], models:[], selected_model:'', keys_set:{ omni:false, openclaw:false } });
  route('**/api/sessions', { sessions:[] });
  route('**/api/sessions/glass-fixture', { id:'glass-fixture', messages:[{role:'user',content:'Check the clear lens.'},{role:'assistant',content:'A fixture answer.'}] });
  route('**/api/brain/models', { models:[{ id:'openai/gpt-fixture', label:'GPT fixture', context:200000 }], selected:'openai/gpt-fixture', default:'openai/gpt-fixture', thinking:'high', thinking_levels:['low','high'], available:true });
  route('**/api/chat/context**', { parts:[], chars:0, dropped:0, history_limit:20 });
  browser('open', `${origin}${path}#/chat`);
  evaluate(`localStorage.setItem('claudeBotLang','en'); localStorage.setItem('claudeBotTheme','dark');
    localStorage.setItem('claudeBotPopup','glass'); localStorage.setItem('claudeBotPopupGlassTargets','[]');
    localStorage.setItem('claudeBotChatAppearance',JSON.stringify({background:'sky',targets:['chat'],material:'glass',opacity:35,blur:12})); true`);
  browser('reload');
  browser('wait', '.chat-thread[data-empty]');
  clearPlate();
  assert.equal(selected(), '', 'an explicit empty selection overrides legacy all-glass');
  openModels();
  assert.equal(modelLens(), false);
  closeModels();

  // Moving the composer after a real UI send must keep the same clear glass plate.
  evaluate(`window.__clearComposer=document.querySelector('.prompt-bar textarea');
    const original=window.fetch; window.fetch=async(url,options)=>{
      if(String(url).startsWith('/api/chat') && options?.method==='POST'){
        const event=(name,data)=>'event: '+name+'\\ndata: '+JSON.stringify(data)+'\\n\\n';
        return new Response(event('delta',{chunk:'A fixture answer.'})+event('done',{reply:'A fixture answer.',session_id:'glass-fixture',emotion:'idle',mode:'test',model:'openai/gpt-fixture',tool_results:[]}),{headers:{'Content-Type':'text/event-stream'}});
      } return original(url,options);
    }; true`);
  browser('fill', '.prompt-bar textarea', 'Check the clear lens.');
  browser('press', 'Enter');
  browser('wait', '--text', 'A fixture answer.');
  browser('wait', '--fn', 'getComputedStyle(document.querySelector("[data-chat-composer-position]")).transform === "none"');
  clearPlate();
  assert.equal(evaluate('window.__clearComposer===document.querySelector(".prompt-bar textarea")'), true);
  browser('screenshot', '/tmp/clear-composer-after-send.png');

  settings();
  browser('click', '.popup-glass-choices label:nth-child(1) [role="switch"]');
  browser('wait', '--fn', 'document.documentElement.dataset.popupGlassTargets === "models"');
  assert.deepEqual(evaluate('JSON.parse(localStorage.getItem("claudeBotPopupGlassTargets"))'), ['models']);
  chat();
  openModels();
  browser('wait', '--fn', 'document.querySelector(".model-effort-menu").closest("[data-radix-popper-content-wrapper]").classList.contains("popup-lens")');
  assert.equal(modelLens(), true);
  closeModels();
  browser('click', '.prompt-bar__tool[aria-controls="chat-attachment-menu"]');
  browser('wait', '[data-attachment-menu]');
  assert.equal(evaluate('document.querySelector("[data-attachment-menu]").classList.contains("popup-lens")'), false, 'excluded attachments stay solid');
  browser('press', 'Escape');
  browser('click', '.chat-composer button[aria-label="Conversation context"]');
  browser('wait', '[data-popup-kind="context"]');
  assert.equal(evaluate('document.querySelector("[data-popup-kind=context]").closest("[data-radix-popper-content-wrapper]").classList.contains("popup-lens")'), false);
  browser('press', 'Escape');

  // The fallback still arms selected CSS surfaces when SVG filters are unavailable.
  evaluate('window.__glassSupported=window.Hyalite.supported; window.Hyalite.supported=()=>false; true');
  setTargets([]);
  setTargets(['models']);
  openModels();
  browser('wait', '--fn', 'document.querySelector(".model-effort-menu").closest("[data-radix-popper-content-wrapper]").classList.contains("popup-lens")');
  assert.match(evaluate('getComputedStyle(document.querySelector(".model-effort-menu").closest("[data-radix-popper-content-wrapper]")).backdropFilter'), /blur/);
  closeModels();
  evaluate('window.Hyalite.supported=window.__glassSupported; true');
  setTargets([]);
  setTargets(['attachments']);
  openModels();
  assert.equal(modelLens(), false, 'models cannot be reclassified as generic menus');
  closeModels();
  browser('click', '.prompt-bar__tool[aria-controls="chat-attachment-menu"]');
  browser('wait', '[data-attachment-menu].popup-lens');
  browser('press', 'Escape');

  settings();
  assert.equal(selected(), 'attachments', 'theme consumers must not reset selective popup glass');
  evaluate(`window.__popupSetItem=Storage.prototype.setItem; Storage.prototype.setItem=function(key,value){
    if(key==='claudeBotPopupGlassTargets')throw new DOMException('Full','QuotaExceededError');
    return window.__popupSetItem.call(this,key,value); }; true`);
  browser('click', '.popup-glass-choices label:nth-child(1) [role="switch"]');
  browser('wait', '--text', 'Could not save popup appearance');
  assert.equal(selected(), 'attachments');
  assert.equal(evaluate('document.querySelector(".popup-glass-choices label:nth-child(1) [role=\\"switch\\"]").ariaChecked'), 'false');
  evaluate('Storage.prototype.setItem=window.__popupSetItem; true');
  browser('reload');
  browser('wait', '.popup-glass-settings');
  assert.equal(selected(), 'attachments', 'selection survives reload and legacy prepaint');
  browser('find', 'role', 'button', 'click', '--name', 'Clear selection', '--exact');
  assert.equal(selected(), '');
  assert.equal(evaluate('document.documentElement.hasAttribute("data-popup")'), false);
  console.log('PASS: clear composer before/after send, stock migration, selective popup glass, CSS fallback, reload and quota rollback');
} catch (error) {
  console.error(browser('snapshot', '-i'));
  browser('screenshot', '/tmp/glass-popup-failure.png');
  throw error;
} finally { browser('close'); }
