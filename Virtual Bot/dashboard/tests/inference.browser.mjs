/** Isolated browser fixtures exercise reporting without changing the user's bot. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `inference-test-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:18103';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8', timeout: 45000 });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', code)).data.result;
const route = (url, body) => {
  browser('network', 'unroute', url);
  browser('network', 'route', url, '--body', JSON.stringify(body));
};
const cost = { input: 10000, output: 1000, cacheRead: 40000, cacheWrite: 0, totalTokens: 51000,
  totalCost: .0075, inputCost: .001, outputCost: .0025, cacheReadCost: .004, cacheWriteCost: 0,
  missingCostEntries: 0, noCacheCost: .0085 };
const sid = 'a'.repeat(64);
const data = { available: true, days: 30, start_date: '2026-09-01', end_date: '2026-09-30',
  updated_at: Date.now(), indexing: false, totals: cost, replies: 4, errors: 1, tool_calls: 2,
  providers: [
    { ...cost, provider: 'openai', replies: 4, auth: 'oauth', quota: { name: 'OpenAI', plan: 'plus', windows: [
      { label: '5h', used_percent: 86, reset_at: Date.now() + 3600000 },
      { label: 'Week', used_percent: 43, reset_at: Date.now() + 86400000 },
    ] } },
    { ...cost, totalCost: 0, inputCost: 0, outputCost: 0, cacheReadCost: 0, missingCostEntries: 2, provider: 'nvidia', replies: 2, auth: 'api_key', quota: null },
  ],
  models: [
    { ...cost, provider: 'openai', model: 'gpt-6-luna', replies: 4 },
    { ...cost, totalCost: 0, missingCostEntries: 2, provider: 'nvidia', model: 'openai/gpt-oss-20b', replies: 2 },
  ],
  daily: [{ date: '2026-09-29', tokens: 20000, cost: .003 }, { date: '2026-09-30', tokens: 31000, cost: .0045 }],
  daily_models: [{ date: '2026-09-30', provider: 'nvidia', tokens: 500, cost: 0 }],
  sessions: [
    { id: sid, provider: 'openai', model: 'gpt-6-luna', updated_at: Date.now(), replies: 4, errors: 1, usage: cost,
      models: [{ provider: 'openai', model: 'gpt-6-luna' }, { provider: 'nvidia', model: 'openai/gpt-oss-20b' }] },
    { id: 'b'.repeat(64), provider: 'regolo', model: 'gpt-oss-120b', updated_at: Date.now(), replies: 0, errors: 0, usage: null, models: [] },
  ], sessions_limited: false,
};
const inferences = { available: true, limited: false, inferences: [
  { id: '1', timestamp: Date.now(), provider: 'nvidia', model: 'openai/gpt-oss-20b', status: 'tool',
    input: 100, output: 20, cacheRead: 50, cacheWrite: 0, totalTokens: 170, cost: null },
  { id: '2', timestamp: Date.now(), provider: 'openai', model: 'gpt-6-luna', status: 'ok',
    input: null, output: null, cacheRead: null, cacheWrite: null, totalTokens: null, cost: null },
] };
try {
  browser('open', 'about:blank');
  route('**/api/auth/config', { disabled: true });
  route('**/api/brain/models', { models: [], selected: '', default: '', available: false });
  route('**/api/status', { mode: 'openclaw', openclaw: true, anthropic: false, vision: false, display: false });
  route('**/api/openclaw/analytics?**', data);
  route('**/api/openclaw/analytics/inferences?**', inferences);
  browser('set', 'viewport', '1440', '1000');
  browser('open', `${origin}${path}#/inference`);
  browser('wait', '[data-inference-panel] table');
  assert.match(evaluate('document.querySelector("[data-inference-panel]").innerText'), /gpt-6-luna/);
  assert.equal(evaluate('document.querySelectorAll("[data-inference-panel] table").length'), 2);

  // Filters use every model in the session, rather than just its chosen primary.
  browser('select', 'select[aria-label="Provider"], select[aria-label="Провайдер"]', 'nvidia');
  const modelNames = evaluate('[...document.querySelectorAll("[data-inference-panel] table:first-of-type tbody tr")].map(x=>x.innerText)');
  assert.ok(modelNames.some((name) => name.includes('gpt-oss-20b')));
  browser('click', `button[aria-label="Inspect session aaaaaaaa"], button[aria-label="Переглянути сесію aaaaaaaa"]`);
  browser('wait', '[role="dialog"] tbody tr');
  assert.equal(evaluate('document.querySelectorAll("[role=dialog] tbody tr").length'), 1);
  assert.match(evaluate('document.querySelector("[role=dialog]").innerText'), /nvidia/);
  assert.ok(!evaluate('document.querySelector("[role=dialog]").innerText').includes('$0.00'));
  browser('press', 'Escape');
  assert.equal(evaluate('document.querySelectorAll("[role=dialog]").length'), 0);
  assert.ok(evaluate('document.activeElement.getAttribute("aria-label")').includes('aaaaaaaa'));

  for (const width of [320, 390, 768, 1180, 1440]) {
    browser('set', 'viewport', String(width), '900');
    assert.ok(evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), `page overflow at ${width}`);
    assert.ok(evaluate('document.querySelector("[data-inference-panel]").scrollWidth <= document.querySelector("[data-inference-panel]").clientWidth + 1'), `panel overflow at ${width}`);
  }
  browser('set', 'viewport', '390', '900');
  browser('click', `button[aria-label="Inspect session aaaaaaaa"], button[aria-label="Переглянути сесію aaaaaaaa"]`);
  browser('wait', '[role="dialog"] tbody tr');
  assert.ok(evaluate('document.querySelector("[role=dialog]").getBoundingClientRect().width <= innerWidth'));
  browser('press', 'Escape');

  browser('select', 'select[aria-label="Provider"], select[aria-label="Провайдер"]', '');
  browser('fill', 'input[aria-label="Search sessions"], input[aria-label="Пошук сесій"]', 'no-such-model');
  assert.match(evaluate('document.querySelector("[data-inference-panel]").innerText'), /No matching sessions|Відповідних сесій немає/);
  browser('fill', 'input[aria-label="Search sessions"], input[aria-label="Пошук сесій"]', '');

  // A cold/partial index must be disclosed, and a failed report cannot look like zero spending.
  route('**/api/openclaw/analytics?**', { ...data, indexing: true });
  browser('reload');
  browser('wait', '[data-inference-panel] [role=status]');
  assert.match(evaluate('document.querySelector("[data-inference-panel]").innerText'), /incomplete|неповні/);
  route('**/api/openclaw/analytics?**', { ...data, available: false });
  browser('reload');
  browser('wait', '--text', 'OpenClaw');
  browser('wait', '--fn', '!!document.querySelector("[data-inference-panel]") && /unavailable|недоступна/.test(document.querySelector("[data-inference-panel]").innerText)');
  assert.equal(evaluate('document.querySelectorAll("[data-inference-panel] table").length'), 0);
  console.log('Inference UI: fallback filters, private journal, focus, 5 widths, partial index and unavailable state passed.');
} finally {
  browser('close');
}
