/** Phone layout/navigation fixtures never write to the bot or its model configuration. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
const session = 'phone-navigation-' + process.pid;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', '(() => eval(' + JSON.stringify(code) + '))()')).data.result;
const route = (url, data) => browser('network', 'route', url, '--body', JSON.stringify(data));
const sections = ['overview', 'chat', 'memory', 'files', 'browser', 'vision', 'services', 'inference', 'agents', 'sessions', 'automation', 'channels', 'logs', 'settings'];
const menu = () => {
  browser('wait', '--fn', '!document.querySelector(".u-veil")');
  browser('click', '[data-phone-navigation]');
  browser('wait', '.phone-navigation-dialog');
  evaluate('Promise.all(document.querySelector(".phone-navigation-dialog").getAnimations({subtree:true}).map(animation=>animation.finished.catch(()=>{})))');
};
const choose = id => {
  browser('click', '[data-phone-section="' + id + '"]');
  browser('wait', '--fn', 'document.querySelector("main").dataset.section === ' + JSON.stringify(id) + ' && !document.querySelector(".phone-navigation-dialog")');
  // Suspense can retain a hidden previous panel while the next route loads.
  browser('wait', '--fn', id === 'chat' ? 'document.querySelector(".chat-phone-toolbar") && !document.querySelector("[data-global-topbar]")' : '!document.querySelector(".chat-layout")');
};
const assertPhoneChat = () => {
  assert.equal(evaluate('document.querySelector("[data-global-topbar]") === null'), true, 'chat owns the only phone header');
  assert.equal(evaluate('document.querySelectorAll(".chat-phone-toolbar").length'), 1);
  assert.equal(evaluate('document.querySelector(".mobile-nav, .dock-navigation") === null'), true, 'phones have no footer or dock');
  assert.equal(evaluate('document.querySelectorAll("[data-phone-navigation]").length'), 1);
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  assert.equal(evaluate('getComputedStyle(document.querySelector(".prompt-bar textarea")).fontSize'), '16px');
  assert.equal(evaluate('getComputedStyle(document.querySelector(".chat-conversation")).paddingBottom'), '0px');
};
try {
  browser('open', 'about:blank');
  browser('set', 'viewport', '390', '844');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [{ id: 'chosen', title: 'Chosen conversation', count: 2 }] });
  route('**/api/sessions/chosen', { id: 'chosen', messages: [{ role: 'user', content: 'A saved question.' }, { role: 'assistant', content: 'A saved answer.' }] });
  route('**/api/brain/models', { models: [{ id: 'openai/gpt-fixture', label: 'GPT fixture', context: 200000 }], selected: 'openai/gpt-fixture', default: 'openai/gpt-fixture', thinking: 'high', thinking_levels: ['low', 'high'], available: true });
  route('**/api/setup', { configured: true, profile: { configured: true, name: 'Test', language: 'en', persona: 'friendly', persona_custom: '', greeting: '', reply_length: 'balanced', use_emoji: true, spontaneous: false }, languages: [], personas: [], reply_lengths: [], models: [], selected_model: '', keys_set: { omni: false, openclaw: false } });
  route('**/api/workspace/info**', { root: '/fixture/workspace', session_path: 'sessions/default' });
  route('**/api/workspace/files**', { files: [] });
  route('**/api/projects', { projects: [] });
  route('**/api/chat/context**', { parts: [], chars: 0, dropped: 0, history_limit: 20 });
  browser('open', origin + path + '#/chat');
  evaluate("localStorage.setItem('claudeBotLang','en'); localStorage.setItem('claudeBotTheme','dark'); localStorage.setItem('claudeBotDockSide','left'); true");
  browser('reload');
  browser('wait', '.chat-phone-toolbar');
  evaluate("window.__phoneWrites=0; const original=window.fetch; window.fetch=async(url,options)=>{ if(options?.method && !['GET','HEAD'].includes(options.method)){window.__phoneWrites++; return new Response('{}',{status:405});} return original(url,options); }; true");
  assertPhoneChat();
  browser('wait', '--fn', 'document.documentElement.dataset.dock === "bottom"');
  browser('fill', '.prompt-bar textarea', 'Keep this draft.');
  evaluate('window.__phoneDraft=document.querySelector(".prompt-bar textarea"); true');

  menu();
  assert.deepEqual(evaluate('[...document.querySelectorAll("[data-phone-section]")].map(button=>button.dataset.phoneSection)'), sections);
  assert.equal(evaluate('document.querySelectorAll("[role=dialog]").length'), 1);
  browser('press', 'Tab');
  assert.equal(evaluate('document.querySelector(".phone-navigation-dialog").contains(document.activeElement)'), true);
  browser('press', 'Escape');
  browser('wait', '--fn', '!document.querySelector(".phone-navigation-dialog")');
  browser('wait', '--fn', 'document.activeElement.matches("[data-phone-navigation]")');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea")===window.__phoneDraft'), true);
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Keep this draft.');

  // An edge gesture transfers ownership instead of stacking navigation and conversations.
  menu();
  evaluate("window.dispatchEvent(new Event('vbot:open-drawer')); true");
  browser('wait', '--fn', '!document.querySelector(".phone-navigation-dialog") && document.querySelectorAll("[role=dialog]").length===1');
  browser('wait', '[data-session-id="chosen"]');
  browser('wait', '--fn', 'document.querySelector("[role=dialog]").contains(document.activeElement)');
  browser('press', 'Escape');
  browser('wait', '--fn', '!document.querySelector("[role=dialog]")');
  browser('wait', '--fn', '!document.querySelector(".u-veil")');
  assert.equal(evaluate('document.body.style.overflow'), '');
  browser('click', '.chat-phone-toolbar button[aria-label="Conversations"]');
  browser('wait', '[data-session-id="chosen"]');
  evaluate('Promise.all(document.querySelector("[role=dialog]:has([data-session-id])").getAnimations({subtree:true}).map(animation=>animation.finished.catch(()=>{})))');
  browser('click', '[data-session-id="chosen"]');
  browser('wait', '--text', 'A saved question.');
  assert.equal(evaluate('document.querySelector(".chat-layout").dataset.chatSession'), 'chosen');

  menu();
  choose('settings');
  browser('wait', '[data-global-topbar]');
  assert.equal(evaluate('document.querySelectorAll("[data-phone-navigation]").length'), 1);
  assert.equal(evaluate('document.querySelector(".mobile-nav")===null'), true);
  menu();
  choose('chat');
  browser('wait', '.chat-phone-toolbar');
  browser('wait', '--text', 'A saved question.');
  assertPhoneChat();

  // Workbench and pinned Panels remain separate, reachable actions in the plus sheet.
  assert.equal(evaluate('document.querySelector(".chat-phone-toolbar [data-workbench-trigger]")===null'), true);
  browser('click', '.prompt-bar__tool[aria-controls="chat-attachment-menu"]');
  browser('wait', '[data-attachment-menu]');
  browser('find', 'role', 'button', 'click', '--name', 'Show the workbench', '--exact');
  browser('wait', '.workbench');
  assert.equal(evaluate('document.querySelector(".workbench").closest("[role=dialog]")!==null'), true);
  browser('press', 'Escape');
  browser('wait', '--fn', '!document.querySelector(".workbench")');
  browser('wait', '--fn', 'document.activeElement.matches(".prompt-bar__tool[aria-controls]")');
  browser('wait', '--fn', '!document.querySelector(".u-veil")');
  browser('click', '.prompt-bar__tool[aria-controls="chat-attachment-menu"]');
  browser('wait', '[data-attachment-menu]');
  browser('find', 'role', 'button', 'click', '--name', 'Panels', '--exact');
  browser('wait', '[role=dialog] .chat-pins');
  assert.equal(evaluate('document.querySelector(".workbench")===null'), true);
  browser('press', 'Escape');
  browser('wait', '--fn', '!document.querySelector("[role=dialog]")');
  browser('wait', '--fn', '!document.querySelector(".u-veil")');

  for (const [width, height] of [[320, 568], [430, 932], [667, 375]]) {
    browser('set', 'viewport', String(width), String(height));
    evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    assertPhoneChat();
    assert.ok(evaluate('document.querySelector(".chat-phone-toolbar").getBoundingClientRect().height') <= 60);
    assert.ok(evaluate('document.querySelector("[data-phone-navigation]").getBoundingClientRect().height') >= 44);
  }
  browser('set', 'viewport', '390', '844');
  menu();
  browser('set', 'viewport', '1440', '960');
  browser('wait', '[data-chat-toolbar]');
  browser('wait', '--fn', '!document.querySelector(".phone-navigation-dialog") && document.documentElement.dataset.dock === "left"');
  assert.equal(evaluate('document.querySelector("[data-global-topbar]")!==null'), true);
  assert.equal(evaluate('document.querySelector(".dock-navigation")!==null'), true);
  assert.equal(evaluate('document.body.style.overflow'), '');
  browser('set', 'viewport', '390', '844');
  browser('wait', '.chat-phone-toolbar');
  browser('wait', '--fn', 'document.documentElement.dataset.dock === "bottom"');
  assertPhoneChat();
  assert.equal(evaluate('document.querySelector("main").getBoundingClientRect().left'), 0);
  browser('wait', '--fn', 'getComputedStyle(document.querySelector("[data-chat-composer-position]")).transform === "none"');
  assert.equal(evaluate('[...document.querySelector(".chat-phone-toolbar").children].every(node=>{const box=node.getBoundingClientRect();return box.left>=-1&&box.right<=innerWidth+1;})'), true);
  assert.equal(evaluate('document.querySelector(".prompt-bar").getBoundingClientRect().right<=innerWidth'), true);
  assert.equal(evaluate('window.__phoneWrites'), 0);
  browser('screenshot', '/tmp/phone-chat-final.png');
  console.log('PASS: one phone chat toolbar, no footer/global band, shared menu, independent drawers/focus, Workbench/Panels, responsive docking and touch controls');
} catch (error) {
  console.error(browser('snapshot', '-i'));
  browser('screenshot', '/tmp/phone-navigation-failure.png');
  throw error;
} finally { browser('close'); }
