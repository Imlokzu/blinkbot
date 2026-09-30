export interface Costs {
  input: number; output: number; cacheRead: number; cacheWrite: number; totalTokens: number;
  totalCost: number; inputCost: number; outputCost: number; cacheReadCost: number; cacheWriteCost: number;
  missingCostEntries: number; noCacheCost?: number;
}
export interface ModelUsage extends Costs { provider: string; model: string; replies: number }
export interface ProviderUsage extends Costs {
  provider: string; replies: number; auth: string;
  quota: { name: string; plan: string; windows: { label: string; used_percent: number | null; reset_at: number | null }[] } | null;
}
export interface SessionUsage {
  id: string; updated_at: number | null; provider: string; model: string; replies: number; errors: number;
  usage: Costs | null; models: { provider: string; model: string }[];
}
export interface DayUsage { date: string; tokens: number | null; cost: number | null; errors?: number; provider?: string }
export interface Report {
  available: boolean; days: number; start_date?: string; end_date?: string; updated_at?: number | null;
  indexing?: boolean; totals: Costs | null; replies?: number; errors?: number; tool_calls?: number;
  providers: ProviderUsage[]; models: ModelUsage[]; daily: DayUsage[]; daily_models?: DayUsage[];
  sessions: SessionUsage[]; sessions_limited?: boolean;
}
export interface Inference {
  id: string; timestamp: number; provider: string; model: string; status: 'ok' | 'error' | 'tool';
  input: number | null; output: number | null; cacheRead: number | null; cacheWrite: number | null;
  totalTokens: number | null; cost: number | null;
}
export interface InferenceReport {
  available: boolean; inferences: Inference[]; limited?: boolean; message_limit?: number;
  pricing_limited?: boolean;
}

const known = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0;

/** Successful unmetered replies have zero-filled aggregates, not measured zero usage. */
export function tokenTotal(costs: Costs | null | undefined, replies?: number): number | null {
  if (!costs || !known(costs.totalTokens)) return null;
  return costs.totalTokens > 0 || replies === 0 ? costs.totalTokens : null;
}
export function costTotal(costs: Costs | null | undefined, replies?: number): number | null {
  if (!costs || !known(costs.totalCost)) return null;
  if (costs.totalCost > 0) return costs.totalCost;
  return !costs.missingCostEntries && (tokenTotal(costs, replies) != null || replies === 0) ? 0 : null;
}
export function isColdIndex(report: Report): boolean {
  return !!report.indexing && !report.replies && !report.totals?.totalTokens && !report.totals?.totalCost
    && !report.totals?.missingCostEntries && !report.models.length;
}

/** Rates are effective averages from recorded API costs, not a guessed catalog price. */
export function rate(costs: Costs, kind: 'input' | 'output' | 'cacheRead'): number | null {
  const tokens = costs[kind];
  const cost = costs[`${kind}Cost`];
  return costs.missingCostEntries || !known(tokens) || !tokens || !known(cost) ? null : cost / tokens * 1_000_000;
}
export function cacheShare(costs: Costs): number | null {
  const prompt = costs.input + costs.cacheRead + costs.cacheWrite;
  return prompt ? costs.cacheRead / prompt * 100 : null;
}
export function sessionMatches(session: SessionUsage, provider: string, query: string): boolean {
  // Once actual usage is known, configured/selected metadata cannot make a
  // session match a provider or model that never answered in this period.
  const models = session.models.length ? session.models : [{ provider: session.provider, model: session.model }];
  if (provider && !models.some((m) => m.provider === provider)) return false;
  const terms = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
  const text = [session.id, ...models.filter((m) => !provider || m.provider === provider).flatMap((m) => [m.provider, m.model])].join(' ').toLowerCase();
  return terms.every((term) => text.includes(term));
}
export function calendar(report: Report, provider: string): DayUsage[] {
  if (!report.start_date || !report.end_date) return [];
  const start = Date.parse(`${report.start_date}T00:00:00Z`);
  const end = Date.parse(`${report.end_date}T00:00:00Z`);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start || end - start >= 90 * 86_400_000) return [];
  const source = provider ? (report.daily_models ?? []).filter((day) => day.provider === provider) : report.daily;
  const providerRow = provider ? report.providers?.find((row) => row.provider === provider) : undefined;
  const scope = provider ? providerRow : report.totals;
  const replies = provider ? providerRow?.replies : report.replies;
  const hasTraffic = !!(replies || scope?.totalTokens || scope?.totalCost || scope?.missingCostEntries);
  // Missing breakdowns and unfinished indexing cannot prove that a day was idle.
  const incomplete = report.indexing || scope === null
    || (provider && (!report.daily_models || (report.providers && !providerRow))) || (!source.length && hasTraffic);
  const missingTokens = !!scope && hasTraffic && tokenTotal(scope, replies) == null;
  const missingCost = !!scope && hasTraffic && (costTotal(scope, replies) == null || scope.missingCostEntries > 0);
  const days = new Map<string, DayUsage>();
  for (const day of source) {
    const previous = days.get(day.date);
    const tokens = known(day.tokens) && !(missingTokens && day.tokens === 0) ? day.tokens : null;
    const cost = known(day.cost) && !(missingCost && day.cost === 0) ? day.cost : null;
    days.set(day.date, { date: day.date,
      tokens: tokens == null || previous?.tokens === null ? null : (previous?.tokens ?? 0) + tokens,
      cost: cost == null || previous?.cost === null ? null : (previous?.cost ?? 0) + cost,
    });
  }
  const result: DayUsage[] = [];
  for (let at = start; at <= end; at += 86_400_000) {
    const date = new Date(at).toISOString().slice(0, 10);
    result.push(days.get(date) ?? { date, tokens: incomplete ? null : 0, cost: incomplete ? null : 0 });
  }
  return result;
}
