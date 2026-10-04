import { useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as Dialog from '@radix-ui/react-dialog';
import { Activity, ArrowUpRight, RefreshCw, X, CircleDollarSign, Database, ChevronRight, KeyRound, Network } from '../../vendor/solar-icons/compat.ts';
import { api } from '@/lib/api';
import { cn } from '@/lib/cn';
import { t } from '@/locales/inference';
import { BRAND_ICONS, type Brand } from '@/vendor/lobe-icons';
import ComparisonPanel from './ComparisonPanel';
import { Panel, PanelHead } from '@/components/ui/Panel';
import { Button } from '@/components/ui/Button';
import { Input, Select } from '@/components/ui/Field';
import { Empty, Skeleton } from '@/components/ui/Feedback';
import { useIsPhone } from '@/hooks/useMediaQuery';
import { calendar, cacheShare, costTotal, isColdIndex, rate, sessionMatches, tokenTotal } from './report';
import type { Costs, InferenceReport, ProviderUsage, Report, SessionUsage } from './report';

const locale = () => document.documentElement.lang.startsWith('en') ? 'en-US' : 'uk-UA';
const number = (value: number | null | undefined) => value == null || !Number.isFinite(value) ? t('dash') : new Intl.NumberFormat(locale(), { maximumFractionDigits: 0 }).format(value);
const money = (value: number | null | undefined) => value == null || !Number.isFinite(value) ? t('dash') : new Intl.NumberFormat(locale(), { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: value > 0 && value < 0.01 ? 5 : 3 }).format(value);
function MetricMoney({ value }: { value: number | null }) {
  if (value == null || !Number.isFinite(value)) return <>{t('dash')}</>;
  const parts = new Intl.NumberFormat(locale(), { style: 'currency', currency: 'USD', minimumFractionDigits: 5, maximumFractionDigits: 5 }).formatToParts(value);
  return <>{parts.map((part, i) => part.type === 'decimal' || part.type === 'fraction' ? <span key={i} className="text-[0.65em] text-ink-2">{part.value}</span> : part.value)}</>;
}
const when = (stamp: number | null | undefined) => stamp != null && Number.isFinite(stamp) && !Number.isNaN(new Date(stamp).getTime()) ? new Intl.DateTimeFormat(locale(), { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(stamp) : t('dash');
const dayLabel = (date: string) => new Intl.DateTimeFormat(locale(), { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${date}T00:00:00Z`));
const shortId = (id: string) => id.slice(0, 8);
const th = 'whitespace-nowrap px-4 py-3 text-left font-medium text-ink-3';
const td = 'px-4 py-3 align-top';

function Loading() {
  return <div role="status" aria-label={t('loading')} className="space-y-5">
    <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-28" />)}</div>
    <Skeleton className="h-64" /><Skeleton className="h-48" />
  </div>;
}
function Unavailable({ retry, journal = false }: { retry: () => void; journal?: boolean }) {
  return <div role="status"><Empty icon={Activity} title={t(journal ? 'journalUnavailable' : 'offline')} hint={journal ? t('journalUnavailableHint') : undefined} action={<Button onClick={retry}>{t('retry')}</Button>} /></div>;
}
function Cost({ costs, replies, prominent = false }: { costs: Costs | null; replies?: number; prominent?: boolean }) {
  return <span className="inline-flex max-w-full flex-col gap-1 [overflow-wrap:anywhere]">
    <span>{prominent ? <MetricMoney value={costTotal(costs, replies)} /> : money(costTotal(costs, replies))}</span>
    {costs?.missingCostEntries ? <span className="font-sans text-[11px] text-warn">{t('unpriced', { count: costs.missingCostEntries })}</span> : null}
  </span>;
}
function Metrics({ report, provider }: { report: Report; provider: string }) {
  const costs = provider ? report.providers.find((row) => row.provider === provider) : report.totals;
  const replies = provider ? report.providers.find((row) => row.provider === provider)?.replies : report.replies;
  const tokens = tokenTotal(costs, replies);
  const share = costs ? cacheShare(costs) : null;
  const items = [
    { label: t('metricCost'), value: <Cost costs={costs ?? null} replies={replies} prominent />, icon: CircleDollarSign },
    { label: t('replies'), value: number(replies), icon: Activity },
    { label: t('tokens'), value: number(tokens), icon: Database },
    { label: t('metricCache'), value: share == null ? t('dash') : `${Math.round(share)}%`, icon: Database },
  ];
  return <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
    {items.map(({ label, value, icon: Icon }) => <Panel key={label} className="gap-4 !p-5 sm:!p-6">
      <p className="flex items-center gap-3 font-mono text-[11px] uppercase tracking-wider text-ink-2"><Icon size={22} strokeWidth={1.75} aria-hidden="true" />{label}</p>
      <div className="break-words font-mono text-[26px] leading-tight tracking-[-0.045em] text-ink [overflow-wrap:anywhere] sm:text-[34px]">{value}</div>
    </Panel>)}
  </div>;
}
function DailyChart({ report, provider }: { report: Report; provider: string }) {
  const [metric, setMetric] = useState<'cost' | 'tokens'>('cost');
  const [hovered, setHovered] = useState<string | null>(null);
  const days = calendar(report, provider);
  const values = days.flatMap((day) => day[metric] == null ? [] : [day[metric]!]);
  const max = values.length ? Math.max(...values) : null;
  const detail = days.find((day) => day.date === hovered) ?? days.at(-1);
  const active = Math.max(0, days.findIndex((day) => day.date === detail?.date));
  return <Panel className="min-h-[320px] sm:min-h-[380px]">
    <PanelHead className="flex-col items-start sm:flex-row sm:items-center [&_h2]:!font-sans [&_h2]:!text-lg [&_h2]:!font-semibold [&_h2]:!normal-case [&_h2]:!tracking-normal" label={t('traffic')} actions={<div className="flex gap-1">{(['cost', 'tokens'] as const).map((key) => <Button key={key} size="sm" variant={metric === key ? 'quiet' : 'ghost'} aria-pressed={metric === key} onClick={() => setMetric(key)}>{t(key === 'cost' ? 'dailyCost' : key)}</Button>)}</div>} />
    <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <span className="sr-only">{t('peak')}: {metric === 'cost' ? money(max) : number(max)}</span>
      <span className="font-mono text-[11px] text-ink-3" aria-live="polite">{hovered && detail ? t('dayDetail', { date: dayLabel(detail.date), tokens: number(detail.tokens), cost: money(detail.cost) }) : ''}</span>
    </div>
    <div role="group" aria-label={t('chart')} className="flex h-44 items-end sm:h-48 gap-px border-b border-line sm:gap-[3px]">
      {days.map((day, i) => <button type="button" key={day.date} tabIndex={i === active ? 0 : -1} className="group flex h-full min-w-0 flex-1 items-end rounded-t-sm focus-visible:outline-2 focus-visible:outline-accent" aria-label={t('dayDetail', { date: day.date, tokens: number(day.tokens), cost: money(day.cost) })} onMouseEnter={() => setHovered(day.date)} onFocus={() => setHovered(day.date)} onClick={() => setHovered(day.date)} onKeyDown={(event) => {
        const next = event.key === 'ArrowRight' ? Math.min(days.length - 1, i + 1) : event.key === 'ArrowLeft' ? Math.max(0, i - 1) : event.key === 'Home' ? 0 : event.key === 'End' ? days.length - 1 : null;
        if (next == null) return;
        event.preventDefault();
        event.currentTarget.parentElement?.querySelectorAll('button')[next]?.focus();
      }}>
        <span aria-hidden="true" className={cn('block w-full rounded-t-sm group-hover:bg-ink-3', day[metric] == null ? 'border-t border-dashed border-line-strong' : i === active ? 'bg-accent' : 'bg-surface-3')} style={{ height: max && day[metric] ? `${Math.max(2, day[metric]! / max * 100)}%` : '2px' }} />
      </button>)}
    </div>
    {days.some((day) => day[metric] == null) || !days.length ? <p className="mt-2 text-xs text-ink-3">{t('dailyUnknown')}</p> : null}
    <div className="mt-2 flex justify-between font-mono text-[10px] text-ink-3"><span>{days[0] ? dayLabel(days[0].date) : t('dash')}</span><span>{t('utc')}</span><span>{days.at(-1) ? dayLabel(days.at(-1)!.date) : t('dash')}</span></div>
  </Panel>;
}
function ProviderLogo({ provider }: { provider: string }) {
  if (Object.hasOwn(BRAND_ICONS, provider)) return <svg aria-hidden="true" viewBox="0 0 24 24" fill="currentColor" className="size-7 shrink-0" dangerouslySetInnerHTML={{ __html: BRAND_ICONS[provider as Brand] }} />;
  return provider === 'regolo' ? <KeyRound className="size-7 shrink-0" strokeWidth={1.75} /> : <Network className="size-7 shrink-0" strokeWidth={1.75} />;
}
function ProviderRow({ row, selected, choose }: { row: ProviderUsage; selected: boolean; choose: () => void }) {
  const quota = row.quota?.windows[0];
  const percent = quota?.used_percent;
  return <div className="py-1">
    <button type="button" aria-pressed={selected} onClick={choose} className={cn('flex min-h-14 w-full items-center gap-3 rounded-md px-1 text-left text-ink hover:bg-surface-2', selected && 'bg-surface-2')}>
      <ProviderLogo provider={row.provider} />
      <span className="min-w-0 flex-1 break-words text-sm font-medium">{row.quota?.name || row.provider}</span>
      <span className="hidden h-1.5 w-20 shrink-0 rounded-full bg-surface-3 min-[380px]:block" aria-label={percent == null ? t('quotaNone') : t('quota', { window: quota?.label || '', percent: Math.round(percent) })}>
        {percent != null ? <span className={cn('block h-full rounded-full', percent >= 90 ? 'bg-err' : percent >= 70 ? 'bg-warn' : 'bg-ok')} style={{ width: `${Math.min(100, Math.max(0, percent))}%` }} /> : null}
      </span>
      <span className="w-20 shrink-0 text-right font-mono text-xs"><Cost costs={row} replies={row.replies} /></span><ChevronRight size={14} className="shrink-0 text-ink-3" />
    </button>
    {selected ? <div className="space-y-2 px-2 pb-3 pl-11 text-xs text-ink-3"><p>{row.auth === 'oauth' ? t('subscription') : row.auth === 'api_key' ? t('apiKey') : t('authUnknown')} · {number(row.replies)} {t('replies')}</p>{row.quota?.windows.map((window, i) => <p key={i}>{window.label}: {window.used_percent == null ? t('unknown') : `${Math.round(window.used_percent)}%`}{window.reset_at ? ` · ${t('reset', { time: when(window.reset_at) })}` : ''}</p>)}</div> : null}
  </div>;
}
function Models({ report, provider }: { report: Report; provider: string }) {
  const models = report.models.filter((model) => !provider || model.provider === provider);
  return <Panel flush><div className="px-4 pt-4 sm:px-5"><PanelHead label={t('models')} hint={number(models.length)} /><p className="mb-4 text-[12px] text-ink-3">{t('rates')}</p></div>
    {models.length ? <div role="region" aria-label={t('modelTable')} tabIndex={0} className="overflow-x-auto"><table className="w-full min-w-[760px] border-collapse text-[12px]"><thead className="border-y border-line bg-surface-2"><tr>{(['model', 'replies', 'input', 'output', 'cacheRate', 'total'] as const).map((key) => <th key={key} scope="col" className={th}>{t(key)}</th>)}</tr></thead>
      <tbody className="font-mono text-ink-2">{models.map((model) => <tr key={`${model.provider}/${model.model}`} className="border-b border-line last:border-b-0">
        <td className={td}><div className="max-w-80 break-words text-ink">{model.model || t('unknown')}</div><div className="mt-1 text-[10px] text-ink-3">{model.provider || t('unknown')}</div></td>
        <td className={td}>{number(model.replies)}</td><td className={td}>{money(rate(model, 'input'))}</td><td className={td}>{money(rate(model, 'output'))}</td><td className={td}>{money(rate(model, 'cacheRead'))}</td><td className={td}><Cost costs={model} replies={model.replies} /></td>
      </tr>)}</tbody></table></div> : <Empty title={t('noModels')} />}
  </Panel>;
}
function Journal({ session, days, provider, modality, close, restoreFocus }: { session: SessionUsage; days: number; provider: string; modality: 'all' | 'text' | 'voice'; close: () => void; restoreFocus: () => void }) {
  const [limit, setLimit] = useState(1000);
  const phone = useIsPhone();
  const query = useQuery({ queryKey: ['inference-journal', session.id, days, limit, modality], queryFn: async ({ signal }) => {
    const data = await api<InferenceReport>(`/api/openclaw/analytics/inferences?session_id=${encodeURIComponent(session.id)}&days=${days}&limit=${limit}&modality=${modality}`, { signal });
    if (!data?.available) throw new Error('journal_unavailable');
    return data;
  }, placeholderData: (previous, previousQuery) => previousQuery?.queryKey[1] === session.id && previousQuery.queryKey[2] === days && previousQuery.queryKey[4] === modality ? previous : undefined,
  staleTime: 0, refetchOnWindowFocus: false, retry: 1 });
  const data = query.data;
  const rows = data?.inferences.filter((row) => !provider || row.provider === provider) ?? [];
  return <Dialog.Root open onOpenChange={(open) => { if (!open) close(); }}><Dialog.Portal>
    <Dialog.Overlay className="fixed inset-0 bg-[var(--c-overlay)]" style={{ zIndex: 'var(--z-modal)' }} />
    <Dialog.Content onCloseAutoFocus={(event) => { event.preventDefault(); restoreFocus(); }} className={cn('fixed flex max-h-[85dvh] min-w-0 flex-col border border-line bg-surface shadow-pop', phone ? 'inset-x-0 bottom-0 rounded-t-lg u-safe-b' : 'left-1/2 top-1/2 w-[min(1100px,calc(100vw-3rem))] -translate-x-1/2 -translate-y-1/2 rounded-lg')} style={{ zIndex: 'var(--z-modal)' }}>
      <header className="flex shrink-0 items-start justify-between gap-3 border-b border-line p-4 sm:p-5"><div className="min-w-0"><Dialog.Title className="text-lg font-semibold text-ink">{t('journal')} <span className="ml-2 font-mono text-xs text-ink-3">{shortId(session.id)}</span></Dialog.Title><Dialog.Description className="mt-1 text-xs text-ink-3">{t('journalHint')}</Dialog.Description><p className="mt-2 break-words font-mono text-xs text-ink-2">{t('journalScope', { period: days === 1 ? t('today') : t('days', { days }), provider: provider || t('all'), modality: t(modality === 'all' ? 'modalityAll' : modality === 'text' ? 'modalityText' : 'modalityVoice') })}</p></div><Dialog.Close asChild><Button className="shrink-0" size="icon" variant="ghost" aria-label={t('close')}><X strokeWidth={1.75} /></Button></Dialog.Close></header>
      <div className="min-h-0 overflow-y-auto overscroll-contain">
        {data?.available && (query.isError || query.fetchStatus === 'paused') ? <p role="status" className="p-4 text-xs text-warn">{t(query.isError ? 'stale' : 'paused')}</p> : null}
        {query.isPending && query.fetchStatus !== 'paused' ? <div role="status" aria-label={t('loading')} className="p-5"><Skeleton className="h-52" /></div> : !data?.available ? <Unavailable journal retry={() => void query.refetch()} /> : rows.length ? <div role="region" aria-label={t('journalTable')} tabIndex={0} className="overflow-x-auto"><table className="w-full min-w-[1050px] border-collapse text-[11px]"><thead className="border-b border-line bg-surface-2"><tr>{(['time', 'model', 'status', 'inputTokens', 'outputTokens', 'cacheTokens', 'cacheWriteTokens', 'total'] as const).map((key) => <th key={key} scope="col" className={th}>{t(key)}</th>)}</tr></thead>
          <tbody className="font-mono text-ink-2">{rows.map((row) => <tr key={row.id} className="border-b border-line"><td className={`${td} whitespace-nowrap`}>{when(row.timestamp)}</td><td className={td}><span className="block max-w-64 break-words text-ink">{row.model || t('unknown')}</span><span className="mt-1 block text-ink-3">{row.provider || t('unknown')}</span></td><td className={cn(td, row.status === 'error' && 'text-err')}>{t(row.status)}</td><td className={td}>{number(row.input)}</td><td className={td}>{number(row.output)}</td><td className={td}>{number(row.cacheRead)}</td><td className={td}>{number(row.cacheWrite)}</td><td className={td}>{money(row.cost)}</td></tr>)}</tbody></table></div> : <Empty title={t('noInferences')} />}
      </div>
      {data?.limited || data?.pricing_limited ? <footer className="shrink-0 space-y-3 border-t border-line p-4">{data.pricing_limited ? <p className="text-xs text-ink-3">{t('pricingLimit')}</p> : null}{data.limited ? <><p className="text-xs text-ink-3">{t('journalLimit', { count: data.message_limit ?? limit })}</p>{limit < 10000 ? <Button size="sm" disabled={query.isFetching || query.isPlaceholderData} onClick={() => setLimit((n) => Math.min(10000, n * 2))}>{t('moreMessages')}</Button> : <p className="text-xs text-ink-3">{t('maxMessages')}</p>}</> : null}</footer> : null}
    </Dialog.Content>
  </Dialog.Portal></Dialog.Root>;
}

export default function InferencePanel() {
  const queryClient = useQueryClient();
  const [days, setDays] = useState(7);
  const [modality, setModality] = useState<'all' | 'text' | 'voice'>('all');
  const [tab, setTab] = useState(() => new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('tab') === 'comparison' ? 'comparison' : 'usage');
  const [limit, setLimit] = useState(500);
  const [provider, setProvider] = useState('');
  const [search, setSearch] = useState('');
  const [shown, setShown] = useState(50);
  const [selected, setSelected] = useState<SessionUsage | null>(null);
  const journalTrigger = useRef<HTMLButtonElement | null>(null);
  const periodControl = useRef<HTMLSelectElement | null>(null);
  const query = useQuery({ queryKey: ['openclaw-analytics', days, limit, modality], queryFn: async ({ signal }) => {
    const data = await api<Report>(`/api/openclaw/analytics?days=${days}&limit=${limit}&refresh=true&modality=${modality}`, { signal });
    if (!data.available) throw new Error('usage_unavailable');
    return data;
  }, staleTime: 30_000, refetchInterval: (q) => q.state.data?.indexing ? 10_000 : 60_000, refetchOnWindowFocus: false, retry: 1 });
  const report = query.data;
  const visibleProvider = provider;
  const sessions = useMemo(() => report?.sessions.filter((s) => sessionMatches(s, visibleProvider, search)) ?? [], [report, visibleProvider, search]);
  const chooseProvider = (value: string) => { setProvider(value); setSelected(null); setShown(50); };
  return <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain [&_h2]:!text-ink" data-inference-panel="">
    <div className="mx-auto max-w-[1600px] space-y-5 p-4 sm:p-6 lg:p-8">
      <header className="flex items-center justify-between gap-4"><h1 className="text-[28px] font-semibold tracking-tight text-ink">{t('usageTitle')}</h1><Button variant="outline" size="icon" disabled={query.isFetching} aria-label={t('refresh')} onClick={() => { void query.refetch(); void queryClient.invalidateQueries({ queryKey: ['usage-comparison'] }); }}><RefreshCw size={17} strokeWidth={1.75} /></Button></header>
      <div className="flex gap-2" role="tablist" aria-label={t('tabs')}>{['usage', 'comparison'].map((id) => <button type="button" role="tab" id={`usage-tab-${id}`} aria-controls={`usage-panel-${id}`} tabIndex={tab === id ? 0 : -1} aria-selected={tab === id} key={id} className={cn('min-h-10 rounded-md px-4 text-sm', tab === id ? 'bg-surface-2 font-medium text-ink' : 'text-ink-3 hover:text-ink')} onClick={() => { setTab(id); setSelected(null); }} onKeyDown={(event) => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); const next = id === 'usage' ? 'comparison' : 'usage'; setTab(next); setSelected(null); document.getElementById(`usage-tab-${next}`)?.focus(); } }}>{t(id === 'usage' ? 'usageTab' : 'comparisonTab')}</button>)}</div>
      <div className="flex flex-wrap items-center justify-between gap-3"><div className="flex flex-wrap gap-3">
        <label className="flex items-center gap-2 text-xs text-ink-3"><span className="sr-only">{t('period')}</span><Select ref={periodControl} aria-label={t('period')} className="w-auto min-w-28" value={days} onChange={(e) => { setDays(Number(e.target.value)); setSelected(null); setShown(50); }}>{[1, 7, 30, 90].map((n) => <option key={n} value={n}>{n === 1 ? t('today') : t('days', { days: n })}</option>)}</Select></label>
        <label className="flex min-w-0 items-center gap-2 text-xs text-ink-3"><span className="sr-only">{t('provider')}</span><Select aria-label={t('provider')} className="w-auto max-w-[200px]" value={visibleProvider} onChange={(e) => chooseProvider(e.target.value)}><option value="">{t('all')}</option>{provider && !report?.providers.some((p) => p.provider === provider) ? <option value={provider}>{provider}</option> : null}{report?.providers.map((p) => <option key={p.provider} value={p.provider}>{p.quota?.name || p.provider}</option>)}</Select></label>
        <Select aria-label={t('modality')} className="w-auto max-w-[180px]" value={modality} onChange={(e) => { setModality(e.target.value as typeof modality); setSelected(null); setShown(50); }}>{(['all', 'text', 'voice'] as const).map((kind) => <option key={kind} value={kind}>{t(kind === 'all' ? 'modalityAll' : kind === 'text' ? 'modalityText' : 'modalityVoice')}</option>)}</Select>
      </div><div className="font-mono text-[10px] text-ink-3">{report?.start_date && report.end_date ? <p>{t('range', { start: report.start_date, end: report.end_date })}</p> : null}<p>{report?.updated_at != null ? t('updated', { time: when(report.updated_at) }) : t(query.fetchStatus === 'paused' ? 'paused' : report ? 'unknown' : 'loading')}</p></div></div>
      <div role="tabpanel" id={`usage-panel-${tab}`} aria-labelledby={`usage-tab-${tab}`} className="space-y-5">
      {tab === 'comparison' ? <ComparisonPanel days={days} modality={modality} provider={provider} /> : query.isPending && query.fetchStatus !== 'paused' ? <Loading /> : !report ? <Unavailable retry={() => void query.refetch()} /> : <>
        {query.isError || report.indexing || query.fetchStatus === 'paused' ? <p role="status" className="rounded-md border border-warn/30 bg-warn/5 px-4 py-3 text-xs text-warn">{t(query.isError ? 'stale' : query.fetchStatus === 'paused' ? 'paused' : 'partial')}</p> : null}
        {modality !== 'all' ? <p role="status" className="text-xs text-ink-3">{t('modalityNote')}</p> : null}
        {modality !== 'all' && report.coverage?.history_limited ? <p role="status" className="text-xs text-warn">{t('matchedLimited')}</p> : null}
        {isColdIndex(report) ? <Loading /> : <>
        <Metrics report={report} provider={visibleProvider} />
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1.8fr)_minmax(280px,1fr)]"><DailyChart report={report} provider={visibleProvider} /><Panel><PanelHead className="[&_h2]:!font-sans [&_h2]:!text-lg [&_h2]:!font-semibold [&_h2]:!normal-case [&_h2]:!tracking-normal" label={t('providers')} hint={number(report.providers.length)} /><div className="max-h-[340px] overflow-y-auto pr-1">{report.providers.map((row) => <ProviderRow key={row.provider} row={row} selected={visibleProvider === row.provider} choose={() => chooseProvider(provider === row.provider ? '' : row.provider)} />)}</div></Panel></div>
        <div className="flex flex-col gap-1 border-l-2 border-line-strong pl-3 text-xs leading-relaxed text-ink-3"><p>{t('note')}</p><p>{t('missingNote')}</p></div>
        <Models report={report} provider={visibleProvider} />
        <Panel flush><div className="px-4 pt-4 sm:px-5"><PanelHead label={t('sessions')} hint={`${Math.min(shown, sessions.length)} / ${sessions.length}`} /><p className="mb-3 text-xs text-ink-3">{t('sessionHint')}</p><Input aria-label={t('searchLabel')} placeholder={t('search')} value={search} onChange={(e) => { setSearch(e.target.value); setShown(50); }} className="mb-4 max-w-md" /></div>
          {sessions.length ? <div role="region" aria-label={t('sessionTable')} tabIndex={0} className="overflow-x-auto"><table className="w-full min-w-[800px] border-collapse text-[12px]"><thead className="border-y border-line bg-surface-2"><tr>{(['session', 'model', 'lastActive', 'replies', 'tokens', 'total'] as const).map((key) => <th scope="col" className={th} key={key}>{t(key)}</th>)}</tr></thead><tbody className="font-mono text-ink-2">{sessions.slice(0, shown).map((row) => <tr key={row.id} className="border-b border-line last:border-b-0 hover:bg-surface-2/50"><td className={td}><button type="button" className="flex min-h-9 items-center gap-2 text-ink underline-offset-4 hover:underline max-[759px]:min-h-11" aria-label={t('open', { id: shortId(row.id) })} aria-haspopup="dialog" onClick={(event) => { journalTrigger.current = event.currentTarget; setSelected(row); }}>{shortId(row.id)}<ArrowUpRight size={13} strokeWidth={1.75} /></button></td><td className={td}><div className="max-w-80 break-words text-ink">{row.models.length ? row.models.map((m) => m.model).join(' · ') : row.model || t('unknown')}</div><div className="mt-1 text-[10px] text-ink-3">{row.models.length ? [...new Set(row.models.map((m) => m.provider))].join(' · ') : row.provider || t('unknown')}</div></td><td className={`${td} whitespace-nowrap`}>{when(row.updated_at)}</td><td className={td}>{row.usage ? number(row.replies) : t('dash')}</td><td className={td}>{number(tokenTotal(row.usage, row.replies))}</td><td className={td}><Cost costs={row.usage} replies={row.replies} /></td></tr>)}</tbody></table></div> : <Empty title={t(search || visibleProvider ? 'noMatches' : 'empty')} hint={t('emptyHint')} />}
          <footer className="space-y-2 border-t border-line p-4 text-[11px] text-ink-3"><p>{t('sessionTotals')}</p>{report.sessions_limited ? <p>{t('sessionLimit')}</p> : null}{shown < sessions.length || report.sessions_limited && limit < 2000 ? <Button variant="outline" size="sm" onClick={() => { if (shown >= sessions.length) setLimit(2000); setShown((n) => n + 50); }}>{t('moreSessions')}</Button> : null}</footer>
        </Panel>
        </>}
      </>}
      </div>
    </div>
    {selected ? <Journal key={`${selected.id}-${days}-${modality}`} session={selected} days={days} provider={provider} modality={modality} close={() => setSelected(null)} restoreFocus={() => { (journalTrigger.current?.isConnected ? journalTrigger.current : periodControl.current)?.focus(); }} /> : null}
  </div>;
}
