import assert from 'node:assert/strict';
import test from 'node:test';
import { parseBrainModelsCache } from '../src/lib/brainModelsCache.ts';

const data = {
  models: [{ id: 'openai/test', label: 'Test', context: 200000, vision: true }],
  selected: 'openai/test', default: 'openai/test', thinking: 'high',
  thinking_levels: ['low', 'high'], available: true,
};
const cache = (changes = {}) => ({ data: { ...data, ...changes }, savedAt: Date.now() - 1000 });

test('valid cached settings retain their timestamp and unknown model fields', () => {
  const value = cache();
  assert.deepEqual(parseBrainModelsCache(value), value);
  assert.ok(parseBrainModelsCache(cache({ thinking: '', selected: '', thinking_levels: [] })));
});

test('cached per-model effort profiles retain their exact levels and default', () => {
  const value = cache({ models: [
    { id: 'openai/reasoner', label: 'Reasoner', efforts: ['low', 'medium', 'high'], effort_default: 'medium' },
    { id: 'regolo/unknown', label: 'Unknown', efforts: [], effort_default: '' },
  ] });
  assert.deepEqual(parseBrainModelsCache(value), value);
});

test('malformed cached settings cannot crash the menu or suppress a fresh read', () => {
  for (const value of [null, {}, 'catalog', { ...cache(), savedAt: NaN }, { ...cache(), savedAt: Date.now() + 60000 }]) {
    assert.equal(parseBrainModelsCache(value), undefined);
  }
  for (const changes of [
    { models: [] }, { models: 'catalog' }, { models: [null] }, { models: [{ id: 12, label: 'Test' }] },
    { thinking_levels: 'high' }, { thinking_levels: [null] }, { selected: {} },
    { default: null }, { thinking: 5 }, { available: 'true' },
    { models: [{ id: 'test', label: 'Test', context: 'big' }] },
    { models: [{ id: 'test', label: 'Test', vision: 'yes' }] },
    { models: [{ id: 'test', label: 'Test', efforts: 'high' }] },
    { models: [{ id: 'test', label: 'Test', efforts: ['low', null] }] },
    { models: [{ id: 'test', label: 'Test', efforts: ['low', ''] }] },
    { models: [{ id: 'test', label: 'Test', efforts: ['low'], effort_default: 'adaptive' }] },
    { models: [{ id: 'test', label: 'Test', effort_default: 'high' }] },
  ]) assert.equal(parseBrainModelsCache(cache(changes)), undefined, JSON.stringify(changes));
});
