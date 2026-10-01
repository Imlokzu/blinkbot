import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { CalendarClock, ChevronDown, Pause, Play, Plus } from 'lucide-react';
import { useRouteParam } from '@/app/useRoute';
import { Button } from '@/components/ui/Button';
import { Input, Select } from '@/components/ui/Field';
import { Empty } from '@/components/ui/Feedback';
import { Panel } from '@/components/ui/Panel';
import { post } from '@/lib/api';
import { t } from '@/locales/control';
import { ControlLayout, count, date, Fact, Metrics, NoMatches, Notice, PageControls, QueryState, State } from './ControlLayout';
import { matches, type Agent, type Job, type JobRun, type JobsReport, type Schedule } from './data';
import { NewJobForm } from './NewJobForm';
import { CONTROL_KEY, useControl } from './queries';

function scheduleText(schedule: Schedule): string {
  if (schedule.kind === 'every') return schedule.every_ms == null ? t('unknown') : t('jobs.scheduleEvery', { minutes: count(schedule.every_ms / 60000) });
  if (schedule.kind === 'cron') return t('jobs.scheduleDaily', { expression: schedule.expression || t('unknown'), timezone: schedule.timezone || t('unknown') });
  if (schedule.kind === 'at') return t('jobs.scheduleAt', { time: date(Date.parse(schedule.at || '')) });
  return t('jobs.scheduleOther');
}

function RunHistory({ job }: { job: Job }) {
  const query = useControl<{ runs: JobRun[]; has_more: boolean }>(`jobs/${encodeURIComponent(job.id)}/runs`);
  return <div id={`history-${job.id}`} role="region" aria-label={t('jobs.history')} className="mt-4 border-t border-line pt-4">
    <QueryState pending={query.isPending} error={query.error} hasData={!!query.data}>
      {!query.data?.runs.length ? <p className="text-[12px] text-ink-3">{t('jobs.noHistory')}</p> :
        <ol className="divide-y divide-line">
          {query.data.runs.map((run, index) => <li key={`${run.at}:${index}`} className="flex flex-wrap items-center justify-between gap-2 py-2 text-[11px]">
            <span className="font-mono text-ink-3">{date(run.at)}</span>
            <State value={run.status ? `status.${run.status}` : 'unknown'} />
            <span className="break-all font-mono text-ink-3">{run.model || t('unknown')}</span>
            <span className="font-mono text-ink-3">{run.duration_ms === null ? t('unknown') : t('jobs.duration', { seconds: count(Math.round(run.duration_ms / 100) / 10) })}</span>
          </li>)}
        </ol>}
    {query.data?.has_more ? <p className="mt-2 text-[11px] text-ink-3">{t('jobs.historyLimit')}</p> : null}
    </QueryState>
  </div>;
}

export default function AutomationPanel() {
  const routeAgent = useRouteParam('agent');
  const [agent, setAgent] = useState(routeAgent);
  const [offset, setOffset] = useState(0);
  const [history, setHistory] = useState<number[]>([]);
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);
  const [success, setSuccess] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const createButton = useRef<HTMLButtonElement>(null);
  const changing = useRef(false);
  const client = useQueryClient();
  const agents = useControl<{ agents: Agent[] }>('agents');
  const query = useControl<JobsReport>('jobs', { offset, agent });
  const change = useMutation({ mutationFn: (job: Job) => post(`/api/openclaw/control/jobs/${encodeURIComponent(job.id)}/enabled`, { enabled: !job.enabled, revision: job.revision }),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: [...CONTROL_KEY, 'jobs'] }); setSuccess(t('jobs.changed')); },
    onSettled: () => { changing.current = false; } });
  useEffect(() => { setAgent(routeAgent); setOffset(0); setHistory([]); setExpanded(null); }, [routeAgent]);
  const jobs = query.data?.jobs ?? [];
  const visible = jobs.filter((job) => (!agent || job.agent === agent) && matches(search, job.name, job.agent));
  const closeForm = () => { setCreating(false); requestAnimationFrame(() => createButton.current?.focus()); };
  return <ControlLayout page="automation" title={t('jobs.title')} hint={t('jobs.hint')}
    refreshing={query.isFetching || change.isPending} updated={query.dataUpdatedAt} onRefresh={() => {
      change.reset(); void query.refetch();
      if (expanded) void client.invalidateQueries({ queryKey: [...CONTROL_KEY, `jobs/${encodeURIComponent(expanded)}/runs`] });
    }}
    actions={<Button ref={createButton} size="sm" variant="solid" disabled={creating || change.isPending || query.isFetching || query.isError || agents.isError || !agents.data?.agents.length || !query.data}
      onClick={() => { setCreating(true); setSuccess(''); }}><Plus />{t('jobs.new')}</Button>}>
    <QueryState pending={query.isPending} error={query.error} hasData={!!query.data}>
      <Metrics values={[{ label: t('jobs.loaded'), value: jobs.length }, { label: t('jobs.enabled'), value: jobs.filter((job) => job.enabled).length },
        { label: t('jobs.scheduler'), value: query.data?.scheduler_enabled === true ? t('jobs.schedulerOn') : query.data?.scheduler_enabled === false ? t('jobs.schedulerOff') : t('unknown') }]} />
      {query.data?.scheduler_enabled === false ? <Notice>{t('jobs.disabledNote')}</Notice> : null}
      {success ? <Notice>{success}</Notice> : null}
      {change.isError ? <Notice error>{t('jobs.failed')}</Notice> : null}
      {creating ? <NewJobForm agents={agents.data?.agents ?? []} initialAgent={agent} onCancel={closeForm}
        onCreated={() => { closeForm(); setSuccess(t('jobs.saved')); setOffset(0); setHistory([]); setSearch(''); setAgent(''); }} /> : null}
      <div className="flex flex-wrap gap-3">
        <Input className="min-w-0 flex-1 basis-[240px]" aria-label={t('search')} placeholder={t('search')} value={search} onChange={(event) => setSearch(event.target.value)} />
        <Select className="w-full sm:w-52" aria-label={t('sessions.agent')} value={agent} onChange={(event) => {
          setAgent(event.target.value); setOffset(0); setHistory([]); setExpanded(null);
        }}>
          <option value="">{t('allAgents')}</option>
          {agent && !agents.data?.agents.some((item) => item.id === agent) ? <option value={agent}>{agent}</option> : null}
          {agents.data?.agents.map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}
        </Select>
      </div>
      {!jobs.length ? <Empty icon={CalendarClock} title={t('jobs.empty')} hint={t('jobs.emptyHint')} /> : !visible.length ? <NoMatches /> :
        <div className="space-y-3">
          {visible.map((job) => <Panel key={job.id} data-job={job.id}>
            <header className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0 flex-1 basis-[240px]"><h2 className="break-words text-[16px] font-medium text-ink [overflow-wrap:anywhere]">{job.name || job.id}</h2>
                <p className="mt-1 break-words font-mono text-[11px] text-ink-3">{scheduleText(job.schedule)}</p></div>
              <State value={job.running ? 'status.running' : job.enabled ? 'status.enabled' : 'status.paused'} />
            </header>
            <div className="mt-4 grid grid-cols-1 gap-4 min-[480px]:grid-cols-3">
              <Fact label={t('sessions.agent')} value={job.agent} />
              <Fact label={t('jobs.nextRun')} value={job.enabled ? date(job.next_run) : t('status.paused')} />
              <Fact label={t('jobs.lastRun')} value={<><span>{date(job.last_run)}</span>{job.last_status ? <span className="ml-2"><State value={`status.${job.last_status}`} /></span> : null}</>} />
            </div>
            <footer className="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-line pt-3">
              <Button size="sm" variant="ghost" aria-expanded={expanded === job.id} aria-controls={`history-${job.id}`}
                onClick={() => setExpanded((current) => current === job.id ? null : job.id)}><ChevronDown />{expanded === job.id ? t('jobs.hideHistory') : t('jobs.history')}</Button>
              <div className="flex flex-wrap items-center gap-2">
                {!job.revision ? <span className="text-[11px] text-ink-3">{t('jobs.noRevision')}</span> : null}
                <Button size="sm" variant="outline" disabled={change.isPending || query.isFetching || query.isError || !job.revision}
                  onClick={() => {
                    if (changing.current) return;
                    changing.current = true; setSuccess(''); change.mutate(job);
                  }}>
                  {job.enabled ? <Pause /> : <Play />}{job.enabled ? t('jobs.pause') : t('jobs.enable')}
                </Button>
              </div>
            </footer>
            {expanded === job.id ? <RunHistory job={job} /> : null}
          </Panel>)}
        </div>}
      {query.data ? <PageControls page={query.data} length={jobs.length} history={history} busy={query.isFetching || change.isPending} onOffset={(next, back) => {
        setHistory((old) => back ? old.slice(0, -1) : [...old, offset]); setOffset(next); setSearch(''); setExpanded(null);
      }} /> : null}
      <p className="max-w-[65ch] border-t border-line pt-4 text-[12px] text-ink-3">{t('jobs.note')}</p>
    </QueryState>
  </ControlLayout>;
}
