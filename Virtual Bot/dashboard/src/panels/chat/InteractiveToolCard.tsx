import { useMemo, useState } from 'react';
import { Check, Send } from '../../vendor/solar-icons/compat.ts';
import { t } from '@/lib/i18n';
import type { ToolStep } from './types';
import { interactiveToolData, type InteractiveChoice, type InteractiveTodoItem } from './interactiveToolData';

declare global {
  interface Window {
    __vbotSendMessage?: (text: string) => void;
  }
}

function sendAnswer(value: string): boolean {
  const answer = value.trim();
  if (!answer) return false;
  if (typeof window.__vbotSendMessage !== 'function') return false;
  window.__vbotSendMessage(answer);
  return true;
}

/**
 * Renders the small UI tools inline with the activity that produced them.
 * The tool input is already persisted in ToolStep, so reopening a chat can
 * reconstruct the card without adding another response format yet.
 */
export function InteractiveToolCard({ step }: { step: ToolStep }) {
  const data = interactiveToolData(step);
  if (!data) return null;
  if (data.kind === 'todo') return <TodoCard title={data.title} initialItems={data.items} />;
  return <ChoiceCard kind={data.kind} title={data.title} options={data.options} allowCustom={data.allowCustom} />;
}

function ChoiceCard({ kind, title, options, allowCustom }: {
  kind: 'question' | 'choice';
  title: string;
  options: InteractiveChoice[];
  allowCustom: boolean;
}) {
  const [sent, setSent] = useState<string | null>(null);
  const [custom, setCustom] = useState('');
  const heading = kind === 'question' ? t('ui.question') : t('ui.choice');
  const submit = (value: string) => {
    if (sent || !sendAnswer(value)) return;
    setSent(value.trim());
  };

  return <section className="mt-2 w-full max-w-[520px] rounded-lg border border-accent/30 bg-surface-2 p-3" data-interactive-tool={kind}>
    <p className="u-label text-accent">{heading}</p>
    {title ? <p className="mt-1 text-[14px] leading-relaxed text-ink">{title}</p> : null}
    <div className="mt-2 grid gap-1.5">
      {options.map((option) => <button
        key={option.label}
        type="button"
        disabled={sent !== null}
        onClick={() => submit(option.label)}
        className="flex min-h-10 items-center gap-2 rounded-md border border-line px-2.5 py-2 text-left text-[13px] text-ink-2 transition-colors hover:border-accent hover:bg-accent-soft hover:text-ink disabled:cursor-default disabled:opacity-60"
      >
        {sent === option.label ? <Check size={14} className="shrink-0 text-accent" /> : <span className="size-3.5 shrink-0 rounded-full border border-line" aria-hidden="true" />}
        <span className="min-w-0 flex-1"><span className="block">{option.label}</span>{option.description ? <span className="mt-0.5 block text-[11px] text-ink-3">{option.description}</span> : null}</span>
      </button>)}
    </div>
    {allowCustom && sent === null ? <form className="mt-2 flex gap-1.5" onSubmit={(event) => { event.preventDefault(); submit(custom); }}>
      <input value={custom} onChange={(event) => setCustom(event.target.value)} placeholder={t('ui.customAnswer')} className="min-w-0 flex-1 rounded-md border border-line bg-surface px-2.5 py-2 text-[13px] outline-none focus:border-accent" />
      <button type="submit" aria-label={t('ui.submit')} className="grid min-h-10 min-w-10 place-items-center rounded-md bg-accent px-2.5 text-accent-ink"><Send size={14} /></button>
    </form> : null}
    {sent ? <p className="mt-2 text-[11px] text-ink-3" role="status">{t('ui.sent')}</p> : null}
  </section>;
}

function TodoCard({ title, initialItems }: { title: string; initialItems: InteractiveTodoItem[] }) {
  const [items, setItems] = useState(initialItems);
  const completed = useMemo(() => items.filter((item) => item.done).length, [items]);
  if (!items.length) return null;
  return <section className="mt-2 w-full max-w-[520px] rounded-lg border border-accent/30 bg-surface-2 p-3" data-interactive-tool="todo">
    <div className="flex items-baseline justify-between gap-3">
      <p className="u-label text-accent">{title || t('ui.todo')}</p>
      <span className="font-mono text-[10px] text-ink-3">{completed}/{items.length}</span>
    </div>
    <ul className="mt-2 grid gap-1">
      {items.map((item, index) => <li key={`${item.text}-${index}`}>
        <label className="flex min-h-9 cursor-pointer items-center gap-2 rounded-md px-1.5 text-[13px] text-ink-2 hover:bg-surface">
          <input
            type="checkbox"
            checked={item.done}
            onChange={() => setItems((current) => current.map((entry, itemIndex) => itemIndex === index ? { ...entry, done: !entry.done } : entry))}
            aria-label={t('ui.toggleItem', { item: item.text })}
            className="size-4 accent-[var(--c-accent)]"
          />
          <span className={item.done ? 'text-ink-3 line-through' : ''}>{item.text}</span>
        </label>
      </li>)}
    </ul>
  </section>;
}
