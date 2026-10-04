import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { BookOpen, Cable, ExternalLink } from '../../vendor/solar-icons/compat.ts';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Field';
import { useToast } from '@/components/ui/Toaster';
import { get, post } from '@/lib/api';
import { t, connectorError } from '@/locales/connectors';
import { SettingGroup, SettingRow } from './SettingRow';

export type Connector = { id: string; name: string; kind: string; enabled: boolean; agent_access: boolean; connected: boolean | null; browse: boolean };
type NotebookStatus = { installed: boolean; connected: boolean | null; profile: string; login_running: boolean; agent_access: boolean; code?: string };

export function ConnectorsSection() {
  const toast = useToast();
  const client = useQueryClient();
  const [profile, setProfile] = useState('');
  const [busy, setBusy] = useState(false);
  const inventory = useQuery({ queryKey: ['connectors'], queryFn: () => get<{ connectors: Connector[] }>('/api/connectors') });
  const status = useQuery({ queryKey: ['notebooklm-status'], queryFn: () => get<NotebookStatus>('/api/connectors/notebooklm/status'),
    refetchInterval: (query) => query.state.data?.login_running ? 2000 : false });
  useEffect(() => { if (status.data) setProfile(status.data.profile); }, [status.data?.profile]);
  useEffect(() => {
    client.removeQueries({ queryKey: ['notebooklm-notebooks'] });
    client.removeQueries({ queryKey: ['notebooklm-sources'] });
  }, [client, status.data?.profile, status.data?.connected, status.data?.login_running]);
  const run = async (action: string, body?: unknown) => {
    setBusy(true);
    try {
      const data = await post<NotebookStatus>(`/api/connectors/notebooklm/${action}`, body);
      client.setQueryData(['notebooklm-status'], data);
      void client.invalidateQueries({ queryKey: ['connectors'] });
      if (action === 'config') toast.ok(t('connectors.saved'));
    } catch (error) { toast.error(t('connectors.failed'), connectorError((error as Error).message)); }
    finally { setBusy(false); }
  };
  const data = status.data;
  const unsavedProfile = profile.trim() !== (data?.profile ?? '');
  return (
    <div className="space-y-6">
      <p className="text-[13px] text-ink-3">{t('connectors.hint')}</p>
      {status.error || inventory.error ? <p role="alert" className="text-[13px] text-err">{connectorError(((status.error || inventory.error) as Error).message)}</p> : null}
      <SettingGroup label={t('connectors.notebooklm')}>
        <SettingRow label={t('connectors.profile')} hint={t('connectors.profileHint')} htmlFor="notebook-profile">
          <Input id="notebook-profile" value={profile} onChange={(event) => setProfile(event.target.value)} disabled={busy || data?.login_running} className="h-8 w-[200px] font-mono text-[12px]" />
        </SettingRow>
        <div className="space-y-3 px-4 py-3">
          <div role="status" className="text-[13px] text-ink-2">
            {data?.connected ? t('connectors.connected') : data?.code ? connectorError(data.code) : t('connectors.unchecked')}
            {data?.agent_access ? <p className="mt-1 text-ok">{t('connectors.agentReady')}</p> : null}
            {data?.login_running ? <p className="mt-1 text-warn">{t('connectors.loginRunning')}</p> : null}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" disabled={busy || data?.login_running} onClick={() => void run('config', { profile: profile.trim() })}>{t('connectors.save')}</Button>
            <Button size="sm" variant="outline" disabled={busy || unsavedProfile || !data?.installed || data?.login_running} onClick={() => void run('check')}>{t('connectors.check')}</Button>
            <Button size="sm" variant="outline" disabled={busy || unsavedProfile || !data?.installed || data?.login_running} onClick={() => void run('login')}>{t('connectors.login')}</Button>
            <Button size="sm" variant="solid" disabled={busy || unsavedProfile || !data?.connected || data?.login_running} onClick={() => void run('enable')}><BookOpen />{t('connectors.enable')}</Button>
          </div>
        </div>
      </SettingGroup>
      <SettingGroup label={t('connectors.openclaw')}>
        {(inventory.data?.connectors ?? []).filter((item) => item.kind !== 'notebooklm').map((item) => (
          <SettingRow key={item.id} label={item.name} hint={item.enabled ? t(item.agent_access ? 'connectors.agentReady' : 'connectors.agentMissing') : t('connectors.disabled')}>
            <Button size="sm" variant="ghost" onClick={() => { location.hash = '#/settings?tab=mcp'; }}><Cable /><ExternalLink />{t('connectors.openMcp')}</Button>
          </SettingRow>
        ))}
      </SettingGroup>
    </div>
  );
}
