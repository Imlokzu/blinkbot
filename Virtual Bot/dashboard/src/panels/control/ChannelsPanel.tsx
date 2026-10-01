import { Radio } from 'lucide-react';
import { Empty } from '@/components/ui/Feedback';
import { Panel } from '@/components/ui/Panel';
import { t } from '@/locales/control';
import { ControlLayout, date, Fact, Metrics, QueryState } from './ControlLayout';
import { channelNeedsAttention, type Channel } from './data';
import { useControl } from './queries';

export default function ChannelsPanel() {
  const query = useControl<{ channels: Channel[]; updated_at: number | null }>('channels');
  const channels = query.data?.channels ?? [];
  return <ControlLayout page="channels" title={t('channels.title')} hint={t('channels.hint')}
    refreshing={query.isFetching} updated={query.dataUpdatedAt} onRefresh={() => void query.refetch()}>
    <QueryState pending={query.isPending} error={query.error} hasData={!!query.data}>
      <Metrics values={[{ label: t('channels.accounts'), value: channels.length },
        { label: t('channels.running'), value: channels.filter((channel) => channel.running).length },
        { label: t('channels.attention'), value: channels.filter(channelNeedsAttention).length }]} />
      {!channels.length ? <Empty icon={Radio} title={t('channels.empty')} hint={t('channels.emptyHint')}
        action={<a href="https://docs.openclaw.ai/channels" target="_blank" rel="noreferrer">{t('channels.settings')}</a>} /> :
        <div className="grid items-start gap-4 min-[960px]:grid-cols-2">
          {channels.map((channel) => <Panel key={channel.id} data-channel={channel.channel}>
            <header className="mb-5 flex items-center gap-3">
              <Radio size={21} strokeWidth={1.75} className="shrink-0 text-ink-3" />
              <div className="min-w-0"><h2 className="break-words text-[18px] font-semibold text-ink">{channel.label}</h2>
                <p className="font-mono text-[10px] text-ink-3">{t('channels.account', { id: channel.id.slice(0, 8) })}</p></div>
            </header>
            <div className="grid grid-cols-1 gap-4 min-[480px]:grid-cols-2">
              <Fact label={t('channels.configured')} value={channel.configured === null ? t('unknown') : channel.configured ? t('channels.yes') : t('channels.no')} />
              <Fact label={t('channels.process')} value={channel.running === null ? t('unknown') : channel.running ? t('channels.running') : t('channels.stopped')} />
              <Fact label={t('channels.connection')} value={channel.connected === null ? t('unknown') : channel.connected ? t('channels.connected') : t('channels.disconnected')} />
              <Fact label={t('status.enabled')} value={channel.enabled === null ? t('unknown') : channel.enabled ? t('channels.yes') : t('channels.no')} />
              <Fact label={t('channels.inbound')} value={date(channel.last_inbound)} />
              <Fact label={t('channels.outbound')} value={date(channel.last_outbound)} />
            </div>
            {channel.has_error ? <p className="mt-4 border-t border-err/25 pt-3 text-[12px] text-err">{t('channels.error')}</p> : null}
          </Panel>)}
        </div>}
      <aside className="flex flex-wrap items-start justify-between gap-4 border-t border-line pt-4">
        <p className="max-w-[65ch] text-[12px] text-ink-3">{t('channels.note')}</p>
        <a href="https://docs.openclaw.ai/channels" target="_blank" rel="noreferrer" className="text-[12px]">{t('channels.settings')}</a>
      </aside>
    </QueryState>
  </ControlLayout>;
}
