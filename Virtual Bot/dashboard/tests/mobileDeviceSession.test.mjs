/** Pure session decisions: status, attention alerts and renew-vs-restore bodies. */
import assert from 'node:assert/strict';
import test from 'node:test';
import { deviceStatus, authFailure, attentionKind, needsAttention, renewalBody } from '../src/panels/settings/mobileDeviceSession.ts';

// Seconds, as the backend sends them. A fixed clock keeps the decisions exact.
const NOW = 1793534400; // 2026-10-30T12:00:00Z

const active = {
  device_id: 'phone-1',
  device_name: 'Pixel 8 · Android 16',
  platform: 'android',
  created_at: NOW - 86400,
  expires_at: NOW + 86400,
  revoked_at: null,
  status: 'active',
  last_auth_failure: null,
  last_auth_failure_at: null,
};

test('the effective status trusts the backend report and falls back to timestamps', () => {
  assert.equal(deviceStatus(active), 'active', 'the contract status is used as-is');
  assert.equal(deviceStatus({ ...active, status: 'revoked' }), 'revoked');
  assert.equal(deviceStatus({ ...active, expires_at: NOW - 1 }, NOW * 1000), 'active', 'explicit server status avoids client clock skew');
  // Older backends send no status field: derive it, instead of showing nothing.
  assert.equal(deviceStatus({ device_id: 'legacy', expires_at: NOW + 1 }, NOW * 1000), 'active');
  assert.equal(deviceStatus({ device_id: 'legacy', expires_at: NOW }, NOW * 1000), 'expired', 'a session expiring this second is already expired');
  assert.equal(deviceStatus({ device_id: 'legacy', revoked_at: 0 }), 'revoked');
  assert.equal(deviceStatus({ device_id: 'legacy', revoked_at: NOW - 5 }), 'revoked',
    'a revocation timestamp marks the device even without an explicit status');
  assert.equal(deviceStatus({ ...active, revoked_at: NOW - 5, expires_at: NOW - 10 }), 'revoked',
    'revocation outranks expiry: a revoked phone stays revoked after its session lapses');
  assert.equal(deviceStatus({ device_id: 'unknown-shape', status: 'ready' }), 'active',
    'a status string outside the contract is ignored, never shown to the user');
  assert.equal(deviceStatus({ device_id: 'bare' }), 'active', 'no evidence at all reads as active');
});

test('attention follows an expiring or revoked session or a failed sign-in', () => {
  assert.equal(attentionKind(active), null, 'a healthy session stays quiet');
  assert.equal(attentionKind({ ...active, status: 'expired' }), 'expired');
  assert.equal(attentionKind({ ...active, last_auth_failure: 'expired' }), 'expired',
    'the phone may say the sign-in failed because of expiry while the reported status was still active');
  assert.equal(attentionKind({ ...active, last_auth_failure: 'revoked' }), 'revoked');
  assert.equal(attentionKind({ ...active, status: 'revoked' }), 'revoked');
  assert.equal(attentionKind({ ...active, status: 'revoked', last_auth_failure: 'expired' }), 'revoked',
    'a revocation supersedes an older expiry failure in the same record');
  assert.equal(attentionKind({ ...active, last_auth_failure: 'nonsense' }), null,
    'an unknown failure kind must not invent an alert the panel cannot explain');
  assert.equal(needsAttention(active), false);
  assert.equal(needsAttention({ ...active, status: 'expired' }), true);
  assert.equal(needsAttention({ ...active, revoked_at: NOW - 5 }), true);
});

test('renew extends active and expired sessions; only an explicit restore touches a revoked one', () => {
  assert.deepEqual(renewalBody(active), { restore_revoked: false });
  assert.deepEqual(renewalBody({ ...active, status: 'expired' }), { restore_revoked: false },
    'an expired session is extended, not restored — the credential itself stayed valid');
  assert.deepEqual(renewalBody({ ...active, status: 'revoked' }), { restore_revoked: true },
    'the backend rejects a plain extension for a revoked credential (409 device_revoked)');
  assert.deepEqual(renewalBody({ device_id: 'legacy', revoked_at: NOW - 5 }), { restore_revoked: true },
    'legacy records without the status field still reach the restore branch through the fallback');
  assert.deepEqual(renewalBody({ ...active, last_auth_failure: 'expired' }), { restore_revoked: false },
    'a failed sign-in is a signal to extend, not to restore');
});
