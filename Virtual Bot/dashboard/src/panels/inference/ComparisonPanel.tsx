import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowDownRight, ArrowUpRight, GitBranch, Sparkles } from '../../vendor/solar-icons/compat.ts';
import { api } from '@/lib/api';
import { Panel } from '@/components/ui/Panel';
import { Select } from '@/components/ui/Field';
import { Button } from '@/components/ui/Button';
import { Empty, Skeleton } from '@/components/ui/Feedback';
import { t } from '@/locales/comparison';

interface Comparison {
  available: boolean; replies: number; population: string; baseline_model: string;
  observed_cost: number | null; router_cost: number | null; actual_cost: number | null;
  without_router_cost: number | null; opus_cost: number | null; savings_vs_opus: number | null;
  savings_vs_baseline: number | null; savings_percent: number | null;
  baselines: { id: string; label: string }[];
  coverage: { tracked_turns: number; matched_inferences: number; router_turns: number;
    unknown_router_costs: number; unclassified_inferences: number; history_limited: boolean };
  pricing: { checked_at: string; opus_source: string; router_source: string };
  indexing?: boolean;
}
const currency = () => new Intl.NumberFormat(document.documentElement.lang.startsWith('en') ? 'en-US' : 'uk-UA', { style: 'currency', currency: 'USD', minimumFractionDigits: 5, maximumFractionDigits: 5 });
const money = (value: number | null | undefined) => value == null || !Number.isFinite(value) ? t('unknown') : currency().format(value);
function MetricMoney({ value }: { value: number | null }) {
  if (value == null || !Number.isFinite(value)) return <>{t('unknown')}</>;
  return <>{currency().formatToParts(value).map((part, i) => part.type === 'decimal' || part.type === 'fraction' ? <span key={i} className="text-[0.65em] text-ink-2">{part.value}</span> : part.value)}</>;
}
export default function ComparisonPanel({ days, modality, provider }: { days: number; modality: 'all' | 'text' | 'voice'; provider: string }) {
  const [population, setPopulation] = useState('observed');
  const [baseline, setBaseline] = useState('');
  const [opus, setOpus] = useState('claude-opus-5-5');
  const query = useQuery({ queryKey: ['usage-comparison', days, modality, provider, population, baseline, opus], queryFn: async ({ signal }) => {
    const params = new URLSearchParams({ days: String(days), modality, provider, population, baseline_model: baseline, opus_model: opus });
    const data = await api<Comparison>(`/api/openclaw/analytics/comparison?${params}`, { signal });
    if (!data.available) throw new Error('comparison_unavailable');
    return data;
  }, staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 });
  const data = query.data;
  const historicalRoutingUnknown = !!data?.coverage.unclassified_inferences;
  const gain = data?.savings_vs_opus;
  const cards = data ? [
    { label: t(population === 'routed' ? 'withRouter' : 'recorded'), value: data.actual_cost, detail: t('counts', { count: data.replies }), icon: GitBranch },
    { label: t('baseline'), value: data.without_router_cost, detail: data.baselines.find((m) => m.id === data.baseline_model)?.label ?? data.baseline_model, icon: ArrowUpRight },
    { label: t('opus'), value: data.opus_cost, detail: data.baselines.find((m) => m.id === opus)?.label ?? opus, icon: Sparkles },
  ] : [];
  const max = Math.max(...cards.map((card) => card.value ?? 0));
  return <section className="space-y-5" data-comparison-panel="">
    <div className="flex flex-wrap gap-4">
      <label className="min-w-0 flex-1 text-xs text-ink-2">{t('population')}<Select className="mt-2" value={population} onChange={(e) => setPopulation(e.target.value)}><option value="observed">{t('observed')}</option><option value="routed">{t('routed')}</option></Select></label>
      <label className="min-w-0 flex-1 text-xs text-ink-2">{t('baseline')}<Select className="mt-2" value={baseline || data?.baseline_model || ''} onChange={(e) => setBaseline(e.target.value)} disabled={!data}>{data?.baselines.map((model) => <option key={model.id} value={model.id}>{model.label}</option>)}</Select></label>
      <label className="min-w-0 flex-1 text-xs text-ink-2">{t('chooseOpus')}<Select className="mt-2" value={opus} onChange={(e) => setOpus(e.target.value)}>{['claude-opus-5-5', 'claude-opus-4-8'].map((id) => <option key={id} value={id}>{id}</option>)}</Select></label>
    </div>
    <p className="text-xs text-ink-3">{t('baselineHelp')}</p>
    {query.isPending ? <div role="status" aria-label={t('loading')}><Skeleton className="h-40" /></div> : !data ? <Empty title={t('unavailable')} action={<Button onClick={() => void query.refetch()}>{t('retry')}</Button>} /> : <>
      {query.isError ? <p role="status" className="text-sm text-warn">{t('stale')}</p> : null}
      {!data.replies ? <Empty title={t(population === 'routed' ? 'empty' : 'noUsage')} hint={t(population === 'routed' ? 'emptyHint' : 'noUsageHint')} /> : <>
        <Panel className="gap-3"><div className="flex items-center gap-2 text-sm text-ink-2">{gain != null && gain < 0 ? <ArrowUpRight size={18} /> : <ArrowDownRight size={18} />}{t(gain != null && gain < 0 ? 'more' : population === 'routed' ? 'netSavings' : 'savings')}</div><div className="break-words font-mono text-4xl text-ink"><MetricMoney value={gain == null ? null : Math.abs(gain)} /></div>{data.savings_percent != null && data.savings_percent >= 0 ? <span className="text-sm text-ok">{t('percent', { value: data.savings_percent.toFixed(1) })}</span> : null}<p className="text-xs text-ink-3">{t('counterfactual')}</p></Panel>
        <div className="grid gap-3 md:grid-cols-3">{cards.map(({ label, value, detail, icon: Icon }) => <Panel key={label} className="gap-3"><h2 className="flex items-center gap-2 text-sm text-ink"><Icon size={18} strokeWidth={1.75} />{label}</h2><span className="break-all font-mono text-3xl text-ink"><MetricMoney value={value} /></span><p className="break-words font-mono text-xs text-ink-3">{detail}</p></Panel>)}</div>
        <Panel><h2 className="mb-4 text-lg font-semibold text-ink">{t('plot')}</h2><div className="space-y-5">{cards.map((card) => <div key={card.label}><div className="mb-2 flex justify-between gap-3 text-sm"><span>{card.label}</span><span className="font-mono">{money(card.value)}</span></div><div className="h-3 rounded-sm bg-surface-2"><div className="h-full rounded-sm bg-ink-3" style={{ width: card.value == null || !max ? '0%' : `${card.value / max * 100}%` }} /></div></div>)}</div></Panel>
      </>}
      <p className="text-xs text-ink-3">{t('coverage', { turns: data.coverage.tracked_turns, matched: data.coverage.matched_inferences, routed: data.coverage.router_turns })}</p>
      {historicalRoutingUnknown ? <p className="rounded-md border border-line bg-surface-2 p-3 text-xs text-ink-2">{t('history')}</p> : null}
      {data.coverage.history_limited ? <p className="text-xs text-warn">{t('limited')}</p> : null}
      {data.coverage.unknown_router_costs ? <p className="text-xs text-warn">{t('unknownOverhead')}</p> : null}
      <p className="font-mono text-xs text-ink-2">{t('overhead')}: {money(historicalRoutingUnknown ? null : data.router_cost)}</p>
      {data.replies && data.actual_cost == null ? <p className="text-xs text-warn">{t('incomplete')}</p> : null}
      <div className="space-y-2 border-t border-line pt-4 text-xs leading-relaxed text-ink-3"><p>{t('billingNote')}</p><p>{t('hint')}</p><p>{t('assumptions')}</p><p>{t('source')} · <a className="underline" href={data.pricing.opus_source} target="_blank" rel="noreferrer">{t('anthropic')}</a> · <a className="underline" href={data.pricing.router_source} target="_blank" rel="noreferrer">{t('typesafe')}</a> · {t('checked', { date: data.pricing.checked_at })}</p></div>
    </>}
  </section>;
}
