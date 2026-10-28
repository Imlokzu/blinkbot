import { useEffect, useRef, useState } from 'react';
import { Smartphone } from '../../vendor/solar-icons/compat.ts';
import { get, post, del } from '@/lib/api';
import { useLanguage } from '@/hooks/useLanguage';
import { t } from '@/locales/mobile';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Field';
import { attentionKind, deviceStatus, renewalBody, type DeviceSessionStatus } from './mobileDeviceSession';
import { mobileErrorKey, type MobileErrorKey } from './mobileConnectionErrors';

interface Pairing { qr_svg: string; expires_at: number; pairing_code?: string }
interface Device {
  device_id: string;
  device_name: string;
  platform: string;
  created_at?: number | null;
  expires_at?: number | null;
  revoked_at: number | null;
  /** Newer contract fields; the session helpers tolerate their absence. */
  status?: string;
  last_auth_failure?: string | null;
  last_auth_failure_at?: number | null;
}

const statusLabels: Record<DeviceSessionStatus, 'statusActive' | 'statusExpired' | 'statusRevoked'> = {
  active: 'statusActive',
  expired: 'statusExpired',
  revoked: 'statusRevoked',
};

function deviceDate(value: number | null | undefined): string | null {
  if (!value) return null;
  const date = new Date(value * 1000);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export function MobileConnection() {
  useLanguage();
  const [server, setServer] = useState('https://api-bot.waveio.me');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<MobileErrorKey | null>(null);
  const [deviceError, setDeviceError] = useState<MobileErrorKey | null>(null);
  const [deviceNotice, setDeviceNotice] = useState<'sessionExtended' | 'accessRestored' | null>(null);
  const [devicesLoading, setDevicesLoading] = useState(true);
  const [pairing, setPairing] = useState<Pairing | null>(null);
  const [imageUrl, setImageUrl] = useState('');
  const [expired, setExpired] = useState(false);
  const [remaining, setRemaining] = useState(0);
  const [copyStatus, setCopyStatus] = useState<'copied' | 'copyFailed' | null>(null);
  const [copying, setCopying] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);
  /** Inline restore confirmation: the device id whose repair panel is open. */
  const [restoreConfirm, setRestoreConfirm] = useState<string | null>(null);
  const deviceActions = useRef(new Map<string, HTMLButtonElement>());
  const restoreCancel = useRef<HTMLButtonElement>(null);
  const returnFocus = useRef<string | null>(null);
  const mounted = useRef(false);
  const deviceRevision = useRef(0);
  const pairingRevision = useRef(0);
  /** Mutation guards are checked synchronously, before any await. */
  const pendingMutations = useRef(new Set<string>());

  useEffect(() => {
    if (busy) return;
    if (returnFocus.current) {
      const action = deviceActions.current.get(returnFocus.current);
      if (action?.isConnected && !action.disabled) {
        action.focus();
        if (document.activeElement === action) returnFocus.current = null;
      }
    } else if (restoreConfirm) restoreCancel.current?.focus();
  }, [restoreConfirm, busy]);

  function closeRestore(id: string) {
    returnFocus.current = id;
    setRestoreConfirm(null);
  }

  function clearPairing() {
    pairingRevision.current++;
    setPairing(null); setImageUrl(''); setExpired(false); setRemaining(0);
    setCopyStatus(null); setCopying(false);
  }

  useEffect(() => {
    mounted.current = true;
    let cancelled = false;
    let loading = false;
    const load = async () => {
      if (loading) return;
      loading = true;
      const revision = deviceRevision.current;
      try {
        const data = await get<{ devices: Device[] }>('/api/mobile/devices');
        if (!cancelled && revision === deviceRevision.current) {
          // Revoked devices stay in the list so owners can repair or re-check them.
          setDevices(data.devices);
          setDeviceError(null);
        }
      } catch (failure) {
        if (!cancelled && revision === deviceRevision.current) setDeviceError(mobileErrorKey(failure));
      } finally {
        loading = false;
        if (!cancelled) setDevicesLoading(false);
      }
    };
    void load();
    const timer = window.setInterval(() => { void load(); }, 10000);
    return () => { cancelled = true; mounted.current = false; window.clearInterval(timer); };
  }, []);

  useEffect(() => {
    if (!pairing) return;
    const expire = () => { clearPairing(); setExpired(true); };
    const duration = pairing.expires_at * 1000 - Date.now();
    if (!Number.isFinite(duration) || duration <= 0) { expire(); return; }
    let url = '';
    // The human code remains usable when the browser cannot display the QR.
    try { url = URL.createObjectURL(new Blob([pairing.qr_svg], { type: 'image/svg+xml' })); }
    catch { /* Keep the shared expiry and manual pairing available. */ }
    setImageUrl(url);
    setExpired(false);
    const tick = () => {
      const seconds = Math.ceil((pairing.expires_at * 1000 - Date.now()) / 1000);
      if (seconds <= 0) expire();
      else setRemaining(seconds);
    };
    tick();
    const clock = window.setInterval(tick, 1000);
    const timer = window.setTimeout(expire, duration);
    return () => {
      if (url) URL.revokeObjectURL(url);
      window.clearTimeout(timer); window.clearInterval(clock);
    };
  }, [pairing]);

  async function create() {
    setBusy(true); setError(null); clearPairing();
    try {
      const result = await post<Pairing>('/api/mobile/pairings', { server: server.trim() });
      // Retain only display fields; the opaque QR token never needs its own state.
      if (mounted.current) setPairing({ qr_svg: result.qr_svg, expires_at: result.expires_at,
        pairing_code: typeof result.pairing_code === 'string' && /^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(result.pairing_code) ? result.pairing_code : undefined });
    } catch (failure) { if (mounted.current) setError(mobileErrorKey(failure)); }
    finally { if (mounted.current) setBusy(false); }
  }

  async function copyCode() {
    if (!pairing?.pairing_code || copying || pairing.expires_at * 1000 <= Date.now()) return;
    const revision = pairingRevision.current;
    const expiresAt = pairing.expires_at;
    setCopying(true); setCopyStatus(null);
    const current = () => mounted.current && revision === pairingRevision.current && expiresAt * 1000 > Date.now();
    try {
      await navigator.clipboard.writeText(pairing.pairing_code);
      if (current()) setCopyStatus('copied');
    } catch { if (current()) setCopyStatus('copyFailed'); }
    finally { if (current()) setCopying(false); }
  }

  async function renew(id: string, snapshot: Device) {
    // Duplicate mutations are suppressed synchronously, before any await.
    if (pendingMutations.current.has(id) || !mounted.current) return;
    const wasRevoked = deviceStatus(snapshot) === 'revoked';
    if (wasRevoked && restoreConfirm !== id) return;
    pendingMutations.current.add(id);
    setDeviceError(null); setDeviceNotice(null); setBusy(true);
    // Ignore in-flight polls captured before the renewal; a fresh list replaces this row.
    deviceRevision.current++;
    const restoreOpen = restoreConfirm === id;
    try {
      const result = await post<{ ok: boolean; device: Device | null }>(
        `/api/mobile/devices/${encodeURIComponent(id)}/renew`, renewalBody(snapshot));
      const renewed = result?.device;
      if (result?.ok !== true || !renewed || renewed.device_id !== id ||
          renewed.status !== 'active' || renewed.revoked_at != null ||
          typeof renewed.expires_at !== 'number' || !Number.isFinite(renewed.expires_at)) {
        throw Object.assign(new Error('invalid_device_response'), { status: 502 });
      }
      if (mounted.current) {
        deviceRevision.current++;
        setDevices(current => current.map(item =>
          item.device_id === id ? renewed : item));
        if (restoreOpen) closeRestore(id);
        setDeviceNotice(wasRevoked ? 'accessRestored' : 'sessionExtended');
      }
    } catch (failure) { if (mounted.current) setDeviceError(mobileErrorKey(failure, 'renew')); }
    finally { pendingMutations.current.delete(id); if (mounted.current) setBusy(false); }
  }

  async function revoke(id: string) {
    if (pendingMutations.current.has(id) || !mounted.current) return;
    pendingMutations.current.add(id);
    setDeviceError(null); setDeviceNotice(null); setBusy(true);
    deviceRevision.current++;
    try {
      await del(`/api/mobile/devices/${encodeURIComponent(id)}`);
      if (mounted.current) {
        deviceRevision.current++;
        // Keep the row: mark it revoked instead of hiding it.
        setDevices(current => current.map(item => item.device_id === id
          ? { ...item, revoked_at: item.revoked_at ?? Math.floor(Date.now() / 1000), status: 'revoked' }
          : item));
      }
    } catch (failure) { if (mounted.current) setDeviceError(mobileErrorKey(failure, 'revoke')); }
    finally { pendingMutations.current.delete(id); if (mounted.current) setBusy(false); }
  }

  return <section className="mb-5 rounded-xl border border-line p-4">
    <h2 className="flex min-h-11 items-center gap-3 text-[14px] font-medium text-ink">
      <Smartphone size={18} aria-hidden="true" />{t('title')}
    </h2>
    <div className="mt-3 space-y-3">
      <label htmlFor="mobile-api-origin" className="block text-[12px] text-ink-2">{t('server')}</label>
      <Input id="mobile-api-origin" value={server} disabled={busy} onChange={event => { setServer(event.target.value); clearPairing(); setError(null); }} placeholder={t('placeholder')} type="url" autoComplete="off" />
      <Button onClick={() => { void create(); }} disabled={busy}>{t(busy ? 'working' : 'create')}</Button>
      {error && <p role="alert" className="text-[13px] text-red-600">{t(error)}</p>}
      {pairing && !expired && pairing.expires_at * 1000 > Date.now() && <div className="space-y-2">
        <div className="flex flex-wrap items-center justify-center gap-4">
          {imageUrl && <img src={imageUrl} onError={() => setImageUrl('')} alt={t('qr')} width={300} height={300} className="aspect-square w-full max-w-[300px] rounded-xl bg-white" />}
          {pairing.pairing_code && <div className="min-w-0 max-w-full space-y-2 text-center">
            <p id="mobile-pairing-code-label" className="text-[12px] text-ink-2">{t('pairingCode')}</p>
            <code aria-labelledby="mobile-pairing-code-label" className="block select-all whitespace-nowrap font-mono text-[24px] font-semibold tracking-widest text-ink">{pairing.pairing_code}</code>
            <Button variant="ghost" disabled={copying} onClick={() => { void copyCode(); }}>{t('copyCode')}</Button>
            {copyStatus && <p role="status" className="max-w-[260px] text-[12px] text-ink-2">{t(copyStatus)}</p>}
          </div>}
        </div>
        <p className="text-[12px] text-ink-2">{t(pairing.pairing_code ? 'scan' : 'scanQr')}</p>
        <p role="timer" className="text-[12px] text-ink-2">{t('pairingExpires').replace('{seconds}', String(remaining))}</p>
        <Button variant="ghost" onClick={clearPairing}>{t('close')}</Button>
      </div>}
      {expired && <p className="text-[12px] text-ink-2">{t('expired')}</p>}
      <h2 className="pt-2 text-[13px] font-medium text-ink">{t('devices')}</h2>
      {deviceError && <p role="alert" className="text-[13px] text-red-600">{t(deviceError)}</p>}
      {!deviceError && deviceNotice && <p role="status" className="text-[12px] text-ink-2">{t(deviceNotice)}</p>}
      {devicesLoading ? <p role="status" className="text-[12px] text-ink-3">{t('loadingDevices')}</p> : !deviceError && devices.length === 0 && <p className="text-[12px] text-ink-3">{t('empty')}</p>}
      {devices.map(device => {
        const status = deviceStatus(device);
        const attention = attentionKind(device);
        const restoreOpen = restoreConfirm === device.device_id;
        const restoreId = `mobile-restore-${encodeURIComponent(device.device_id)}`;
        const actionRef = (node: HTMLButtonElement | null) => {
          if (node) deviceActions.current.set(device.device_id, node);
          else deviceActions.current.delete(device.device_id);
        };
        return <div key={device.device_id} className="border-t border-line py-3">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="flex min-w-0 items-start gap-3">
              <Smartphone className="mt-0.5 shrink-0 text-ink-2" size={18} aria-hidden="true" />
              <div className="min-w-0">
                <span className="block truncate text-[13px] text-ink">{device.device_name}</span>
                <span className="block text-[11px] text-ink-3">{device.platform} · {t('pairedWithApp')}</span>
                <span className="block font-mono text-[11px] text-ink-3">{t(statusLabels[status])}</span>
                {deviceDate(device.created_at) && <span className="block text-[11px] text-ink-3">{t('connectedAt')}: {deviceDate(device.created_at)}</span>}
                {deviceDate(device.expires_at) && <span className="block text-[11px] text-ink-3">{t('expiresAt')}: {deviceDate(device.expires_at)}</span>}
                {deviceDate(device.revoked_at) && <span className="block text-[11px] text-ink-3">{t('revokedAt')}: {deviceDate(device.revoked_at)}</span>}
                {deviceDate(device.last_auth_failure_at) && <span className="block text-[11px] text-ink-3">{t('lastAuthFailedAt')}: {deviceDate(device.last_auth_failure_at)}</span>}
                {attention && <p role="alert" className="mt-1 max-w-[34rem] text-[12px] text-red-600">
                  {t(attention === 'revoked' ? 'sessionAttentionRevoked' : 'sessionAttentionExpired')}
                </p>}
              </div>
            </div>
            <div className="flex shrink-0 flex-wrap items-center gap-2">
              <Button ref={actionRef} variant="ghost" size="sm" disabled={busy}
                aria-expanded={status === 'revoked' ? restoreOpen : undefined} aria-controls={restoreOpen ? restoreId : undefined} onClick={() => {
                if (status !== 'revoked') void renew(device.device_id, device);
                else if (restoreOpen) closeRestore(device.device_id);
                else setRestoreConfirm(device.device_id);
              }}>{t(status === 'revoked' ? 'repair' : 'extend')}</Button>
              {status !== 'revoked' && <Button variant="ghost" size="sm" disabled={busy} onClick={() => { void revoke(device.device_id); }}>{t('revoke')}</Button>}
            </div>
          </div>
          {restoreOpen && <div id={restoreId} role="group" aria-labelledby={`${restoreId}-title`} aria-describedby={`${restoreId}-body`}
            onKeyDown={event => { if (event.key === 'Escape' && !busy) { event.stopPropagation(); closeRestore(device.device_id); } }}
            className="mt-3 space-y-2 rounded-md border border-line bg-surface-2 p-3">
            <p id={`${restoreId}-title`} className="text-[13px] font-medium text-ink">{t('restoreTitle')}</p>
            <p id={`${restoreId}-body`} className="text-[12px] text-ink-2">{t('restoreBody')}</p>
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" size="sm" disabled={busy} onClick={() => { void renew(device.device_id, device); }}>{t('restoreConfirm')}</Button>
              <Button ref={restoreCancel} variant="ghost" size="sm" disabled={busy} onClick={() => { closeRestore(device.device_id); }}>{t('cancel')}</Button>
            </div>
          </div>}
        </div>;
      })}
    </div>
  </section>;
}
