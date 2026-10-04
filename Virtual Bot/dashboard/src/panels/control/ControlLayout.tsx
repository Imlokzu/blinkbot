import type { ReactNode } from 'react';
import { ArrowRight, Bot, CalendarClock, MessagesSquare, Radio, RefreshCw } from '../../vendor/solar-icons/compat.ts';
import { SectionHeader } from '@/components/shell/SectionHeader';
import { Button } from '@/components/ui/Button';
import { Empty, SkeletonList } from '@/components/ui/Feedback';
import { ApiError } from '@/lib/api';
import { cn } from '@/lib/cn';
import { t, type ControlKey } from '@/locales/control';
import { nextPageOffset, type Page } from './data';

const pages = [
  { id: 'agents', key: 'nav.agents', icon: Bot },
  { id: 'sessions', key: 'nav.sessions', icon: MessagesSquare },
  { id: 'automation', key: 'nav.automation', icon: CalendarClock },
  { id: 'channels', key: 'nav.channels', icon: Radio },
] as const;

export function date(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return t('unknown');
  const stamp = new Date(value);
  if (!Number.isFinite(stamp.getTime())) return t('unknown');
  return new Intl.DateTimeFormat(document.documentElement.lang || 'en', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(stamp);
}

export function count(value: number | null | undefined): string {
  return value == null ? t('unknown') : new Intl.NumberFormat(document.documentElement.lang || 'en').format(value);
}

export function ControlLayout({ page, title, hint, refreshing, updated, onRefresh, actions, children }: {
  page: string; title: string; hint: string; refreshing: boolean; updated: number; onRefresh: () => void;
  actions?: ReactNode; children: ReactNode;
}) {
  return (
    <div className="min-h-0 min-w-0 flex-1 overflow-y-auto p-4 sm:p-6" data-control-page={page}>
      <div className="mx-auto w-full max-w-[1080px] space-y-6 pb-4">
        <SectionHeader label={t('label')} title={title} hint={hint}
          className="[&_h1]:overflow-visible [&_h1]:whitespace-normal" />
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line pb-3">
          <nav aria-label={t('label')} className="flex max-w-full items-center gap-1 overflow-x-auto">
            {pages.map(({ id, key, icon: Icon }) => (
              <a key={id} href={`#/${id}`} aria-current={page === id ? 'page' : undefined}
                className={cn('flex min-h-11 shrink-0 items-center gap-2 rounded-sm px-3 text-[13px] no-underline',
                  page === id ? 'bg-surface-3 font-medium text-ink' : 'text-ink-3 hover:bg-surface-2 hover:text-ink')}>
                <Icon size={16} strokeWidth={1.75} />{t(key)}
              </a>
            ))}
          </nav>
          <div className="flex flex-wrap items-center gap-2">
            <span className="hidden font-mono text-[10px] text-ink-3 xl:inline">{updated ? t('updated', { time: date(updated) }) : ''}</span>
            <Button size="sm" variant="outline" disabled={refreshing} onClick={onRefresh}>
              <RefreshCw />{t('refresh')}
            </Button>
            {actions}
          </div>
        </div>
        {children}
      </div>
    </div>
  );
}

export function QueryState({ pending, error, hasData, children }: {
  pending: boolean; error: Error | null; hasData: boolean; children: ReactNode;
}) {
  if (pending) return <div role="status" aria-label={t('loading')}><SkeletonList rows={5} /></div>;
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) return <Notice error>{t('operator')}</Notice>;
  if (error && !hasData) return <Notice error>{t('unavailable')}</Notice>;
  return <>{error ? <Notice error>{t('stale')}</Notice> : null}{children}</>;
}

export function Notice({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <p role={error ? 'alert' : 'status'} className={cn('rounded-md border p-3 text-[13px]',
    error ? 'border-err/35 bg-err/8 text-ink' : 'border-line bg-surface-2 text-ink-2')}>{children}</p>;
}

export function Metrics({ values }: { values: { label: string; value: string | number }[] }) {
  return <dl className="grid grid-cols-1 gap-4 border-y border-line py-5 min-[480px]:grid-cols-3">
    {values.map(({ label, value }) => <div key={label} className="min-w-0">
      <dt className="u-label">{label}</dt><dd className="mt-1 font-mono text-[25px] leading-tight tracking-[-0.04em] text-ink sm:text-[30px]">{value}</dd>
    </div>)}
  </dl>;
}

export function Fact({ label, value }: { label: string; value: ReactNode }) {
  return <dl className="min-w-0"><dt className="mb-1 text-[12px] text-ink-3">{label}</dt>
    <dd className="break-words font-mono text-[12px] text-ink [overflow-wrap:anywhere]">{value === '' || value == null ? t('unknown') : value}</dd></dl>;
}

export function State({ value }: { value: ControlKey }) {
  const kind = ['status.failed', 'status.error', 'status.timeout'].includes(value) ? 'bg-err'
    : ['status.running', 'status.enabled'].includes(value) ? 'bg-ok'
    : ['status.queued', 'status.skipped'].includes(value) ? 'bg-warn' : 'bg-line-strong';
  return <span className="inline-flex items-center gap-2 font-mono text-[11px] text-ink-2">
    <span className={cn('size-1.5 shrink-0 rounded-full', kind)} aria-hidden="true" />{t(value)}
  </span>;
}

export function PageControls({ page, length, history, busy = false, onOffset }: {
  page: Page; length: number; history: number[]; busy?: boolean; onOffset: (offset: number, back: boolean) => void;
}) {
  const next = nextPageOffset(page);
  return <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
    <p className="font-mono text-[11px] text-ink-3">{t('page', { count: length, start: page.offset + (length ? 1 : 0) })}
      {page.total !== null ? ` · ${t('total', { count: page.total })}` : ''}</p>
    <div className="flex gap-2">
      <Button size="sm" variant="outline" disabled={busy || !history.length} onClick={() => onOffset(history[history.length - 1], true)}>{t('previous')}</Button>
      <Button size="sm" variant="outline" disabled={busy || next === null}
        onClick={() => { if (next !== null) onOffset(next, false); }}>{t('next')}<ArrowRight /></Button>
    </div>
  </footer>;
}

export function NoMatches() { return <Empty title={t('noMatches')} hint={t('noMatchesHint')} />; }
