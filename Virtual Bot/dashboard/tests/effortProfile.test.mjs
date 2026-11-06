import assert from 'node:assert/strict';
import test from 'node:test';
import { effortProfile } from '../src/panels/chat/effortProfile.ts';

const reasoner = { efforts: ['low', 'medium', 'high', 'xhigh', 'max'], effort_default: 'medium' };

test('the selected model supplies its exact effort levels without a synthetic automatic stop', () => {
  const profile = effortProfile(reasoner, 'high');
  assert.deepEqual(profile, { levels: reasoner.efforts, thinking: 'high' });
  for (const invented of ['', 'automatic', 'off', 'minimal', 'adaptive']) {
    assert.equal(profile.levels.includes(invented), false);
  }
});

test('legacy omitted or unsupported overrides display the resolved model default', () => {
  for (const saved of ['', 'none', 'adaptive', 'off', 'minimal']) {
    assert.equal(effortProfile(reasoner, saved).thinking, 'medium');
  }
  const smaller = { efforts: ['low', 'medium', 'high'], effort_default: 'high' };
  assert.deepEqual(effortProfile(smaller, 'xhigh'), { levels: smaller.efforts, thinking: 'high' });
});

test('missing capabilities never inherit levels from another model or invent a selected value', () => {
  for (const model of [undefined, {}, { efforts: [] }, { efforts: [], effort_default: 'high' }]) {
    assert.deepEqual(effortProfile(model, 'high'), { levels: [], thinking: '' });
  }
  assert.deepEqual(effortProfile({ efforts: ['low', 'high'] }, 'adaptive'), { levels: ['low', 'high'], thinking: '' });
});

test('provider-defined future levels are retained without aliasing', () => {
  assert.deepEqual(effortProfile({ efforts: ['low', 'gateway-next', 'low'], effort_default: 'gateway-next' }, ''), {
    levels: ['low', 'gateway-next'], thinking: 'gateway-next',
  });
});
