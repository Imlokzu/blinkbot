import { useEffect, useRef, useState } from 'react';
import { submitToolAnswer } from '@/panels/chat/chatSubmission';
import { Check, Send, X } from '../../vendor/solar-icons/compat.ts';
import { useBotEvents } from '@/hooks/useBotEvents';
import { t } from '@/lib/i18n';
import { useRoute } from '@/app/useRoute';

interface UiPayload {
  id?: string;
  question?: string;
  title?: string;
  options?: (string | { id?: string; label?: string; description?: string })[];
  option_ids?: string[];
  allow_custom?: boolean;
}

type BotUi = { kind: string; data: UiPayload };

export function BotUiOverlay() {
  const [section] = useRoute();
  const [card, setCardState] = useState<BotUi | null>(null);
  const epoch = useRef(0);
  const setCard = (value: BotUi | null) => { ++epoch.current; setCardState(value); };
  const [custom, setCustom] = useState('');
  const [error, setError] = useState(false);
  const pending = useRef(false);
  const send = async (value: string) => {
    if (pending.current || !value.trim()) return;
    const version = epoch.current;
    pending.current = true;
    try {
      const result = await submitToolAnswer(value);
      if (version !== epoch.current) return;
      if (result.accepted) setCard(null); else setError(true);
    }
    finally { pending.current = false; }
  };

  useBotEvents((event) => {
    if (event.type !== 'ui' || event.kind === 'todo') return;
    const data = event.data && typeof event.data === 'object' ? event.data as UiPayload : {};
    setCard({ kind: String(event.kind || ''), data });
    setCustom('');
    setError(false);
  });

  useEffect(() => {
    if (!card) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setCard(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [card]);

  useEffect(() => {
    if (section === 'chat') setCard(null);
  }, [section]);

  // Chat renders tool UI inline with the activity that produced it. Keep the
  // global overlay for spontaneous UI while the user is in another section.
  if (section === 'chat' || !card) return null;
  const { data } = card;
  const options = (data.options ?? []).map((option, index) => typeof option === 'string'
    ? { id: data.option_ids?.[index] || String(index), label: option, description: '' }
    : { id: option.id || String(index), label: String(option.label || ''), description: String(option.description || '') });

  return (
    <aside className="bot-ui-overlay pointer-events-none fixed inset-x-3 z-[var(--z-modal)] flex justify-end sm:inset-x-auto sm:right-[260px] sm:w-[min(390px,calc(100vw-24px))]" aria-live="polite">
      <section className="pointer-events-auto w-full rounded-lg border border-accent/35 bg-surface p-3 shadow-pop u-pop">
        <header className="mb-2 flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <p className="u-label text-accent">{card.kind === 'question' ? t('ui.question') : data.title || t('ui.choice')}</p>
            {data.question ? <p className="mt-1 text-[14px] leading-relaxed text-ink">{data.question}</p> : null}
          </div>
          <button type="button" onClick={() => setCard(null)} aria-label={t('question.dismiss')} className="grid size-8 shrink-0 place-items-center rounded-sm text-ink-3 hover:bg-surface-2 hover:text-ink max-[759px]:size-11"><X size={15} /></button>
        </header>

        {card.kind === 'question' || card.kind === 'choice' ? (
          <div className="grid gap-1.5">
            {options.map((option) => (
              <button key={option.id} type="button" onClick={() => { void send(option.label); }} className="flex min-h-11 items-center gap-2 rounded-md border border-line px-2.5 py-2 text-left text-[13px] text-ink-2 transition-colors hover:border-accent hover:bg-accent-soft hover:text-ink">
                <Check size={14} className="shrink-0 text-accent" />
                <span className="min-w-0 flex-1"><span className="block">{option.label}</span>{option.description ? <span className="mt-0.5 block text-[11px] text-ink-3">{option.description}</span> : null}</span>
              </button>
            ))}
            {card.kind === 'question' && data.allow_custom !== false ? (
              <form className="mt-1 flex gap-1.5" onSubmit={(event) => { event.preventDefault(); void send(custom); }}>
                <input value={custom} onChange={(event) => setCustom(event.target.value)} placeholder={t('question.custom')} className="min-w-0 flex-1 rounded-md border border-line bg-surface-2 px-2.5 py-2 text-[13px] outline-none focus:border-accent" />
                <button type="submit" aria-label={t('question.send')} className="grid min-h-11 min-w-11 place-items-center rounded-md bg-accent px-2.5 text-accent-ink"><Send size={14} /></button>
              </form>
            ) : null}
          </div>
        ) : null}
        {error ? <p role="alert">{t("ui.failed")}</p> : null}
      </section>
    </aside>
  );
}
