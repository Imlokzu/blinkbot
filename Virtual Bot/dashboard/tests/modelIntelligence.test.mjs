import assert from 'node:assert/strict';
import test from 'node:test';
import { ZONES, againstLeader, coverage, gaugeArc, gaugePoint, loadShowIntel, parseIntelligence, saveShowIntel, scoreLine, tierOf } from '../src/panels/chat/modelIntelligence.ts';

const benchmarks = [
  { key: 'gpqa', name: 'GPQA Diamond' },
  { key: 'aime', name: 'OTIS Mock AIME' },
  { key: 'critpt', name: 'CritPt' },
];

test('a well-formed reply survives parsing intact', () => {
  const reply = parseIntelligence({
    available: true, updated: 1_790_000_000,
    source: { name: 'Epoch AI', url: 'https://epoch.ai/benchmarks', license: 'CC BY 4.0' },
    benchmarks,
    models: { 'openai/gpt-6-sol': { index: 68.7, scores: { gpqa: 0.943, critpt: 0.309 } } },
  });
  assert.equal(reply.available, true);
  assert.equal(reply.source.url, 'https://epoch.ai/benchmarks');
  assert.deepEqual(reply.models['openai/gpt-6-sol'], { index: 68.7, scores: { gpqa: 0.943, critpt: 0.309 } });
});

test('garbage from an old or broken server leaves an empty, unavailable index', () => {
  for (const raw of [null, 'oops', [], { models: 'x' }, { available: 'yes' }]) {
    const reply = parseIntelligence(raw);
    assert.equal(reply.available, false);
    assert.deepEqual(reply.models, {});
  }
});

test('broken entries and scores are dropped, not shown as NaN', () => {
  const reply = parseIntelligence({
    available: true, benchmarks: [...benchmarks, { key: 1 }],
    models: {
      good: { index: 140, scores: { gpqa: 0.9, aime: 'high', critpt: null } },
      noIndex: { scores: { gpqa: 0.5 } },
      nanIndex: { index: Number.NaN, scores: {} },
    },
  });
  assert.deepEqual(Object.keys(reply.models), ['good']);
  // Clamped: the bar width is a percentage.
  assert.equal(reply.models.good.index, 100);
  assert.deepEqual(reply.models.good.scores, { gpqa: 0.9 });
  assert.equal(reply.benchmarks.length, 3);
});

test('out-of-range benchmark values are bounded before percentages are shown', () => {
  const reply = parseIntelligence({
    available: true,
    benchmarks: [{ key: 'gpqa', name: 'GPQA Diamond', top: 4 }],
    models: { model: { index: 50, scores: { gpqa: 4, negative: -2 } } },
  });
  assert.equal(reply.benchmarks[0].top, 1);
  assert.deepEqual(reply.models.model.scores, { gpqa: 1, negative: 0 });
  assert.equal(scoreLine(reply.models.model, reply.benchmarks), 'GPQA Diamond 100%');
});

test('only an http(s) link becomes the attribution href', () => {
  const reply = parseIntelligence({ available: true, source: { name: 'x', url: 'javascript:alert(1)', license: '' } });
  assert.equal(reply.source.url, '');
});

test('the tooltip lists the scores the model actually has, in index order', () => {
  const entry = { index: 60, scores: { critpt: 0.204, gpqa: 0.9431 } };
  assert.equal(coverage(entry, benchmarks), 2);
  assert.equal(scoreLine(entry, benchmarks), 'GPQA Diamond 94% · CritPt 20%');
});

test('zones cover 0–100 without gaps, and an index lands in exactly one', () => {
  assert.equal(ZONES[0].from, 0);
  assert.equal(ZONES.at(-1).to, 100);
  for (let i = 1; i < ZONES.length; i++) assert.equal(ZONES[i].from, ZONES[i - 1].to);
  assert.deepEqual([0, 29.9, 30, 49, 50, 64.9, 65, 100, 120].map(tierOf), [0, 0, 1, 1, 2, 2, 3, 3, 3]);
});

test('the dial runs from the left end through the top to the right end', () => {
  const near = (a, b) => Math.abs(a - b) < 1e-9;
  const left = gaugePoint(0, 70, 72, 60);
  const top = gaugePoint(50, 70, 72, 60);
  const right = gaugePoint(100, 70, 72, 60);
  assert.ok(near(left.x, 10) && near(left.y, 72));
  assert.ok(near(top.x, 70) && near(top.y, 12));
  assert.ok(near(right.x, 130) && near(right.y, 72));
  // Out-of-range values pin to the ends instead of drawing below the dial.
  assert.deepEqual(gaugePoint(-5, 70, 72, 60), left);
  assert.equal(gaugeArc(0, 100, 70, 72, 60), 'M 10.00 72.00 A 60 60 0 0 1 130.00 72.00');
});

test('a score is read against the benchmark leader', () => {
  // 32 % on CritPt is the top of the field, so it fills the bar.
  assert.equal(againstLeader(0.32, 0.32), 1);
  assert.equal(againstLeader(0.16, 0.32), 0.5);
  assert.equal(againstLeader(0.5, 0), 0, 'an unknown leader draws no bar rather than a full one');
});

test('a missing or broken leader score parses as unknown', () => {
  const reply = parseIntelligence({ available: true, benchmarks: [
    { key: 'gpqa', name: 'GPQA Diamond', top: 0.96 }, { key: 'aime', name: 'AIME', top: 'x' }, { key: 'critpt', name: 'CritPt' },
  ] });
  assert.deepEqual(reply.benchmarks.map(b => b.top), [0.96, 0, 0]);
});

test('the index defaults to shown; the settings switch turns it off', () => {
  const storage = (seed = {}) => ({ values: { ...seed },
    getItem(key) { return this.values[key] ?? null; },
    setItem(key, value) { this.values[key] = value; } });
  assert.equal(loadShowIntel(storage()), true, 'never touched: on');
  assert.equal(loadShowIntel(storage({ claudeBotModelIntel: '1' })), true, 'legacy brain toggle "1" reads on');
  assert.equal(loadShowIntel(storage({ claudeBotModelIntel: '0' })), false, 'switch off persists');
  const kept = storage();
  saveShowIntel(false, kept);
  assert.equal(loadShowIntel(kept), false);
  saveShowIntel(true, kept);
  assert.equal(loadShowIntel(kept), true);
  const blocked = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } };
  assert.equal(loadShowIntel(blocked), true, 'a blocked browser still shows it');
  saveShowIntel(false, blocked); // must not throw
});
