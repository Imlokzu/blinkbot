import { useEffect, useId, useMemo, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { Check, ChevronDown, Search, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import { t } from '@/locales/chat';
import { t as pickerText } from '@/locales/modelPicker';
import { useBrainIntelligence, type BrainModel } from '@/lib/queries';
import { BrandLogo } from './BrandLogo';
import { EffortOptions } from './EffortMenu';
import { BRAND_NAMES, arrange, parseRecent, remember } from './modelCatalog';
import { coverage, loadShowIntel, scoreLine, type IntelBenchmark, type IntelEntry, type IntelligenceResponse } from './modelIntelligence';
import { IntelCard, IntelGauge } from './IntelGauge';
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

/** Hover-card state: which row's card is open, and whether a click pinned it. */
type Detail = { id: string; pinned: boolean } | null;

/** The dial and number shown in a row; the breakdown lives in the hover card. */
function IntelScore({ entry }: { entry: IntelEntry }) {
  return (
    <>
      <IntelGauge value={entry.index} />
      <span className="model-intel-value">{Math.round(entry.index)}</span>
    </>
  );
}

/** Screen readers get the card's facts on the dial button itself. */
function intelSummary(entry: IntelEntry, benchmarks: IntelBenchmark[]): string {
  const count = coverage(entry, benchmarks);
  return pickerText('intelDetail', {
    index: Math.round(entry.index), count, total: benchmarks.length, scores: scoreLine(entry, benchmarks),
  });
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
  const [showIntel, setShowIntel] = useState(loadShowIntel);
  useEffect(() => {
    const sync = () => setShowIntel(loadShowIntel());
    window.addEventListener('storage', sync);
    window.addEventListener('claudeBotModelIntelChange', sync);
    return () => {
      window.removeEventListener('storage', sync);
      window.removeEventListener('claudeBotModelIntelChange', sync);
    };
  }, []);
  const intel = useBrainIntelligence(open && showIntel);
  const [detail, setDetail] = useState<Detail>(null);
  const detailTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(detailTimer.current), []);
  // Hover opens a card after a short rest and swaps quickly between rows; a
  // click on the dial pins it, so it survives the pointer leaving.
  // Hover opens the card only on the dial itself; rows stay inert, so a
  // phone scroll and a quick mouse pass never flash a card. The dial is
  // pointer-events-enabled while its row is keyboard-focused too.
  const hoverDetail = (id: string | null) => {
    window.clearTimeout(detailTimer.current);
    detailTimer.current = window.setTimeout(() => {
      setDetail(current => current?.pinned ? current : id ? { id, pinned: false } : null);
    }, id ? (detail ? 60 : 220) : 160);
  };
  const input = useRef<HTMLInputElement>(null);
  const search = useRef<HTMLDivElement>(null);
  const searchCloseTimer = useRef<number | undefined>(undefined);
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
    window.clearTimeout(searchCloseTimer.current);
    setOpen(next);
    window.clearTimeout(detailTimer.current);
    setDetail(null);
    if (next) {
      setQuery('');
      setSearchOpen(false);
      setActive(0);
    }
  };
  useEffect(() => () => window.clearTimeout(searchCloseTimer.current), []);
  const intelData = intel.data;
  const intelShown = Boolean(showIntel && intelData?.available);
  // Beside the row on the desk (over the thinking column); below it on a
  // phone, where the side has no room.
  const narrow = typeof window !== 'undefined' && window.matchMedia?.('(max-width: 759px)').matches;
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
                onPointerEnter={(event) => {
                  window.clearTimeout(searchCloseTimer.current);
                  if (event.pointerType === 'mouse') setSearchOpen(true);
                }}
                onPointerLeave={() => {
                  if (!query && !search.current?.contains(document.activeElement)) {
                    window.clearTimeout(searchCloseTimer.current);
                    searchCloseTimer.current = window.setTimeout(() => {
                      if (!query && !search.current?.contains(document.activeElement)) setSearchOpen(false);
                    }, 450);
                  }
                }}
                onBlur={(event) => { if (!query && !event.currentTarget.contains(event.relatedTarget)) setSearchOpen(false); }}
              >
                <button type="button" aria-label={t('models.openSearch')} aria-expanded={searchOpen} aria-controls={`${base}-search`}
                  className="model-picker-search-button" onFocus={() => { window.clearTimeout(searchCloseTimer.current); setSearchOpen(true); }}
                  onClick={() => { window.clearTimeout(searchCloseTimer.current); setSearchOpen(true); input.current?.focus(); }}>
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
                      const radio = (
                        <button key={`${group.key}-${model.id}`} id={`${base}-${index}`} type="button" role="radio"
                          data-model={model.id} data-index={index} aria-checked={current} aria-disabled={unavailable}
                          tabIndex={index === cursor ? 0 : -1} title={model.label}
                          onFocus={() => setActive(index)}
                          onPointerEnter={() => { if (document.activeElement === input.current) setActive(index); }}
                          onClick={() => { void pick(model); }}
                          className={cn('brain-picker-row model-picker-row', unavailable && 'is-disabled', index === cursor && query && 'is-search-active')}>
                          <BrandLogo model={model} />
                          <span className="min-w-0 flex-1">
                            <span className="brain-picker-name block">{model.label}</span>
                            {model.available === false ? <span className="brain-picker-feedback">{t('models.unavailable')}</span> : null}
                          </span>
                          {intelShown && !model.auto ? <span aria-hidden="true" className="model-intel-slot" /> : null}
                          {intelShown ? (
                            // A fixed slot: the check no longer pushes the dial out of its column.
                            <span className="brain-picker-check-slot">
                              {current ? <Check aria-hidden="true" className="brain-picker-check" strokeWidth={1.75} /> : null}
                            </span>
                          ) : current ? <Check aria-hidden="true" className="brain-picker-check" strokeWidth={1.75} /> : null}
                        </button>
                      );
                      if (!intelShown || model.auto || !intelData) return radio;
                      const entry = intelData.models[model.id];
                      return (
                        <IntelLine key={`${group.key}-${model.id}`} model={model} entry={entry} data={intelData}
                          open={detail?.id === model.id} pinned={Boolean(detail?.pinned)} narrow={narrow}
                          onHover={hoverDetail} onToggle={() => setDetail(shown =>
                            shown?.id === model.id && shown.pinned ? null : { id: model.id, pinned: true })}
                          onClose={() => setDetail(null)}>
                          {radio}
                        </IntelLine>
                      );
                    })}
                  </GroupBlock>
                ))}
                {!flat.length ? <p className="brain-picker-note">{brain.loading ? t('composer.loading')
                  : brain.models.length ? t('models.none', { query }) : null}</p> : null}
              </div>
            </div>
            {showIntel ? (
              <p className="model-intel-source" role="status">
                {intel.isError || (intelData && !intelData.available) ? pickerText('intelFailed')
                  : !intelData ? pickerText('intelLoading')
                    : <a href={intelData.source.url || undefined} target="_blank" rel="noopener noreferrer">
                      {pickerText('intelSource', { count: intelData.benchmarks.length, source: intelData.source.name, license: intelData.source.license })}
                    </a>}
              </p>
            ) : null}
          </section>
          <EffortOptions brain={brain} />
          {brain.saving ? <p className="sr-only" role="status">{pickerText('saving')}</p> : null}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

/**
 * A picker row with its index: the radio row plus, laid over its right end,
 * the dial as a separate button — a button inside the radio would be invalid
 * nesting, and on a phone the dial is the only way to open the card.
 */
function IntelLine({ model, entry, data, open, pinned, narrow, onHover, onToggle, onClose, children }: {
  model: BrainModel; entry?: IntelEntry; data: IntelligenceResponse; open: boolean; pinned: boolean; narrow: boolean;
  onHover: (id: string | null) => void; onToggle: () => void; onClose: () => void; children: React.ReactNode;
}) {
  if (!entry) {
    return (
      <div className="model-picker-line">
        {children}
        <span className="model-intel model-intel-missing" title={pickerText('intelMissing')}>—</span>
      </div>
    );
  }
  return (
    <Popover.Root open={open} onOpenChange={(next) => { if (!next) onClose(); }}>
      <Popover.Anchor asChild>
        <div className="model-picker-line" data-intel-line={model.id}>
          {children}
          <button type="button" className="model-intel model-intel-button"
            aria-label={intelSummary(entry, data.benchmarks)} aria-expanded={open}
            onPointerEnter={(event) => { if (event.pointerType === 'mouse') onHover(model.id); }}
            onPointerLeave={(event) => { if (event.pointerType === 'mouse') onHover(null); }}
            onFocus={() => onHover(model.id)} onBlur={() => onHover(null)}
            onClick={onToggle}>
            <IntelScore entry={entry} />
          </button>
        </div>
      </Popover.Anchor>
      <Popover.Portal>
        <Popover.Content side={narrow ? 'bottom' : 'right'} align={narrow ? 'end' : 'center'} sideOffset={8}
          collisionPadding={12} style={{ zIndex: 'var(--z-pop)' }} data-pinned={pinned ? '' : undefined}
          className="intel-card-pop popup-shell u-pop rounded-lg border border-line bg-surface shadow-pop"
          onOpenAutoFocus={(event) => event.preventDefault()} onCloseAutoFocus={(event) => event.preventDefault()}
          onInteractOutside={(event) => {
            // The dial's own click toggles the card; do not close it first.
            const line = (event.target as Element | null)?.closest?.('[data-intel-line]');
            if (line?.getAttribute('data-intel-line') === model.id) event.preventDefault();
          }}>
          <IntelCard label={model.label} entry={entry} benchmarks={data.benchmarks} source={data.source} />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}

function GroupBlock({ children }: { children: React.ReactNode }) { return <>{children}</>; }
