import { useId, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { Brain, Check } from 'lucide-react';
import { cn } from '@/lib/cn';
import { t } from '@/locales/chat';
import { t as pickerText } from '@/locales/modelPicker';
import { thinkingLabel, useBrainChoice } from './useBrainChoice';

/** Thinking is a shared OpenClaw setting; offer only levels its catalog reports. */
export function EffortMenu({ variant = 'header' }: { variant?: 'header' | 'bar' }) {
  const brain = useBrainChoice();
  const [open, setOpen] = useState(false);
  const group = useRef<HTMLDivElement>(null);
  const hintId = useId();
  const disabled = brain.loading || brain.saving || brain.unavailable;
  // Clearing the setting uses OpenClaw's default, which is distinct from "off".
  const levels = ['', ...new Set(brain.levels.filter(Boolean))];
  const supported = levels.includes(brain.thinking);
  const focusableLevel = supported ? brain.thinking : '';
  const current = brain.thinking ? thinkingLabel(brain.thinking) : t('composer.asConfigured');
  const label = t('composer.chooseEffortCurrent', { level: current });

  const pick = (level: string) => {
    if (!disabled) void brain.pickThinking(level);
  };

  const onKey = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const forward = event.key === 'ArrowDown' || event.key === 'ArrowRight';
    const backward = event.key === 'ArrowUp' || event.key === 'ArrowLeft';
    if (!forward && !backward && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    if (disabled) return;
    const rows = [...group.current!.querySelectorAll<HTMLButtonElement>('[role=radio]:not([aria-disabled=true])')];
    if (!rows.length) return;
    const active = rows.indexOf(event.target as HTMLButtonElement);
    const index = event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1
      : (Math.max(0, active) + (forward ? 1 : rows.length - 1)) % rows.length;
    const next = rows[index];
    next.focus();
    pick(next.dataset.level ?? '');
  };

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button
          type="button"
          aria-label={label}
          title={label}
          className={cn(
            'effort-trigger grid shrink-0 place-items-center rounded-md text-ink-2 outline-none transition-colors hover:bg-surface-2 hover:text-ink focus-visible:ring-2 focus-visible:ring-accent data-[state=open]:bg-surface-2',
            variant === 'header' ? 'size-11' : 'size-8 [@media(pointer:coarse)]:size-11',
          )}
        >
          <Brain aria-hidden="true" className="size-4" strokeWidth={1.75} />
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          side={variant === 'header' ? 'bottom' : 'top'}
          align="end"
          sideOffset={6}
          collisionPadding={12}
          style={{ zIndex: 'var(--z-pop)' }}
          className="effort-menu popup-shell u-pop flex max-h-[var(--radix-popover-content-available-height)] w-[min(264px,calc(100vw-24px))] flex-col overflow-hidden rounded-lg border border-line bg-surface shadow-pop"
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            group.current?.querySelector<HTMLButtonElement>('[role=radio][tabindex="0"]')?.focus();
          }}
        >
          <div className="popup-plate liquid-glass" aria-hidden="true" />
          <div className="shrink-0 border-b border-line px-3 py-2.5">
            <p className="text-[13px] font-medium text-ink">{t('composer.effort')}</p>
            <p id={hintId} className="mt-1 text-[11px] leading-snug text-ink-3">{t('composer.effortHintShort')}</p>
          </div>
          <div
            ref={group}
            role="radiogroup"
            aria-label={t('composer.chooseEffort')}
            aria-describedby={hintId}
            aria-disabled={disabled}
            aria-busy={brain.saving}
            onKeyDown={onKey}
            className="min-h-0 max-h-[min(60dvh,440px)] flex-1 overflow-y-auto overscroll-contain p-1.5"
          >
            {levels.map((level) => (
              <button
                key={level}
                type="button"
                role="radio"
                data-level={level}
                aria-checked={brain.thinking === level}
                aria-disabled={disabled}
                tabIndex={level === focusableLevel ? 0 : -1}
                onClick={() => pick(level)}
                className={cn(
                  'flex min-h-11 w-full items-center gap-3 rounded-md px-2.5 text-left text-[13px] text-ink outline-none transition-colors focus-visible:ring-2 focus-visible:ring-accent',
                  disabled ? 'cursor-default opacity-50' : 'hover:bg-surface-2',
                )}
              >
                <span className="min-w-0 flex-1">{level ? thinkingLabel(level) : t('composer.asConfigured')}</span>
                <Check aria-hidden="true" className={cn('size-4 shrink-0 text-ink-2', brain.thinking !== level && 'invisible')} />
              </button>
            ))}
            {!supported ? (
              <button
                type="button"
                role="radio"
                aria-checked="true"
                aria-disabled="true"
                tabIndex={-1}
                className="flex min-h-11 w-full items-center gap-3 rounded-md px-2.5 text-left text-[13px] text-ink-3"
              >
                <span className="min-w-0 flex-1">
                  <span className="block">{current}</span>
                  <span className="block text-[11px]">{t('composer.effortUnsupported')}</span>
                </span>
                <Check aria-hidden="true" className="size-4 shrink-0" />
              </button>
            ) : null}
          </div>
          {brain.failed || brain.unavailable || brain.saving || brain.levels.length === 0 ? (
            <div role="status" className="flex shrink-0 items-center gap-2 border-t border-line px-3 py-2">
              <p className="min-w-0 flex-1 text-[11px] leading-snug text-ink-3">
                {brain.saving ? pickerText('saving') : brain.unavailable ? pickerText('unavailable')
                  : brain.failed ? t('composer.effortLoadFailed') : brain.loading ? t('composer.loading')
                    : t('composer.effortUnavailable')}
              </p>
              {brain.failed || brain.unavailable ? (
                <button
                  type="button"
                  disabled={brain.refreshing || brain.saving}
                  onClick={() => { void brain.retry(); }}
                  className="min-h-11 shrink-0 rounded-md px-2 text-[12px] text-ink outline-none hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-accent disabled:opacity-50"
                >
                  {pickerText(brain.refreshing ? 'retrying' : 'retry')}
                </button>
              ) : null}
            </div>
          ) : null}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
