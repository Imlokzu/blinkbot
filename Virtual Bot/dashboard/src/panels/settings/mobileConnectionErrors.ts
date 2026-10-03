export type MobileErrorKey = 'backendOutdated' | 'deviceMissing' | 'signInRequired' | 'operatorRequired' | 'invalidOrigin' | 'networkFailed' | 'failed';

/** Pairing failures must distinguish an old host from an invalid API address. */
export function mobileErrorKey(error: unknown, operation: 'pairing' | 'revoke' = 'pairing'): MobileErrorKey {
  const status = error && typeof error === 'object' && 'status' in error ? error.status : undefined;
  switch (status) {
    case 401: return 'signInRequired';
    case 403: return 'operatorRequired';
    case 404: return operation === 'revoke' ? 'deviceMissing' : 'backendOutdated';
    case 400:
    case 422: return 'invalidOrigin';
    case undefined:
    case 0: return 'networkFailed';
    default: return 'failed';
  }
}
