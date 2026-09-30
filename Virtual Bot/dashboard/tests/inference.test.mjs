import assert from 'node:assert/strict';
import test from 'node:test';
import { rate, cacheShare, calendar, costTotal, isColdIndex, sessionMatches, tokenTotal } from '../src/panels/inference/report.ts';

const costs = { input: 100, output: 10, cacheRead: 400, cacheWrite: 0, totalTokens: 510,
  inputCost: .001, outputCost: .002, cacheReadCost: .0004, cacheWriteCost: 0, totalCost: .0034, missingCostEntries: 0 };
const zero = Object.fromEntries(Object.keys(costs).map((key) => [key, 0]));
const report = { available: true, days: 3, start_date: '2026-09-28', end_date: '2026-09-30',
  indexing: false, totals: costs, replies: 1, providers: [], models: [], daily: [], daily_models: [], sessions: [] };

test('unmetered replies never become free charges, while idle and recorded free usage stay zero', () => {
  assert.equal(tokenTotal(zero, 1), null);
  assert.equal(costTotal(zero, 1), null);
  assert.equal(tokenTotal(null, 0), null);
  assert.equal(costTotal(null, 0), null);
  assert.equal(tokenTotal(zero, 0), 0);
  assert.equal(costTotal(zero, 0), 0);
  assert.equal(tokenTotal(costs, 1), 510);
  assert.equal(costTotal({ ...costs, totalCost: 0 }, 1), 0);
  assert.equal(costTotal({ ...costs, totalCost: 0, missingCostEntries: 1 }, 1), null);
  assert.equal(costTotal({ ...costs, missingCostEntries: 1 }, 2), .0034);
  assert.equal(costTotal({ ...zero, totalCost: .01 }, 1), .01);
});

test('cold indexing waits for recorded traffic instead of presenting idle totals as final', () => {
  assert.ok(isColdIndex({ ...report, indexing: true, totals: zero, replies: 0 }));
  assert.ok(isColdIndex({ ...report, indexing: true, totals: null, replies: 0 }));
  assert.ok(!isColdIndex({ ...report, indexing: false, totals: zero, replies: 0 }));
  assert.ok(!isColdIndex({ ...report, indexing: true }));
  assert.ok(!isColdIndex({ ...report, indexing: true, totals: zero }));
});

test('observed rates preserve configured free usage and never price a missing model at zero', () => {
  assert.equal(rate(costs, 'input'), 10);
  assert.equal(rate(costs, 'cacheRead'), 1);
  assert.equal(rate({ ...costs, inputCost: 0 }, 'input'), 0);
  assert.equal(rate({ ...costs, missingCostEntries: 1 }, 'input'), null);
  assert.equal(rate({ ...costs, output: 0 }, 'output'), null);
  assert.equal(rate({ ...costs, inputCost: null }, 'input'), null);
  assert.equal(rate({ ...costs, input: NaN }, 'input'), null);
  assert.equal(rate({ ...costs, inputCost: Infinity }, 'input'), null);
  assert.equal(cacheShare(costs), 80);
  assert.equal(cacheShare({ ...costs, input: 0, cacheRead: 0 }), null);
});

test('provider filtering finds sessions that switched to a fallback model', () => {
  const session = { id: 'abc123', provider: 'primary', model: 'chosen',
    models: [{ provider: 'nvidia', model: 'openai/gpt-oss-20b' }] };
  assert.ok(sessionMatches(session, 'nvidia', 'gpt nvidia'));
  assert.ok(!sessionMatches(session, 'primary', 'abc'));
  assert.ok(!sessionMatches(session, '', 'chosen'));
  assert.ok(sessionMatches({ ...session, models: [] }, 'primary', 'abc chosen'));
  assert.ok(!sessionMatches(session, 'regolo', ''));
  assert.ok(!sessionMatches(session, '', 'no-such-model'));
});

test('provider and search terms must match the same actual model scope', () => {
  const session = { id: 'abc123', provider: 'old', model: 'old-choice',
    models: [{ provider: 'a', model: 'first-model' }, { provider: 'b', model: 'fallback-model' }] };
  assert.ok(sessionMatches(session, '', 'fallback a'));
  assert.ok(sessionMatches(session, 'b', 'fallback b'));
  assert.ok(!sessionMatches(session, 'a', 'fallback'));
  assert.ok(!sessionMatches(session, 'b', 'first-model'));
  assert.ok(!sessionMatches(session, 'old', ''));
});

test('calendar uses UTC, fills real idle days, and sums models only for the selected provider', () => {
  const report = { start_date: '2026-09-28', end_date: '2026-09-30',
    daily: [{ date: '2026-09-28', tokens: 100, cost: .4 }],
    daily_models: [
      { date: '2026-09-28', provider: 'a', tokens: 10, cost: .1 },
      { date: '2026-09-28', provider: 'a', tokens: 20, cost: .2 },
      { date: '2026-09-28', provider: 'b', tokens: 70, cost: .1 },
    ] };
  assert.deepEqual(calendar(report, '').map((day) => day.tokens), [100, 0, 0]);
  const selected = calendar(report, 'a');
  assert.deepEqual(selected.map((day) => day.date), ['2026-09-28', '2026-09-29', '2026-09-30']);
  assert.deepEqual(selected.map((day) => day.tokens), [30, 0, 0]);
  assert.ok(Math.abs(selected[0].cost - .3) < 1e-10);
});

test('daily breakdown absence and unfinished indexing are unknown rather than idle', () => {
  assert.deepEqual(calendar(report, '').map((day) => day.tokens), [null, null, null]);
  assert.deepEqual(calendar({ ...report, indexing: true,
    daily: [{ date: '2026-09-28', tokens: 10, cost: .1 }] }, '').map((day) => day.tokens), [10, null, null]);
  const provider = { ...costs, provider: 'a', replies: 1 };
  assert.deepEqual(calendar({ ...report, providers: [provider] }, 'a').map((day) => day.cost), [null, null, null]);
  assert.deepEqual(calendar({ ...report, providers: [provider], daily_models: undefined }, 'a').map((day) => day.tokens), [null, null, null]);
  assert.deepEqual(calendar(report, 'missing').map((day) => day.tokens), [null, null, null]);
  assert.deepEqual(calendar({ ...report, totals: zero, replies: 0 }, '').map((day) => day.tokens), [0, 0, 0]);
});

test('daily zero-filled unmetered usage and unpriced usage retain their unknown fields', () => {
  const day = { date: '2026-09-28', tokens: 0, cost: 0 };
  const unmetered = calendar({ ...report, totals: zero, daily: [day] }, '')[0];
  assert.equal(unmetered.tokens, null);
  assert.equal(unmetered.cost, null);
  const unpriced = calendar({ ...report, totals: { ...costs, totalCost: 0, missingCostEntries: 1 },
    daily: [{ ...day, tokens: 100 }] }, '')[0];
  assert.equal(unpriced.tokens, 100);
  assert.equal(unpriced.cost, null);
  assert.equal(calendar({ ...report, totals: { ...costs, totalCost: 0 },
    daily: [{ ...day, tokens: 100 }] }, '')[0].cost, 0);
});

test('calendar rejects invalid, reversed and oversized ranges', () => {
  assert.deepEqual(calendar({ ...report, start_date: 'invalid' }, ''), []);
  assert.deepEqual(calendar({ ...report, end_date: '2026-09-27' }, ''), []);
  assert.deepEqual(calendar({ ...report, start_date: '2026-01-01' }, ''), []);
});
