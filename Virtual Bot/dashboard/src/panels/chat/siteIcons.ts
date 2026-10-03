const LOCAL_SUFFIXES = new Set([
  'localhost', 'local', 'localdomain', 'lan', 'home', 'internal', 'intranet',
  'test', 'invalid', 'onion',
]);

function displayHost(host: string): string {
  return host.toLowerCase().replace(/\.$/, '').replace(/^www\./, '');
}

/** Use the source's public origin, never its path or an external icon service. */
export function siteIconUrl(raw: string, host?: string): string | null {
  if (!/^https:\/\//i.test(raw)) return null;
  try {
    const url = new URL(raw);
    if (url.protocol !== 'https:' || url.username || url.password) return null;

    const hostname = url.hostname.toLowerCase().replace(/\.$/, '');
    // Home-network names are locally scoped and must not reach public icon caches.
    if (hostname === 'home.arpa' || hostname.endsWith('.home.arpa')) return null;
    const labels = hostname.split('.');
    if (labels.length < 2 || hostname.length > 253) return null;
    if (labels.some((label) => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) return null;
    const suffix = labels.at(-1)!;
    // A numeric suffix includes browser-normalized IPv4 literals. IPv6 cannot
    // pass the hostname-label check. Local/reserved names have no public icon.
    if (/^\d+$/.test(suffix) || LOCAL_SUFFIXES.has(suffix)) return null;
    if (host !== undefined && displayHost(host) !== displayHost(hostname)) return null;

    return `${url.origin}/favicon.ico`;
  } catch {
    return null;
  }
}

/** Try the real origin first; public host-only caches cover missing/blocked icons. */
export function siteIconCandidates(raw: string, host?: string): string[] {
  if (!/^https?:\/\//i.test(raw)) return [];
  let normalized: string;
  try {
    const source = new URL(raw);
    // Public HTTP citations still get secure image requests on HTTPS dashboards.
    if (source.protocol === 'http:') source.protocol = 'https:';
    normalized = source.toString();
  } catch { return []; }
  const direct = siteIconUrl(normalized, host);
  if (!direct) return [];
  const origin = new URL(direct);
  // A custom-port application can have a different mark from its domain's site.
  if (origin.port) return [direct];
  const domain = encodeURIComponent(origin.hostname.replace(/\.$/, ''));
  return [
    direct,
    `https://www.google.com/s2/favicons?domain=${domain}&sz=32`,
    `https://icons.duckduckgo.com/ip3/${domain}.ico`,
  ];
}
