import assert from 'node:assert/strict';
import test from 'node:test';
import { mobileErrorKey } from '../src/panels/settings/mobileConnectionErrors.ts';
import { mobileLocales } from '../src/locales/mobile.ts';

const failure = status => Object.assign(new Error('An API fixture failed'), { status });

test('pairing failures give distinct recovery advice for host, identity and origin', () => {
  for (const [status, expected] of [
    [404, 'backendOutdated'], [401, 'signInRequired'], [403, 'operatorRequired'],
    [400, 'invalidOrigin'], [422, 'invalidOrigin'],
  ]) {
    assert.equal(mobileErrorKey(failure(status)), expected, `HTTP ${status}`);
    assert.equal(mobileErrorKey({ status, message: 'A different backend detail' }), expected);
  }
});

test('an unreachable host is distinct from a reachable service returning an error', () => {
  for (const error of [new TypeError('Failed to fetch'), new Error('Network unavailable'), failure(0), null, undefined]) {
    assert.equal(mobileErrorKey(error), 'networkFailed');
  }
  for (const status of [409, 429, 500, 502, 503, 504]) {
    assert.equal(mobileErrorKey(failure(status)), 'failed', `HTTP ${status}`);
  }
  // Text in an error body must not impersonate the transport's actual status.
  assert.equal(mobileErrorKey(Object.assign(new Error('HTTP 404: invalid_server_origin'), { status: 503 })), 'failed');
});

test('a missing device on revoke does not imply that pairing routes are outdated', () => {
  assert.equal(mobileErrorKey(failure(404), 'revoke'), 'deviceMissing');
  assert.equal(mobileErrorKey(failure(404), 'pairing'), 'backendOutdated');
  for (const [status, expected] of [[401, 'signInRequired'], [403, 'operatorRequired'], [503, 'failed']]) {
    assert.equal(mobileErrorKey(failure(status), 'revoke'), expected);
  }
});

test('both mobile locales cover every recovery state and the device-loading state', () => {
  assert.deepEqual(Object.keys(mobileLocales.en).sort(), Object.keys(mobileLocales.uk).sort());
  const errorKeys = ['backendOutdated', 'signInRequired', 'operatorRequired', 'invalidOrigin', 'networkFailed', 'failed', 'deviceMissing'];
  for (const locale of Object.values(mobileLocales)) {
    for (const key of [...errorKeys, 'loadingDevices']) assert.ok(locale[key]?.trim(), key);
    assert.equal(new Set(errorKeys.map(key => locale[key])).size, errorKeys.length,
      'users must receive different advice for failures with different remedies');
    assert.notEqual(locale.loadingDevices, locale.empty, 'an unfinished query is not an empty device list');
  }
});
