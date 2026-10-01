/** Isolated operator UI regression. Every write is intercepted in this browser. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `control-ui-test-${process.pid}`;
const origin = (process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8117').replace(/\/$/, '');
const dashboard = origin.endsWith('/static/dash') ? `${origin}/` : `${origin}/dash/`;
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', code)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const button = (name) => browser('find', 'role', 'button', 'click', '--name', name, '--exact');
const navigate = (page, suffix = '') => {
  evaluate(`location.hash = '#/${page}${suffix}'`);
  browser('wait', `[data-control-page="${page}"]`);
};
const now = Date.UTC(2026, 8, 30, 12, 0);
const agents = { agents: [{ id: 'main', name: 'Desk companion', default: true, model: 'openai/gpt-6-luna',
  fallbacks: ['regolo/gpt-oss-120b'], runtime: 'codex', thinking: 'off', workspace_configured: true },
  { id: 'work', name: 'Workshop', default: false, model: 'regolo/gpt-oss-120b', fallbacks: [],
    runtime: 'codex', thinking: 'high', workspace_configured: true }] };
const sessions = { sessions: [
  { id: 'session-one', agent: 'main', source: 'custom', model: 'gpt-6-luna', provider: 'openai',
    updated_at: now, active: true, status: 'running', tokens: 500, tokens_fresh: true, context_window: 200000 },
  { id: 'session-two', agent: 'main', source: 'heartbeat', model: 'gpt-oss-120b', provider: 'regolo',
    updated_at: null, active: false, status: 'queued', tokens: 900, tokens_fresh: false, context_window: 128000 },
], total: 51, has_more: true, next_offset: 50, offset: 0 };
const secondPage = { sessions: [{ ...sessions.sessions[0], id: 'last-session', active: false, status: 'done' }],
  total: 51, has_more: false, next_offset: null, offset: 50 };
const jobs = { jobs: [
  { id: 'morning-job', name: 'Morning notes', agent: 'main', enabled: true,
    schedule: { kind: 'cron', expression: '0 9 * * *', timezone: 'Europe/Berlin' },
    next_run: now + 1000, last_run: now, last_status: 'ok', running: false, revision: 'r1' },
  { id: 'paused-job', name: 'Task review', agent: 'work', enabled: false,
    schedule: { kind: 'every', every_ms: 3600000 }, next_run: null, last_run: null,
    last_status: null, running: false, revision: 'r2' },
], scheduler_enabled: true, total: 2, has_more: false, next_offset: null, offset: 0 };
const channels = { channels: [{ id: 'channel-one', channel: 'telegram', label: 'Telegram',
  enabled: true, configured: true, running: true, connected: null, has_error: false,
  last_inbound: now, last_outbound: null }], updated_at: now };

try {
  browser('open', `${dashboard}#/agents`);
  route('**/api/auth/config', { disabled: true });
  route('**/api/status', { openclaw: true, mode: 'openclaw' });
  route('**/api/models', { models: [], selected: '', active: 'fixture', default: '' });
  route('**/api/brain/models', { models: [], selected: '', default: '', available: false });
  route('**/api/setup', { configured: true, profile: { configured: true, name: '', language: 'en', persona: 'friendly',
    persona_custom: '', greeting: '', reply_length: 'normal', use_emoji: false, spontaneous: false },
    languages: [], personas: [], reply_lengths: [], models: [], selected_model: '', keys_set: { omni: false, openclaw: false } });
  route('**/api/openclaw/settings', { available: true, fields: [] });
  route('**/api/openclaw/control/agents?*', agents);
  route('**/api/openclaw/control/sessions?offset=0*', sessions);
  route('**/api/openclaw/control/sessions?offset=50*', secondPage);
  route('**/api/openclaw/control/jobs?*', jobs);
  route('**/api/openclaw/control/channels?*', channels);
  route('**/api/openclaw/control/jobs/*/runs?*', { runs: [{ at: now, status: 'skipped', duration_ms: 0, model: '', provider: '' }], has_more: false });
  // These routes remain installed even if a page reload destroys the recorder.
  route('**/api/openclaw/control/jobs/*/enabled', { ok: true });
  route('**/api/openclaw/control/jobs', { ok: true, id: 'created-fixture' });
  browser('set', 'viewport', '1440', '900');
  browser('reload');
  browser('wait', '[data-agent="main"]');
  evaluate("document.documentElement.lang='en'; localStorage.setItem('claudeBotLang','en')");
  navigate('sessions'); navigate('agents'); browser('wait', '[data-agent="main"]');
  assert.equal(evaluate('document.querySelectorAll("[data-agent]").length'), 2);
  browser('fill', 'input[aria-label="Search this page"]', 'missing');
  browser('wait', '--text', 'No matching results');
  browser('fill', 'input[aria-label="Search this page"]', 'main');
  browser('wait', '[data-agent="main"]');
  browser('focus', 'a[href="#/settings?tab=brain"]'); browser('press', 'Enter');
  browser('wait', '[data-section="settings"]'); browser('wait', '--text', 'Brain');
  assert.equal(evaluate('document.querySelector("main h1").innerText'), 'Brain');
  evaluate("location.hash='#/settings?tab=profile'");
  browser('wait', '--text', 'Personality');
  assert.equal(evaluate('document.querySelector("main h1").innerText'), 'Personality');
  navigate('agents'); browser('wait', '[data-agent="main"]');
  browser('click', '[data-agent="main"] a[href^="#/sessions"]');
  browser('wait', '[data-gateway-session="session-one"]');
  assert.equal(evaluate('document.querySelector("select").value'), 'main');
  assert.equal(evaluate('document.querySelectorAll("[role=meter]").length'), 1);
  assert.ok(evaluate('document.body.innerText.includes("Token count needs refresh")'));
  assert.ok(evaluate('document.body.innerText.includes("Queued")'));
  button('Next'); browser('wait', '[data-gateway-session="last-session"]');
  button('Previous'); browser('wait', '[data-gateway-session="session-one"]');
  console.log('PASS: agents, deep links, context freshness, queued state and pagination');

  navigate('automation'); browser('wait', '[data-job="morning-job"]');
  // Record writes without allowing them to escape the mocked network routes.
  evaluate(`window.__controlWrites=[]; const originalFetch=window.fetch.bind(window);
    window.fetch=async (input,init)=>{ const url=String(input);
      if(init?.method==='POST' && url.includes('/api/openclaw/control/jobs'))
        window.__controlWrites.push({url,body:JSON.parse(init.body)});
      return originalFetch(input,init); };`);
  browser('click', '[data-job="morning-job"] button[aria-expanded]');
  browser('wait', '[role="region"]'); browser('wait', '--text', 'Skipped');
  button('Pause'); browser('wait', '--text', 'Schedule updated.');
  assert.deepEqual(evaluate('window.__controlWrites[0].body'), { enabled: false, revision: 'r1' });
  button('New job'); browser('wait', '#job-name');
  assert.equal(evaluate('document.activeElement.id'), 'job-name');
  browser('fill', '#job-name', 'Evening review'); browser('fill', '#job-task', 'Review my unfinished tasks');
  // Chromium's segmented time input needs a native value setter plus the
  // input event; agent-browser's text fill can leave React's draft unchanged.
  evaluate(`const time=document.querySelector('#job-time');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(time,'18:45');
    time.dispatchEvent(new Event('input',{bubbles:true}));`);
  browser('fill', '#job-timezone', 'Europe/Berlin');
  button('Create paused job'); browser('wait', '--text', 'Job created and paused.');
  const create = evaluate('window.__controlWrites.find(write=>write.url.endsWith("/jobs"))');
  assert.equal(create.body.hour, 18); assert.equal(create.body.minute, 45);
  assert.equal(create.body.timezone, 'Europe/Berlin'); assert.equal(create.body.kind, 'daily');
  assert.equal(evaluate('document.querySelector("[data-job-form]")'), null);
  assert.ok(evaluate('document.activeElement.innerText.includes("New job")'));
  assert.equal(evaluate('window.__controlWrites.length'), 2);
  navigate('channels'); browser('wait', '[data-channel="telegram"]');
  assert.ok(evaluate('document.querySelector("[data-channel]").innerText.includes("Not reported")'));
  assert.ok(!evaluate('document.querySelector("[data-channel]").innerText.includes("Connected")'));
  console.log('PASS: isolated pause/create actions, focus restoration, run history and unknown connectivity');

  for (const width of [320, 390, 768, 1180, 1440]) {
    browser('set', 'viewport', String(width), '900');
    for (const page of ['agents', 'sessions', 'automation', 'channels']) {
      navigate(page); browser('wait', `[data-control-page="${page}"]`);
      assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true, `${page} at ${width}`);
      assert.equal(evaluate('document.querySelector("[data-control-page]").scrollWidth <= document.querySelector("[data-control-page]").clientWidth'), true, `${page} content at ${width}`);
    }
  }
  console.log('PASS: four pages at 320/390/768/1180/1440px');
  for (const width of [768, 1180]) {
    browser('set', 'viewport', String(width), '600');
    for (const side of ['top', 'left', 'right', 'bottom']) {
      evaluate(`localStorage.setItem('claudeBotDockSide','${side}')`); browser('reload');
      browser('wait', '[data-channel]'); browser('wait', '.dock-item');
      const scrolls = side === 'left' || side === 'right' || side === 'top' && width === 768;
      browser('wait', '--fn', `document.querySelector('.dock-navigation')?.dataset.overflow === '${scrolls}'`);
      assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
      const items = evaluate('document.querySelectorAll(".dock-item").length');
      assert.equal(items, 14);
      for (let index = 0; index < items; index++) {
        evaluate(`document.querySelectorAll('.dock-item')[${index}].focus()`);
        browser('wait', '--fn', `(()=>{const item=document.querySelectorAll('.dock-item')[${index}];
          const r=item.getBoundingClientRect(),p=item.closest('.dock-panel').getBoundingClientRect();
          const hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);
          return r.top>=p.top-1&&r.bottom<=p.bottom+1&&r.left>=p.left-1&&r.right<=p.right+1&&(item===hit||item.contains(hit));})()`);
        const target = evaluate(`(()=>{const item=document.querySelectorAll('.dock-item')[${index}];
          const r=item.getBoundingClientRect(),p=item.closest('.dock-panel').getBoundingClientRect();
          const hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);
          return {height:r.height,visible:r.top>=p.top-1&&r.bottom<=p.bottom+1&&r.left>=p.left-1&&r.right<=p.right+1&&r.top>=0&&r.bottom<=innerHeight,
            reachable:item===hit||item.contains(hit)};})()`);
        assert.ok(target.visible && target.reachable, `dock target ${index} at ${width}/${side}`);
        if (side === 'left' || side === 'right') assert.equal(target.height, 44);
      }
      if (side === 'top' && scrolls) {
        evaluate(`document.querySelector('.dock-panel').scrollLeft=0;
          document.querySelector('.dock-navigation').dispatchEvent(new WheelEvent('wheel',{deltaY:160,bubbles:true,cancelable:true}));`);
        assert.ok(evaluate("document.querySelector('.dock-panel').scrollLeft>0"));
      }
    }
  }
  console.log('PASS: all 14 dock targets on four edges at 768/1180 × 600px');
  evaluate("localStorage.setItem('claudeBotDockSide','bottom')"); browser('reload'); browser('wait', '[data-channel]');
  browser('set', 'viewport', '1440', '900');
  for (const theme of ['light', 'dark']) {
    evaluate(`document.documentElement.dataset.theme='${theme}'`);
    for (const [page, selector] of [['agents', '[data-agent]'], ['sessions', '[data-gateway-session]'], ['automation', '[data-job]'], ['channels', '[data-channel]']]) {
      navigate(page); browser('wait', selector);
      const audit = JSON.parse(browser('a11y', '--selector', '[data-control-page]', '--tags', 'wcag2a,wcag2aa', '--json'));
      assert.equal(audit.data.counts.violations, 0, `${page} accessibility in ${theme}: ${JSON.stringify(audit.data.violations)}`);
    }
  }
  console.log('PASS: scoped axe checks on all four pages in both themes');
  for (const lang of ['uk', 'en']) {
    evaluate(`document.documentElement.lang='${lang}'`);
    navigate('channels');
    navigate('agents'); browser('wait', '[data-agent="main"]');
    assert.ok(evaluate(`document.querySelector('[data-control-page]').innerText.includes(${JSON.stringify(lang === 'uk' ? 'Основна модель' : 'Primary model')})`));
  }
  evaluate(`window.__channelCode=200;
    const beforeChannelState=window.fetch;
    window.fetch=(input,init)=>String(input).includes('/api/openclaw/control/channels')
      ? Promise.resolve(new Response(JSON.stringify(window.__channelCode===200 ? {channels:[],updated_at:${now}} : {detail:'gateway_unavailable'}),
          {status:window.__channelCode,headers:{'Content-Type':'application/json'}}))
      : beforeChannelState(input,init);`);
  navigate('channels'); button('Refresh'); browser('wait', '--text', 'No channel accounts returned');
  evaluate('window.__channelCode=502');
  button('Refresh'); browser('wait', '--text', 'The last refresh failed');
  navigate('agents'); browser('wait', '[data-agent="main"]');
  evaluate(`const beforePermissionLoss=window.fetch;
    window.fetch=(input,init)=>String(input).includes('/api/openclaw/control/agents')
      ? Promise.resolve(new Response(JSON.stringify({detail:'operator_required'}),{status:403,headers:{'Content-Type':'application/json'}}))
      : beforePermissionLoss(input,init);`);
  button('Refresh'); browser('wait', '--text', 'This page is available to the gateway operator');
  assert.equal(evaluate('document.querySelectorAll("[data-agent]").length'), 0);
  console.log('PASS: both languages, empty state and stale refresh feedback');
  console.log('PASS: permission loss hides cached operator metadata');
} catch (error) {
  console.log(browser('snapshot', '-i'));
  throw error;
} finally {
  browser('close');
}
