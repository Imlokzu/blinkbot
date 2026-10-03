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
  browser('click', 'button[aria-label="Customize chat appearance"]');
  browser('wait', '[role="dialog"]');
};
const closeAppearance = () => {
  browser('click', '[role="dialog"] button[aria-label="Close"]');
  browser('wait', '--fn', 'document.querySelector("[role=dialog]") === null');
};
const clickText = (text) => browser('find', 'role', 'button', 'click', '--name', text, '--exact');
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
  route('**/api/sessions', { sessions: [{ id: 'history', title: 'Saved conversation', count: 2 }] });
  route('**/api/sessions/history', { id: 'history', messages: [
    { role: 'user', content: 'A saved question.' }, { role: 'assistant', content: 'A saved answer.' },
  ] });
  route('**/api/brain/models', { models: [
    { id: 'openai/gpt-6-sol', label: 'GPT-6 Sol', context: 200000 },
    { id: 'omni/claude/claude-opus-4-8', label: 'Claude Opus', context: 200000 },
    { id: 'regolo/qwen3', label: 'Qwen 3', context: 100000 },
  ], selected: 'openai/gpt-6-sol', default: 'openai/gpt-6-sol', thinking: 'high', thinking_levels: ['low', 'high'], available: true });
  browser('open', `${origin}${path}#/chat`);
  evaluate("localStorage.setItem('claudeBotLang','en'); localStorage.setItem('claudeBotTheme','light'); localStorage.removeItem('claudeBotChatAppearance')");
  browser('reload');
  browser('wait', '.chat-thread[data-empty]');
  assert.ok(centerError() <= 2, 'the new-chat composer must be centered');
  assert.equal(conversation().wallpaper, 'sky');
  browser('fill', '.prompt-bar textarea', 'Keep this draft.');
  evaluate('window.__originalComposer = document.querySelector(".prompt-bar textarea"); true');
  openAppearance();
  clickText('Dusk');
  clickText('Ocean');
  browser('focus', '[role="dialog"] input[type="range"][min="65"]');
  browser('press', 'Home');
  assert.equal(saved().opacity, 65);
  closeAppearance();
  assert.equal(conversation().wallpaper, 'dusk');
  assert.equal(conversation().chatColor, 'ocean');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea") === window.__originalComposer'), true);
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Keep this draft.');

  // Upload is genuinely local: re-encode a raster and retain it through a reload.
  const png = evaluate(`(() => { const canvas = document.createElement('canvas'); canvas.width = 32; canvas.height = 32;
    const context = canvas.getContext('2d'); context.fillStyle = '#79aab5'; context.fillRect(0,0,32,32); return canvas.toDataURL('image/png'); })()`);
  writeFileSync(imageFile, Buffer.from(png.split(',')[1], 'base64'));
  writeFileSync(rejectedFile, '<svg xmlns="http://www.w3.org/2000/svg"/>');
  openAppearance();
  browser('upload', '[role="dialog"] input[type="file"]', imageFile);
  browser('wait', '--fn', 'document.querySelector(".chat-conversation").dataset.wallpaper === "custom"');
  assert.match(saved().image, /^data:image\/jpeg;base64,/);
  const previous = saved();
  browser('upload', '[role="dialog"] input[type="file"]', rejectedFile);
  browser('wait', '[role="dialog"] [role="alert"]');
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
  assert.ok(evaluate('document.querySelector("button[aria-label=Dictate]").getBoundingClientRect().height') >= 44);
  openAppearance();
  assert.equal(evaluate('document.querySelector("[role=dialog]").getBoundingClientRect().width <= innerWidth'), true);
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
  console.log('PASS: centered composer, preserved draft, local image upload/reload/rejection/quota/reset, first-send movement, history, mobile keyboard and reduced motion');
} finally {
  browser('close');
  rmSync(imageFile, { force: true });
  rmSync(rejectedFile, { force: true });
}
