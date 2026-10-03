import assert from 'node:assert/strict';
import test from 'node:test';
import { coverage, parseIntelligence, scoreLine } from '../src/panels/chat/modelIntelligence.ts';

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

test('only an http(s) link becomes the attribution href', () => {
  const reply = parseIntelligence({ available: true, source: { name: 'x', url: 'javascript:alert(1)', license: '' } });
  assert.equal(reply.source.url, '');
});

test('the tooltip lists the scores the model actually has, in index order', () => {
  const entry = { index: 60, scores: { critpt: 0.204, gpqa: 0.9431 } };
  assert.equal(coverage(entry, benchmarks), 2);
  assert.equal(scoreLine(entry, benchmarks), 'GPQA Diamond 94% · CritPt 20%');
});
