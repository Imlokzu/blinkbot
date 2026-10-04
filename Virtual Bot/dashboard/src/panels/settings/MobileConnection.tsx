import { useEffect, useRef, useState } from 'react';
import { Smartphone } from '../../vendor/solar-icons/compat.ts';
import { get, post, del } from '@/lib/api';
import { useLanguage } from '@/hooks/useLanguage';
import { t } from '@/locales/mobile';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Field';
import { mobileErrorKey, type MobileErrorKey } from './mobileConnectionErrors';

interface Pairing { qr_svg: string; expires_at: number }
interface Device {
  device_id: string;
  device_name: string;
  platform: string;
  created_at?: number | null;
  expires_at?: number | null;
  revoked_at: number | null;
}

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
  const [devicesLoading, setDevicesLoading] = useState(true);
  const [pairing, setPairing] = useState<Pairing | null>(null);
  const [imageUrl, setImageUrl] = useState('');
  const [expired, setExpired] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const mounted = useRef(false);
  const deviceRevision = useRef(0);

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
          setDevices(data.devices.filter(device => !device.revoked_at));
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
    if (!pairing) { setImageUrl(''); setExpired(false); return; }
    if (pairing.expires_at * 1000 <= Date.now()) { setImageUrl(''); setExpired(true); return; }
    const url = URL.createObjectURL(new Blob([pairing.qr_svg], { type: 'image/svg+xml' }));
    setImageUrl(url);
    setExpired(false);
    const timer = window.setTimeout(() => { setExpired(true); setImageUrl(''); }, Math.max(0, pairing.expires_at * 1000 - Date.now()));
    return () => { URL.revokeObjectURL(url); window.clearTimeout(timer); };
  }, [pairing]);

  async function create() {
    setBusy(true); setError(null); setPairing(null);
    try {
      const result = await post<Pairing>('/api/mobile/pairings', { server: server.trim() });
      if (mounted.current) setPairing(result);
    } catch (failure) { if (mounted.current) setError(mobileErrorKey(failure)); }
    finally { if (mounted.current) setBusy(false); }
  }

  async function revoke(id: string) {
    setDeviceError(null); setBusy(true);
    deviceRevision.current++;
    try {
      await del(`/api/mobile/devices/${encodeURIComponent(id)}`);
      if (mounted.current) {
        deviceRevision.current++;
        setDevices(current => current.filter(device => device.device_id !== id));
      }
    } catch (failure) { if (mounted.current) setDeviceError(mobileErrorKey(failure, 'revoke')); }
    finally { if (mounted.current) setBusy(false); }
  }

  return <section className="mb-5 rounded-xl border border-line p-4">
    <h2 className="flex min-h-11 items-center gap-3 text-[14px] font-medium text-ink">
      <Smartphone size={18} aria-hidden="true" />{t('title')}
    </h2>
    <div className="mt-3 space-y-3">
      <label htmlFor="mobile-api-origin" className="block text-[12px] text-ink-2">{t('server')}</label>
      <Input id="mobile-api-origin" value={server} disabled={busy} onChange={event => { setServer(event.target.value); setPairing(null); setError(null); }} placeholder={t('placeholder')} type="url" autoComplete="off" />
      <Button onClick={() => { void create(); }} disabled={busy}>{t(busy ? 'working' : 'create')}</Button>
      {error && <p role="alert" className="text-[13px] text-red-600">{t(error)}</p>}
      {imageUrl && <div className="space-y-2">
        <img src={imageUrl} alt={t('qr')} width={300} height={300} className="mx-auto aspect-square w-full max-w-[300px] rounded-xl bg-white" />
        <p className="text-[12px] text-ink-2">{t('scan')}</p>
        <Button variant="ghost" onClick={() => setPairing(null)}>{t('close')}</Button>
      </div>}
      {expired && <p className="text-[12px] text-ink-2">{t('expired')}</p>}
      <h2 className="pt-2 text-[13px] font-medium text-ink">{t('devices')}</h2>
      {deviceError && <p role="alert" className="text-[13px] text-red-600">{t(deviceError)}</p>}
      {devicesLoading ? <p role="status" className="text-[12px] text-ink-3">{t('loadingDevices')}</p> : !deviceError && devices.length === 0 && <p className="text-[12px] text-ink-3">{t('empty')}</p>}
      {devices.map(device => <div key={device.device_id} className="flex flex-wrap items-center justify-between gap-3 border-t border-line py-3">
        <div className="flex min-w-0 items-start gap-3">
          <Smartphone className="mt-0.5 shrink-0 text-ink-2" size={18} aria-hidden="true" />
          <div className="min-w-0">
            <span className="block truncate text-[13px] text-ink">{device.device_name}</span>
            <span className="block text-[11px] text-ink-3">{device.platform} · {t('pairedViaQr')}</span>
            {deviceDate(device.created_at) && <span className="block text-[11px] text-ink-3">{t('connectedAt')}: {deviceDate(device.created_at)}</span>}
            {deviceDate(device.expires_at) && <span className="block text-[11px] text-ink-3">{t('expiresAt')}: {deviceDate(device.expires_at)}</span>}
          </div>
        </div>
        <Button variant="ghost" disabled={busy} onClick={() => { void revoke(device.device_id); }}>{t('revoke')}</Button>
      </div>)}
    </div>
  </section>;
}
