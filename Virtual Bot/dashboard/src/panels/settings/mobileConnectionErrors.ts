export type MobileErrorKey = 'backendOutdated' | 'deviceMissing' | 'signInRequired' | 'operatorRequired' | 'ownerSessionRequired' | 'deviceRevokedNeedRestore' | 'invalidOrigin' | 'networkFailed' | 'failed';

/** Distinct operations get distinct remedies for the same HTTP status. */
export type MobileOperation = 'pairing' | 'revoke' | 'renew';

/** Pairing failures must distinguish an old host from an invalid API address. */
export function mobileErrorKey(error: unknown, operation: MobileOperation = 'pairing'): MobileErrorKey {
  const status = error && typeof error === 'object' && 'status' in error ? error.status : undefined;
  switch (status) {
    case 401: return 'signInRequired';
    // Renewing needs the web owner's signed-in session or a direct-local host;
    // pairing and revocation are operator actions.
    case 403: return operation === 'renew' ? 'ownerSessionRequired' : 'operatorRequired';
    case 404: return operation === 'pairing' ? 'backendOutdated' : 'deviceMissing';
    // A revoked credential can return only through an explicit restore.
    case 409: return operation === 'renew' ? 'deviceRevokedNeedRestore' : 'failed';
    case 400:
    case 422: return 'invalidOrigin';
    case undefined:
    case 0: return 'networkFailed';
    default: return 'failed';
  }
}
