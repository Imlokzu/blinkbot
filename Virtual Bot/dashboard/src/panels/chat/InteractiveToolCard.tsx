import { useContext, useRef, useState } from 'react';
import { Check, Send } from '../../vendor/solar-icons/compat.ts';
import { t } from '@/lib/i18n';
import type { ToolStep } from './types';
import { interactiveToolData, type InteractiveToolData } from './interactiveToolData';
import { submitToolAnswer } from './chatSubmission';
import { useToolInteraction } from './useToolInteraction';
import { InteractiveSessionContext, ToolOwnerContext } from './InteractiveSessionContext';

/** Confirmed receipts supply the UI; conversation-owned actions supply state. */
export function InteractiveToolCard({ step }: { step: ToolStep }) {
  const owner = useContext(ToolOwnerContext);
  const session = useContext(InteractiveSessionContext);
  const data = interactiveToolData(step);
  if (!data) return null;
  return <Card key={JSON.stringify([owner, session, step.id, data.id || 'legacy'])} step={step} data={data} />;
}

function Card({ step, data }: { step: ToolStep; data: InteractiveToolData }) {
  const interaction = useToolInteraction(step.id, step.status === 'done');
  const [custom, setCustom] = useState('');
  const [sending, setSending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [accepted, setAccepted] = useState<{ value: string; optionId: string; messageId: string } | null>(null);
  const pending = useRef(false);
  const answer = interaction.state?.answer?.value || accepted?.value;
  const disabled = !interaction.ready || interaction.saving || sending || Boolean(answer);
  const submit = async (value: string, optionId = '') => {
    if (disabled || pending.current || !value.trim()) return;
    pending.current = true;
    setSending(true);
    setFailure(null);
    try {
      const receipt = await submitToolAnswer(value, interaction.sessionId, { owner_id: interaction.owner, call_id: step.id, option_id: optionId, value, expected_revision: interaction.state!.revision });
      if (!receipt.accepted) {
        setFailure(t(receipt.reason === 'busy' ? 'ui.busy' : receipt.reason === 'stale' ? 'ui.stale' : 'ui.failed'));
        return;
      }
      // Retain a real delivery receipt if saving its marker fails; retrying
      // the marker must never send the answer to the model a second time.
      const confirmed = { value, optionId, messageId: receipt.messageId };
      setAccepted(confirmed);
      await interaction.save({ action: 'answer', value, option_id: optionId, message_id: receipt.messageId });
    } finally {
      pending.current = false;
      setSending(false);
    }
  };
  const error = failure || (interaction.error ? t('ui.unsaved') : null);
  const status = <>
    {sending || interaction.saving ? <p className="mt-2 text-[11px] text-ink-3" role="status">{t('ui.pending')}</p> : null}
    {error ? <p className="mt-2 text-[11px] text-ink-3" role="alert">{error}</p> : null}
    {accepted && !interaction.state?.answer && interaction.error ? <button type="button" onClick={() => void interaction.save({ action: 'answer', value: accepted.value, option_id: accepted.optionId, message_id: accepted.messageId })}>{t('ui.retrySave')}</button> : null}
  </>;
  if (data.kind === 'todo') {
    const items = data.items.map((item, index) => ({ ...item, id: item.id || `item-${index}`, done: interaction.state?.done[item.id || `item-${index}`] ?? item.done }));
    return <section className="mt-2 w-full max-w-[520px] rounded-lg border border-accent/30 bg-surface-2 p-3" data-interactive-tool="todo">
      <div className="flex items-baseline justify-between gap-3"><p className="u-label text-accent">{data.title || t('ui.todo')}</p><span className="font-mono text-[10px] text-ink-3">{items.filter(item => item.done).length}/{items.length}</span></div>
      <ul className="mt-2 grid gap-1">{items.map(item => <li key={item.id}>
        <label className="flex min-h-9 cursor-pointer items-center gap-2 rounded-md px-1.5 text-[13px] text-ink-2 hover:bg-surface">
          <input type="checkbox" disabled={disabled} checked={item.done} onChange={() => void interaction.save({ action: 'toggle', item_id: item.id, done: !item.done })} aria-label={t('ui.toggleItem', { item: item.text })} className="size-4 accent-[var(--c-accent)]" />
          <span className={item.done ? 'text-ink-3 line-through' : ''}>{item.text}</span>
        </label>
      </li>)}</ul>{status}
    </section>;
  }
  return <section className="mt-2 w-full max-w-[520px] rounded-lg border border-accent/30 bg-surface-2 p-3" data-interactive-tool={data.kind}>
    <p className="u-label text-accent">{t(data.kind === 'question' ? 'ui.question' : 'ui.choice')}</p>
    {data.title ? <p className="mt-1 text-[14px] leading-relaxed text-ink">{data.title}</p> : null}
    <div className="mt-2 grid gap-1.5">{data.options.map((option, index) => <button key={option.id || index} type="button" disabled={disabled} onClick={() => void submit(option.label, option.id || `option-${index}`)} className="flex min-h-10 items-center gap-2 rounded-md border border-line px-2.5 py-2 text-left text-[13px] text-ink-2 transition-colors hover:border-accent hover:bg-accent-soft hover:text-ink disabled:cursor-default disabled:opacity-60">
      {answer === option.label ? <Check size={14} className="shrink-0 text-accent" /> : <span className="size-3.5 shrink-0 rounded-full border border-line" aria-hidden="true" />}
      <span className="min-w-0 flex-1"><span className="block">{option.label}</span>{option.description ? <span className="mt-0.5 block text-[11px] text-ink-3">{option.description}</span> : null}</span>
    </button>)}</div>
    {data.allowCustom && !answer ? <form className="mt-2 flex gap-1.5" onSubmit={(event) => { event.preventDefault(); void submit(custom.trim()); }}>
      <input disabled={disabled} value={custom} onChange={(event) => setCustom(event.target.value)} placeholder={t('ui.customAnswer')} className="min-w-0 flex-1 rounded-md border border-line bg-surface px-2.5 py-2 text-[13px] outline-none focus:border-accent" />
      <button disabled={disabled} type="submit" aria-label={t('ui.submit')} className="grid min-h-10 min-w-10 place-items-center rounded-md bg-accent px-2.5 text-accent-ink"><Send size={14} /></button>
    </form> : null}
    {interaction.state?.answer ? <p className="mt-2 text-[11px] text-ink-3" role="status">{t('ui.sent')}</p> : null}{status}
  </section>;
}
