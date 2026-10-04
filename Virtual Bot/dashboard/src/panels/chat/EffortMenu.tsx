import { useEffect, useId, useRef } from 'react';
import { Check } from '../../vendor/solar-icons/compat.ts';
import { cn } from '@/lib/cn';
import { t } from '@/locales/chat';
import { thinkingLabel, type useBrainChoice } from './useBrainChoice';

/** The combined picker shares one live choice state across both columns. */
export function EffortOptions({ brain }: { brain: ReturnType<typeof useBrainChoice> }) {
  const group = useRef<HTMLDivElement>(null);
  const hintId = useId();
  const disabled = brain.loading || brain.saving || brain.unavailable;
  // Clearing the setting uses the gateway default; explicit "off" is a separate choice.
  const levels = ['', ...new Set(brain.levels.filter(Boolean))];
  const supported = levels.includes(brain.thinking);
  const focusableLevel = supported ? brain.thinking : '';

  useEffect(() => {
    const list = group.current;
    if (!list) return;
    // Keep the selected level visible when a phone or its keyboard reduces this column.
    const scrollCurrentRow = () => {
      const row = list.querySelector<HTMLButtonElement>('[role=radio][aria-checked=true]');
      if (!row) return;
      const top = row.offsetTop;
      const bottom = top + row.offsetHeight;
      if (top < list.scrollTop) list.scrollTop = top;
      else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight;
    };
    scrollCurrentRow();
    const observer = new ResizeObserver(scrollCurrentRow);
    observer.observe(list);
    return () => observer.disconnect();
  }, [brain.thinking, brain.levels]);

  const pick = (level: string) => {
    if (!disabled) void brain.pickThinking(level);
  };

  const onKey = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const forward = event.key === 'ArrowDown' || event.key === 'ArrowRight';
    const backward = event.key === 'ArrowUp' || event.key === 'ArrowLeft';
    if (!forward && !backward && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    if (disabled) return;
    const rows = [...(group.current?.querySelectorAll<HTMLButtonElement>('[role=radio]:not([aria-disabled=true])') ?? [])];
    if (!rows.length) return;
    const active = rows.indexOf(event.target as HTMLButtonElement);
    const index = event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1
      : (Math.max(0, active) + (forward ? 1 : rows.length - 1)) % rows.length;
    const next = rows[index];
    next.focus();
    pick(next.dataset.level ?? '');
  };

  return (
    <section className="brain-picker-pane effort-picker-pane" aria-label={t('composer.effort')}>
      <div className="brain-picker-heading"><h2 className="u-label">{t('composer.effort')}</h2></div>
      <p id={hintId} className="sr-only">{t('composer.effortHintShort')}</p>
      <div
        ref={group}
        role="radiogroup"
        aria-label={t('composer.chooseEffort')}
        aria-describedby={hintId}
        aria-disabled={disabled}
        aria-busy={brain.saving}
        onKeyDown={onKey}
        className="brain-picker-list effort-picker-list"
      >
        {levels.map((level) => {
          const label = level ? thinkingLabel(level) : t('composer.asConfigured');
          return (
            <button
              key={level}
              type="button"
              role="radio"
              data-level={level}
              aria-checked={brain.thinking === level}
              aria-disabled={disabled}
              tabIndex={level === focusableLevel ? 0 : -1}
              title={label}
              onClick={() => pick(level)}
              className={cn('brain-picker-row effort-picker-row', disabled && 'is-disabled')}
            >
              <span className="brain-picker-name">{label}</span>
              {brain.thinking === level ? <Check aria-hidden="true" className="brain-picker-check" strokeWidth={1.75} /> : null}
            </button>
          );
        })}
        {!supported ? (
          <button type="button" role="radio" aria-checked="true" aria-disabled="true" tabIndex={-1} className="brain-picker-row is-disabled">
            <span className="min-w-0 flex-1">
              <span className="brain-picker-name block" title={brain.thinking}>{thinkingLabel(brain.thinking)}</span>
              <span className="brain-picker-feedback">{t('composer.effortUnsupported')}</span>
            </span>
            <Check aria-hidden="true" className="brain-picker-check" strokeWidth={1.75} />
          </button>
        ) : null}
        {!brain.loading && !brain.levels.length ? <p className="brain-picker-note">{t('composer.effortUnavailable')}</p> : null}
      </div>
    </section>
  );
}
