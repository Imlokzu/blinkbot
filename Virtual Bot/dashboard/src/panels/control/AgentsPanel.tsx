import { useState } from 'react';
import { ArrowUpRight, Bot } from '../../vendor/solar-icons/compat.ts';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Field';
import { Empty } from '@/components/ui/Feedback';
import { Panel } from '@/components/ui/Panel';
import { t } from '@/locales/control';
import { ControlLayout, Fact, Metrics, NoMatches, QueryState } from './ControlLayout';
import { matches, type Agent } from './data';
import { useControl } from './queries';

export default function AgentsPanel() {
  const query = useControl<{ agents: Agent[] }>('agents');
  const [search, setSearch] = useState('');
  const agents = query.data?.agents ?? [];
  const visible = agents.filter((agent) => matches(search, agent.name, agent.id, agent.model, agent.runtime, ...agent.fallbacks));
  return <ControlLayout page="agents" title={t('agents.title')} hint={t('agents.hint')}
    refreshing={query.isFetching} updated={query.dataUpdatedAt} onRefresh={() => void query.refetch()}>
    <QueryState pending={query.isPending} error={query.error} hasData={!!query.data}>
      <Metrics values={[{ label: t('agents.count'), value: agents.length },
        { label: t('agents.routes'), value: new Set(agents.flatMap((agent) => [agent.model, ...agent.fallbacks]).filter(Boolean)).size },
        { label: t('agents.workspaces'), value: agents.filter((agent) => agent.workspace_configured).length }]} />
      <Input aria-label={t('search')} placeholder={t('search')} value={search} onChange={(event) => setSearch(event.target.value)} className="max-w-md" />
      {!agents.length ? <Empty icon={Bot} title={t('agents.empty')} /> : !visible.length ? <NoMatches /> :
        <div className="grid items-start gap-4 min-[960px]:grid-cols-2">
          {visible.map((agent) => <Panel key={agent.id} data-agent={agent.id}>
            <header className="mb-5 flex min-w-0 items-start gap-3">
              <div className="flex size-10 shrink-0 items-center justify-center rounded-md bg-surface-2 text-ink-2"><Bot size={22} strokeWidth={1.75} /></div>
              <div className="min-w-0 flex-1"><h2 className="break-words text-[18px] font-semibold text-ink [overflow-wrap:anywhere]">{agent.name || agent.id}</h2>
                <p className="break-all font-mono text-[11px] text-ink-3">{agent.id}</p></div>
              {agent.default ? <span className="rounded-sm bg-surface-2 px-2 py-1 text-[11px] text-ink-2">{t('agents.default')}</span> : null}
            </header>
            <div className="grid grid-cols-1 gap-4 min-[480px]:grid-cols-2">
              <Fact label={t('agents.model')} value={agent.model} /><Fact label={t('agents.runtime')} value={agent.runtime} />
              <Fact label={t('agents.thinking')} value={agent.thinking} />
              <Fact label={t('agents.workspace')} value={agent.workspace_configured ? t('channels.yes') : t('channels.no')} />
              <div className="min-w-0 min-[480px]:col-span-2"><Fact label={t('agents.fallbacks')}
                value={agent.fallbacks.length ? <ul className="space-y-1">{agent.fallbacks.map((model, index) => <li key={`${model}:${index}`}>{model}</li>)}</ul> : t('none')} /></div>
            </div>
            <footer className="mt-5 flex flex-wrap gap-2 border-t border-line pt-4">
              <Button size="sm" variant="outline" asChild><a href={`#/sessions?agent=${encodeURIComponent(agent.id)}`}>{t('agents.sessions')}<ArrowUpRight /></a></Button>
              <Button size="sm" variant="ghost" asChild><a href={`#/automation?agent=${encodeURIComponent(agent.id)}`}>{t('agents.jobs')}<ArrowUpRight /></a></Button>
            </footer>
          </Panel>)}
        </div>}
      <aside className="flex flex-wrap items-start justify-between gap-4 border-t border-line pt-4">
        <p className="max-w-[65ch] text-[12px] text-ink-3">{t('agents.note')}</p>
        <a href="#/settings?tab=brain" className="text-[12px]">{t('agents.settings')}</a>
      </aside>
    </QueryState>
  </ControlLayout>;
}
