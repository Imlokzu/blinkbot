export interface Agent {
  id: string; name: string; default: boolean; model: string; fallbacks: string[];
  runtime: string; thinking: string; workspace_configured: boolean;
}
export interface GatewaySession {
  id: string; agent: string; source: 'custom' | 'main' | 'cron' | 'subagent' | 'channel' | 'direct' | 'group' | 'thread' | 'heartbeat' | 'acp';
  model: string; provider: string; updated_at: number | null; active: boolean;
  status: 'queued' | 'running' | 'done' | 'failed' | 'killed' | 'timeout' | null;
  tokens: number | null; tokens_fresh: boolean; context_window: number | null;
}
export interface Schedule {
  kind: 'every' | 'cron' | 'at' | 'other'; every_ms?: number | null;
  expression?: string; timezone?: string; at?: string;
}
export interface Job {
  id: string; name: string; agent: string; enabled: boolean; schedule: Schedule;
  next_run: number | null; last_run: number | null; last_status: 'ok' | 'error' | 'skipped' | null;
  running: boolean; revision: string;
}
export interface Page {
  total: number | null; has_more: boolean; next_offset: number | null; offset: number;
}
export interface JobsReport extends Page { jobs: Job[]; scheduler_enabled: boolean | null }
export interface SessionsReport extends Page { sessions: GatewaySession[] }
export interface Channel {
  id: string; channel: string; label: string; enabled: boolean | null; configured: boolean | null;
  running: boolean | null; connected: boolean | null; has_error: boolean;
  last_inbound: number | null; last_outbound: number | null;
}
export interface JobRun {
  at: number | null; status: 'ok' | 'error' | 'skipped' | null; duration_ms: number | null;
  model: string; provider: string;
}
export interface JobDraft {
  name: string; message: string; agent: string; kind: 'every' | 'daily';
  minutes: string; time: string; timezone: string;
}

/** Keep malformed schedules out of requests while the form retains its input. */
export function jobBody(draft: JobDraft) {
  if (draft.kind !== 'daily' && draft.kind !== 'every') return null;
  const name = draft.name.trim();
  const message = draft.message.trim();
  const agent = draft.agent.trim();
  const timezone = draft.timezone.trim();
  const minutes = Number(draft.minutes);
  const time = /^(\d{2}):(\d{2})$/.exec(draft.time);
  if (!name || name.length > 120 || !message || message.length > 4000
    || !/^[A-Za-z0-9_-]{1,80}$/.test(agent) || !timezone || timezone.length > 100) return null;
  try { new Intl.DateTimeFormat('en', { timeZone: timezone }); } catch { return null; }
  // JavaScript also accepts fixed offsets; OpenClaw expects an IANA zone.
  if (/^[+-]/.test(timezone)) return null;
  if (draft.kind === 'every' && (!draft.minutes.trim() || !Number.isInteger(minutes) || minutes < 5 || minutes > 10080)) return null;
  if (draft.kind === 'daily' && (!time || Number(time[1]) > 23 || Number(time[2]) > 59)) return null;
  return { name, message, agent, kind: draft.kind,
    minutes: draft.kind === 'every' ? minutes : 60,
    hour: draft.kind === 'daily' ? Number(time![1]) : 9,
    minute: draft.kind === 'daily' ? Number(time![2]) : 0, timezone };
}

export function sessionStatus(session: Pick<GatewaySession, 'active' | 'status'>) {
  return session.status ?? (session.active ? 'running' : null);
}

export function channelNeedsAttention(channel: Pick<Channel, 'has_error' | 'enabled' | 'configured' | 'connected'>): boolean {
  return channel.has_error || channel.enabled === true && (channel.configured === false || channel.connected === false);
}

export function nextPageOffset(page: Page): number | null {
  const next = page.next_offset;
  return page.has_more && next !== null && Number.isInteger(next) && next > page.offset && next <= 10000 ? next : null;
}

export function contextPercent(session: GatewaySession): number | null {
  if (!session.tokens_fresh || session.tokens === null || session.context_window === null
    || !Number.isFinite(session.tokens) || !Number.isFinite(session.context_window)
    || session.tokens < 0 || session.context_window <= 0) return null;
  return Math.min(100, Math.round(session.tokens / session.context_window * 100));
}

export function matches(query: string, ...values: string[]): boolean {
  const haystack = values.join(' ').toLocaleLowerCase();
  return query.trim().toLocaleLowerCase().split(/\s+/).every((word) => haystack.includes(word));
}
