import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink, RefreshCw, Send } from '../../vendor/solar-icons/compat.ts';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Field';
import { ErrorNote, SkeletonList } from '@/components/ui/Feedback';
import { useToast } from '@/components/ui/Toaster';
import { get, post } from '@/lib/api';
import { cn } from '@/lib/cn';
import { codeText, t } from '@/locales/integrations';
import { SettingGroup, SettingRow } from './SettingRow';

/*
 * Settings → Integrations.
 *
 * Messengers are the same chat in another app, so each card is only what it
 * takes to get there: a token, then a one-time pairing code (a bot token
 * alone lets anyone on Telegram talk to the bot, and this bot reads files).
 * Google is an OAuth client plus a sign-in. Secrets are only ever sent to
 * the backend; what comes back is masked.
 */

interface Integration {
  id: 'telegram' | 'discord' | 'google';
  label: string;
  configured: boolean;
  connected: boolean;
  running?: boolean;
  error?: string;
  account?: string;
  token?: string;
  owners?: number;
  pair_code?: string;
  pair_link?: string;
  invite_link?: string;
  scopes?: string[];
}

function StatusDot({ item }: { item: Integration }) {
  const state = item.error ? 'err' : item.connected ? 'ok' : 'off';
  return (
    <span className="flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-[0.08em] text-ink-3">
      <span className={cn('size-2 rounded-full', state === 'ok' && 'bg-ok', state === 'err' && 'bg-err', state === 'off' && 'bg-line-strong')} />
      {item.error ? t('int.error', { error: codeText('int.state.', item.error) }) : item.connected ? t('int.connected') : t('int.notConnected')}
    </span>
  );
}

function MessengerCard({ item, onChange }: { item: Integration; onChange: () => void }) {
  const toast = useToast();
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [armed, setArmed] = useState(false);

  const run = async (action: () => Promise<unknown>, okText?: string) => {
    setBusy(true);
    try {
      await action();
      if (okText) toast.ok(okText);
      onChange();
    } catch (error) {
      toast.error(t('int.failed'), codeText('int.err.', (error as Error).message));
    } finally {
      setBusy(false);
    }
  };

  const kind = item.id === 'discord' ? 'discord' : 'telegram';
  return (
    <SettingGroup label={item.label}>
      <SettingRow label={item.account || item.label} hint={item.configured && item.token ? t('int.tokenSaved', { token: item.token }) : t(`int.tokenHint.${kind}`)}>
        <StatusDot item={item} />
      </SettingRow>
      <SettingRow label={t('int.token')} htmlFor={`${item.id}-token`}>
        <div className="flex items-center gap-2">
          <Input
            id={`${item.id}-token`}
            type="password"
            autoComplete="off"
            spellCheck={false}
            className="h-8 w-[240px] font-mono text-[12px]"
            value={token}
            onChange={(event) => setToken(event.target.value)}
          />
          <Button
            size="sm"
            variant="solid"
            disabled={busy || !token.trim()}
            onClick={() => void run(() => post(`/api/integrations/${item.id}/config`, { token: token.trim() }).then(() => setToken('')), t('int.saved'))}
          >
            {busy ? t('int.connecting') : t('int.connect')}
          </Button>
        </div>
      </SettingRow>
      {item.configured ? (
        <SettingRow
          label={t('int.pairing')}
          hint={item.pair_code ? t(`int.pairHint.${kind}`, { code: item.pair_code }) : undefined}
          mark={t('int.owners', { n: item.owners ?? 0 })}
        >
          <div className="flex flex-wrap items-center gap-2">
            {item.pair_code ? <code className="rounded-sm bg-surface-2 px-2 py-1 font-mono text-[13px] tracking-[0.14em]">{item.pair_code}</code> : null}
            {item.pair_link ? (
              <Button size="sm" variant="outline" onClick={() => window.open(item.pair_link, '_blank', 'noopener')}>
                <ExternalLink />
                {t('int.pairOpen')}
              </Button>
            ) : null}
            {item.invite_link ? (
              <Button size="sm" variant="outline" onClick={() => window.open(item.invite_link, '_blank', 'noopener')}>
                <ExternalLink />
                {t('int.invite')}
              </Button>
            ) : null}
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => void run(() => post(`/api/integrations/${item.id}/pair-code`))}>
              <RefreshCw />
              {t('int.newCode')}
            </Button>
          </div>
        </SettingRow>
      ) : null}
      {item.configured ? (
        <SettingRow label={t('int.howForwards')}>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="quiet" disabled={busy || !item.owners} onClick={() => void run(() => post(`/api/integrations/${item.id}/test`), t('int.testSent'))}>
              <Send />
              {t('int.test')}
            </Button>
            <Button
              size="sm"
              variant={armed ? 'danger' : 'ghost'}
              disabled={busy}
              onClick={() => {
                // Two clicks instead of confirm(): the token cannot be read
                // back once it is forgotten.
                if (!armed) { setArmed(true); return; }
                setArmed(false);
                void run(() => post(`/api/integrations/${item.id}/disconnect`));
              }}
            >
              {armed ? t('int.disconnectSure') : t('int.disconnect')}
            </Button>
          </div>
        </SettingRow>
      ) : null}
    </SettingGroup>
  );
}

function GoogleCard({ item, onChange }: { item: Integration; onChange: () => void }) {
  const toast = useToast();
  const [clientId, setClientId] = useState('');
  const [secret, setSecret] = useState('');
  const [busy, setBusy] = useState(false);

  const saveClient = async () => {
    setBusy(true);
    try {
      await post('/api/integrations/google/config', { client_id: clientId.trim(), client_secret: secret.trim() });
      setClientId('');
      setSecret('');
      toast.ok(t('int.saved'));
      onChange();
    } catch (error) {
      toast.error(t('int.failed'), codeText('int.err.', (error as Error).message));
    } finally {
      setBusy(false);
    }
  };

  const signIn = async () => {
    try {
      const { url } = await get<{ url: string }>('/api/integrations/google/auth');
      window.open(url, '_blank', 'noopener');
    } catch (error) {
      toast.error(t('int.failed'), codeText('int.err.', (error as Error).message));
    }
  };

  return (
    <SettingGroup label={t('int.google.name')}>
      <SettingRow label={item.account || t('int.google.name')} hint={item.connected ? t('int.google.scopes') : t('int.google.hint')}>
        <StatusDot item={item} />
      </SettingRow>
      <SettingRow label={t('int.google.clientId')} htmlFor="google-client">
        <Input id="google-client" autoComplete="off" spellCheck={false} className="h-8 w-[280px] font-mono text-[12px]"
          placeholder={item.configured ? t('int.google.savedClient') : ''} value={clientId}
          onChange={(event) => setClientId(event.target.value)} />
      </SettingRow>
      <SettingRow label={t('int.google.clientSecret')} htmlFor="google-secret">
        <div className="flex items-center gap-2">
          <Input id="google-secret" type="password" autoComplete="off" className="h-8 w-[180px] font-mono text-[12px]"
            value={secret} onChange={(event) => setSecret(event.target.value)} />
          <Button size="sm" variant="outline" disabled={busy || !clientId.trim() || !secret.trim()} onClick={() => void saveClient()}>
            {t('int.google.saveClient')}
          </Button>
        </div>
      </SettingRow>
      <SettingRow label={t('int.google.connect')} hint={t('int.google.connectHint')}>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="solid" disabled={!item.configured} onClick={() => void signIn()}>
            <ExternalLink />
            {t('int.google.connect')}
          </Button>
          {item.connected ? (
            <Button size="sm" variant="ghost" onClick={() => void post('/api/integrations/google/disconnect').then(onChange)}>
              {t('int.disconnect')}
            </Button>
          ) : null}
        </div>
      </SettingRow>
    </SettingGroup>
  );
}

export function IntegrationsSection() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ['integrations'],
    queryFn: () => get<{ integrations: Integration[] }>('/api/integrations/details'),
    // Pairing happens on the phone; poll so the "Paired: 1" appears by itself.
    refetchInterval: 5_000,
  });
  const refresh = () => void client.invalidateQueries({ queryKey: ['integrations'] });

  if (query.isPending) return <SkeletonList rows={6} />;
  if (query.isError) return <ErrorNote message={(query.error as Error).message} onRetry={() => void query.refetch()} />;

  return (
    <div className="space-y-6">
      <p className="px-1 text-[13px] leading-relaxed text-ink-2">{t('int.intro')}</p>
      {query.data.integrations.map((item) =>
        item.id === 'google'
          ? <GoogleCard key={item.id} item={item} onChange={refresh} />
          : <MessengerCard key={item.id} item={item} onChange={refresh} />,
      )}
    </div>
  );
}
