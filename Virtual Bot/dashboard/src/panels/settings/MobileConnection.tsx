import { useEffect, useState } from 'react';
import { Smartphone } from 'lucide-react';
import { get, post, del } from '@/lib/api';
import { useLanguage } from '@/hooks/useLanguage';
import { t } from '@/locales/mobile';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Field';

interface Pairing { qr_svg: string; expires_at: number }
interface Device { device_id: string; device_name: string; platform: string; revoked_at: number | null }

export function MobileConnection() {
  useLanguage();
  const [open, setOpen] = useState(false);
  const [server, setServer] = useState('https://api-bot.waveio.me');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const [pairing, setPairing] = useState<Pairing | null>(null);
  const [imageUrl, setImageUrl] = useState('');
  const [expired, setExpired] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    const load = async () => {
      try {
        const data = await get<{ devices: Device[] }>('/api/mobile/devices');
        if (!cancelled) setDevices(data.devices.filter(device => !device.revoked_at));
      } catch { if (!cancelled) setError(true); }
    };
    void load();
    const timer = window.setInterval(() => { void load(); }, 10000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [open]);

  useEffect(() => {
    if (!pairing || !open) { setImageUrl(''); return; }
    const url = URL.createObjectURL(new Blob([pairing.qr_svg], { type: 'image/svg+xml' }));
    setImageUrl(url);
    setExpired(false);
    const timer = window.setTimeout(() => { setExpired(true); setImageUrl(''); }, Math.max(0, pairing.expires_at * 1000 - Date.now()));
    return () => { URL.revokeObjectURL(url); window.clearTimeout(timer); };
  }, [pairing, open]);

  async function create() {
    setBusy(true); setError(false); setPairing(null);
    try {
      const result = await post<Pairing>('/api/mobile/pairings', { server: server.trim() });
      setPairing(result);
    } catch { setError(true); }
    finally { setBusy(false); }
  }

  async function revoke(id: string) {
    setError(false); setBusy(true);
    try { await del(`/api/mobile/devices/${encodeURIComponent(id)}`); setDevices(current => current.filter(device => device.device_id !== id)); }
    catch { setError(true); }
    finally { setBusy(false); }
  }

  return <section className="mb-5 rounded-xl border border-line p-4">
    <button type="button" aria-expanded={open} className="flex min-h-11 w-full items-center gap-3 text-left text-[14px] font-medium text-ink" onClick={() => setOpen(value => !value)}>
      <Smartphone size={18} aria-hidden="true" />{t('title')}
    </button>
    {open && <div className="mt-3 space-y-3">
      <label htmlFor="mobile-api-origin" className="block text-[12px] text-ink-2">{t('server')}</label>
      <Input id="mobile-api-origin" value={server} onChange={event => setServer(event.target.value)} placeholder={t('placeholder')} type="url" autoComplete="off" />
      <Button onClick={() => { void create(); }} disabled={busy}>{t(busy ? 'working' : 'create')}</Button>
      {error && <p role="alert" className="text-[13px] text-red-600">{t('failed')}</p>}
      {imageUrl && <div className="space-y-2">
        <img src={imageUrl} alt={t('qr')} width={300} height={300} className="mx-auto aspect-square w-full max-w-[300px] rounded-xl bg-white" />
        <p className="text-[12px] text-ink-2">{t('scan')}</p>
        <Button variant="ghost" onClick={() => setPairing(null)}>{t('close')}</Button>
      </div>}
      {expired && <p className="text-[12px] text-ink-2">{t('expired')}</p>}
      <h2 className="pt-2 text-[13px] font-medium text-ink">{t('devices')}</h2>
      {devices.length === 0 && <p className="text-[12px] text-ink-3">{t('empty')}</p>}
      {devices.map(device => <div key={device.device_id} className="flex flex-wrap items-center justify-between gap-2 border-t border-line py-2">
        <span className="text-[13px] text-ink">{device.device_name}</span>
        <Button variant="ghost" disabled={busy} onClick={() => { void revoke(device.device_id); }}>{t('revoke')}</Button>
      </div>)}
    </div>}
  </section>;
}
