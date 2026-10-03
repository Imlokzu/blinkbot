import { useState } from 'react';
import { siteIconUrl } from './siteIcons';

/** A website mark with a fixed-size, offline-safe local initial underneath. */
export function SiteIcon({ url, host, className = '' }: {
  url: string;
  host: string;
  className?: string;
}) {
  const iconUrl = siteIconUrl(url, host);
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const loaded = iconUrl !== null && loadedUrl === iconUrl && failedUrl !== iconUrl;
  const fetchable = iconUrl !== null && failedUrl !== iconUrl;

  return (
    <span
      aria-hidden="true"
      data-site-icon={host}
      data-site-url={url}
      data-icon-state={loaded ? 'ready' : fetchable ? 'loading' : 'fallback'}
      className={`chat-site-icon relative grid size-[18px] shrink-0 place-items-center overflow-hidden rounded-xs border border-line bg-surface-2 font-mono text-[10px] uppercase text-ink-2 ${className}`}
    >
      <span className={loaded ? 'invisible' : undefined}>{host.slice(0, 1)}</span>
      {fetchable ? (
        <img
          key={iconUrl}
          src={iconUrl}
          alt=""
          width={16}
          height={16}
          loading="lazy"
          decoding="async"
          referrerPolicy="no-referrer"
          draggable={false}
          className={`absolute inset-0 m-auto size-4 object-contain ${loaded ? '' : 'opacity-0'}`}
          onLoad={(event) => {
            if (event.currentTarget.naturalWidth > 0) setLoadedUrl(iconUrl);
            else setFailedUrl(iconUrl);
          }}
          onError={() => setFailedUrl(iconUrl)}
        />
      ) : null}
    </span>
  );
}
