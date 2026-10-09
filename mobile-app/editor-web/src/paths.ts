/** Only workspace-relative resources reach the local host loader. */
export function workspacePath(source: string, documentPath: string): string | null {
  let path = source.trim();
  if (!path || path.startsWith('#') || path.startsWith('//') || /^[a-z][a-z\d+.-]*:/i.test(path)) return null;
  path = path.split(/[?#]/, 1)[0];
  try { path = decodeURIComponent(path); } catch { return null; }
  const rooted = /^\/(workspace|file|preview)\//.test(path);
  if (rooted) path = path.replace(/^\/(workspace|file|preview)\//, '');
  else if (path.startsWith('/')) return null;
  if (!path || path.includes('\\') || /[\u0000-\u001f]/.test(path) || path.split('/').includes('..')) return null;
  const relative = !rooted && (path.startsWith('./') || !path.includes('/'));
  path = path.replace(/^\.\//, '');
  if (relative && documentPath.includes('/')) path = `${documentPath.slice(0, documentPath.lastIndexOf('/'))}/${path}`;
  return path.split('/').some((part) => !part || part === '.') ? null : path;
}
export const resourceUrl = (path: string) => `/workspace/${path.split('/').map(encodeURIComponent).join('/')}`;
export const isDrawing = (path: string) => /\.excalidraw(?:\.json)?$/i.test(path);
export const isExternal = (source: string) => /^https:\/\//i.test(source);

/** Same-folder creations use portable Markdown, others use an explicit owned root. */
export function documentLink(path: string, documentPath: string): string {
  const parent = documentPath.includes('/') ? documentPath.slice(0, documentPath.lastIndexOf('/') + 1) : '';
  const relative = !parent || path.startsWith(parent);
  const target = relative ? path.slice(parent.length) : path;
  const encoded = target.split('/').map(encodeURIComponent).join('/');
  return relative ? (encoded.includes('/') ? `./${encoded}` : encoded) : `/workspace/${encoded}`;
}
