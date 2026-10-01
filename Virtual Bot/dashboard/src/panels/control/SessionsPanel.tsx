import { useEffect, useState } from 'react';
import { MessagesSquare } from 'lucide-react';
import { useRouteParam } from '@/app/useRoute';
import { Input, Select } from '@/components/ui/Field';
import { Empty } from '@/components/ui/Feedback';
import { t, type ControlKey } from '@/locales/control';
import { ControlLayout, count, date, Fact, Metrics, NoMatches, PageControls, QueryState, State } from './ControlLayout';
import { contextPercent, matches, sessionStatus, type Agent, type SessionsReport } from './data';
import { useControl } from './queries';

export default function SessionsPanel() {
  const initialAgent = useRouteParam('agent');
  const [agent, setAgent] = useState(initialAgent);
  const [offset, setOffset] = useState(0);
  const [history, setHistory] = useState<number[]>([]);
  const [search, setSearch] = useState('');
  useEffect(() => { setAgent(initialAgent); setOffset(0); setHistory([]); }, [initialAgent]);
  const agents = useControl<{ agents: Agent[] }>('agents');
  const query = useControl<SessionsReport>('sessions', { offset, agent });
  const sessions = query.data?.sessions ?? [];
  const visible = sessions.filter((session) => matches(search, session.id, session.model, session.provider, session.agent));
  const fresh = sessions.filter((session) => session.tokens_fresh && session.tokens !== null);
  return <ControlLayout page="sessions" title={t('sessions.title')} hint={t('sessions.hint')}
    refreshing={query.isFetching} updated={query.dataUpdatedAt} onRefresh={() => void query.refetch()}>
    <QueryState pending={query.isPending} error={query.error} hasData={!!query.data}>
      <Metrics values={[{ label: t('sessions.loaded'), value: sessions.length },
        { label: t('sessions.active'), value: sessions.filter((session) => session.active).length },
        { label: t('sessions.tokens'), value: fresh.length ? count(fresh.reduce((sum, session) => sum + session.tokens!, 0)) : t('unknown') }]} />
      <div className="flex flex-wrap gap-3">
        <Input className="min-w-0 flex-1 basis-[240px]" aria-label={t('search')} placeholder={t('search')} value={search} onChange={(event) => setSearch(event.target.value)} />
        <Select className="w-full sm:w-52" aria-label={t('sessions.agent')} value={agent} onChange={(event) => {
          setAgent(event.target.value); setOffset(0); setHistory([]);
        }}>
          <option value="">{t('allAgents')}</option>
          {agent && !agents.data?.agents.some((item) => item.id === agent) ? <option value={agent}>{agent}</option> : null}
          {agents.data?.agents.map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}
        </Select>
      </div>
      {!sessions.length ? <Empty icon={MessagesSquare} title={t('sessions.empty')} /> : !visible.length ? <NoMatches /> :
        <ul className="divide-y divide-line rounded-lg border border-line bg-surface px-4 sm:px-5">
          {visible.map((session) => {
            const percentage = contextPercent(session);
            const status = sessionStatus(session);
            const sourceKey = `source.${session.source}` as ControlKey;
            return <li key={session.id} data-gateway-session={session.id} className="py-5">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2.5"><span className="font-mono text-[12px] text-ink">{session.agent || t('unknown')}</span>
                  <span className="rounded-sm bg-surface-2 px-2 py-0.5 text-[11px] text-ink-3">{t(sourceKey)}</span></div>
                <State value={status ? `status.${status}` : 'unknown'} />
              </div>
              <div className="grid grid-cols-1 gap-4 min-[480px]:grid-cols-2 min-[960px]:grid-cols-[1.4fr_1fr_1fr]">
                <div><Fact label={t('sessions.model')} value={session.model} />
                  <p className="mt-1 break-all font-mono text-[11px] text-ink-3">{session.provider}</p></div>
                <div><Fact label={t('sessions.context')} value={count(session.tokens)} />
                  <p className="mt-1 text-[11px] text-ink-3">{session.tokens === null ? t('unknown') : session.tokens_fresh ? t('sessions.fresh') : t('sessions.staleTokens')}</p>
                  {percentage !== null ? <div className="mt-2 max-w-56">
                    <div role="meter" aria-label={t('sessions.context')} aria-valuenow={percentage} aria-valuemin={0} aria-valuemax={100}
                      aria-valuetext={t('sessions.contextUsed', { percent: percentage, window: count(session.context_window) })}
                      className="h-1 overflow-hidden rounded-full bg-surface-3">
                      <div className="h-full bg-ink-3" style={{ width: `${percentage}%` }} />
                    </div>
                    <p className="mt-1 font-mono text-[10px] text-ink-3">{t('sessions.contextUsed', { percent: percentage, window: count(session.context_window) })}</p>
                  </div> : null}
                </div>
                <div><Fact label={t('sessions.activity')} value={date(session.updated_at)} />
                  <p className="mt-1 break-all font-mono text-[10px] text-ink-3">{t('sessions.metadata', { id: session.id.slice(0, 10) })}</p></div>
              </div>
            </li>;
          })}
        </ul>}
      {query.data ? <PageControls page={query.data} length={sessions.length} history={history} busy={query.isFetching} onOffset={(next, back) => {
        setHistory((old) => back ? old.slice(0, -1) : [...old, offset]); setOffset(next); setSearch('');
      }} /> : null}
      <aside className="flex flex-wrap items-start justify-between gap-4">
        <p className="max-w-[65ch] text-[12px] text-ink-3">{t('sessions.note')}</p>
        <a href="#/inference" className="text-[12px]">{t('sessions.usage')}</a>
      </aside>
    </QueryState>
  </ControlLayout>;
}
