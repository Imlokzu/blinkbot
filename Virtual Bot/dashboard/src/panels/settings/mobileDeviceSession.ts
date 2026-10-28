/**
 * Pure decisions for the phone-session list in MobileConnection.
 *
 * The backend reports one record per device (including revoked ones) with a
 * status and the outcome of the phone's last authentication attempt. These
 * helpers turn that record into the decisions the panel needs — which status
 * to display, whether the session needs attention, and whether a renew
 * request should extend the session or explicitly restore a revoked
 * credential. Keeping the logic here makes it testable without a browser.
 *
 * Newer contract fields (`status`, `last_auth_failure`, ...) are trusted when
 * present so the UI follows the server's clock, but every helper derives the
 * same answer from the plain timestamps alone, so older backends keep working.
 */

export type DeviceSessionStatus = 'active' | 'expired' | 'revoked';
export type AuthFailure = 'expired' | 'revoked' | null;

export interface MobileDeviceRecord {
  device_id: string;
  device_name?: string;
  platform?: string;
  created_at?: number | null;
  expires_at?: number | null;
  revoked_at?: number | null;
  /** Reported session status; other strings are ignored and re-derived. */
  status?: string;
  last_auth_failure?: string | null;
  last_auth_failure_at?: number | null;
}

/** Effective expiry in seconds (timestamps come as seconds from the backend). */
function isExpired(record: Pick<MobileDeviceRecord, 'expires_at'>, now: number): boolean {
  return typeof record.expires_at === 'number' && record.expires_at * 1000 <= now;
}

export function deviceStatus(record: MobileDeviceRecord, now: number = Date.now()): DeviceSessionStatus {
  if (record.revoked_at != null) return 'revoked';
  if (record.status === 'active' || record.status === 'expired' || record.status === 'revoked') {
    return record.status;
  }
  if (isExpired(record, now)) return 'expired';
  return 'active';
}

export function authFailure(record: MobileDeviceRecord): AuthFailure {
  return record.last_auth_failure === 'expired' || record.last_auth_failure === 'revoked'
    ? record.last_auth_failure
    : null;
}

/**
 * What the per-device alert should say: revocation supersedes an expiry (an
 * expired session is still revoked after a revoke), otherwise an expiry or an
 * authentication failure marks the row. `null` means the session is active
 * and the last sign-in succeeded — nothing needs attention.
 */
export function attentionKind(record: MobileDeviceRecord, now: number = Date.now()): 'expired' | 'revoked' | null {
  const failure = authFailure(record);
  if (deviceStatus(record, now) === 'revoked' || failure === 'revoked') return 'revoked';
  if (deviceStatus(record, now) === 'expired' || failure === 'expired') return 'expired';
  return null;
}

export function needsAttention(record: MobileDeviceRecord, now: number = Date.now()): boolean {
  return attentionKind(record, now) !== null;
}

/**
 * Body for POST /api/mobile/devices/{id}/renew. An active or expired session
 * is simply extended; a revoked credential may only come back through an
 * explicit restore, which the backend rejects with `device_revoked` unless
 * `restore_revoked` is sent — and the panel only sends it after the user
 * confirmed the restore inline.
 */
export function renewalBody(record: MobileDeviceRecord, now: number = Date.now()): { restore_revoked: boolean } {
  return { restore_revoked: deviceStatus(record, now) === 'revoked' };
}
