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

test('renew failures distinguish a lost device, a revoked credential and a missing owner session', () => {
  assert.equal(mobileErrorKey(failure(404), 'renew'), 'deviceMissing');
  // Renewing needs the signed-in web owner or a direct-local host: phone
  // credentials are explicitly forbidden, so this is not the operator message.
  assert.equal(mobileErrorKey(failure(403), 'renew'), 'ownerSessionRequired');
  assert.equal(mobileErrorKey(failure(409), 'renew'), 'deviceRevokedNeedRestore');
  for (const [status, expected] of [[401, 'signInRequired'], [400, 'invalidOrigin'], [422, 'invalidOrigin'],
    [503, 'failed'], [500, 'failed']]) {
    assert.equal(mobileErrorKey(failure(status), 'renew'), expected, `HTTP ${status}`);
  }
  // The other operations keep their established meanings for these statuses.
  assert.equal(mobileErrorKey(failure(403), 'pairing'), 'operatorRequired');
  assert.equal(mobileErrorKey(failure(409), 'pairing'), 'failed');
  assert.equal(mobileErrorKey(failure(409), 'revoke'), 'failed');
});

test('both mobile locales cover every recovery state and the device-loading state', () => {
  assert.deepEqual(Object.keys(mobileLocales.en).sort(), Object.keys(mobileLocales.uk).sort());
  const errorKeys = ['backendOutdated', 'signInRequired', 'operatorRequired', 'ownerSessionRequired',
    'deviceRevokedNeedRestore', 'invalidOrigin', 'networkFailed', 'failed', 'deviceMissing'];
  for (const locale of Object.values(mobileLocales)) {
    for (const key of [...errorKeys, 'loadingDevices']) assert.ok(locale[key]?.trim(), key);
    assert.equal(new Set(errorKeys.map(key => locale[key])).size, errorKeys.length,
      'users must receive different advice for failures with different remedies');
    assert.match(locale.ownerSessionRequired, locale === mobileLocales.en ? /sign/i : /увійди/i,
      'the renew 403 remedy must name the web sign-in, not the phone operator flow');
    assert.notEqual(locale.deviceRevokedNeedRestore, locale.deviceMissing,
      'a revoked credential needs a repair action, not a passive refresh');
    assert.notEqual(locale.loadingDevices, locale.empty, 'an unfinished query is not an empty device list');
  }
});
