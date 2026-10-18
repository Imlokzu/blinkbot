import { api, type ApiOptions } from './api';

let available = false;
let checkedUntil = 0;
let probe: Promise<void> | undefined;
let pending = false;
let lastTick = -Infinity;

/** Bound auth lookup as well as fetch, so either failure releases the single-flight latch. */
async function request<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const controller = new AbortController();
  let timer: number | undefined;
  try {
    return await Promise.race([
      api<T>(path, { ...options, signal: controller.signal }),
      new Promise<never>((_, reject) => {
        timer = window.setTimeout(() => {
          controller.abort();
          reject(new Error('local_haptics_timeout'));
        }, 1_500);
      }),
    ]);
  } finally { window.clearTimeout(timer); }
}

/** The native bridge belongs to this computer, never a remote bot host. */
function localMac(): boolean {
  return typeof window !== 'undefined' && window.top === window.self
    && typeof AbortController !== 'undefined'
    && ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
    && /Mac/.test(navigator.platform) && navigator.maxTouchPoints < 2;
}

/** Warm up silently when the picker opens; this endpoint never produces a tick. */
export function prepareLocalHaptics(): void {
  if (!localMac() || probe || Date.now() < checkedUntil) return;
  checkedUntil = Date.now() + 60_000;
  probe = request<{ available: boolean; kind: string | null }>('/api/local/haptics')
    .then(result => { available = result.available && result.kind === 'macos-trackpad'; })
    .catch(() => { available = false; })
    .finally(() => { probe = undefined; });
}

/** Call only from a trusted interaction crossing a discrete slider stop. */
export function tickLocalHaptics(trusted: boolean): void {
  if (!trusted || !available || !localMac() || document.hidden || !document.hasFocus() || pending) return;
  const now = performance.now();
  if (now - lastTick < 100) return;
  lastTick = now;
  pending = true;
  void request('/api/local/haptics/tick', { method: 'POST', headers: { 'X-Blink-Haptic': '1' } })
    .catch(() => { /* Tactile feedback must never interrupt the actual setting change. */ })
    .finally(() => { pending = false; });
}
