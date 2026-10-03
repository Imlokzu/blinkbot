import { useQueryClient } from '@tanstack/react-query';
import { MessageCircle, Plus } from 'lucide-react';
import { SwipeRow } from '@/vendor/reactbits';
import { cn } from '@/lib/cn';
import { Button } from '@/components/ui/Button';
import { Empty, SkeletonList } from '@/components/ui/Feedback';
import { useToast } from '@/components/ui/Toaster';
import { useCssVar } from '@/hooks/useAccentRgb';
import { del } from '@/lib/api';
import { SessionCard } from './SessionCard';
import type { SessionSummary } from './types';
import { t } from '@/lib/i18n';
import { t as chatT } from '@/locales/chat';
import './session-sidebar.css';

/*
 * Список розмов.
 *
 * Прокрутка, поява рядків і затемнення по краях — AnimatedList з React
 * Bits. Свій тут вміст рядка й два способи дістатись до дій:
 *
 *   змах убік (SwipeRow) — те, що працює пальцем і чого не треба шукати;
 *   картка по наведенню (SessionCard) — повна назва, скільки реплік, коли
 *   востаннє, і кнопки; те, що працює мишею.
 *
 * Два способи, бо один рядок 13-м кеглем не вміщає ні назви цілком, ні
 * кнопок, а ховати все за «…» означає зробити зайвий клік обов'язковим.
 */

type SessionGroup = 'today' | 'week' | 'month' | 'earlier';
const GROUPS: SessionGroup[] = ['today', 'week', 'month', 'earlier'];

function ageInDays(ts?: number): number {
  if (!ts) return Number.POSITIVE_INFINITY;
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const date = new Date(ts * 1000);
  const day = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  return Math.max(0, Math.round((start - day) / 86_400_000));
}

function groupOf(ts?: number): SessionGroup {
  const days = ageInDays(ts);
  if (days === 0) return 'today';
  if (days <= 7) return 'week';
  if (days <= 30) return 'month';
  return 'earlier';
}

function when(ts?: number): string {
  if (!ts) return '';
  const date = new Date(ts * 1000);
  const days = ageInDays(ts);
  const locale = document.documentElement.lang.startsWith('en') ? 'en' : 'uk';
  if (days === 0) return date.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
  if (days <= 7) return t('sessions.daysAgo', { count: days });
  return date.toLocaleDateString(locale, { day: '2-digit', month: '2-digit' });
}

export function SessionList({
  sessions,
  loading,
  current,
  onOpen,
  onNew,
  className,
}: {
  sessions: SessionSummary[];
  loading: boolean;
  current: string;
  onOpen: (id: string) => void;
  onNew: () => void;
  className?: string;
}) {
  const client = useQueryClient();
  const toast = useToast();
  const ink = useCssVar('--c-text', '#231e19');
  const surface3 = useCssVar('--c-surface-3', '#e5ddd0');
  const danger = useCssVar('--c-err', '#b2412e');

  const refresh = () => void client.invalidateQueries({ queryKey: ['sessions'] });

  /*
   * Видалення змахом — без перепитування, але з відкотом? Ні: чат_store
   * видаляє файл, повертати нема звідки. Тому змах лише ВІДКРИВАЄ шухляду,
   * а натиснути «Видалити» в ній — уже свідома дія (fullSwipe вимкнено).
   */
  const removeSession = async (session: SessionSummary) => {
    try {
      await del(`/api/sessions/${encodeURIComponent(session.id)}`);
      refresh();
      if (session.id === current) onNew();
      toast.toast(chatT('sessions.deleted'));
    } catch (error) {
      toast.error(chatT('sessions.deleteFailed'), (error as Error).message);
    }
  };

  const item = (session: SessionSummary) => {
    const row = (
      <div className="conversation-row">
        <button type="button" className="conversation-row__main"
          aria-label={chatT('sessions.openConversation', { title: session.title || chatT('sessions.untitled') })}
          aria-current={session.id === current ? 'true' : undefined}
          onClick={() => onOpen(session.id)}>
          <span className="conversation-row__title">{session.title || chatT('sessions.untitled')}</span>
          {session.updated || session.count ? <span className="conversation-row__meta">
            {session.updated ? <span>{when(session.updated)}</span> : null}
            {session.count ? <span className="conversation-row__count"
              title={chatT('sessions.messageCount', { count: session.count })}>
              <MessageCircle size={11} aria-hidden="true" />
              <span>{session.count}</span>
            </span> : null}
          </span> : null}
        </button>
      </div>
    );

    return (
      <SessionCard
        session={session}
        onOpen={() => onOpen(session.id)}
        onDeleted={(id) => {
          if (id === current) onNew();
        }}
      >
        <SwipeRow
          className="session-swipe"
          height={60}
          radius={8}
          actionWidth={96}
          // A full swipe never deletes: an accidental gesture cannot be undone.
          fullSwipe={false}
          rowColor="transparent"
          textColor={ink}
          drawerColor={surface3}
          actionColor={danger}
          label={session.title || chatT('sessions.conversation')}
          actions={[
            { id: 'delete', label: chatT('sessions.delete') },
          ]}
          onAction={(action) => {
            if (action.id === 'delete') void removeSession(session);
          }}
        >
          {row}
        </SwipeRow>
      </SessionCard>
    );
  };

  const grouped = GROUPS.map((group) => ({
    group,
    sessions: sessions.filter((session) => groupOf(session.updated) === group),
  })).filter(({ sessions: items }) => items.length > 0);

  return (
    <div data-swipe-ignore className={cn('conversation-list flex min-h-0 flex-1 flex-col', className)}>
      <div className="conversation-list__header flex items-center justify-between">
        <span className="u-label min-w-0 flex-1">{chatT('sessions.title')}</span>
        <Button variant="ghost" size="icon-sm" onClick={onNew} aria-label={chatT('chat.newSession')}>
          <Plus />
        </Button>
      </div>

      <div className="min-h-0 flex-1 overflow-hidden pb-2">
        {loading ? (
          <SkeletonList rows={6} className="px-3" />
        ) : sessions.length === 0 ? (
          <Empty title={chatT('sessions.empty')} hint={chatT('sessions.emptyHint')} />
        ) : (
          <div className="conversation-list__scroll h-full overflow-y-auto">
            {grouped.map(({ group, sessions: groupSessions }) => (
              <section key={group} className="conversation-list__group">
                <h2 className="conversation-list__group-title u-label">
                  {t(`sessions.${group}`)}
                </h2>
                <div className="space-y-1">
                  {groupSessions.map((session) => (
                    <div key={session.id} data-session-id={session.id} data-session-active={session.id === current ? '' : undefined}>
                      {item(session)}
                    </div>
                  ))}
                </div>
              </section>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
