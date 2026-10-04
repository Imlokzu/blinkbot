/** Browser-only SSE fixtures exercise the real chat adapter without server writes. */
import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';

const session = `activity-tree-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding:'utf8' });
const browserAsync = (...args) => new Promise((resolve, reject) => execFile('agent-browser', ['--session', session, ...args], { encoding:'utf8' }, (error, stdout) => error ? reject(error) : resolve(stdout)));
const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const emit = (type, data) => evaluate(`window.__emit(${JSON.stringify(type)}, ${JSON.stringify(data)}); true`);
const row = id => `[data-tool-step=${JSON.stringify(id)}]`;
const tree = id => `.chat-activity-tree:has(${row(id)})`;
const state = id => evaluate(`document.querySelector(${JSON.stringify(row(id))})?.dataset.toolStatus`);
const arrivalNames = ['chat-activity-arrive', 'chat-activity-trunk', 'chat-activity-elbow',
  'chat-activity-icon', 'chat-activity-draw', 'chat-activity-text', 'chat-activity-glint'];
const arrivalSnapshot = id => evaluate(`(() => {
  const branch = document.querySelector(${JSON.stringify(row(id))});
  const record = window.__activityArrivals.get(${JSON.stringify(id)});
  const current = branch.getAnimations({ subtree:true }).filter(animation => window.__activityArrivalNames.has(animation.animationName));
  return { entering:branch.hasAttribute('data-activity-enter'), sameNode:record?.node === branch,
    sameAnimations:current.length === record?.animations.length && current.every(animation => record.animations.includes(animation)),
    names:current.map(animation => animation.animationName), captured:record?.animations.map(animation => ({ name:animation.animationName, current:current.includes(animation), time:animation.currentTime, state:animation.playState, target:animation.effect?.target?.outerHTML?.slice(0,200), pseudo:animation.effect?.pseudoElement })) };
})()`);
const arrivalFrame = (id, time) => evaluate(`(() => {
  const record = window.__activityArrivals.get(${JSON.stringify(id)});
  const branch = record.node;
  const attached = new Set(branch.getAnimations({ subtree:true }));
  for (const animation of record.animations) if (attached.has(animation)) animation.currentTime = ${time};
  const transform = (node, pseudo) => {
    const value = getComputedStyle(node, pseudo).transform;
    const matrix = new DOMMatrixReadOnly(value === 'none' ? undefined : value);
    return { x:matrix.m41, y:matrix.m42, scaleX:matrix.a, scaleY:matrix.d };
  };
  const icon = branch.querySelector('.chat-activity-tool-icon');
  const description = branch.querySelector('.chat-activity-description');
  const followup = branch.querySelector('.chat-activity-followup');
  const text = [...description.querySelectorAll('.chat-activity-tool-name, .chat-activity-detail, .chat-activity-state')];
  return { trunk:transform(branch, '::before'), elbow:transform(branch, '::after'),
    iconOpacity:Number(getComputedStyle(icon).opacity),
    strokes:[...icon.querySelectorAll('[pathLength]')].map(shape => ({ length:shape.getAttribute('pathLength'), offset:parseFloat(getComputedStyle(shape).strokeDashoffset) })),
    text:text.map(node => ({ text:node.textContent, opacity:Number(getComputedStyle(description).opacity) * Number(getComputedStyle(node).opacity), ...transform(description) })),
    followup:{ opacity:Number(getComputedStyle(followup).opacity), ...transform(followup) } };
})()`);
const noArrival = (ids, reason) => {
  const observed = evaluate(`[...document.querySelectorAll('[data-tool-step]')]
    .filter(branch => ${JSON.stringify(ids)}.includes(branch.dataset.toolStep))
    .map(branch => ({ id:branch.dataset.toolStep, entering:branch.hasAttribute('data-activity-enter'), drawing:branch.querySelectorAll('.chat-activity-tool-icon > [data-activity-stroke]').length,
      names:branch.getAnimations({ subtree:true }).filter(animation => window.__activityArrivalNames.has(animation.animationName)).map(animation => animation.animationName) }))`);
  assert.equal(observed.length, ids.length, 'all requested activity rows must exist');
  assert.deepEqual(observed.filter(branch => branch.entering || branch.drawing || branch.names.length), [], reason);
};
const settleTree = (id, expanded) => browser('wait', '--fn', `(() => {
  const root = document.querySelector(${JSON.stringify(tree(id))});
  const fold = root?.querySelector('[data-activity-branches]');
  return root?.querySelector('[data-activity-toggle]')?.getAttribute('aria-expanded') === '${expanded}'
    && fold.getAnimations().every(animation => animation.playState !== 'running')
    && ${expanded ? 'fold.getBoundingClientRect().height > 0' : 'fold.getBoundingClientRect().height === 0'};
})()`);
const settleSources = expanded => browser('wait', '--fn', `(() => {
  const root = document.querySelector('.chat-source-strip');
  const fold = root?.querySelector('[data-sources-fold]');
  return root?.querySelector('[data-sources-toggle]')?.getAttribute('aria-expanded') === '${expanded}'
    && fold?.dataset.phase === '${expanded ? 'open' : 'closed'}'
    && fold.inert === ${!expanded} && fold.getAttribute('aria-hidden') === '${!expanded}'
    && fold.getAnimations({ subtree:true }).every(animation => animation.playState !== 'running')
    && ${expanded ? 'fold.getBoundingClientRect().height > 0' : 'fold.getBoundingClientRect().height === 0'};
})()`);
const sourceBounds = () => {
  const outside = evaluate(`[...document.querySelectorAll('.chat-source-strip, [data-sources-toggle], [data-sources-fold]')]
    .map(node => { const bounds = node.getBoundingClientRect(); return { className:node.className, left:bounds.left, right:bounds.right }; })
    .filter(bounds => bounds.left < -1 || bounds.right > innerWidth + 1)`);
  assert.deepEqual(outside, [], 'sources must fit the viewport even when the app shell clips document overflow');
};
const finish = reply => emit('done', { reply, session_id:'activity-fixture', emotion:'idle', mode:'test', model:'activity-model', tool_results:[] });
const send = message => {
  evaluate('window.__activityStream = null; true');
  browser('fill', '.prompt-bar textarea', message);
  browser('press', 'Enter');
  browser('wait', '--fn', 'Boolean(window.__activityStream)');
};
const overflow = () => {
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'activity must fit the viewport');
  assert.equal(evaluate(`[...document.querySelectorAll('.chat-activity-tree, [data-tool-row]')].every(node => {
    const bounds = node.getBoundingClientRect();
    return !bounds.width || (bounds.left >= -1 && bounds.right <= innerWidth + 1);
  })`), true, 'long paths and open logs must not push rows outside the phone');
};
const readLabelFits = () => {
  const label = evaluate(`(() => {
    const name = document.querySelector('[data-tool-step="read-a"] .chat-activity-tool-name');
    return { text:name.textContent, width:name.clientWidth, contentWidth:name.scrollWidth, viewport:innerWidth };
  })()`);
  assert.equal(label.text, 'Read file');
  assert.ok(label.width >= label.contentWidth - 1,
    `a known read operation must stay readable beside a long path at ${label.viewport}px (${label.width}/${label.contentWidth}px)`);
};
const shot = name => {
  if (process.env.ACTIVITY_TREE_SHOTS) browser('screenshot', `${process.env.ACTIVITY_TREE_SHOTS}/${name}.png`);
};
const controls = selector => evaluate(`(() => {
  const control = document.querySelector(${JSON.stringify(selector)});
  const id = control?.getAttribute('aria-controls');
  return { expanded:control?.getAttribute('aria-expanded'), linked:Boolean(id && document.getElementById(id)) };
})()`);
const captureIconDeadlines = () => evaluate(`window.__faviconTimers = new Map(); window.__iconSetTimeout = window.setTimeout; window.__iconClearTimeout = window.clearTimeout;
  window.setTimeout = (callback, delay, ...args) => {
    if (delay !== 6000) return window.__iconSetTimeout(callback, delay, ...args);
    const id = window.__iconSetTimeout(() => {}, 600000);
    window.__faviconTimers.set(id, () => callback(...args)); return id;
  };
  window.clearTimeout = id => { window.__faviconTimers.delete(id); window.__iconClearTimeout(id); };
  window.__fireIconDeadline = () => {
    const entry = window.__faviconTimers.entries().next().value;
    if (!entry) throw new Error('No pending fixture icon deadline');
    const [id, callback] = entry; window.clearTimeout(id); callback(); return true;
  };
  window.__fireAllIconDeadlines = () => {
    const entries = [...window.__faviconTimers];
    for (const [id, callback] of entries) { window.clearTimeout(id); callback(); }
    return entries.length;
  }; true`);
const restoreIconDeadlines = () => evaluate(`for (const id of window.__faviconTimers.keys()) window.__iconClearTimeout(id);
  window.__faviconTimers.clear(); window.setTimeout = window.__iconSetTimeout; window.clearTimeout = window.__iconClearTimeout; true`);

let socket;
let cdpSession;
let socketFailure;
let nextId = 0;
const pending = new Map();
const faviconRequests = [];
const unexpectedExternal = [];
const heldFavicons = [];
const faviconSites = new Set(['docs.example.org','lookup.example.org','failed.example.org','fresh.example.org','hidden.example.org','timeout.example.org']);
const faviconCandidate = url => {
  if (url.pathname === '/favicon.ico' && faviconSites.has(url.hostname)) return { site:url.hostname, candidate:'direct' };
  if (url.hostname === 'www.google.com' && url.pathname === '/s2/favicons') {
    const site = url.searchParams.get('domain');
    if (faviconSites.has(site) && url.searchParams.get('sz') === '32' && [...url.searchParams.keys()].every(key => ['domain','sz'].includes(key))) return { site, candidate:'google' };
  }
  if (url.hostname === 'icons.duckduckgo.com' && /^\/ip3\/[^/]+\.ico$/.test(url.pathname)) {
    const site = decodeURIComponent(url.pathname.slice(5, -4));
    if (faviconSites.has(site)) return { site, candidate:'ddg' };
  }
  return null;
};
const waitForFixture = async predicate => {
  const deadline = Date.now() + 5000;
  while (!predicate()) {
    if (socketFailure) throw socketFailure;
    assert.ok(Date.now() < deadline, 'the intercepted favicon request must arrive');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
};
// Generated locally: a deterministic blue 16px PNG, never a real site fetch.
const faviconPng = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAGUlEQVR4nGPQq73znxLMMGrAqAGjBgwXAwAX4YYf8tQajgAAAABJRU5ErkJggg==';
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextId;
  const timeout = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timed out: ${method}`)); }, 10000);
  pending.set(id, { resolve:value => { clearTimeout(timeout); resolve(value); }, reject:error => { clearTimeout(timeout); reject(error); } });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});

const respondToPausedRequest = async (method, params, sessionId) => {
  try { return await cdp(method, params, sessionId); }
  catch (error) {
    // Navigation and source replacement can cancel a paused favicon before the
    // fixture fulfills it. Every other protocol failure must fail the test.
    if (error.cdpError?.code === -32602 && error.cdpError.message === 'Invalid InterceptionId.') return;
    throw error;
  }
};

// Install before React mounts, including after history/locale reloads. The
// catch-all browser route protects initial startup; this guard records and
// rejects every unexpected mutation throughout the exercised interactions.
const init = `(() => {
  window.EventSource = class {
    static CONNECTING = 0; static OPEN = 1; static CLOSED = 2;
    readyState = 1;
    constructor() { queueMicrotask(() => this.onopen?.(new Event('open'))); }
    close() { this.readyState = 2; }
  };
  const original = window.fetch.bind(window);
  const frame = (type, data) => 'event: ' + type + '\\ndata: ' + JSON.stringify(data) + '\\n\\n';
  window.__activitySends = []; window.__activityAborts = 0; window.__fixtureImageReads = 0;
  // Capture on insertion, before CLI round trips can consume the reveal. Seeking
  // only entry animations leaves disclosure, status pulses and the browser clock real.
  window.__activityArrivalNames = new Set(${JSON.stringify(arrivalNames)});
  window.__activityArrivals = new Map();
  window.__activityPauseArrivals = new Set(['read-a', 'read-b', 'search']);
  new MutationObserver(() => {
    for (const node of document.querySelectorAll('[data-tool-step]')) {
      const id = node.dataset.toolStep;
      if (window.__activityArrivals.has(id)) continue;
      const animations = node.getAnimations({ subtree:true }).filter(animation => window.__activityArrivalNames.has(animation.animationName));
      if (!animations.length) continue;
      window.__activityArrivals.set(id, { node, animations });
      if (window.__activityPauseArrivals.has(id)) {
        for (const animation of animations) { animation.pause(); animation.currentTime = 0; }
      }
    }
  }).observe(document, { childList:true, subtree:true });
  window.__blockedWrites = JSON.parse(sessionStorage.getItem('activity-fixture-blocked-writes') || '[]');
  window.__emit = (type, data) => window.__activityStream.enqueue(new TextEncoder().encode(frame(type, data)));
  window.fetch = async (url, options) => {
    const pathname = new URL(url instanceof Request ? url.url : String(url), location.href).pathname;
    const method = String(options?.method || (url instanceof Request ? url.method : 'GET')).toUpperCase();
    if (pathname === '/uploads/activity-fixture.png' && method === 'GET') {
      window.__fixtureImageReads++;
      return new Response(Uint8Array.from(atob('${faviconPng}'), byte => byte.charCodeAt(0)), { headers:{ 'Content-Type':'image/png' } });
    }
    if (pathname === '/api/chat' && method === 'POST') {
      window.__activitySends.push(JSON.parse(options.body));
      return new Response(new ReadableStream({ start(controller) {
        window.__activityStream = controller;
        options.signal?.addEventListener('abort', () => {
          window.__activityAborts++;
          try { controller.error(new DOMException('Aborted', 'AbortError')); } catch {}
        }, { once:true });
      }, cancel() {} }), { headers:{ 'Content-Type':'text/event-stream' } });
    }
    if (pathname.startsWith('/api/') && !['GET','HEAD'].includes(method)) {
      window.__blockedWrites.push({ pathname, method });
      sessionStorage.setItem('activity-fixture-blocked-writes', JSON.stringify(window.__blockedWrites));
      return Response.json({ error:'Unexpected browser-fixture write' }, { status:405 });
    }
    return original(url, options);
  };
})();`;

const longPath = `session/research/${'a-very-long-directory-name/'.repeat(12)}reference.ts`;
const first = { call_id:'read-a', tool:'workspace__workspace_read', detail:longPath, input:{ path:longPath } };
const second = { call_id:'read-b', tool:'workspace__workspace_read', detail:'', input:{ path:'session/second.md' } };
const search = { call_id:'search', tool:'web_search', detail:'reference animation', input:{ query:'reference animation' } };
const searchResult = { results:[
  { title:'Fixture guide', url:'https://docs.example.org/guide' },
  { title:'Fixture reference', url:'https://docs.example.org/reference?mode=compact' },
  { title:'Fixture HTTP source', url:'http://lookup.example.org/reading?topic=private' },
  { title:'Fixture unavailable icon', url:'https://failed.example.org/reading' },
  { title:'Fixture private address', url:'https://127.0.0.1/private' },
] };
const untrusted = '<img src=x onerror="window.__activityInjected=true">';
const saved = { id:'activity-history', messages:[{ role:'assistant', content:'Saved activity fixture.', steps:[
  { id:'saved-ok', label:'workspace_read', detail:longPath, status:'done', input:{ path:longPath }, result:{ content:untrusted, results:[
    { title:'Saved public guide', url:'https://docs.example.org/saved' },
    { title:'Saved public lookup', url:'http://lookup.example.org/saved' },
    { title:'Previously unseen hidden source', url:'https://hidden.example.org/article?private=query' },
  ] } },
  { id:'saved-error', label:'web_search', detail:'offline query', status:'failed', result:{ error:'Fixture unavailable' } },
  { id:'saved-stop', label:'workspace_read', detail:'session/unfinished.md', status:'interrupted' },
  { id:'saved-legacy', tool:'legacy_inspector', args:{ path:'session/legacy.md' } },
  { id:'saved-active', label:'memory_search', detail:'old unfinished search', status:'active' },
] }] };

try {
  execFileSync('osascript', ['-e', 'set volume output muted true']);
  if (process.env.ACTIVITY_TREE_SHOTS) mkdirSync(process.env.ACTIVITY_TREE_SHOTS, { recursive:true });
  browser('open', 'about:blank');
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled:true });
  route('**/api/setup', { configured:true, profile:{ configured:true, name:'Test', language:'en', persona:'friendly', reply_length:'balanced', use_emoji:true, spontaneous:false }, languages:[], personas:[], reply_lengths:[], models:[], selected_model:'', keys_set:{} });
  route('**/api/sessions', { sessions:[{ id:'activity-history', title:'Saved activity fixture', count:1 }] });
  route('**/api/sessions/activity-history', saved);
  route('**/api/brain/models', { models:[{ id:'activity-model', label:'Activity model', context:200000 }], selected:'activity-model', default:'activity-model', thinking:'high', thinking_levels:['low','high'], available:true });
  route('**/api/models', { models:[{ id:'activity-model', label:'Activity model' }], selected:'activity-model', default:'activity-model', active:'activity-model', brain:'activity-model' });
  route('**/api/status', { mode:'test' });
  route('**/api/projects', { projects:[] });
  route('**/api/chat/context**', { parts:[], chars:0, dropped:0, history_limit:20 });
  route('**/api/workspace/info**', { root:'/fixture/workspace', session_path:'sessions/activity-fixture' });
  route('**/api/**', {});
  browser('open', `${origin}${path}#/chat`);

  socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once:true });
    socket.addEventListener('error', reject, { once:true });
  });
  const handleMessage = async event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Fetch.requestPaused') {
      const request = message.params;
      const url = new URL(request.request.url);
      if (url.origin === origin) {
        await respondToPausedRequest('Fetch.continueRequest', { requestId:request.requestId }, message.sessionId);
      } else if (faviconCandidate(url)) {
        const candidate = faviconCandidate(url);
        faviconRequests.push({ ...candidate, url:url.href, headers:request.request.headers });
        if (candidate.site === 'timeout.example.org') heldFavicons.push({ requestId:request.requestId, sessionId:message.sessionId, ...candidate });
        else {
          const succeeds = (['docs.example.org','fresh.example.org','hidden.example.org'].includes(candidate.site) && candidate.candidate === 'google')
            || (candidate.site === 'lookup.example.org' && candidate.candidate === 'ddg');
          await respondToPausedRequest('Fetch.fulfillRequest', { requestId:request.requestId,
            responseCode:succeeds ? 200 : 404,
            responseHeaders:[{ name:'Content-Type', value:'image/png' }, { name:'Cache-Control', value:'no-store' }],
            body:succeeds ? faviconPng : '',
          }, message.sessionId);
        }
      } else {
        unexpectedExternal.push(url.href);
        await respondToPausedRequest('Fetch.failRequest', { requestId:request.requestId, errorReason:'BlockedByClient' }, message.sessionId);
      }
      return;
    }
    const request = pending.get(message.id);
    if (!request) return;
    pending.delete(message.id);
    if (message.error) {
      const error = new Error(JSON.stringify(message.error));
      error.cdpError = message.error;
      request.reject(error);
    } else request.resolve(message.result);
  };
  socket.addEventListener('message', event => {
    // Keep asynchronous interception failures inside the main try/finally so
    // the fixture always closes its own browser, including on protocol errors.
    handleMessage(event).catch(error => { socketFailure = error; });
  });
  const target = (await cdp('Target.getTargets')).targetInfos.find(info => info.type === 'page' && info.url.startsWith(`${origin}${path}`));
  assert.ok(target, 'the isolated dashboard target must exist');
  cdpSession = (await cdp('Target.attachToTarget', { targetId:target.targetId, flatten:true })).sessionId;
  await cdp('Page.enable');
  await cdp('Fetch.enable', { patterns:[{ urlPattern:'https://*', requestStage:'Request' }] });
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source:init });
  evaluate("localStorage.setItem('claudeBotLang','en'); localStorage.setItem('claudeBotTheme','light'); localStorage.setItem('claudeBotConversationList','open'); true");
  browser('reload');
  browser('wait', '.prompt-bar__send');

  send('A controlled activity fixture.');
  assert.equal(evaluate('document.querySelectorAll("[data-agent-activity]").length'), 0, 'waiting for a reply must not fabricate tool actions');
  for (const step of [first, second, search]) emit('tool_start', step);
  browser('wait', row('read-a'));
  browser('wait', row('read-b'));
  browser('wait', row('search'));
  assert.equal(evaluate('document.querySelector("[data-tool-step=read-a] .chat-activity-tool-icon").dataset.solarIcon'), 'read');
  assert.equal(evaluate('document.querySelector("[data-tool-step=search] .chat-activity-tool-icon").dataset.solarIcon'), 'search');
  assert.equal(evaluate('document.querySelector("[data-activity-toggle] > svg").dataset.solarIcon'), 'chevron');
  browser('wait', '--fn', 'window.__activityArrivals.size === 3');
  const initialArrival = arrivalSnapshot('read-a');
  assert.equal(initialArrival.entering, true, 'only a newly discovered live branch enters');
  for (const name of arrivalNames) assert.ok(initialArrival.names.includes(name), `capture the ${name} phase`);
  const startFrame = arrivalFrame('read-a', 0);
  assert.equal(startFrame.trunk.scaleY, 0, 'the new segment begins undrawn');
  assert.equal(startFrame.elbow.scaleX, 0);
  assert.equal(startFrame.iconOpacity, 0);
  assert.ok(startFrame.strokes.length > 0, 'the actual tool icon contains normalized drawing geometry');
  assert.ok(startFrame.strokes.every(stroke => stroke.length === '1' && stroke.offset === 1));
  assert.ok(startFrame.text.length >= 3 && startFrame.text.every(text => text.opacity === 0));
  assert.equal(startFrame.followup.opacity, 0, 'sources and logs share the delayed label reveal');
  const trunkFrame = arrivalFrame('read-a', 90);
  assert.ok(trunkFrame.trunk.scaleY > 0 && trunkFrame.trunk.scaleY < 1, 'the vertical trunk visibly extends down before the next phase');
  assert.equal(trunkFrame.elbow.scaleX, 0, 'the elbow waits for the trunk');
  assert.equal(trunkFrame.iconOpacity, 0);
  assert.ok(trunkFrame.text.every(text => text.opacity === 0));
  shot('activity-arrival-trunk');
  const elbowFrame = arrivalFrame('read-a', 170);
  assert.ok(elbowFrame.elbow.scaleX > 0 && elbowFrame.elbow.scaleX < 1, 'the elbow grows toward the waiting icon');
  assert.equal(elbowFrame.iconOpacity, 0);
  const iconFrame = arrivalFrame('read-a', 320);
  assert.equal(iconFrame.trunk.scaleY, 1);
  assert.equal(iconFrame.elbow.scaleX, 1);
  assert.ok(iconFrame.iconOpacity > 0 && iconFrame.iconOpacity < 1, 'the icon fades up after its connection');
  assert.ok(iconFrame.strokes.every(stroke => stroke.offset > 0 && stroke.offset < 1), 'the icon strokes draw through a real intermediate frame');
  assert.ok(iconFrame.text.every(text => text.opacity === 0), 'labels wait for the icon drawing');
  assert.equal(iconFrame.followup.opacity, 0, 'early results cannot appear ahead of their tool label');
  shot('activity-arrival-icon');
  assert.equal(arrivalSnapshot('read-a').sameAnimations, true, 'frame seeking preserves the same animation objects');
  assert.equal(evaluate('document.querySelectorAll(".chat-activity-tree").length'), 1);
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step]").length'), 3, 'same-named concurrent calls keep separate rows');
  assert.equal(evaluate('document.querySelector(".chat-activity-tree").hasAttribute("data-running")'), true);
  assert.equal(evaluate('document.querySelectorAll("[data-activity-branches] ol > li[data-tool-step]").length'), 3, 'the branch body is an ordered list');
  assert.deepEqual(controls('[data-activity-toggle]'), { expanded:'true', linked:true });
  assert.ok(evaluate('document.querySelectorAll(".chat-activity-branch").length') >= 3, 'real actions form connected branches');
  assert.equal(evaluate(`[...document.querySelectorAll('.chat-activity-branch')].every(node => node.getAttribute('aria-hidden') !== 'true')`), true, 'connected action rows remain available to assistive technology');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('read-a')} .chat-activity-detail`)}).textContent`), longPath, 'paths remain actual tool metadata');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('search')} .chat-activity-detail`)}).textContent`), search.detail);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('read-a')} [data-tool-state]`)}).textContent`), 'Running');

  const readToggle = `${row('read-a')} button[data-tool-row]`;
  browser('focus', readToggle);
  browser('press', 'Enter');
  browser('wait', `${row('read-a')} [data-tool-details]`);
  assert.deepEqual(controls(readToggle), { expanded:'true', linked:true });
  emit('tool_progress', { ...first, result:{ content:'A partial read' } });
  browser('wait', '--fn', `document.querySelector(${JSON.stringify(`${row('read-a')} [data-tool-details]`)})?.textContent.includes('A partial read')`);
  assert.match(evaluate(`document.querySelector(${JSON.stringify(`${row('read-a')} [data-tool-details]`)}).textContent`), /Parameters.*Partial result/s);
  // A backend snapshot replaces the matching call, never appends another row.
  emit('tool_progress', { step:{ id:'read-a', label:first.tool, detail:longPath, status:'active', input:first.input, result:{ content:'Replacement snapshot' } } });
  browser('wait', '--text', 'Replacement snapshot');
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step]").length'), 3);
  assert.equal(controls(readToggle).expanded, 'true', 'progress replacement preserves an open row');
  emit('tool_progress', { ...second, detail:'session/second.md' });
  browser('wait', '--fn', 'document.querySelector("[data-tool-step=read-b] .chat-activity-detail")?.textContent === "session/second.md"');
  assert.equal(arrivalSnapshot('read-b').sameAnimations, true, 'late metadata shares its existing description animation instead of starting another fade');
  assert.equal(arrivalFrame('read-b', 320).text.find(text => text.text === 'session/second.md').opacity, 0, 'late metadata stays gated with the rest of its still-hidden label');
  assert.equal(arrivalSnapshot('read-a').sameNode, true, 'progress retains the live branch node');
  assert.equal(arrivalSnapshot('read-a').sameAnimations, true, `progress snapshots cannot restart a partly drawn icon or label: ${JSON.stringify(arrivalSnapshot('read-a'))}`);
  const progressFrame = arrivalFrame('read-a', 320);
  assert.deepEqual(progressFrame, iconFrame, 'progress leaves every sampled arrival phase at its current frame');
  const textFrame = arrivalFrame('read-a', 520);
  assert.equal(textFrame.iconOpacity, 1);
  assert.ok(textFrame.strokes.every(stroke => stroke.offset === 0));
  assert.ok(textFrame.text.every(text => text.opacity > 0 && text.opacity < 1 && text.y > 0), 'tool name, metadata and status fade together after drawing');
  assert.equal(textFrame.followup.opacity, textFrame.text[0].opacity, 'followup content keeps the same reveal clock as its label');
  shot('activity-arrival-text');
  // Let CSS complete normally so its animationend cleanup is exercised too.
  evaluate(`for (const record of window.__activityArrivals.values()) {
    const attached = new Set(record.node.getAnimations({ subtree:true }));
    for (const animation of record.animations) {
      if (attached.has(animation) && animation.currentTime < animation.effect.getComputedTiming().endTime) animation.play();
    }
  } true`);
  browser('wait', '--fn', '[...document.querySelectorAll("[data-tool-step]")].every(branch => !branch.hasAttribute("data-activity-enter"))');
  noArrival(['read-a','read-b','search'], 'completed entry phases release their CSS animation objects');
  const settledFrame = arrivalFrame('read-a', 800);
  assert.ok(settledFrame.text.every(text => text.opacity === 1 && text.y === 0));
  assert.ok(settledFrame.strokes.every(stroke => stroke.offset === 0));
  shot('activity-arrival-finished');

  evaluate(`window.__activityFoldSamples = []; window.__activityFoldRoot = document.querySelector('.chat-activity-tree');
    const started = performance.now(); window.__activityFoldStop = false;
    const sample = () => {
      const root = window.__activityFoldRoot, rect = root.getBoundingClientRect(), backing = getComputedStyle(root, '::before');
      window.__activityFoldSamples.push({ width:rect.width, height:rect.height,
        backingWidth:parseFloat(backing.width), backingHeight:parseFloat(backing.height),
        position:backing.position, animation:backing.animationName, transition:backing.transitionDuration,
        blur:backing.backdropFilter || backing.getPropertyValue('-webkit-backdrop-filter'),
        rowFilter:getComputedStyle(root.querySelector('.chat-activity-row')).filter,
        textFilter:getComputedStyle(root.querySelector('.chat-activity-tool-name')).filter });
      if (!window.__activityFoldStop && performance.now() - started < 12000) requestAnimationFrame(sample); else window.__activityFoldSampled = true;
    }; window.__activityFoldSampled = false; requestAnimationFrame(sample); true`);
  browser('focus', '[data-activity-toggle]');
  browser('press', 'Space');
  browser('wait', '--fn', 'document.querySelector("[data-activity-toggle]")?.getAttribute("aria-expanded") === "false"');
  assert.deepEqual(controls('[data-activity-toggle]'), { expanded:'false', linked:true });
  browser('wait', '--fn', 'document.querySelector("[data-activity-branches]").getAnimations().every(animation => animation.playState !== "running") && document.querySelector("[data-activity-branches]").getBoundingClientRect().height === 0');
  assert.equal(evaluate('document.querySelector("[data-activity-branches]").getBoundingClientRect().height'), 0);
  browser('press', 'Enter');
  browser('wait', '--fn', 'document.querySelector("[data-activity-toggle]")?.getAttribute("aria-expanded") === "true"');
  assert.equal(controls(readToggle).expanded, 'true', 'reopening the group preserves row logs');
  assert.equal(evaluate('document.activeElement.matches("[data-activity-toggle]")'), true, 'group toggling retains keyboard focus');
  settleTree('read-a', true);
  noArrival(['read-a','read-b','search'], 'manual close and reopen cannot replay branch creation');
  evaluate('window.__activityFoldStop = true; true');
  browser('wait', '--fn', 'window.__activityFoldSampled');
  const backingSamples = evaluate('window.__activityFoldSamples');
  assert.ok(new Set(backingSamples.map(sample => sample.height)).size > 2, 'observe real intermediate fold geometry');
  for (const sample of backingSamples) {
    assert.equal(sample.position, 'absolute', 'backing belongs to the tree instead of a detached viewport rectangle');
    assert.equal(sample.animation, 'none', 'the backing has no independent animation');
    assert.ok(sample.transition.split(',').every(value => parseFloat(value) === 0), 'the backing has no independent transition');
    assert.match(sample.blur, /blur\([\d.]+px\)/, 'only the backing softens the wallpaper');
    assert.equal(sample.rowFilter, 'none');
    assert.equal(sample.textFilter, 'none', 'row text stays sharp');
    assert.ok(Math.abs((sample.backingWidth - sample.width) - (backingSamples[0].backingWidth - backingSamples[0].width)) <= 1);
    assert.ok(Math.abs((sample.backingHeight - sample.height) - (backingSamples[0].backingHeight - backingSamples[0].height)) <= 1,
      'the backing follows every intermediate tree height without a residual rectangle');
  }

  emit('tool_done', { ...first, result:{ content:'Confirmed read' } });
  emit('tool_error', { ...second, result:{ error:'Fixture read failed' } });
  emit('tool_done', { ...search, result:searchResult });
  browser('wait', '--fn', 'document.querySelectorAll("[data-tool-status=active]").length === 0');
  assert.deepEqual(['read-a','read-b','search'].map(state), ['done','failed','done']);
  assert.equal(evaluate('document.querySelector("[data-tool-step=read-a] [data-tool-state] > svg").dataset.solarIcon'), 'check');
  assert.equal(evaluate('document.querySelector("[data-tool-step=read-b] [data-tool-state] > svg").dataset.solarIcon'), 'alert');
  assert.equal(evaluate('document.querySelector(".chat-activity-tree").hasAttribute("data-running")'), false, 'reply streaming alone must not mark completed tools as running');
  assert.equal(controls('[data-activity-toggle]').expanded, 'true', 'completed calls remain open until the assistant reply settles');
  await browserAsync('wait', '--fn', 'document.querySelector(".chat-activity-sites [data-site-icon=\\"docs.example.org\\"]")?.dataset.iconState === "ready" && document.querySelector(".chat-activity-sites [data-site-icon=\\"lookup.example.org\\"]")?.dataset.iconState === "ready" && document.querySelector(".chat-activity-sites [data-site-icon=\\"failed.example.org\\"]")?.dataset.iconState === "fallback"');
  assert.equal(evaluate('document.querySelectorAll(".chat-activity-sites [data-site-icon=\\"docs.example.org\\"]").length'), 1, 'two completed links from one site share one summary icon');
  const decoded = evaluate('document.querySelector(".chat-activity-sites [data-site-icon=\\"docs.example.org\\"] img")?.naturalWidth');
  assert.equal(decoded, 16, 'the fixture icon actually decodes');
  assert.deepEqual(faviconRequests.filter(request => request.site === 'docs.example.org').map(request => request.candidate), ['direct','google'], 'a blocked direct icon recovers through Google');
  assert.deepEqual(faviconRequests.filter(request => request.site === 'lookup.example.org').map(request => request.candidate), ['direct','google','ddg'], 'a second cache recovers after two failures');
  assert.deepEqual(faviconRequests.filter(request => request.site === 'failed.example.org').map(request => request.candidate), ['direct','google','ddg'], 'the finite chain exhausts into the local initial');
  assert.ok(faviconRequests.some(request => request.url === 'https://lookup.example.org/favicon.ico'), 'public HTTP source icons upgrade to HTTPS');
  assert.equal(evaluate('document.querySelector(".chat-activity-sites [data-site-icon=\\"failed.example.org\\"]").getBoundingClientRect().width'), 18, 'an error keeps the reserved icon size');
  assert.equal(evaluate('document.querySelector(".chat-activity-sites [data-site-icon=\\"failed.example.org\\"] img")'), null);
  assert.equal(evaluate('document.querySelector(".chat-activity-sites [data-site-icon=\\"127.0.0.1\\"]")?.dataset.iconState'), 'fallback', 'a private source gets a local fallback');
  evaluate('window.__loadedFallbackWrapper = document.querySelector(".chat-activity-sites [data-site-icon=\\"docs.example.org\\"]"); window.__loadedFallbackImage = window.__loadedFallbackWrapper.querySelector("img"); true');
  const docsRequests = faviconRequests.filter(request => request.site === 'docs.example.org').length;
  const updatedSearchResult = { results:searchResult.results.map((source, index) => index === 1 ? { ...source, url:'https://docs.example.org/updated-article?private=query' } : source) };
  emit('tool_progress', { step:{ id:'search', label:'web_search', status:'done', detail:search.detail, input:search.input, result:updatedSearchResult } });
  browser('wait', '--fn', 'document.querySelector(".chat-activity-sites [data-site-icon=\\"docs.example.org\\"]")?.dataset.siteUrl === "https://docs.example.org/updated-article?private=query"');
  assert.equal(evaluate('document.querySelector(".chat-activity-sites [data-site-icon=\\"docs.example.org\\"]") === window.__loadedFallbackWrapper'), true);
  assert.equal(evaluate('window.__loadedFallbackWrapper.querySelector("img") === window.__loadedFallbackImage && window.__loadedFallbackWrapper.dataset.iconState === "ready"'), true, 'same-origin article updates retain the loaded fallback image');
  assert.equal(faviconRequests.filter(request => request.site === 'docs.example.org').length, docsRequests, 'same-origin article updates do not retry a blocked direct candidate');
  emit('tool_progress', { step:{ id:'search', label:'web_search', status:'done', detail:search.detail, input:search.input, result:searchResult } });
  browser('wait', '--fn', 'document.querySelector(".chat-activity-sites [data-site-icon=\\"docs.example.org\\"]")?.dataset.siteUrl === "https://docs.example.org/reference?mode=compact"');
  assert.match(evaluate(`document.querySelector(${JSON.stringify(`${row('read-a')} [data-tool-details]`)}).textContent`), /Result.*Confirmed read/s);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('read-b')} [data-tool-state]`)}).textContent`), 'Failed');
  const connector = evaluate(`(() => {
    const branch = document.querySelector('[data-tool-step="read-a"]');
    const trunk = getComputedStyle(branch, '::before'), elbow = getComputedStyle(branch, '::after');
    return { trunk:trunk.backgroundColor, width:elbow.borderLeftWidth, style:elbow.borderLeftStyle, color:elbow.borderLeftColor };
  })()`);
  assert.notEqual(connector.trunk, 'rgba(0, 0, 0, 0)', 'the tree trunk resolves to a visible theme color');
  assert.notEqual(connector.trunk, 'transparent');
  assert.deepEqual({ width:connector.width, style:connector.style }, { width:'1px', style:'solid' }, 'each row has a drawn elbow');
  assert.notEqual(connector.color, 'rgba(0, 0, 0, 0)');
  const audit = JSON.parse(browser('a11y', '--selector', '.chat-activity-tree', '--json'));
  assert.equal(audit.data.counts.violations, 0, JSON.stringify(audit.data.violations));
  // Decorative wallpaper may stop automated contrast inference. Exercise the
  // same expanded live tree against a plain surface to catch muted-label drift.
  evaluate(`window.__activityAppearance = localStorage.getItem('claudeBotChatAppearance');
    localStorage.setItem('claudeBotChatAppearance', JSON.stringify({ ...JSON.parse(window.__activityAppearance || '{}'), background:'none' }));
    window.dispatchEvent(new StorageEvent('storage', { key:'claudeBotChatAppearance' })); true`);
  browser('wait', '.chat-conversation[data-wallpaper="none"]');
  const plainAudit = JSON.parse(browser('a11y', '--selector', '.chat-activity-tree', '--json'));
  assert.equal(plainAudit.data.counts.violations, 0, JSON.stringify(plainAudit.data.violations));
  evaluate(`if (window.__activityAppearance === null) localStorage.removeItem('claudeBotChatAppearance');
    else localStorage.setItem('claudeBotChatAppearance', window.__activityAppearance);
    window.dispatchEvent(new StorageEvent('storage', { key:'claudeBotChatAppearance' })); true`);
  browser('wait', '.chat-conversation[data-wallpaper="sky"]');
  readLabelFits();
  shot('activity-desktop');
  for (const width of [390,320]) {
    browser('set', 'viewport', String(width), '844');
    browser('wait', '.chat-phone-toolbar');
    overflow();
    readLabelFits();
    shot(`activity-${width}`);
  }
  captureIconDeadlines();
  browser('focus', readToggle);
  finish('The controlled calls finished.');
  browser('wait', '--text', 'The controlled calls finished.');
  assert.deepEqual(['read-a','read-b','search'].map(state), ['done','failed','done']);
  settleTree('read-a', false);
  assert.equal(evaluate('document.activeElement.isConnected'), true, 'settling never leaves focus on a detached control');
  assert.equal(evaluate(`document.activeElement.matches(${JSON.stringify(`${tree('read-a')} [data-activity-toggle]`)})`), true, 'automatic folding returns focused logs to their own tree header');
  shot('activity-completed-collapsed');
  await browserAsync('wait', '--fn', 'document.querySelector(".chat-source-strip [data-sources-toggle] [data-site-icon=\\"docs.example.org\\"]")?.dataset.iconState === "ready" && document.querySelector(".chat-source-strip [data-sources-toggle] [data-site-icon=\\"lookup.example.org\\"]")?.dataset.iconState === "ready"');
  assert.equal(evaluate('document.querySelectorAll(".chat-source-strip [data-sources-toggle] [data-site-icon=\\"docs.example.org\\"]").length'), 1);
  settleSources(false);
  assert.equal(controls('.chat-source-strip [data-sources-toggle]').linked, true);
  sourceBounds();
  for (let hop = 0; hop < 3; hop++) {
    evaluate('window.__fireAllIconDeadlines()');
    await browserAsync('--json', 'eval', '(async () => { await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); return true; })()');
  }
  assert.equal(evaluate('[...document.querySelectorAll(".chat-source-strip ol [data-site-icon=\\"docs.example.org\\"], .chat-source-strip ol [data-site-icon=\\"lookup.example.org\\"]")].every(icon => icon.dataset.iconState !== "fallback")'), true,
    'closed source rows must not exhaust recoverable candidates before an image load opportunity');
  restoreIconDeadlines();
  browser('focus', '.chat-source-strip [data-sources-toggle]');
  browser('press', 'Enter');
  settleSources(true);
  sourceBounds();
  await browserAsync('wait', '--fn', '[...document.querySelectorAll(".chat-source-strip ol [data-site-icon=\\"docs.example.org\\"]")].every(icon => icon.dataset.iconState === "ready") && document.querySelector(".chat-source-strip ol [data-site-icon=\\"lookup.example.org\\"]")?.dataset.iconState === "ready" && document.querySelector(".chat-source-strip ol [data-site-icon=\\"failed.example.org\\"]")?.dataset.iconState === "fallback"');
  assert.deepEqual(evaluate('[...document.querySelectorAll(".chat-source-strip ol a")].map(link => link.href)'), searchResult.results.map(source => source.url), 'distinct original source pages remain separate links');
  assert.equal(evaluate('document.querySelectorAll(".chat-source-strip ol [data-site-icon=\\"docs.example.org\\"]").length'), 2);
  assert.equal(evaluate('document.querySelector(".chat-source-strip ol [data-site-icon=\\"127.0.0.1\\"]").dataset.iconState'), 'fallback');
  assert.equal(evaluate('document.querySelector(".chat-source-strip ol [data-site-icon=\\"127.0.0.1\\"] img")'), null);
  assert.equal(evaluate('document.querySelector(".chat-source-strip ol [data-site-icon=\\"failed.example.org\\"]").getBoundingClientRect().width'), 18);
  assert.equal(evaluate('document.activeElement.matches(".chat-source-strip [data-sources-toggle]")'), true);
  browser('focus', `${tree('read-a')} [data-activity-toggle]`);
  browser('press', 'Enter');
  settleTree('read-a', true);
  overflow();
  shot('activity-reopened-sources');

  send('A controlled interruption.');
  assert.equal(controls(`${tree('read-a')} [data-activity-toggle]`).expanded, 'true', 'a manually reopened settled tree stays open during later turns');
  // A result may be the first log payload: the row changes from div to button.
  // That structural update settles the existing entry instead of replaying it
  // on the new icon and text nodes.
  evaluate('window.__activityPauseArrivals.add("late-log"); true');
  const lateLog = { call_id:'late-log', tool:'workspace_read', detail:'session/late-result.md' };
  emit('tool_start', lateLog);
  browser('wait', '--fn', 'window.__activityArrivals.has("late-log")');
  arrivalFrame('late-log', 320);
  assert.equal(evaluate('document.querySelector("[data-tool-step=late-log] [data-tool-row]").tagName'), 'DIV');
  emit('tool_progress', { ...lateLog, result:{ content:'A first log arrived during icon drawing' } });
  browser('wait', '--fn', 'document.querySelector("[data-tool-step=late-log] [data-tool-row]")?.tagName === "BUTTON"');
  assert.equal(arrivalSnapshot('late-log').sameNode, true, 'late log data retains its list item identity');
  noArrival(['late-log'], 'a structural result update reveals the replacement content without replay');
  const lateLogFrame = arrivalFrame('late-log', 320);
  assert.equal(lateLogFrame.iconOpacity, 1);
  assert.ok(lateLogFrame.strokes.every(stroke => stroke.offset === 0));
  assert.ok(lateLogFrame.text.every(text => text.opacity === 1 && text.y === 0));
  evaluate('window.__activityPauseArrivals.delete("late-log"); true');
  const pendingRead = { call_id:'stop-active', tool:'workspace_read', detail:longPath, input:{ path:longPath } };
  const completedRead = { call_id:'stop-done', tool:'workspace_read', detail:'session/complete.md', input:{ path:'session/complete.md' } };
  emit('tool_start', pendingRead);
  emit('tool_start', completedRead);
  emit('tool_done', { ...completedRead, result:{ content:'Already confirmed' } });
  browser('wait', row('stop-active'));
  evaluate('const stop = document.querySelector(".prompt-bar__send"); stop.click(); stop.click(); true');
  browser('wait', `${row('stop-active')}[data-tool-status=interrupted]`);
  assert.equal(state('stop-done'), 'done', 'Stop preserves confirmed outcomes');
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step=stop-active]").length'), 1, 'repeated Stop cannot duplicate activity');
  assert.equal(evaluate('document.querySelectorAll("[data-agent-activity][data-running]").length'), 0);
  assert.equal(evaluate('window.__activityAborts'), 1);
  settleTree('stop-active', false);

  // Image generation keeps its own honest pending surface, alongside ordinary
  // activity, rather than gaining a duplicate generic branch row.
  send('A mixed generation fixture.');
  emit('tool_start', { step:{ id:'paint', label:'image_generate', status:'active', detail:'', input:{ prompt:'A local fixture image' } } });
  emit('tool_start', { call_id:'mixed-search', tool:'web_search', detail:'fixture query', input:{ query:'fixture query' } });
  browser('wait', '[data-image-generation][data-state=generating]');
  browser('wait', row('mixed-search'));
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step=paint]").length'), 0);
  assert.equal(evaluate('document.querySelectorAll("[data-image-generation]").length'), 1);
  browser('click', '.prompt-bar__send');
  browser('wait', '[data-image-generation][data-state=interrupted]');
  browser('wait', `${row('mixed-search')}[data-tool-status=interrupted]`);
  settleTree('mixed-search', false);
  assert.equal(evaluate('window.__activitySends.length'), 3);
  // The runtime now preserves every tool reply ID. Keep the specialized image
  // surface mounted through its own completion and the final reply snapshot.
  send('A completed generation fixture.');
  const imageStep = { id:'paint-complete', label:'image_generate', status:'active', detail:'', input:{ prompt:'A deterministic fixture image' } };
  emit('tool_start', { step:imageStep });
  browser('wait', '[data-image-generation][data-state=generating]');
  evaluate('window.__completedImageCard = document.querySelector("[data-image-generation][data-state=generating]"); true');
  const completedImageStep = { ...imageStep, status:'done', result:{ provider:'codex', images:[{ url:'/uploads/activity-fixture.png', type:'image/png' }] } };
  emit('tool_done', { step:completedImageStep });
  browser('wait', '[data-image-generation][data-state=complete]');
  assert.equal(evaluate('document.querySelector("[data-image-generation][data-state=complete]") === window.__completedImageCard'), true);
  const imageReply = '![A deterministic fixture image](/uploads/activity-fixture.png)';
  emit('done', { reply:imageReply, session_id:'activity-fixture', emotion:'idle', mode:'test', model:'activity-model', tool_results:[],
    steps:[completedImageStep], parts:[{ type:'steps', ids:['paint-complete'] }, { type:'text', text:imageReply }] });
  browser('wait', '--fn', 'document.querySelector(".prompt-bar__send").disabled');
  assert.equal(evaluate('document.querySelector("[data-image-generation][data-state=complete]") === window.__completedImageCard'), true, 'settling preserves the existing generated image node');
  assert.equal(evaluate('window.__completedImageCard.querySelector("img").naturalWidth'), 16);
  assert.equal(evaluate('window.__fixtureImageReads'), 1, 'settling must not download the same private result again');
  assert.equal(evaluate('window.__activitySends.length'), 4);
  assert.deepEqual(evaluate('window.__blockedWrites'), []);
  assert.deepEqual(unexpectedExternal, [], 'no private source or unexpected external request leaves the browser fixture');
  assert.ok(faviconRequests.some(request => request.url === 'https://docs.example.org/favicon.ico'));
  assert.ok(faviconRequests.every(request => !('Referer' in request.headers) && !('referer' in request.headers)), 'favicons omit the conversation referrer');

  send('A bounded icon lifecycle fixture.');
  const edge = { id:'icons-edge', label:'web_search', detail:'icon lifecycle', status:'done', input:{ query:'icon lifecycle' }, result:{ results:[{ title:'Old article', url:'https://docs.example.org/old' }] } };
  emit('tool_done', { step:edge });
  await browserAsync('wait', '--fn', 'document.querySelector("[data-tool-step=icons-edge] [data-site-icon=\\"docs.example.org\\"]")?.dataset.iconState === "ready"');
  evaluate('window.__staleSiteImage = document.querySelector("[data-tool-step=icons-edge] [data-site-icon=\\"docs.example.org\\"] img"); true');
  const freshEdge = { ...edge, result:{ results:[{ title:'Fresh article', url:'https://fresh.example.org/new?keep=local' }] } };
  emit('tool_progress', { step:freshEdge });
  await browserAsync('wait', '--fn', 'document.querySelector("[data-tool-step=icons-edge] [data-site-icon=\\"fresh.example.org\\"]")?.dataset.iconState === "ready"');
  evaluate('window.__staleSiteImage.dispatchEvent(new Event("load")); window.__staleSiteImage.dispatchEvent(new Event("error")); true');
  assert.equal(evaluate('document.querySelector("[data-tool-step=icons-edge] [data-site-icon=\\"fresh.example.org\\"]").dataset.iconState'), 'ready', 'old image callbacks cannot alter the new domain');
  assert.equal(evaluate('document.querySelector("[data-tool-step=icons-edge] [data-site-icon=\\"fresh.example.org\\"] img").naturalWidth'), 16);
  assert.equal(evaluate('document.querySelector("[data-tool-step=icons-edge] [data-site-icon=\\"docs.example.org\\"]")'), null);

  // Capture only newly created six-second icon deadlines. UI timers keep their
  // real clock; manually firing each deadline proves a stalled request advances.
  captureIconDeadlines();
  emit('tool_progress', { step:{ ...edge, result:{ results:[{ title:'Stalled icon', url:'https://timeout.example.org/article?private=query' }] } } });
  browser('scrollintoview', '[data-tool-step=icons-edge]');
  for (const candidate of ['direct','google','ddg']) {
    await waitForFixture(() => heldFavicons.some(request => request.site === 'timeout.example.org' && request.candidate === candidate));
    await browserAsync('wait', '--fn', 'window.__faviconTimers.size > 0');
    evaluate('window.__fireIconDeadline()');
  }
  browser('wait', '--fn', 'document.querySelector("[data-tool-step=icons-edge] [data-site-icon=\\"timeout.example.org\\"]")?.dataset.iconState === "fallback"');
  assert.equal(evaluate('document.querySelector("[data-tool-step=icons-edge] [data-site-icon=\\"timeout.example.org\\"]").getBoundingClientRect().width'), 18);
  assert.equal(evaluate('window.__faviconTimers.size'), 0, 'the bounded chain leaves no icon deadline running');
  restoreIconDeadlines();
  for (const request of heldFavicons) {
    await respondToPausedRequest('Fetch.fulfillRequest', { requestId:request.requestId, responseCode:404, body:'' }, request.sessionId);
  }
  assert.deepEqual(faviconRequests.filter(request => request.site === 'timeout.example.org').map(request => request.candidate), ['direct','google','ddg']);
  assert.ok(faviconRequests.every(request => !request.url.includes('article') && !request.url.includes('private') && !request.url.includes('mode=')), 'cache requests carry only the validated hostname');
  finish('The icon lifecycle fixture finished.');
  browser('wait', '--text', 'The icon lifecycle fixture finished.');
  settleTree('icons-edge', false);
  assert.deepEqual(unexpectedExternal, []);
  assert.deepEqual(evaluate('window.__blockedWrites'), []);

  // Verify restored history with motion enabled first: reduced-motion CSS alone
  // would otherwise hide an accidental replay of an old call's entry animation.
  assert.equal(evaluate('matchMedia("(prefers-reduced-motion: reduce)").matches'), false);
  browser('click', '.chat-narrow-toolbar button[aria-label="Conversations"]');
  browser('wait', '[data-session-id="activity-history"]');
  browser('wait', '--fn', `(() => {
    const entry = document.querySelector('[data-session-id="activity-history"]');
    const title = [...entry.querySelectorAll('*')].find(node => !node.children.length && node.textContent.trim() === 'Saved activity fixture');
    if (!title) return false;
    for (let owner = title; owner; owner = owner.parentElement)
      if (owner.getAnimations().some(animation => animation.playState === 'running')) return false;
    const bounds = title.getBoundingClientRect();
    return entry.contains(document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2));
  })()`);
  browser('find', 'text', 'Saved activity fixture', 'click', '--exact');
  browser('wait', '--text', 'Saved activity fixture.');
  settleTree('saved-ok', false);
  noArrival(['saved-ok','saved-error','saved-stop','saved-legacy','saved-active'], 'normal-motion saved history starts with fully drawn, unanimated rows');
  browser('focus', '[data-activity-toggle]');
  browser('press', 'Enter');
  settleTree('saved-ok', true);
  noArrival(['saved-ok','saved-error','saved-stop','saved-legacy','saved-active'], 'opening normal-motion history reveals existing rows without a new-entry sequence');
  assert.equal(evaluate('["saved-ok","saved-error","saved-stop","saved-legacy","saved-active"].some(id => window.__activityArrivals.has(id))'), false,
    'the insertion observer must never see a hidden history entry animation');
  overflow();
  shot('activity-history-320-motion');

  // Reloaded legacy activity cannot turn unknown or unfinished outcomes into
  // success. Native reduced-motion emulation also checks the live tree path.
  await cdp('Emulation.setEmulatedMedia', { features:[
    { name:'prefers-color-scheme', value:'dark' }, { name:'prefers-reduced-motion', value:'reduce' },
  ] });
  evaluate("localStorage.setItem('claudeBotLang','uk'); localStorage.setItem('claudeBotTheme','dark'); true");
  browser('reload');
  browser('wait', '.chat-phone-toolbar');
  assert.equal(evaluate('matchMedia("(prefers-reduced-motion: reduce)").matches'), true);
  captureIconDeadlines();
  browser('click', '.chat-narrow-toolbar button[aria-label="Розмови"]');
  browser('wait', '[data-session-id="activity-history"]');
  browser('wait', '--fn', `(() => {
    const entry = document.querySelector('[data-session-id="activity-history"]');
    const title = [...entry.querySelectorAll('*')].find(node => !node.children.length && node.textContent.trim() === 'Saved activity fixture');
    if (!title) return false;
    for (let owner = title; owner; owner = owner.parentElement)
      if (owner.getAnimations().some(animation => animation.playState === 'running')) return false;
    const bounds = title.getBoundingClientRect();
    return entry.contains(document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2));
  })()`);
  browser('find', 'text', 'Saved activity fixture', 'click', '--exact');
  browser('wait', '--text', 'Saved activity fixture.');
  assert.equal(controls('[data-activity-toggle]').expanded, 'false', 'saved history starts collapsed');
  settleTree('saved-ok', false);
  settleSources(false);
  sourceBounds();
  await browserAsync('wait', '--fn', 'document.querySelector(".chat-source-strip [data-sources-toggle] [data-site-icon=\\"docs.example.org\\"]")?.dataset.iconState === "ready" && document.querySelector(".chat-source-strip [data-sources-toggle] [data-site-icon=\\"lookup.example.org\\"]")?.dataset.iconState === "ready"');
  assert.equal(evaluate('document.querySelector(".chat-source-strip [data-sources-toggle] [data-site-icon=\\"hidden.example.org\\"]")'), null, 'the third phone site has no visible summary icon to warm its cache');
  for (let hop = 0; hop < 3; hop++) {
    evaluate('window.__fireAllIconDeadlines()');
    await browserAsync('--json', 'eval', '(async () => { await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); return true; })()');
  }
  assert.equal(evaluate('document.querySelector(".chat-source-strip ol [data-site-icon=\\"hidden.example.org\\"]").dataset.iconState !== "fallback"'), true,
    'an unseen hidden history icon cannot exhaust before becoming visible');
  restoreIconDeadlines();
  browser('focus', '.chat-source-strip [data-sources-toggle]');
  browser('press', 'Enter');
  settleSources(true);
  sourceBounds();
  await browserAsync('wait', '--fn', 'document.querySelector(".chat-source-strip ol [data-site-icon=\\"hidden.example.org\\"]")?.dataset.iconState === "ready"');
  browser('focus', '[data-activity-toggle]');
  browser('press', 'Enter');
  settleTree('saved-ok', true);
  browser('wait', row('saved-legacy'));
  assert.deepEqual(['saved-ok','saved-error','saved-stop','saved-legacy','saved-active'].map(state), ['done','failed','interrupted','interrupted','interrupted']);
  assert.equal(evaluate('document.querySelectorAll("[data-tool-step]").length'), 5);
  noArrival(['saved-ok','saved-error','saved-stop','saved-legacy','saved-active'], 'restored history has no live entry marker or drawing animation');
  assert.equal(evaluate('document.querySelectorAll("[data-agent-activity][data-running]").length'), 0);
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('saved-stop')} [data-tool-state]`)}).textContent`), 'Перервано · завершення не підтверджено');
  browser('focus', `${row('saved-ok')} button[data-tool-row]`);
  browser('press', 'Enter');
  browser('wait', `${row('saved-ok')} [data-tool-details]`);
  assert.match(evaluate(`document.querySelector(${JSON.stringify(`${row('saved-ok')} [data-tool-details]`)}).textContent`), /Параметри.*Результат/s);
  assert.equal(evaluate(`JSON.parse([...document.querySelectorAll(${JSON.stringify(`${row('saved-ok')} [data-tool-details] pre`)})].at(-1).textContent).content`), untrusted);
  assert.equal(evaluate('window.__activityInjected === undefined'), true, 'tool payloads remain inert text');
  assert.equal(evaluate('document.querySelectorAll("[data-tool-details] img").length'), 0);
  assert.notEqual(evaluate(`document.querySelector(${JSON.stringify(`${row('saved-stop')} [data-tool-row]`)}).tagName`), 'BUTTON', 'rows without input/result expose no empty log button');
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(`${row('saved-stop')} [data-tool-row]`)}).getAttribute('aria-expanded')`), null);
  overflow();
  shot('activity-history-320-reduced');
  assert.equal(evaluate('window.__activitySends.length'), 0, 'restoring history must not send a new request');
  assert.deepEqual(evaluate('window.__blockedWrites'), []);

  send('A reduced-motion fixture.');
  emit('tool_start', { call_id:'reduced-read', tool:'workspace_read', detail:longPath, input:{ path:longPath } });
  browser('wait', row('reduced-read'));
  assert.equal(evaluate(`document.querySelector(${JSON.stringify(row('reduced-read'))}).getAnimations({ subtree:true }).filter(animation => animation.playState === 'running').length`), 0, 'reduced motion suppresses entry and ongoing branch animation');
  const reducedFrame = evaluate(`(() => {
    const branch = document.querySelector(${JSON.stringify(row('reduced-read'))});
    const description = branch.querySelector('.chat-activity-description');
    return { icon:Number(getComputedStyle(branch.querySelector('.chat-activity-tool-icon')).opacity),
      strokes:[...branch.querySelectorAll('.chat-activity-tool-icon [pathLength]')].map(shape => parseFloat(getComputedStyle(shape).strokeDashoffset)),
      labels:[...branch.querySelectorAll('.chat-activity-tool-name, .chat-activity-detail, .chat-activity-state')].map(node => Number(getComputedStyle(node).opacity) * Number(getComputedStyle(description).opacity)) };
  })()`);
  assert.equal(reducedFrame.icon, 1, 'reduced motion reveals the complete icon immediately');
  assert.ok(reducedFrame.strokes.every(offset => offset === 0), 'reduced motion never leaves partially drawn strokes');
  assert.ok(reducedFrame.labels.every(opacity => opacity === 1), 'reduced motion exposes every label immediately');
  noArrival(['reduced-read'], 'reduced motion removes the live entry and direct SVG stroke gates');
  overflow();
  browser('click', '.prompt-bar__send');
  browser('wait', `${row('reduced-read')}[data-tool-status=interrupted]`);
  settleTree('reduced-read', false);
  await cdp('Emulation.setEmulatedMedia', { features:[
    { name:'prefers-color-scheme', value:'dark' }, { name:'prefers-reduced-motion', value:'reduce' },
    { name:'prefers-reduced-transparency', value:'reduce' },
  ] });
  assert.equal(evaluate('matchMedia("(prefers-reduced-transparency: reduce)").matches'), true);
  await browserAsync('--json', 'eval', '(async () => { await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); return true; })()');
  const transparencyRules = evaluate(`(() => {
    const found = [];
    const visit = rule => {
      if (rule.conditionText?.includes('prefers-reduced-transparency') && rule.cssText.includes('.chat-activity-tree'))
        found.push({ condition:rule.conditionText, matches:matchMedia(rule.conditionText).matches, css:rule.cssText });
      if (rule.cssRules) [...rule.cssRules].forEach(visit);
    };
    for (const sheet of document.styleSheets) { try { [...sheet.cssRules].forEach(visit); } catch {} }
    return found;
  })()`);
  assert.ok(transparencyRules.some(rule => rule.matches), 'the loaded tree transparency media rule matches the native emulation');
  browser('wait', '--fn', `getComputedStyle(document.querySelector(${JSON.stringify(tree('reduced-read'))}), '::before').backdropFilter === 'none'`);
  const reducedBacking = evaluate(`(() => {
    const backing = getComputedStyle(document.querySelector(${JSON.stringify(tree('reduced-read'))}), '::before');
    return { blur:backing.backdropFilter || backing.getPropertyValue('-webkit-backdrop-filter'), color:backing.backgroundColor };
  })()`);
  assert.equal(reducedBacking.blur, 'none', 'reduced transparency uses a stationary solid backing');
  assert.notEqual(reducedBacking.color, 'rgba(0, 0, 0, 0)');
  send('A composer focus fixture.');
  emit('tool_start', { call_id:'composer-read', tool:'workspace_read', detail:'session/focus.md', input:{ path:'session/focus.md' } });
  browser('wait', row('composer-read'));
  assert.equal(evaluate('document.activeElement.matches(".prompt-bar textarea")'), true);
  emit('tool_done', { call_id:'composer-read', tool:'workspace_read', result:{ content:'Confirmed focus fixture' } });
  finish('The composer kept its focus.');
  browser('wait', '--text', 'The composer kept its focus.');
  settleTree('composer-read', false);
  assert.equal(evaluate('document.activeElement.matches(".prompt-bar textarea") && document.activeElement.isConnected'), true, 'automatic folding preserves focus owned by the composer');
  assert.equal(controls(`${tree('saved-ok')} [data-activity-toggle]`).expanded, 'true', 'manually reopened history survives later settled replies');
  assert.deepEqual(evaluate('window.__blockedWrites'), []);
  assert.deepEqual(unexpectedExternal, []);
  if (socketFailure) throw socketFailure;
  console.log('PASS: real SSE concurrency/progress/snapshots/outcomes, settle/Stop/history folding and manual reopen, keyboard/log state, stationary backing, sampled trunk/elbow/icon/text choreography without progress/reopen/history replay, decoded/fallback favicons and distinct source links, no fabricated activity, separate images, inert payloads, en/uk, desktop/390/320px, native reduced motion/transparency; no server mutations or external network');
} catch (error) {
  if (socketFailure) console.error('Interception failure:', socketFailure);
  try {
    console.error(evaluate('({ text:document.body.innerText.slice(-2200), states:[...document.querySelectorAll("[data-tool-step]")].map(node => ({ id:node.dataset.toolStep, status:node.dataset.toolStatus, entry:node.hasAttribute("data-activity-enter"), animations:node.getAnimations({ subtree:true }).filter(animation => window.__activityArrivalNames.has(animation.animationName)).map(animation => ({ name:animation.animationName, state:animation.playState, time:animation.currentTime, style:getComputedStyle(animation.effect.target, animation.effect.pseudoElement).animationName })) })), blockedWrites:window.__blockedWrites })'));
    shot('activity-failure');
  } catch { /* Preserve the original failure if the browser itself has closed. */ }
  throw error;
} finally {
  socket?.close();
  browser('close');
}
