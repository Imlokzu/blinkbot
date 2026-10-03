/** Browser-only routes exercise appearance without posting to the bot or changing models. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const session = `chat-appearance-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const imageFile = join(tmpdir(), `${session}.png`);
const rejectedFile = join(tmpdir(), `${session}.svg`);
const videoFile = join(tmpdir(), `${session}.webm`);
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const conversation = () => evaluate('({ ...document.querySelector(".chat-conversation").dataset })');
const saved = () => evaluate('JSON.parse(localStorage.getItem("claudeBotChatAppearance"))');
const centerError = () => evaluate(`(() => {
  const thread = document.querySelector('.chat-thread').getBoundingClientRect();
  const composer = document.querySelector('[data-chat-composer-position]').getBoundingClientRect();
  return Math.abs(composer.top + composer.height / 2 - thread.top - thread.height / 2);
})()`);
const openAppearance = () => {
  evaluate("window.location.hash = '#/settings?tab=look'; true");
  browser('wait', '.chat-appearance-settings');
};
const closeAppearance = () => {
  evaluate("window.location.hash = '#/chat'; true");
  browser('wait', '.chat-thread');
};
const clickText = (text) => {
  evaluate('document.querySelector("[aria-label=\\"Dismiss question\\"]")?.click(); true');
  browser('find', 'role', 'button', 'click', '--name', text, '--exact');
};
const mockReply = () => evaluate(`(() => {
  const original = window.fetch;
  window.__chatAppearanceSends = [];
  window.fetch = async (url, options) => {
    if (String(url).startsWith('/api/chat')) {
      window.__chatAppearanceSends.push(JSON.parse(options.body));
      const event = (name, data) => 'event: ' + name + '\\ndata: ' + JSON.stringify(data) + '\\n\\n';
      return new Response(event('delta', { chunk: 'A local test reply.' }) + event('done', {
        reply: 'A local test reply.', session_id: 'appearance-fixture', emotion: 'idle',
        mode: 'test', model: 'openai/gpt-6-sol', tool_results: [],
      }), { headers: { 'Content-Type': 'text/event-stream' } });
    }
    return original(url, options);
  };
})()`);

try {
  browser('open', 'about:blank');
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled: true });
  route('**/api/setup', { configured: true, profile: { configured: true, name: 'Test', language: 'en', persona: 'friendly', persona_custom: '', greeting: '', reply_length: 'balanced', use_emoji: true, spontaneous: false }, languages: [], personas: [], reply_lengths: [], models: [], selected_model: '', keys_set: { omni: false, openclaw: false } });
  route('**/api/sessions', { sessions: [{ id: 'history', title: 'Saved conversation', count: 2 }] });
  route('**/api/sessions/history', { id: 'history', messages: [
    { role: 'user', content: 'A saved question.' }, { role: 'assistant', content: 'A saved answer.' },
  ] });
  route('**/api/brain/models', { models: [
    { id: 'openai/gpt-6-sol', label: 'GPT-6 Sol', context: 200000 },
    { id: 'omni/claude/claude-opus-4-8', label: 'Claude Opus', context: 200000 },
    { id: 'regolo/qwen3', label: 'Qwen 3', context: 100000 },
    ...Array.from({ length: 60 }, (_, index) => ({ id: `openai/gpt-fixture-${index}`, label: `GPT fixture ${index}`, context: 100000 })),
  ], selected: 'openai/gpt-6-sol', default: 'openai/gpt-6-sol', thinking: 'high', thinking_levels: ['low', 'high'], available: true });
  browser('open', `${origin}${path}#/chat`);
  evaluate("localStorage.setItem('claudeBotLang','en'); localStorage.setItem('claudeBotTheme','light'); localStorage.removeItem('claudeBotChatAppearance')");
  browser('reload');
  browser('wait', '.chat-thread[data-empty]');
  assert.ok(centerError() <= 2, 'the new-chat composer must be centered');
  assert.equal(conversation().wallpaper, 'sky');
  browser('click', '.brain-choice-trigger');
  browser('wait', '.model-picker-list [role=radio]');
  evaluate('Promise.all(document.querySelector(".model-effort-menu").getAnimations({subtree:true}).map(animation => animation.finished.catch(() => {})))');
  assert.ok(evaluate('document.querySelectorAll(".model-picker-list [role=radio]").length') >= 60);
  assert.equal(evaluate(`(() => { const box = document.querySelector('.model-effort-menu').getBoundingClientRect();
    return box.top >= -1 && box.bottom <= innerHeight + 1; })()`), true,
    'a large model catalog must keep search and controls inside the viewport');
  browser('press', 'Escape');
  browser('wait', '--fn', 'document.querySelector(".model-effort-menu") === null');
  assert.equal(evaluate('document.querySelector("button[aria-label=\\"Customize chat appearance\\"]") === null'), true,
    'appearance is configured in Settings, without another chat toolbar button');
  browser('fill', '.prompt-bar textarea', 'Keep this draft.');
  evaluate('window.__originalComposer = document.querySelector(".prompt-bar textarea"); true');
  evaluate(`localStorage.setItem('claudeBotChatAppearance', JSON.stringify({ background:'dusk', color:'ocean', opacity:35, blur:12 }));
    window.dispatchEvent(new StorageEvent('storage',{key:'claudeBotChatAppearance'})); true`);
  assert.equal(conversation().wallpaper, 'dusk');
  assert.equal(conversation().chatColor, 'ocean');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea") === window.__originalComposer'), true);
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Keep this draft.');
  openAppearance();
  browser('focus', '.chat-appearance-settings input[type="range"][max="100"]');
  browser('press', 'End');
  browser('press', 'Home');
  assert.equal(saved().opacity, 0);
  closeAppearance();

  // Upload is genuinely local: re-encode a raster and retain it through a reload.
  const png = evaluate(`(() => { const canvas = document.createElement('canvas'); canvas.width = 32; canvas.height = 32;
    const context = canvas.getContext('2d'); context.fillStyle = '#79aab5'; context.fillRect(0,0,32,32); return canvas.toDataURL('image/png'); })()`);
  writeFileSync(imageFile, Buffer.from(png.split(',')[1], 'base64'));
  writeFileSync(rejectedFile, '<svg xmlns="http://www.w3.org/2000/svg"/>');
  openAppearance();
  evaluate(`(() => {
    const zone = document.querySelector('[data-wallpaper-drop="image"]');
    const raw = atob(${JSON.stringify(png.split(',')[1])});
    const bytes = Uint8Array.from(raw, character => character.charCodeAt(0));
    const data = new DataTransfer();
    data.items.add(new File([bytes], 'dropped.png', { type: 'image/png' }));
    zone.dispatchEvent(new DragEvent('dragenter', { bubbles: true, cancelable: true, dataTransfer: data }));
    zone.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: data }));
    return true;
  })()`);
  browser('wait', '--fn', 'document.querySelector("[data-wallpaper-drop=\\"image\\"]")?.hasAttribute("data-wallpaper-drop-active")');
  evaluate(`(() => {
    const zone = document.querySelector('[data-wallpaper-drop="image"]');
    const raw = atob(${JSON.stringify(png.split(',')[1])});
    const bytes = Uint8Array.from(raw, character => character.charCodeAt(0));
    const data = new DataTransfer();
    data.items.add(new File([bytes], 'dropped.png', { type: 'image/png' }));
    zone.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: data }));
    return true;
  })()`);
  browser('wait', '--fn', 'document.documentElement.dataset.wallpaper === "custom"');
  assert.match(saved().image, /^data:image\/jpeg;base64,/);
  browser('upload', '.chat-appearance-settings input[type="file"][accept^="image/"]', imageFile);
  browser('wait', '--fn', 'document.documentElement.dataset.wallpaper === "custom"');
  assert.match(saved().image, /^data:image\/jpeg;base64,/);
  const previous = saved();
  browser('upload', '.chat-appearance-settings input[type="file"][accept^="image/"]', rejectedFile);
  browser('wait', '.chat-appearance-settings [role="alert"]');
  assert.deepEqual(saved(), previous, 'unsupported uploads must preserve the saved image');
  closeAppearance();
  browser('reload');
  browser('wait', '.chat-thread[data-empty]');
  assert.equal(conversation().wallpaper, 'custom');
  assert.deepEqual(saved(), previous, 'preferences and the local image survive reload');

  // First submit moves the same textarea, then New conversation restores its centre.
  evaluate('window.__originalComposer = document.querySelector(".prompt-bar textarea"); true');
  mockReply();
  browser('fill', '.prompt-bar textarea', 'A browser-only message.');
  browser('press', 'Enter');
  browser('wait', '.chat-thread:not([data-empty])');
  browser('wait', '--text', 'A local test reply.');
  browser('wait', '--fn', 'Math.abs(document.querySelector("[data-chat-composer-position]").getBoundingClientRect().bottom - document.querySelector(".chat-thread").getBoundingClientRect().bottom) < 2');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea") === window.__originalComposer'), true);
  assert.equal(evaluate('window.__chatAppearanceSends.length'), 1);
  browser('click', '[data-chat-toolbar] button[aria-label="New conversation"]');
  browser('wait', '.chat-thread[data-empty]');
  browser('wait', '--fn', 'getComputedStyle(document.querySelector("[data-chat-composer-position]")).transform === "none"');
  assert.ok(centerError() <= 2);
  browser('click', '[data-session-id="history"]');
  browser('wait', '--text', 'A saved question.');
  assert.equal(evaluate('document.querySelector(".chat-thread").hasAttribute("data-empty")'), false);

  openAppearance();
  const beforeQuota = saved();
  evaluate(`window.__appearanceSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function(key, value) {
      if (key === 'claudeBotChatAppearance') throw new DOMException('Full', 'QuotaExceededError');
      return window.__appearanceSetItem.call(this, key, value);
    }; true`);
  clickText('Forest');
  browser('wait', '--text', 'Could not save chat appearance');
  assert.deepEqual(saved(), beforeQuota, 'quota errors must retain the previous saved appearance');
  evaluate('Storage.prototype.setItem = window.__appearanceSetItem; true');
  clickText('Reset appearance');
  closeAppearance();
  assert.equal(conversation().wallpaper, 'sky');
  assert.equal(saved().image, null);
  openAppearance();
  clickText('Entire app');
  assert.equal(saved().targets.length, 5);
  assert.equal(evaluate('document.documentElement.dataset.backgroundTargets.split(" ").length'), 5);
  clickText('Selected areas');
  assert.deepEqual(saved().targets, ['chat']);
  closeAppearance();
  evaluate("localStorage.setItem('claudeBotChatPins',JSON.stringify(['projects','usage'])); true");
  browser('reload');
  browser('wait', '.chat-pins');
  const pins = evaluate("localStorage.getItem('claudeBotChatPins')");
  browser('click', '[data-right-panel-trigger]');
  evaluate('Promise.all(document.querySelector("[data-right-panel-menu]").getAnimations({subtree:true}).map(animation => animation.finished.catch(() => {})))');
  browser('click', '[data-panel-choice="hidden"]');
  browser('wait', '--fn', 'document.querySelector("[data-right-panel-menu]") === null');
  browser('wait', '--fn', 'document.querySelector(".chat-pins") === null');
  assert.equal(saved().sidebarVisible, false);
  assert.equal(evaluate("localStorage.getItem('claudeBotChatPins')"), pins);
  browser('reload');
  browser('wait', '.chat-thread');
  assert.equal(evaluate('document.querySelector(".chat-pins") === null'), true);
  browser('click', '[data-right-panel-trigger]');
  evaluate('Promise.all(document.querySelector("[data-right-panel-menu]").getAnimations({subtree:true}).map(animation => animation.finished.catch(() => {})))');
  browser('click', '[data-panel-choice="panels"]');
  browser('wait', '--fn', 'document.querySelector("[data-right-panel-menu]") === null');
  browser('wait', '.chat-pins');
  browser('click', '.chat-pins button[aria-label="Hide right panels"]');
  browser('wait', '--fn', 'document.querySelector(".chat-pins") === null');
  assert.equal(evaluate("localStorage.getItem('claudeBotChatPins')"), pins);

  // A real muted video persists as a local blob and loops through one shared player.
  const webm = evaluate(`(async () => {
    const canvas = document.createElement('canvas'); canvas.width = 320; canvas.height = 180;
    const context = canvas.getContext('2d'); context.fillStyle = '#87bfe8'; context.fillRect(0,0,320,180);
    const stream = canvas.captureStream(12);
    const recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp8' });
    const chunks = []; recorder.ondataavailable = event => chunks.push(event.data);
    const done = new Promise(resolve => { recorder.onstop = resolve; });
    recorder.start();
    const drawing = setInterval(() => { context.fillRect(0,0,320,180); }, 80);
    await new Promise(resolve => setTimeout(resolve, 1200));
    recorder.stop(); await done; clearInterval(drawing); stream.getTracks().forEach(track => track.stop());
    const bytes = new Uint8Array(await new Blob(chunks).arrayBuffer());
    return btoa(String.fromCharCode(...bytes));
  })()`);
  writeFileSync(videoFile, Buffer.from(webm, 'base64'));
  openAppearance();
  browser('upload', '.chat-appearance-settings input[type="file"][accept^="video/"]', videoFile);
  browser('wait', '--fn', 'document.documentElement.dataset.wallpaper === "video"');
  assert.match(saved().videoId, /^wallpaper-/);
  assert.ok(JSON.stringify(saved()).length < 2000, 'video bytes belong in IndexedDB, not localStorage');
  assert.equal(evaluate('document.querySelectorAll("video").length'), 1, 'Settings must reuse the shared player with a still preview');
  evaluate(`(() => {
    const zone = document.querySelector('[data-wallpaper-drop="video"]');
    const raw = atob(${JSON.stringify(webm)});
    const bytes = Uint8Array.from(raw, character => character.charCodeAt(0));
    const data = new DataTransfer();
    data.items.add(new File([bytes], 'dropped.webm', { type: 'video/webm' }));
    zone.dispatchEvent(new DragEvent('dragenter', { bubbles: true, cancelable: true, dataTransfer: data }));
    zone.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: data }));
    return true;
  })()`);
  browser('wait', '--fn', 'document.querySelector("[data-wallpaper-drop=\\"video\\"]")?.hasAttribute("data-wallpaper-drop-active")');
  evaluate(`(() => {
    const zone = document.querySelector('[data-wallpaper-drop="video"]');
    const raw = atob(${JSON.stringify(webm)});
    const bytes = Uint8Array.from(raw, character => character.charCodeAt(0));
    const data = new DataTransfer();
    data.items.add(new File([bytes], 'dropped.webm', { type: 'video/webm' }));
    zone.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: data }));
    return true;
  })()`);
  browser('wait', '--fn', 'document.documentElement.dataset.wallpaper === "video"');
  assert.match(saved().videoId, /^wallpaper-/);
  closeAppearance();
  browser('wait', '--fn', 'document.querySelector(".app-wallpaper video")?.paused === false');
  assert.equal(evaluate('document.querySelector(".app-wallpaper video").muted && document.querySelector(".app-wallpaper video").loop'), true);
  browser('reload');
  browser('wait', '--fn', 'document.querySelector(".app-wallpaper video")?.paused === false');
  evaluate("Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange')); true");
  browser('wait', '--fn', 'document.querySelector(".app-wallpaper video").paused');
  evaluate("delete document.hidden;document.dispatchEvent(new Event('visibilitychange')); true");
  browser('wait', '--fn', 'document.querySelector(".app-wallpaper video").paused === false');
  browser('screenshot', join(tmpdir(), 'claude-chat-conversation.png'));

  // Real media emulation ensures motion is skipped and touch targets remain reachable.
  browser('set', 'media', 'dark', 'reduced-motion');
  browser('set', 'viewport', '390', '844');
  evaluate("localStorage.setItem('claudeBotTheme','dark')");
  browser('reload');
  browser('wait', '.chat-thread[data-empty]');
  assert.ok(centerError() <= 2);
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  assert.equal(evaluate('window.matchMedia("(prefers-reduced-motion: reduce)").matches'), true);
  browser('wait', '--fn', 'document.querySelector(".app-wallpaper video")?.paused === true');
  assert.ok(evaluate('document.querySelector("button[aria-label=Dictate]").getBoundingClientRect().height') >= 44);
  openAppearance();
  assert.equal(evaluate('document.querySelector(".chat-appearance-settings").getBoundingClientRect().width <= innerWidth'), true);
  browser('screenshot', join(tmpdir(), 'claude-chat-appearance-mobile.png'));
  closeAppearance();
  evaluate(`(() => {
    const original = window.fetch;
    window.fetch = async (url, options) => {
      if (String(url) === '/api/chat/upload') return Response.json({ url: '/uploads/review.txt', name: 'review.txt', type: 'text/plain', size: 10 });
      if (String(url).startsWith('/api/chat/attachment-preview')) return Response.json({ text: 'Review fixture', type: 'text/plain', size: 10 });
      return original(url, options);
    };
    const input = document.querySelector('input[data-attachment-picker="files"]');
    const data = new DataTransfer();
    data.items.add(new File(['fixture'], 'review.txt', { type: 'text/plain' }));
    input.files = data.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`);
  browser('wait', '.attachment-card.is-draft');
  const tallDraft = Array.from({ length: 8 }, (_, index) => `Line ${index + 1}: Keep this draft intact.`).join('\n');
  browser('fill', '.prompt-bar textarea', tallDraft);
  evaluate(`window.__crowdedComposer = document.querySelector('.prompt-bar textarea');
    document.documentElement.setAttribute('data-kb', ''); document.documentElement.style.setProperty('--kb', '320px'); true`);
  browser('wait', '--fn', 'Math.abs(document.querySelector(".app-shell").getBoundingClientRect().height - 524) < 2');
  assert.equal(evaluate(`document.querySelector('.prompt-bar__send').getBoundingClientRect().bottom <= document.querySelector('.chat-thread').getBoundingClientRect().bottom`), true,
    'the send button must stay above a software keyboard with a tall draft and attachment');
  assert.equal(evaluate('getComputedStyle(document.querySelector(".prompt-bar textarea")).overflowY'), 'auto');
  assert.equal(evaluate('getComputedStyle(document.querySelector(".chat-landing-heading")).display'), 'none');
  evaluate("document.documentElement.removeAttribute('data-kb'); document.documentElement.style.setProperty('--kb','0px'); true");
  browser('wait', '--fn', 'Math.abs(document.querySelector(".app-shell").getBoundingClientRect().height - 844) < 2');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea") === window.__crowdedComposer'), true);
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), tallDraft);
  assert.equal(evaluate('document.querySelectorAll(".attachment-card.is-draft").length'), 1);
  assert.ok(centerError() <= 2);
  mockReply();
  browser('fill', '.prompt-bar textarea', 'A quiet mobile message.');
  browser('press', 'Enter');
  browser('wait', '.chat-thread:not([data-empty])');
  browser('wait', '--text', 'A local test reply.');
  assert.equal(evaluate('getComputedStyle(document.querySelector("[data-chat-composer-position]")).transform'), 'none');
  browser('screenshot', join(tmpdir(), 'claude-chat-mobile.png'));
  console.log('PASS: centered composer, preserved draft, click and drag/drop image/video uploads, reload/rejection/quota/reset, first-send movement, history, mobile keyboard and reduced motion');
} finally {
  browser('close');
  rmSync(imageFile, { force: true });
  rmSync(rejectedFile, { force: true });
  rmSync(videoFile, { force: true });
}
