/** Real composer interactions, with every chat write confined to browser fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `send-bubble-test-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5180';
const page = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const bubble = '[data-send-bubble]';
const switchSelector = '[role="switch"][aria-label="Send bubble"]';

function watchFlights() {
  // Record short-lived effects without slowing the production animation.
  evaluate(`window.__flightObserver?.disconnect(); window.__flights = []; window.__sends = [];
    const observer = new MutationObserver(records => {
      for (const record of records) for (const node of record.addedNodes) {
        if (!(node instanceof Element) || !node.isConnected) continue;
        const flight = node.matches('${bubble}') ? node : node.querySelector('${bubble}');
        if (flight) {
          const animation = flight.getAnimations()[0];
          const destination = document.querySelector('[data-user-message="' + flight.dataset.sendBubble + '"]');
          const samples = [0, 490, 710].map(time => {
            animation.pause(); animation.currentTime = time;
            const style = getComputedStyle(flight);
            return { rect: flight.getBoundingClientRect().toJSON(), opacity: Number(style.opacity), filter: style.filter };
          });
          animation.currentTime = 0; animation.play();
          window.__flights.push({ samples, text: flight.textContent, destination: destination.getBoundingClientRect().toJSON(),
            targetHidden: getComputedStyle(destination).opacity === '0', pointerEvents: getComputedStyle(flight).pointerEvents,
            hidden: flight.closest('[aria-hidden=true]') !== null,
            buttonPresent: document.querySelectorAll('.prompt-bar__send').length === 1 });
        }
      }
    }); observer.observe(document.body, { childList: true, subtree: true }); window.__flightObserver = observer;
    const originalFetch = window.fetch;
    window.fetch = async (url, options) => {
      if (new URL(String(url), location.href).pathname === '/api/chat') {
        window.__sends.push(JSON.parse(options.body));
        return new Response('event: done\\ndata: {"reply":""}\\n\\n', {
          headers: { 'Content-Type': 'text/event-stream' } });
      }
      return originalFetch(url, options);
    };`);
}

function openChat() {
  browser('open', `${origin}${page}#/chat`);
  browser('wait', '.prompt-bar__send');
  watchFlights();
}

function send(text, keyboard = false) {
  browser('wait', '--fn', 'document.querySelector(".prompt-bar__send").getAttribute("aria-label") === "Send"');
  browser('fill', '.prompt-bar__input', text);
  browser('wait', '--fn', '!document.querySelector(".prompt-bar__send").disabled');
  evaluate('window.__source = document.querySelector(".prompt-bar__send").getBoundingClientRect().toJSON()');
  if (keyboard) browser('press', 'Enter');
  else browser('click', '.prompt-bar__send');
  browser('wait', '--fn', 'document.querySelector(".prompt-bar__send").disabled && document.querySelector(".prompt-bar__send").getAttribute("aria-label") === "Send"');
}

try {
  browser('open', `${origin}${page}#/chat`);
  browser('set', 'viewport', '1280', '850');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [] });
  route('**/api/setup', { configured: true, profile: { configured: true }, keys_set: {}, languages: [], personas: [], reply_lengths: [] });
  route('**/api/brain/models', { models: [{ id: 'test', label: 'Test', context: 200000 }],
    selected: 'test', default: 'test', thinking: 'high', thinking_levels: ['high'], available: true });
  evaluate("localStorage.setItem('claudeBotLang', 'en'); localStorage.removeItem('claudeBotSendBubble')");
  browser('reload');
  browser('wait', '.prompt-bar__send');
  watchFlights();
  assert.equal(evaluate('document.querySelector(".prompt-bar__send").disabled'), true);
  browser('fill', '.prompt-bar__input', '   ');
  browser('press', 'Enter');
  assert.equal(evaluate('window.__flights.length'), 0, 'empty drafts cannot launch a bubble');

  send('Mouse send');
  assert.equal(evaluate('window.__flights.length'), 1);
  assert.deepEqual(evaluate('window.__flights[0].buttonPresent'), true);
  assert.equal(evaluate('window.__flights[0].pointerEvents'), 'none');
  assert.equal(evaluate('window.__flights[0].hidden'), true);
  const samples = evaluate('window.__flights[0].samples');
  const destination = evaluate('window.__flights[0].destination');
  const source = evaluate('window.__source');
  assert.equal(evaluate('window.__flights[0].text'), 'Mouse send', 'the whole message travels');
  assert.equal(evaluate('window.__flights[0].targetHidden'), true, 'the destination waits for the travelling message');
  assert.ok(Math.abs(samples[0].rect.left + samples[0].rect.width / 2 - source.left - source.width / 2) < 1, 'the message starts at the button');
  assert.ok(Math.abs(samples[0].rect.top + samples[0].rect.height / 2 - source.top - source.height / 2) < 1);
  assert.ok(samples[1].rect.top < samples[0].rect.top - 80, 'the copy flies upward');
  assert.ok(Math.abs(samples[1].rect.left - destination.left) < 1, 'the flight reaches the real message');
  assert.ok(Math.abs(samples[1].rect.top - destination.top) < 1);
  assert.ok(Math.abs(samples[1].rect.width - destination.width) < 1, 'the complete bubble lands at full size');
  assert.ok(samples[2].opacity < 0.15, 'the bubble dissolves at its destination');
  assert.ok(Number(samples[2].filter.match(/[\d.]+/)[0]) > 4, 'the exit has visible motion blur');
  browser('wait', '--fn', `document.querySelector('${bubble}') === null`);
  assert.equal(evaluate('getComputedStyle(document.querySelector("[data-user-message]")).opacity'), '1', 'the message remains after the flight');
  send('Keyboard send', true);
  assert.equal(evaluate('window.__flights.length'), 2);
  assert.equal(evaluate('window.__sends.length'), 2, 'the effect must not duplicate requests');
  browser('wait', '--fn', `document.querySelector('${bubble}') === null`);
  const multiline = 'The complete message travels to the chat.\nLine two stays in the bubble.\nLine three stays too.\nAnd the final line arrives with it.';
  send(multiline);
  assert.equal(evaluate('window.__flights.length'), 3);
  assert.equal(evaluate('window.__flights.at(-1).text'), multiline, 'multiline content is never replaced by an icon or truncated');
  assert.ok(evaluate('window.__flights.at(-1).samples[1].rect.height > 60'));

  browser('open', `${origin}${page}#/settings?tab=look`);
  browser('wait', switchSelector);
  assert.equal(evaluate(`document.querySelector('${bubble}') === null`), true, 'leaving the chat removes its travelling copy');
  browser('scrollintoview', switchSelector);
  assert.equal(evaluate(`document.querySelector('${switchSelector}').getAttribute('aria-checked')`), 'true');
  browser('click', switchSelector);
  browser('wait', '--fn', 'localStorage.getItem("claudeBotSendBubble") === "off"');
  assert.equal(evaluate("localStorage.getItem('claudeBotSendBubble')"), 'off');
  browser('reload');
  browser('wait', switchSelector);
  assert.equal(evaluate(`document.querySelector('${switchSelector}').getAttribute('aria-checked')`), 'false');
  openChat();
  send('Disabled effect');
  assert.equal(evaluate('window.__flights.length'), 0);
  assert.equal(evaluate('window.__sends.length'), 1, 'disabled effects still send normally');

  // A different tab can update the choice while neither subscribing panel exists.
  browser('open', `${origin}${page}#/settings?tab=profile`);
  browser('wait', '--fn', 'document.querySelector(".prompt-bar__send") === null');
  evaluate("localStorage.setItem('claudeBotSendBubble', 'on')");
  openChat();
  send('Updated while unmounted');
  assert.equal(evaluate('window.__flights.length'), 1);
  browser('wait', '--fn', `document.querySelector('${bubble}') === null`);
  evaluate("localStorage.setItem('claudeBotSendBubble', 'off'); window.dispatchEvent(new StorageEvent('storage', { key: 'claudeBotSendBubble' }))");
  send('Updated while mounted');
  assert.equal(evaluate('window.__flights.length'), 1, 'a mounted panel responds to storage events');

  browser('open', `${origin}${page}#/settings?tab=look`);
  browser('wait', switchSelector);
  browser('focus', switchSelector);
  browser('press', 'Space');
  browser('set', 'media', 'light', 'reduced-motion');
  openChat();
  send('Reduced motion');
  assert.equal(evaluate('window.__flights.length'), 0);
  assert.equal(evaluate('window.__sends.length'), 1);
  browser('set', 'media', 'light');

  for (const [theme, width, height] of [['light', 1280, 850], ['dark', 390, 844]]) {
    evaluate(`localStorage.setItem('claudeBotTheme', '${theme}')`);
    browser('set', 'viewport', String(width), String(height));
    browser('reload');
    browser('wait', '.prompt-bar__send');
    watchFlights();
    send(`${theme} layout`);
    assert.equal(evaluate('window.__flights.length'), 1);
    assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
    assert.equal(evaluate('document.querySelectorAll(".prompt-bar__send").length'), 1);
    browser('wait', '--fn', `document.querySelector('${bubble}') === null`);
  }
  console.log('PASS: mouse/Enter, empty drafts, cleanup, persistent/cross-tab toggle, reduced motion, light desktop and dark phone');
} catch (error) {
  console.error(browser('errors'));
  console.error(evaluate('({ flights: window.__flights, sends: window.__sends, enabled: localStorage.getItem("claudeBotSendBubble"), reduced: matchMedia("(prefers-reduced-motion: reduce)").matches })'));
  throw error;
} finally {
  browser('close');
}
