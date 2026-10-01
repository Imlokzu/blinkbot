import assert from 'node:assert/strict';
import test from 'node:test';
import { jobBody, contextPercent, matches, sessionStatus, channelNeedsAttention, nextPageOffset } from '../src/panels/control/data.ts';
import { controlLocales } from '../src/locales/control.ts';

const draft = { name: ' Daily tasks ', message: ' Review tasks ', agent: 'main',
  kind: 'daily', minutes: '60', time: '09:30', timezone: 'Europe/Berlin' };

test('daily schedules preserve local time and time zone across daylight saving', () => {
  assert.deepEqual(jobBody(draft), { name: 'Daily tasks', message: 'Review tasks', agent: 'main',
    kind: 'daily', minutes: 60, hour: 9, minute: 30, timezone: 'Europe/Berlin' });
  assert.equal(jobBody({ ...draft, time: '24:00' }), null);
  assert.equal(jobBody({ ...draft, time: '12:60' }), null);
  assert.equal(jobBody({ ...draft, time: '' }), null);
});

test('invalid intervals and incomplete tasks never produce a request body', () => {
  for (const minutes of ['', ' ', 'NaN', '-1', '0', '4', '5.5', '10081', 'Infinity']) {
    assert.equal(jobBody({ ...draft, kind: 'every', minutes }), null, minutes);
  }
  assert.equal(jobBody({ ...draft, kind: 'every', minutes: '5', time: '' }).minutes, 5);
  assert.equal(jobBody({ ...draft, kind: 'every', minutes: '10080' }).minutes, 10080);
  for (const field of ['name', 'message', 'agent', 'timezone']) assert.equal(jobBody({ ...draft, [field]: ' ' }), null);
  assert.equal(jobBody({ ...draft, timezone: 'Not/AZone' }), null);
  assert.equal(jobBody({ ...draft, timezone: '+02:00' }), null);
  assert.equal(jobBody({ ...draft, agent: 'agent/other' }), null);
  assert.equal(jobBody({ ...draft, name: 'x'.repeat(121) }), null);
  assert.equal(jobBody({ ...draft, message: 'x'.repeat(4001) }), null);
  assert.equal(jobBody({ ...draft, kind: 'stream' }), null);
});

test('queued and missing lifecycle states are not invented as running or idle', () => {
  assert.equal(sessionStatus({ active: true, status: 'queued' }), 'queued');
  assert.equal(sessionStatus({ active: true, status: null }), 'running');
  assert.equal(sessionStatus({ active: false, status: null }), null);
  assert.equal(sessionStatus({ active: false, status: 'timeout' }), 'timeout');
});

test('unknown channel configuration does not count as a confirmed failure', () => {
  const channel = { enabled: true, configured: null, connected: null, has_error: false };
  assert.equal(channelNeedsAttention(channel), false);
  assert.equal(channelNeedsAttention({ ...channel, configured: false }), true);
  assert.equal(channelNeedsAttention({ ...channel, connected: false }), true);
  assert.equal(channelNeedsAttention({ ...channel, has_error: true }), true);
  assert.equal(channelNeedsAttention({ ...channel, enabled: false, connected: false }), false);
});

test('pagination follows only forward cursors accepted by the bounded gateway view', () => {
  const page = { offset: 50, total: 1000, has_more: true, next_offset: 100 };
  assert.equal(nextPageOffset(page), 100);
  for (const next_offset of [null, 50, 0, -1, 51.5, 10001, Infinity]) {
    assert.equal(nextPageOffset({ ...page, next_offset }), null);
  }
  assert.equal(nextPageOffset({ ...page, has_more: false }), null);
});

test('context meters distinguish unknown or stale counts from an actual zero', () => {
  const session = { tokens: 50, tokens_fresh: true, context_window: 200 };
  assert.equal(contextPercent(session), 25);
  assert.equal(contextPercent({ ...session, tokens: 0 }), 0);
  assert.equal(contextPercent({ ...session, tokens: 300 }), 100);
  assert.equal(contextPercent({ ...session, tokens: null }), null);
  assert.equal(contextPercent({ ...session, tokens_fresh: false }), null);
  assert.equal(contextPercent({ ...session, context_window: 0 }), null);
  assert.equal(contextPercent({ ...session, tokens: Infinity }), null);
});

test('metadata search matches every word without depending on private chat text', () => {
  assert.ok(matches('main GPT', 'main', 'openai/gpt-6-luna'));
  assert.ok(matches('  ', 'main'));
  assert.ok(!matches('main missing', 'main', 'gpt-6-luna'));
});

test('both locales cover all control labels and preserve interpolation names', () => {
  const placeholders = (value) => [...value.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
  assert.deepEqual(Object.keys(controlLocales.en).sort(), Object.keys(controlLocales.uk).sort());
  for (const key of Object.keys(controlLocales.en)) {
    assert.ok(controlLocales.uk[key]);
    assert.deepEqual(placeholders(controlLocales.en[key]), placeholders(controlLocales.uk[key]), key);
  }
});
