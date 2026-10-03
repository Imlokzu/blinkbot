import { useEffect, useId, useMemo, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { Brain, Check, ChevronDown, Eye, LifeBuoy, Search, X, Zap } from 'lucide-react';
import { Segmented } from '@/components/ui/Segmented';
import { t as inferenceText } from '@/locales/inference';
import { cn } from '@/lib/cn';
import { t } from '@/locales/chat';
import { t as pickerText } from '@/locales/modelPicker';
import type { BrainModel } from '@/lib/queries';
import { BrandLogo } from './BrandLogo';
import { BRAND_NAMES, arrange, hostOf, parseRecent, remember, type Capability } from './modelCatalog';
import { useBrainChoice } from './useBrainChoice';

/*
 * Which model answers.
 *
 * One picker for every layout. On a phone it is the chat header's title; on
 * the desk it sits in the prompt bar where the vendor's pickers were. The
 * vendor list had no search and showed the catalog in its own order, which
 * was fine for five models and painful for sixty — so both layouts now get
 * the same searchable, grouped list (see modelCatalog.ts).
 */

const RECENT_KEY = 'claudeBotRecentModels';

/** Browser storage is a convenience here; the menu works without it. */
function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}
function save(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Private mode or blocked storage: the choice just is not remembered.
  }
}

function GroupHead({ brand, count }: { brand: string | null; count: number }) {
  if (!brand) return null;
  const title = brand === 'recent' ? t('models.recent')
    : brand === 'auto' ? t('models.auto')
    : brand === 'other' ? t('models.other')
      : brand === 'all' ? t('models.all')
      : BRAND_NAMES[brand as keyof typeof BRAND_NAMES];
  return (
    // Sticky, so a long group still says whose models you are scrolling.
    <li role="presentation" className="sticky top-0 z-[1] flex items-center gap-2 bg-surface px-2 pb-1 pt-2.5">
      {Object.hasOwn(BRAND_NAMES, brand) ? <BrandLogo brand={brand as keyof typeof BRAND_NAMES} className="size-3.5" /> : null}
      <span className="u-label">{title}</span>
      <span className="font-mono text-[10px] text-ink-3">{count}</span>
    </li>
  );
}

function ModelRow({ model, id, current, active, disabled, onPick, onHover }: {
  model: BrainModel;
  id: string;
  current: boolean;
  active: boolean;
  disabled: boolean;
  onPick: () => void;
  onHover: () => void;
}) {
  // Jev has no host worth naming; say what it does instead.
  const host = model.auto ? t('models.autoHint') : hostOf(model.id);
  return (
    <li
      id={id}
      role="option"
      aria-selected={current}
      aria-disabled={disabled}
      data-active={active ? '' : undefined}
      onPointerEnter={onHover}
      // A mouse press must not steal focus from the search field, or the
      // arrow keys would stop working after the first hover-and-miss.
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => { if (!disabled) onPick(); }}
      className={cn(
        'flex min-h-11 cursor-pointer items-center gap-2.5 rounded-md px-2 text-left text-ink transition-colors',
        current ? 'bg-accent-soft' : 'data-[active]:bg-surface-2',
        disabled && 'cursor-default opacity-50',
      )}
    >
      <BrandLogo model={model} />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[13px]">{model.label}</span>
        {host ? <span className="block truncate font-mono text-[10.5px] text-ink-3">{host}</span> : null}
      </span>
      {model.available === false ? <span className="shrink-0 text-[10px] text-ink-3">{t('models.unavailable')}</span> : null}
      {model.vision ? <Eye aria-label={t('trait.vision')} className="size-3.5 shrink-0 text-ink-3" /> : null}
      {model.fast ? (
        <Zap
          aria-label={model.seconds ? t('trait.fastSeconds', { seconds: model.seconds }) : t('trait.fast')}
          className="size-3.5 shrink-0 text-ink-3"
        />
      ) : null}
      {model.is_default ? <Brain aria-label={t('role.default')} className="size-3.5 shrink-0 text-ink-3" /> : null}
      {model.fallback ? <LifeBuoy aria-label={t('role.fallback')} className="size-3.5 shrink-0 text-ink-3" /> : null}
      <Check className={cn('size-4 shrink-0 text-accent', !current && 'invisible')} />
    </li>
  );
}

export function ModelMenu({ variant = 'header' }: {
  /** `header` is the phone chat title; `bar` sits inside the desktop prompt bar. */
  variant?: 'header' | 'bar';
}) {
  const brain = useBrainChoice();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [recent, setRecent] = useState<string[]>(() => parseRecent(load<unknown>(RECENT_KEY, [])));
  const [capability, setCapability] = useState<Capability>('all');
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const base = useId();

  const groups = useMemo(
    () => arrange(brain.models, { query, recent, capability }),
    [brain.models, query, recent, capability],
  );
  // One flat sequence for the arrow keys; a model can appear twice (recent
  // and its own group), so rows are addressed by position, not by id.
  const flat = useMemo(() => groups.flatMap((group) => group.models), [groups]);
  const cursor = Math.min(active, Math.max(0, flat.length - 1));
  const count = new Set(flat.map((model) => model.id)).size;
  const disabled = brain.saving || brain.unavailable;

  const pick = async (model: BrainModel) => {
    if (disabled || model.available === false || !await brain.pickModel(model.id)) return;
    // Only acknowledged choices enter recents; a rejected write stays retryable.
    const next = remember(parseRecent(load<unknown>(RECENT_KEY, recent)), model.id);
    setRecent(next);
    save(RECENT_KEY, next);
    setOpen(false);
  };

  // Each opening starts clean: an old query would hide models without saying so.
  useEffect(() => {
    if (!open) return;
    setQuery('');
    setCapability('all');
    setActive(0);
  }, [open]);

  // Keep the keyboard's row in view as the arrows walk past the edge.
  useEffect(() => {
    if (!open) return;
    document.getElementById(`${base}-${cursor}`)?.scrollIntoView({ block: 'nearest' });
  }, [open, cursor, base]);

  const onKey = (event: React.KeyboardEvent<HTMLInputElement>) => {
    // Enter confirms an IME composition before it can choose a model.
    if (event.nativeEvent.isComposing) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!flat.length) return;
      setActive((cursor + (event.key === 'ArrowDown' ? 1 : flat.length - 1)) % flat.length);
    } else if (event.key === 'Home' || event.key === 'End') {
      if (query) return; // Preserve text-caret navigation while searching.
      event.preventDefault();
      setActive(event.key === 'Home' ? 0 : Math.max(0, flat.length - 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (flat[cursor]) pick(flat[cursor]);
    }
  };

  const label = brain.currentModel?.label || (brain.loading ? t('composer.loading') : 'OpenClaw');
  let row = -1;

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        {variant === 'header' ? (
          <button
            type="button"
            aria-label={t('composer.chooseModel')}
            className="group flex min-h-11 min-w-0 max-w-full items-center gap-1.5 rounded-md px-2.5 text-ink outline-none transition-colors hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-accent data-[state=open]:bg-surface-2"
          >
            {brain.currentModel ? <BrandLogo model={brain.currentModel} /> : null}
            <span className="truncate font-mono text-[15px] font-medium">{label}</span>
            <ChevronDown className="size-3.5 shrink-0 text-ink-3 transition-transform group-data-[state=open]:rotate-180 motion-reduce:transition-none" />
          </button>
        ) : (
          <button
            type="button"
            aria-label={t('composer.chooseModel')}
            className="group flex h-8 min-w-0 max-w-full items-center gap-1.5 rounded-md px-2 text-[12.5px] text-ink-2 outline-none transition-colors hover:bg-surface-2 hover:text-ink focus-visible:ring-2 focus-visible:ring-accent data-[state=open]:bg-surface-2 [@media(pointer:coarse)]:min-h-11"
          >
            {brain.currentModel ? <BrandLogo model={brain.currentModel} className="size-3.5" /> : null}
            <span className="truncate">{label}</span>
            <ChevronDown className="size-3 shrink-0 text-ink-3 transition-transform group-data-[state=open]:rotate-180 motion-reduce:transition-none" />
          </button>
        )}
      </Popover.Trigger>

      <Popover.Portal>
        <Popover.Content
          side={variant === 'header' ? 'bottom' : 'top'}
          align={variant === 'header' ? 'center' : 'start'}
          sideOffset={6}
          collisionPadding={12}
          style={{ zIndex: 'var(--z-pop)' }}
          className="popup-shell u-pop flex max-h-[min(78dvh,620px,var(--radix-popover-content-available-height))] w-[min(360px,calc(100vw-24px))] flex-col overflow-hidden rounded-lg border border-line bg-surface shadow-pop"
          // On touch the keyboard would cover half the list the moment the
          // menu opens; there the search waits for a tap.
          // First Escape clears the search, the second closes the menu.
          // Radix sees the key before the input does, so it is decided here.
          onEscapeKeyDown={(event) => {
            if (!query) return;
            event.preventDefault();
            setQuery('');
            setActive(0);
          }}
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            if (!window.matchMedia('(pointer: coarse)').matches) input.current?.focus();
          }}
        >
          <div className="popup-plate liquid-glass" aria-hidden="true" />
          <div className="shrink-0 space-y-2 border-b border-line p-2.5">
            <label className="flex h-9 items-center gap-2 rounded-md bg-surface-2 px-2.5 focus-within:ring-2 focus-within:ring-accent max-[759px]:h-11">
              <Search className="size-4 shrink-0 text-ink-3" />
              <input
                ref={input}
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value);
                  setActive(0);
                }}
                onKeyDown={onKey}
                placeholder={t('models.search')}
                aria-label={t('models.search')}
                role="combobox"
                aria-expanded="true"
                aria-autocomplete="list"
                aria-controls={`${base}-list`}
                aria-activedescendant={flat.length ? `${base}-${cursor}` : undefined}
                autoComplete="off"
                spellCheck={false}
                className="min-w-0 flex-1 bg-transparent text-[13px] text-ink outline-none placeholder:text-ink-3 max-[759px]:text-[16px]"
              />
              {query ? (
                <button
                  type="button"
                  aria-label={t('models.clear')}
                  onClick={() => {
                    setQuery('');
                    setActive(0);
                    input.current?.focus();
                  }}
                  className="-my-1 grid size-11 place-items-center rounded-sm text-ink-3 outline-none hover:text-ink focus-visible:ring-2 focus-visible:ring-accent"
                >
                  <X className="size-3.5" />
                </button>
              ) : null}
            </label>
            <Segmented<Capability>
              size="sm"
              ariaLabel={pickerText('filter')}
              value={capability}
              onChange={(next) => { setCapability(next); setActive(0); }}
              className="w-full"
              items={[
                { value: 'all', label: pickerText('all') },
                { value: 'vision', label: t('trait.vision') },
                { value: 'fast', label: t('trait.fast') },
              ]}
            />
            <p role="status" className="text-[11px] text-ink-3">
              {brain.saving ? pickerText('saving') : pickerText('count', { count })}
            </p>
          </div>

          {brain.failed || brain.unavailable || (!brain.loading && !brain.models.length) ? (
            <div className="flex shrink-0 items-center gap-2 border-b border-line px-3 py-2" role="status">
              <p className="min-w-0 flex-1 text-[12px] text-ink-2">
                {brain.failed ? pickerText(brain.models.length ? 'stale' : 'failed')
                  : brain.unavailable ? pickerText('unavailable') : pickerText('empty')}
              </p>
              <button
                type="button"
                disabled={brain.refreshing || brain.saving}
                onClick={() => { void brain.retry(); }}
                className="min-h-11 shrink-0 rounded-md px-2 text-[12px] text-ink hover:bg-surface-2 disabled:opacity-50"
              >
                {pickerText(brain.refreshing ? 'retrying' : 'retry')}
              </button>
            </div>
          ) : null}

          <ul
            id={`${base}-list`}
            role="listbox"
            aria-label={t('composer.models')}
            aria-busy={brain.loading || brain.saving}
            className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-1.5 pb-1.5"
          >
            {groups.map((group) => (
              <GroupBlock key={group.key}>
                {/* A flat list needs no heading on its own, but under the
                    recent picks it does — or it reads as more of them. */}
                <GroupHead brand={group.brand ?? (groups.length > 1 ? 'all' : null)} count={group.models.length} />
                {group.models.map((model) => {
                  row += 1;
                  const index = row;
                  return (
                    <ModelRow
                      key={`${group.key}-${model.id}`}
                      id={`${base}-${index}`}
                      model={model}
                      current={model.id === brain.current}
                      active={index === cursor}
                      disabled={disabled || model.available === false}
                      onHover={() => setActive(index)}
                      onPick={() => pick(model)}
                    />
                  );
                })}
              </GroupBlock>
            ))}
            {!flat.length ? (
              <li className="px-2 py-4 text-center text-[13px] text-ink-3">
                {brain.loading ? t('composer.loading')
                  : brain.models.length ? (capability !== 'all' && !query ? pickerText('noCapability') : t('models.none', { query }))
                    : null}
              </li>
            ) : null}
          </ul>

          <a href="#/inference?tab=comparison" className="shrink-0 border-t border-line px-3 py-3 text-sm text-ink-2 hover:bg-surface-2" onClick={() => setOpen(false)}>{inferenceText('comparisonTab')}</a>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

/** Groups are fragments in the listbox: options must stay its direct children. */
function GroupBlock({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
