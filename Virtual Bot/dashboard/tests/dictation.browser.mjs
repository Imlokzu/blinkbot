/** Dictation regression: the second mic press must survive delayed mic setup. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `dictation-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], {
  encoding: 'utf8',
  timeout: 35000,
});
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const mic = '.prompt-bar__tool[aria-pressed]';

const fixtures = {
  '/api/auth/config': { disabled: true },
  '/api/status': { openclaw: true, anthropic: true, mode: 'fixture' },
  '/api/sessions': { sessions: [] },
  '/api/projects': { projects: [] },
  '/api/setup': { configured: true, profile: { configured: true }, keys_set: {}, languages: [], personas: [], reply_lengths: [] },
  '/api/brain/models': { models: [{ id: 'fixture', label: 'Fixture', context: 200000 }], selected: 'fixture', default: 'fixture', thinking: 'high', thinking_levels: ['high'], available: true },
  '/api/brain/intelligence': { models: [] },
  '/api/chat/context': { parts: [], chars: 0, dropped: 0, history_limit: 20 },
  '/api/asr/status': { enabled: true },
  '/api/tts/status': { enabled: false },
  '/api/connectors': { connectors: [] },
};

// Install browser-only media and API fakes before the dashboard bootstraps.
const init = `(() => {
  const fixtures = ${JSON.stringify(fixtures)};
  const originalFetch = window.fetch.bind(window);
  const state = window.__dictationFixture = {
    asr: 0, getUserMedia: 0, recorderStarts: 0, recorderStops: 0, dataEvents: 0,
    holdMedia: false, releaseMedia: null, recorderReady: false,
  };
  window.__dictationFixtureErrors = [];
  window.addEventListener('error', event => window.__dictationFixtureErrors.push(event.message));
  window.addEventListener('unhandledrejection', event => window.__dictationFixtureErrors.push(String(event.reason)));
  window.EventSource = class {
    static OPEN = 1;
    readyState = 1;
    constructor() { queueMicrotask(() => this.onopen?.(new Event('open'))); }
    close() { this.readyState = 2; }
    addEventListener() {}
    removeEventListener() {}
  };
  window.fetch = async (input, options = {}) => {
    const request = input instanceof Request ? input : null;
    const url = new URL(request ? request.url : String(input), location.href);
    if (!url.pathname.startsWith('/api/')) return originalFetch(input, options);
    const method = String(options.method || request?.method || 'GET').toUpperCase();
    if (method === 'GET' || method === 'HEAD') return Response.json(fixtures[url.pathname] || {});
    if (url.pathname === '/api/asr') {
      state.asr += 1;
      return Response.json({ text: 'fixture dictated text' });
    }
    if (url.pathname === '/api/asr/partial') return Response.json({ text: '' });
    return Response.json({});
  };
  const track = { stop() {} };
  const stream = { getTracks: () => [track], getAudioTracks: () => [track] };
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: {
    getUserMedia: async () => {
      state.getUserMedia += 1;
      if (state.holdMedia) await new Promise(resolve => { state.releaseMedia = resolve; });
      return stream;
    },
  }});
  window.MediaRecorder = class {
    state = 'inactive';
    ondataavailable = null;
    onstop = null;
    constructor() {}
    start() {
      this.state = 'recording';
      state.recorderStarts += 1;
      state.recorderReady = true;
      state.dataEvents += 1;
      this.ondataavailable?.({ data: new Blob(['fixture audio'], { type: 'audio/webm' }) });
    }
    stop() {
      if (this.state === 'inactive') return;
      state.dataEvents += 1;
      this.ondataavailable?.({ data: new Blob(['fixture audio'], { type: 'audio/webm' }) });
      this.state = 'inactive';
      state.recorderStops += 1;
      queueMicrotask(() => this.onstop?.());
    }
  };
  window.AudioContext = class {
    sampleRate = 48000;
    createAnalyser() {
      return {
        fftSize: 1024,
        context: { sampleRate: 48000 },
        getFloatTimeDomainData(buffer) { buffer.fill(0); },
        getByteFrequencyData(buffer) { buffer.fill(0); },
      };
    }
    createMediaStreamSource() { return { connect() {} }; }
    close() { return Promise.resolve(); }
  };
})();`;

let socket;
let cdpSession;
let nextId = 0;
const pending = new Map();
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId;
  const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 5000);
  pending.set(id, {
    resolve: result => { clearTimeout(timeout); resolve(result); },
    reject: error => { clearTimeout(timeout); reject(error); },
  });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
  browser('open', 'about:blank');
  browser('network', 'route', '**/api/**', '--body', '{}');
  for (const [route, body] of Object.entries(fixtures)) {
    browser('network', 'route', `**${route}`, '--body', JSON.stringify(body));
  }
  socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) request.reject(new Error(JSON.stringify(message.error)));
    else request.resolve(message.result);
  });
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url === 'about:blank');
  assert.ok(target, 'the isolated browser target must exist');
  cdpSession = (await cdp('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: init });
  browser('open', `${origin}${path}#/chat`);
  browser('wait', '.prompt-bar textarea');
  browser('wait', mic);

  // The second click happens while getUserMedia is still pending.
  evaluate('window.__dictationFixture.holdMedia = true; true');
  browser('click', mic);
  browser('wait', '--fn', 'document.querySelector(".prompt-bar__tool[aria-pressed]")?.getAttribute("aria-pressed") === "true" && window.__dictationFixture.getUserMedia === 1');
  browser('click', mic);
  assert.equal(evaluate('document.querySelector(".prompt-bar__tool[aria-pressed]").getAttribute("aria-pressed")'), 'true', 'the second press keeps the pending dictation alive');
  evaluate('window.__dictationFixture.holdMedia = false; window.__dictationFixture.releaseMedia?.(); true');
  browser('wait', '--fn', 'window.__dictationFixture.asr === 1 && document.querySelector(".prompt-bar__tool[aria-pressed]").getAttribute("aria-pressed") === "false"');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'fixture dictated text', 'the early stop still inserts ASR text');

  // The normal ready-recorder path must keep its existing forced-stop behavior.
  evaluate('window.__dictationFixture.recorderReady = false; true');
  browser('click', mic);
  browser('wait', '--fn', 'window.__dictationFixture.recorderReady === true && document.querySelector(".prompt-bar__tool[aria-pressed]").getAttribute("aria-pressed") === "true"');
  browser('click', mic);
  browser('wait', '--fn', 'window.__dictationFixture.asr === 2 && document.querySelector(".prompt-bar__tool[aria-pressed]").getAttribute("aria-pressed") === "false"');
  assert.equal(evaluate('window.__dictationFixture.recorderStops'), 2, 'both presses stop their recorder');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'fixture dictated text fixture dictated text');
  assert.deepEqual(evaluate('window.__dictationFixtureErrors'), [], 'the mocked media flow has no uncaught errors');
  console.log('PASS: delayed getUserMedia second press, immediate recorder stop, ASR insertion, and normal forced stop');
} catch (error) {
  try { console.error('fixture state:', evaluate('JSON.stringify(window.__dictationFixture)')); } catch {}
  console.error(browser('snapshot', '-i'));
  console.error(browser('errors'));
  throw error;
} finally {
  socket?.close();
  browser('close');
}
