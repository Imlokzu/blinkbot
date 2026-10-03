import { useId, useMemo, useRef } from 'react';
import { ChevronRight, ExternalLink } from 'lucide-react';
import { collectSources } from './sources';
import { SiteIcon } from './SiteIcon';
import { useIsPhone } from '@/hooks/useMediaQuery';
import { useDisclosureMotion } from '@/hooks/useDisclosureMotion';
import { t } from '@/locales/chat';
import type { ToolStep } from './types';
import './source-strip.css';

/*
 * The strip of pages a reply was built from.
 *
 * Collapsed it is one line — a count and the hostnames — because most of the
 * time the question is only "did it actually look anything up, and where".
 * Opening it gives the titles and the links.
 *
 * SiteIcon tries the public origin, then host-only icon caches for blocked
 * or missing marks. A fixed-size initial remains while loading or offline.
 * Article paths and referrers never enter the icon lookup.
 */

export function SourceStrip({ steps }: { steps: ToolStep[] }) {
  const sources = useMemo(() => collectSources(steps), [steps]);
  const isPhone = useIsPhone();
  const { ref, open, phase, toggle } = useDisclosureMotion();
  const sourcesId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  if (!sources.length) return null;

  // Hosts, not links: the same site found five times is still one name here.
  const sites = sources.filter((source, index) =>
    sources.findIndex((candidate) => candidate.host === source.host) === index);
  // Four names fit a desktop column; on a phone they shrink to "e…", which
  // names nothing. Two readable names beat four unreadable ones.
  const shown = isPhone ? 2 : 4;
  const toggleSources = () => {
    if (open && ref.current?.contains(document.activeElement)) {
      trigger.current?.focus({ preventScroll: true });
    }
    toggle();
  };

  return (
    <div className="chat-source-strip mt-3 min-w-0 max-w-full" data-state={phase}>
      <button
        ref={trigger}
        type="button"
        data-sources-toggle
        aria-expanded={open}
        aria-controls={sourcesId}
        onClick={toggleSources}
        className="chat-source-toggle flex w-full cursor-pointer items-center gap-2 rounded-sm py-1 text-left outline-none transition-colors hover:text-ink focus-visible:ring-2 focus-visible:ring-accent"
      >
        <span className="u-label shrink-0 text-ink-3">
          {t('sources.label', { count: sources.length })}
        </span>
        <span className="flex min-w-0 flex-1 items-center gap-1.5 overflow-hidden">
          {sites.slice(0, shown).map((source) => (
            <span key={source.host} className="flex items-center gap-1 truncate font-mono text-[11px] text-ink-3">
              <SiteIcon url={source.url} host={source.host} />
              <span className="truncate">{source.host}</span>
            </span>
          ))}
          {sites.length > shown ? (
            <span className="shrink-0 font-mono text-[11px] text-ink-3">+{sites.length - shown}</span>
          ) : null}
        </span>
        <ChevronRight aria-hidden="true" className="chat-source-chevron size-3.5 shrink-0 text-ink-3" />
      </button>

      <div
        ref={ref}
        id={sourcesId}
        data-sources-fold
        data-phase={phase}
        data-open={open ? '' : undefined}
        inert={!open}
        aria-hidden={!open}
        className="chat-source-fold"
      >
        <ol className="chat-source-list mt-1.5 space-y-0.5 border-l border-line pl-3">
          {sources.map((source, index) => (
            <li key={source.url}>
              <a
                href={source.url}
                target="_blank"
                rel="noreferrer noopener"
                className="group/src flex items-start gap-2 rounded-sm px-1.5 py-1.5 transition-colors hover:bg-surface"
              >
                <span className="mt-px shrink-0 font-mono text-[10px] text-ink-3">{index + 1}</span>
                <SiteIcon url={source.url} host={source.host} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] text-ink">{source.title || source.host}</span>
                  <span className="mt-0.5 block truncate font-mono text-[10.5px] text-ink-3">
                    {source.host} · {source.tool}
                  </span>
                </span>
                <ExternalLink className="mt-0.5 size-3 shrink-0 text-ink-3 opacity-0 transition-opacity group-hover/src:opacity-100 motion-reduce:transition-none" />
              </a>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}
