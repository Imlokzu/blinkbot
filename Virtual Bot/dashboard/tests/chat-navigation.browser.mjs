/** Verify page-local chat selection and panel toggles using browser-only fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `chat-navigation-test-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const toolbarButton = (label) => `[data-chat-toolbar] button[aria-label="${label}"]`;
const current = () => evaluate('document.querySelector(".chat-layout").dataset.chatSession');
const go = (section) => {
  evaluate(`window.location.hash = '#/${section}'`);
  browser('wait', '--fn', `document.querySelector('main').dataset.section === '${section}'`);
};
const returnToChat = () => {
  go('settings');
  browser('wait', '--fn', 'document.querySelector(".chat-layout") === null');
  go('chat');
  browser('wait', '[data-chat-toolbar]');
};

try {
  browser('open', `${origin}${path}#/chat`);
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [
    { id: 'old-pinned', title: 'Old pinned conversation', pinned: true, count: 2 },
    { id: 'latest', title: 'Latest conversation', updated: Date.now() / 1000, count: 2 },
    { id: 'chosen', title: 'Chosen conversation', project: 'cats', count: 2 },
  ] });
  for (const id of ['old-pinned', 'latest', 'chosen']) {
    route(`**/api/sessions/${id}`, { id, messages: [
      { role: 'user', content: `${id} question` },
      { role: 'assistant', content: `${id} answer` },
    ] });
  }
  route('**/api/brain/models', { models: [{ id: 'test', label: 'Test', context: 200000 }],
    selected: 'test', default: 'test', thinking: 'high', thinking_levels: ['high'], available: true });
  route('**/api/projects', { projects: [{ id: 'cats', name: 'Cats' }] });
  route('**/api/workspace/info**', { root: '/fixture/workspace', session_path: 'sessions/default' });
  evaluate("localStorage.setItem('claudeBotLang', 'en'); localStorage.setItem('claudeBotWorkbench', 'closed'); localStorage.setItem('claudeBotConversationList', 'open')");
  browser('reload');
  browser('wait', '[data-session-id="old-pinned"]');
  assert.equal(current(), '', 'fresh visits must start a new conversation');
  assert.doesNotMatch(browser('network', 'requests', '--method', 'GET', '--filter', '/api/sessions/'), /old-pinned|latest|chosen/);

  // One stationary button opens and closes the Workbench in either state.
  assert.equal(evaluate(`document.querySelectorAll('button[aria-label="Hide conversations"]').length`), 1, 'only the toolbar closes the conversations list');
  const benchRight = () => evaluate(`document.querySelector('[data-chat-toolbar] button[aria-label="Show the workbench"], [data-chat-toolbar] button[aria-label="Hide the workbench"]').getBoundingClientRect().right`);
  const closedRight = benchRight();
  for (let attempt = 0; attempt < 2; attempt++) {
    browser('click', toolbarButton('Show the workbench'));
    browser('wait', '.workbench');
    assert.equal(evaluate(`document.querySelector(${JSON.stringify(toolbarButton('Hide the workbench'))}).ariaExpanded`), 'true');
    assert.ok(Math.abs(benchRight() - closedRight) <= 1, 'the Workbench toggle must stay at the far right when the panel opens');
    assert.ok(evaluate('document.querySelector(".chat-layout").getBoundingClientRect().right') - benchRight() <= 14);
    browser('click', toolbarButton('Hide the workbench'));
    browser('wait', '--fn', 'document.querySelector(".workbench") === null');
  }

  browser('click', '[data-session-id="chosen"]');
  browser('wait', '--text', 'chosen question');
  assert.equal(current(), 'chosen');
  returnToChat();
  browser('wait', '--text', 'chosen question');
  assert.equal(current(), 'chosen', 'Settings must not replace the selected chat with the first list item');

  evaluate("window.location.hash = '#/chat?project=untouched'");
  browser('wait', '--fn', 'document.querySelector(".chat-layout").dataset.chatSession === ""');
  returnToChat();
  browser('wait', '--text', 'chosen question');
  assert.equal(current(), 'chosen', 'an untouched project must not erase the main selection');

  evaluate(`window.__delayHistory = true; window.__queuedSends = []; const savedFetch = window.fetch;
    window.fetch = async (url, options) => {
      if (String(url).includes('/api/sessions/chosen') && window.__delayHistory) {
        await new Promise(resolve => { window.__releaseHistory = resolve; });
      }
      if (String(url).startsWith('/api/chat')) {
        window.__queuedSends.push(JSON.parse(options.body));
        const event = (name, body) => 'event: ' + name + '\\ndata: ' + JSON.stringify(body) + '\\n\\n';
        return new Response(event('delta', { chunk: 'Queued answer' }) + event('done', {
          reply: 'Queued answer', session_id: 'chosen', emotion: 'idle', mode: 'test', model: 'test', tool_results: [],
        }), { headers: { 'Content-Type': 'text/event-stream' } });
      }
      return savedFetch(url, options);
    };`);
  returnToChat();
  browser('wait', '--fn', 'typeof window.__releaseHistory === "function"');
  evaluate("window.__vbotSendMessage('Queued follow-up')");
  browser('wait', '[data-chat-queued]');
  browser('fill', '.prompt-bar textarea', 'Second draft stays here');
  browser('press', 'Enter');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Second draft stays here');
  assert.equal(evaluate('window.__queuedSends.length'), 0, 'send must wait for restored history');
  evaluate('window.__delayHistory = false; window.__releaseHistory()');
  browser('wait', '--text', 'Queued answer');
  assert.match(evaluate('document.querySelector(".chat-conversation").textContent'), /chosen question/);
  assert.equal(evaluate('window.__queuedSends[0].session_id'), 'chosen');

  browser('click', toolbarButton('New conversation'));
  assert.equal(current(), '');
  returnToChat();
  assert.equal(current(), '', 'an intentionally new conversation must also survive navigation');

  // One keyboard-accessible toolbar toggle retains focus in both states.
  browser('focus', toolbarButton('Hide conversations'));
  browser('press', 'Enter');
  browser('wait', '--fn', 'document.querySelector(".chat-sessions") === null');
  assert.equal(evaluate('document.activeElement.matches("[data-chat-toolbar] button[aria-label=\\"Show conversations\\"]")'), true);
  browser('click', toolbarButton('Show conversations'));
  browser('wait', '.chat-sessions');
  browser('click', '[data-session-id="latest"]');
  browser('wait', '--text', 'latest question');
  browser('click', toolbarButton('Hide conversations'));
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/chat-toolbar-collapsed.png`);
  browser('reload');
  browser('wait', '[data-chat-toolbar]');
  assert.equal(current(), '', 'a reload clears the page-local selected chat');
  assert.equal(evaluate('document.querySelector(".chat-sessions") === null'), true, 'the sidebar layout preference should persist');
  browser('click', toolbarButton('Show conversations'));

  // Project navigation keeps a deliberate selection without guessing one.
  evaluate("window.location.hash = '#/chat?project=cats'");
  browser('wait', '[data-session-id="chosen"]');
  assert.equal(current(), '');
  browser('click', '[data-session-id="chosen"]');
  browser('wait', '--text', 'chosen question');
  returnToChat();
  browser('wait', '--text', 'chosen question');
  assert.equal(current(), 'chosen');
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/chat-toolbar.png`);

  browser('set', 'viewport', '390', '844');
  browser('wait', '--fn', 'document.querySelector("[data-chat-toolbar]") === null');
  browser('click', 'button[aria-label="Conversations"], button[aria-label="Розмови"]');
  browser('wait', '[data-session-id="chosen"]');
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  console.log('PASS: fresh/new chat, Settings return, project selection, persistent list toggle, stationary Workbench toggle, phone drawer');
} catch (error) {
  console.error(browser('errors'));
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/chat-navigation-failure.png`);
  throw error;
} finally { browser('close'); }
