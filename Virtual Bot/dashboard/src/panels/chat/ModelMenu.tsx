import { useEffect, useId, useMemo, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { Check, ChevronDown, Search, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { t } from '@/locales/chat';
import { t as pickerText } from '@/locales/modelPicker';
import type { BrainModel } from '@/lib/queries';
import { BrandLogo } from './BrandLogo';
import { EffortOptions } from './EffortMenu';
import { BRAND_NAMES, arrange, parseRecent, remember } from './modelCatalog';
import { thinkingLabel, useBrainChoice } from './useBrainChoice';
import './model-menu.css';

const RECENT_KEY = 'claudeBotRecentModels';

/** Storage is optional: a blocked browser still has a fully working picker. */
function loadRecent(fallback: string[] = []): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    return raw ? parseRecent(JSON.parse(raw)) : fallback;
  } catch { return fallback; }
}

function GroupHead({ brand }: { brand: string | null }) {
  if (!brand) return null;
  const title = brand === 'recent' ? t('models.recent')
    : brand === 'auto' ? t('models.auto')
      : brand === 'other' ? t('models.other')
        : BRAND_NAMES[brand as keyof typeof BRAND_NAMES];
  return <div role="presentation" className="brain-picker-group u-label">{title}</div>;
}

export function ModelMenu({ variant = 'header' }: { variant?: 'header' | 'bar' }) {
  const brain = useBrainChoice();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [recent, setRecent] = useState(() => loadRecent());
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const search = useRef<HTMLDivElement>(null);
  const modelsPane = useRef<HTMLElement>(null);
  const pickedFocus = useRef<string | null>(null);
  const base = useId();
  const groups = useMemo(() => {
    const arranged = arrange(brain.models, { query, recent });
    const recentIds = new Set(arranged.find(group => group.key === 'recent')?.models.map(model => model.id));
    // A radio group has one checked row: recents move choices instead of repeating them.
    return arranged.map(group => group.key === 'recent' ? group : {
      ...group, models: group.models.filter(model => !recentIds.has(model.id)),
    }).filter(group => group.models.length);
  }, [brain.models, query, recent]);
  const flat = useMemo(() => groups.flatMap((group) => group.models), [groups]);
  const cursor = Math.min(active, Math.max(0, flat.length - 1));
  const disabled = brain.loading || brain.saving || brain.unavailable;

  const pick = async (model: BrainModel) => {
    const focusOwner = document.activeElement;
    const restoreRowFocus = focusOwner instanceof HTMLButtonElement
      && focusOwner.dataset.model === model.id && modelsPane.current?.contains(focusOwner);
    if (disabled || model.available === false || !await brain.pickModel(model.id)) return;
    // Recents record acknowledged writes only. Leave the picker open for effort.
    const next = remember(loadRecent(recent), model.id);
    // A delayed acknowledgement must not pull focus back from the other column.
    if (restoreRowFocus && document.activeElement === focusOwner) pickedFocus.current = model.id;
    setRecent(next);
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(next)); } catch { /* Optional storage. */ }
  };

  useEffect(() => {
    if (!pickedFocus.current) return;
    const picked = [...(modelsPane.current?.querySelectorAll<HTMLButtonElement>('[data-model]') ?? [])]
      .find(button => button.dataset.model === pickedFocus.current);
    picked?.focus();
    pickedFocus.current = null;
  }, [recent]);

  const focusModel = (index: number) => {
    setActive(index);
    document.getElementById(`${base}-${index}`)?.focus();
  };

  const onKey = (event: React.KeyboardEvent<HTMLElement>) => {
    if (event.nativeEvent.isComposing || disabled) return;
    const searching = event.target === input.current;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || ((!searching || !query) && (event.key === 'Home' || event.key === 'End'))) {
      event.preventDefault();
      const enabled = flat.map((model, index) => model.available === false ? -1 : index).filter(index => index !== -1);
      if (!enabled.length) return;
      const position = enabled.indexOf(cursor);
      const next = event.key === 'Home' ? enabled[0] : event.key === 'End' ? enabled.at(-1)!
        : enabled[(Math.max(0, position) + (event.key === 'ArrowDown' ? 1 : enabled.length - 1)) % enabled.length];
      if (searching) setActive(next);
      else focusModel(next);
    } else if (event.key === 'Enter' && searching) {
      event.preventDefault();
      if (flat[cursor]) void pick(flat[cursor]);
    }
  };

  useEffect(() => {
    if (!open) return;
    const list = modelsPane.current?.querySelector<HTMLElement>('.brain-picker-list');
    // Scroll only this column; scrollIntoView can otherwise move the chat beneath it.
    if (!list) return;
    const scrollActiveRow = () => {
      const row = document.getElementById(`${base}-${cursor}`);
      if (!row || !list.contains(row)) return;
      const headingHeight = list.querySelector<HTMLElement>('.brain-picker-group')?.offsetHeight ?? 0;
      const top = row.offsetTop;
      const bottom = top + row.offsetHeight;
      if (top < list.scrollTop + headingHeight) list.scrollTop = Math.max(0, top - headingHeight);
      else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight;
    };
    scrollActiveRow();
    // Radix constrains the column after autofocus; keyboard and viewport changes can resize it again.
    const observer = new ResizeObserver(scrollActiveRow);
    observer.observe(list);
    return () => observer.disconnect();
  }, [open, cursor, base, flat, brain.failed, brain.unavailable]);

  const changeOpen = (next: boolean) => {
    setOpen(next);
    if (next) {
      setQuery('');
      setSearchOpen(false);
      setActive(0);
    }
  };
  const label = brain.currentModel?.label || brain.current || (brain.loading ? t('composer.loading') : 'OpenClaw');
  const effort = brain.thinking ? thinkingLabel(brain.thinking) : t('composer.asConfigured');
  const accessibleLabel = t('composer.chooseModelEffort', { model: label, level: effort });
  let row = -1;

  return (
    <Popover.Root open={open} onOpenChange={changeOpen}>
      <Popover.Trigger asChild>
        <button
          type="button"
          aria-label={accessibleLabel}
          title={accessibleLabel}
          data-brain-choice-trigger=""
          className={cn('brain-choice-trigger group', variant === 'header' ? 'brain-choice-trigger-header' : 'brain-choice-trigger-bar')}
        >
          {brain.currentModel ? <BrandLogo model={brain.currentModel} className={variant === 'bar' ? 'size-3.5' : undefined} /> : null}
          <span className="brain-choice-model">{label}</span>
          <span aria-hidden="true" className="brain-choice-effort">{effort}</span>
          <ChevronDown aria-hidden="true" className="size-3.5 shrink-0 text-ink-3 transition-transform group-data-[state=open]:rotate-180 motion-reduce:transition-none" strokeWidth={1.75} />
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          side={variant === 'header' ? 'bottom' : 'top'}
          align={variant === 'header' ? 'center' : 'start'}
          sideOffset={6}
          collisionPadding={12}
          style={{ zIndex: 'var(--z-pop)' }}
          aria-label={t('composer.modelEffort')}
          data-popup-kind="models"
          className="model-effort-menu popup-shell u-pop rounded-lg border border-line bg-surface shadow-pop"
          onEscapeKeyDown={(event) => {
            if (!query && !searchOpen) return;
            event.preventDefault();
            setQuery('');
            setSearchOpen(false);
            setActive(0);
            document.getElementById(`${base}-0`)?.focus();
          }}
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            const current = modelsPane.current?.querySelector<HTMLButtonElement>('[role=radio][aria-checked=true]:not([aria-disabled=true])');
            (current ?? modelsPane.current?.querySelector<HTMLButtonElement>('[role=radio]:not([aria-disabled=true])'))?.focus();
            if (current) setActive(Number(current.dataset.index));
          }}
        >
          <div className="popup-plate liquid-glass" aria-hidden="true" />
          <section ref={modelsPane} className="brain-picker-pane model-picker-pane" aria-label={t('composer.models')}>
            <div className="brain-picker-heading model-picker-heading" data-search-open={searchOpen ? '' : undefined}>
              <h2 className="u-label model-picker-title">{t('composer.models')}</h2>
              <div
                ref={search}
                className="model-picker-search"
                onPointerEnter={(event) => { if (event.pointerType === 'mouse') setSearchOpen(true); }}
                onPointerLeave={() => { if (!query && !search.current?.contains(document.activeElement)) setSearchOpen(false); }}
                onBlur={(event) => { if (!query && !event.currentTarget.contains(event.relatedTarget)) setSearchOpen(false); }}
              >
                <button type="button" aria-label={t('models.openSearch')} aria-expanded={searchOpen} aria-controls={`${base}-search`}
                  className="model-picker-search-button" onFocus={() => setSearchOpen(true)}
                  onClick={() => { setSearchOpen(true); input.current?.focus(); }}>
                  <Search aria-hidden="true" className="size-4" strokeWidth={1.75} />
                </button>
                <div className="model-picker-search-field">
                  <input
                    id={`${base}-search`} ref={input} value={query} tabIndex={searchOpen ? 0 : -1} aria-hidden={!searchOpen}
                    onFocus={() => setSearchOpen(true)} onChange={(event) => { setQuery(event.target.value); setActive(0); }}
                    onKeyDown={onKey} placeholder={t('models.search')} aria-label={t('models.search')}
                    role="searchbox" aria-controls={`${base}-list`} autoComplete="off" spellCheck={false}
                  />
                  {query ? <button type="button" aria-label={t('models.clear')} className="model-picker-clear"
                    onClick={() => { setQuery(''); setActive(0); input.current?.focus(); }}>
                    <X aria-hidden="true" className="size-3.5" strokeWidth={1.75} />
                  </button> : null}
                </div>
              </div>
            </div>
            <div className="brain-picker-list model-picker-list">
              {brain.failed || brain.unavailable || (!brain.loading && !brain.models.length) ? (
                <div className="brain-picker-status" role="status">
                  <p>{brain.failed ? pickerText(brain.models.length ? 'stale' : 'failed')
                    : brain.unavailable ? pickerText('unavailable') : pickerText('empty')}</p>
                  <button type="button" disabled={brain.refreshing || brain.saving} onClick={() => { void brain.retry(); }}>
                    {pickerText(brain.refreshing ? 'retrying' : 'retry')}
                  </button>
                </div>
              ) : null}
              <div id={`${base}-list`} role="radiogroup" aria-label={t('composer.models')}
                aria-disabled={disabled} aria-busy={brain.loading || brain.saving} onKeyDown={onKey}>
                {groups.map((group) => (
                  <GroupBlock key={group.key}>
                    <GroupHead brand={group.brand} />
                    {group.models.map((model) => {
                      const index = ++row;
                      const current = model.id === brain.current;
                      const unavailable = disabled || model.available === false;
                      return (
                        <button key={`${group.key}-${model.id}`} id={`${base}-${index}`} type="button" role="radio"
                          data-model={model.id} data-index={index} aria-checked={current} aria-disabled={unavailable}
                          tabIndex={index === cursor ? 0 : -1} title={model.label} onFocus={() => setActive(index)}
                          onPointerEnter={() => { if (document.activeElement === input.current) setActive(index); }}
                          onClick={() => { void pick(model); }}
                          className={cn('brain-picker-row model-picker-row', unavailable && 'is-disabled', index === cursor && query && 'is-search-active')}>
                          <BrandLogo model={model} />
                          <span className="min-w-0 flex-1">
                            <span className="brain-picker-name block">{model.label}</span>
                            {model.available === false ? <span className="brain-picker-feedback">{t('models.unavailable')}</span> : null}
                          </span>
                          {current ? <Check aria-hidden="true" className="brain-picker-check" strokeWidth={1.75} /> : null}
                        </button>
                      );
                    })}
                  </GroupBlock>
                ))}
                {!flat.length ? <p className="brain-picker-note">{brain.loading ? t('composer.loading')
                  : brain.models.length ? t('models.none', { query }) : null}</p> : null}
              </div>
            </div>
          </section>
          <EffortOptions brain={brain} />
          {brain.saving ? <p className="sr-only" role="status">{pickerText('saving')}</p> : null}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

function GroupBlock({ children }: { children: React.ReactNode }) { return <>{children}</>; }
