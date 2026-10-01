export interface WorkspaceLocation {
  root?: string;
  session_path?: string;
}

/** Convert a workspace link into an API path without trusting arbitrary disk paths. */
export function workspaceLinkPath(href: string, location: WorkspaceLocation, documentPath?: string): string | null {
  let path = href.trim();
  let rooted = false;
  if (!path || path.startsWith('#') || path.startsWith('//')) return null;
  if (/^file:/i.test(path)) {
    try {
      const url = new URL(path);
      if (url.hostname && url.hostname !== 'localhost') return null;
      path = url.pathname;
    } catch { return null; }
  } else if (/^[a-z][a-z\d+.-]*:/i.test(path)) {
    return null;
  }
  path = path.split(/[?#]/, 1)[0];
  try { path = decodeURIComponent(path); } catch { return null; }
  if (path.includes('\\') || path.includes('\0')) return null;
  const root = location.root?.replace(/\/+$/, '');
  if (root && path.startsWith(`${root}/`)) { path = path.slice(root.length + 1); rooted = true; }
  else if (/^\/(preview|file)\//.test(path)) { path = path.replace(/^\/(preview|file)\//, ''); rooted = true; }
  else if (path.startsWith('/')) return null;
  if (!path || path.split('/').includes('..')) return null;
  path = path.replace(/^\.\//, '');
  // Bare Markdown links are relative to the current document or chat folder.
  if (!rooted && (!path.includes('/') || href.startsWith('./'))) {
    const parent = documentPath?.includes('/') ? documentPath.slice(0, documentPath.lastIndexOf('/')) : 'session';
    path = `${parent}/${path}`;
  }
  const prefix = location.session_path ? `${location.session_path}/` : '';
  return prefix && path.startsWith(prefix) ? `session/${path.slice(prefix.length)}` : path;
}

/** Keep file URLs for our link component, while rejecting executable protocols. */
export function safeMarkdownUrl(url: string): string {
  const colon = url.indexOf(':');
  const end = url.search(/[/?#]/);
  return colon < 0 || (end >= 0 && end < colon) || /^(https?|mailto|tel|file):/i.test(url) ? url : '';
}
