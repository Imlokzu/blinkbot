import { useEffect, useRef, useState } from 'react';
import { siteIconCandidates } from './siteIcons';

export const SITE_ICON_TIMEOUT_MS = 6000;

interface SiteIconProps {
  url: string;
  host: string;
  className?: string;
}

/** A website mark with a fixed-size, offline-safe local initial underneath. */
export function SiteIcon(props: SiteIconProps) {
  const candidates = siteIconCandidates(props.url, props.host);
  // Article/query changes keep a loaded mark. A different origin gets fresh state.
  return <SiteIconMark key={candidates.join('|') || `local:${props.host}`} {...props} candidates={candidates} />;
}

function SiteIconMark({ url, host, className = '', candidates }: SiteIconProps & { candidates: string[] }) {
  const mark = useRef<HTMLSpanElement>(null);
  const [visible, setVisible] = useState(false);
  const [state, setState] = useState({ attempt: 0, loaded: false });
  const iconUrl = candidates[state.attempt];
  const loaded = Boolean(iconUrl && state.loaded);
  const canObserve = typeof IntersectionObserver !== 'undefined';
  const attempt = state.attempt;
  const advance = () => setState(previous => previous.attempt === attempt
    ? { attempt: attempt + 1, loaded: false } : previous);
  useEffect(() => {
    const node = mark.current;
    if (!iconUrl || loaded || !node) return;
    if (!canObserve) {
      // Without visibility observation, eager loading gives the deadline a request to bound.
      setVisible(true);
      return;
    }
    // Native lazy images may not request anything in old or closed source rows.
    // Their network deadline starts only when the mark has a visible area.
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting
      && entry.intersectionRect.width > 0 && entry.intersectionRect.height > 0));
    observer.observe(node);
    return () => observer.disconnect();
  }, [iconUrl, loaded, canObserve]);
  useEffect(() => {
    if (!iconUrl || loaded || !visible) return;
    const timer = window.setTimeout(() => setState(previous =>
      previous.attempt === attempt && !previous.loaded
        ? { attempt: attempt + 1, loaded: false } : previous), SITE_ICON_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [iconUrl, loaded, attempt, visible]);

  return (
    <span
      ref={mark}
      aria-hidden="true"
      data-site-icon={host}
      data-site-url={url}
      data-icon-state={loaded ? 'ready' : iconUrl ? 'loading' : 'fallback'}
      className={`chat-site-icon relative grid size-[18px] shrink-0 place-items-center overflow-hidden rounded-xs border border-line bg-surface-2 font-mono text-[10px] uppercase text-ink-2 ${className}`}
    >
      <span className={loaded ? 'invisible' : undefined}>{host.slice(0, 1)}</span>
      {iconUrl ? (
        <img
          key={iconUrl}
          src={iconUrl}
          alt=""
          width={16}
          height={16}
          loading={canObserve ? 'lazy' : 'eager'}
          decoding="async"
          referrerPolicy="no-referrer"
          draggable={false}
          className={`absolute inset-0 m-auto size-4 object-contain ${loaded ? '' : 'opacity-0'}`}
          onLoad={(event) => {
            if (event.currentTarget.naturalWidth > 0) setState(previous => previous.attempt === attempt
              ? { ...previous, loaded: true } : previous);
            else advance();
          }}
          onError={advance}
        />
      ) : null}
    </span>
  );
}
