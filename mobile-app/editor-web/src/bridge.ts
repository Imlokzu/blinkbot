export type EditorKind = 'markdown' | 'code' | 'text' | 'html' | 'drawing' | 'mermaid';
export type EditorAction = 'openWorkspace' | 'openExternal' | 'convertMermaid' | 'createDrawing' | 'exportDrawing' | 'retry';
export interface EditorDocument {
  id: string;
  path: string;
  kind: EditorKind;
  content: string;
  readOnly: boolean;
  theme: 'light' | 'dark';
  language: 'en' | 'uk';
  saveState: string;
}
export interface EditorSession { id: string; generation: number }
export interface EditorSnapshot { document: EditorDocument; session: EditorSession }
type BridgeMessage = Record<string, unknown> & { type: string };
declare global {
  interface Window {
    BlinkWorkspace: {
      openDocument(document: unknown): void;
      updateHost(document: Partial<EditorDocument> & { id: string }): void;
      flush(token: string): void;
      resolveAction(result: { id: string; sequence: number; path?: string; error?: string }): void;
    };
    BlinkNative?: { postMessage(message: string): void };
    webkit?: { messageHandlers?: { BlinkNative?: { postMessage(message: string): void } } };
    EXCALIDRAW_ASSET_PATH?: string | string[];
  }
}

const MAX_CONTENT_BYTES = 2_000_000;
const kinds = new Set<EditorKind>(['markdown', 'code', 'text', 'html', 'drawing', 'mermaid']);
let snapshot: EditorSnapshot | null = null;
let generation = 0;
let sequence = 0;
let flushEditor: (() => void) | null = null;
let invalidContent = false;
const subscribers = new Set<() => void>();
const pendingActions = new Map<number, { session: EditorSession; resolve: (path?: string) => void }>();
const bytes = (value: string) => new TextEncoder().encode(value).length;
const notify = () => subscribers.forEach((listener) => listener());

function post(message: BridgeMessage) {
  const data = JSON.stringify(message);
  if (window.BlinkNative) window.BlinkNative.postMessage(data);
  else window.webkit?.messageHandlers?.BlinkNative?.postMessage(data);
}

function validDocument(value: unknown): value is EditorDocument {
  if (!value || typeof value !== 'object') return false;
  const doc = value as EditorDocument;
  return typeof doc.id === 'string' && doc.id.length > 0 && doc.id.length <= 256
    && typeof doc.path === 'string' && doc.path.length > 0 && doc.path.length <= 4096
    && kinds.has(doc.kind) && typeof doc.content === 'string'
    && typeof doc.readOnly === 'boolean' && ['light', 'dark'].includes(doc.theme)
    && ['en', 'uk'].includes(doc.language) && typeof doc.saveState === 'string';
}

export function currentSession(session: EditorSession): boolean {
  return snapshot?.session.id === session.id && snapshot.session.generation === session.generation;
}
export const subscribe = (listener: () => void) => { subscribers.add(listener); return () => { subscribers.delete(listener); }; };
export const getSnapshot = () => snapshot;

export function editorError(session: EditorSession, code: string) {
  if (currentSession(session)) post({ type: 'error', id: session.id, code });
}

export function changeDocument(session: EditorSession, content: string) {
  if (!currentSession(session) || !snapshot || snapshot.document.readOnly) return;
  if (snapshot.document.content === content) { invalidContent = false; return; }
  if (bytes(content) > MAX_CONTENT_BYTES) { invalidContent = true; editorError(session, 'document_too_large'); return; }
  invalidContent = false;
  snapshot = { ...snapshot, document: { ...snapshot.document, content } };
  post({ type: 'change', id: session.id, sequence: ++sequence, content });
  notify();
}

export function action(session: EditorSession, name: EditorAction, extra: { path?: string; content?: string } = {}, resolve?: (path?: string) => void) {
  if (!currentSession(session) || !snapshot) return;
  if (snapshot.document.saveState === 'writing' && ['createDrawing', 'convertMermaid', 'exportDrawing'].includes(name)) return;
  if (name === 'exportDrawing') {
    if (!['drawing', 'mermaid'].includes(snapshot.document.kind) || extra.path !== undefined ||
      typeof extra.content !== 'string' || !extra.content.startsWith('data:image/png;base64,')) return;
    const encoded = extra.content.slice('data:image/png;base64,'.length);
    if (!encoded || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return;
  }
  if (snapshot.document.readOnly && !['openWorkspace', 'openExternal', 'retry', 'exportDrawing'].includes(name)
    && !(name === 'convertMermaid' && snapshot.document.kind === 'mermaid')) return;
  if (extra.content !== undefined && bytes(extra.content) > MAX_CONTENT_BYTES) {
    if (name !== 'exportDrawing') editorError(session, 'document_too_large');
    return;
  }
  if (name === 'openExternal' && !/^https:\/\//i.test(extra.path ?? '')) return;
  const next = ++sequence;
  if (resolve) pendingActions.set(next, { session, resolve });
  post({ type: 'action', id: session.id, sequence: next, action: name, ...extra });
}

/** A detached editor cannot flush into the next document, even before React cleans it up. */
export function registerFlush(session: EditorSession, flush: () => void) {
  const guarded = () => { if (currentSession(session)) flush(); };
  if (currentSession(session)) flushEditor = guarded;
  return () => { if (flushEditor === guarded) flushEditor = null; };
}

/** View changes must not discard content that native could not accept. */
export function flushCurrent(session: EditorSession): boolean {
  if (!currentSession(session)) return false;
  try { flushEditor?.(); return currentSession(session) && !invalidContent; }
  catch { editorError(session, 'editor_failed'); return false; }
}

export function installBridge() {
  window.BlinkWorkspace = {
    openDocument(value) {
      if (!validDocument(value)) {
        const id = value && typeof value === 'object' && 'id' in value && typeof value.id === 'string' ? value.id : '';
        post({ type: 'error', id, code: 'invalid_document' }); return;
      }
      if (bytes(value.content) > MAX_CONTENT_BYTES) { post({ type: 'error', id: value.id, code: 'document_too_large' }); return; }
      if (snapshot?.document.id === value.id) { this.updateHost(value); return; }
      flushEditor = null;
      invalidContent = false;
      pendingActions.clear();
      sequence = 0;
      snapshot = { document: { ...value }, session: { id: value.id, generation: ++generation } };
      notify();
    },
    updateHost(value) {
      if (!value || !snapshot || snapshot.document.id !== value.id) return;
      // Native owns save/status metadata. Its acknowledged text may lag typing;
      // treating that echo as new editor content would erase edits and selection.
      snapshot = { ...snapshot, document: { ...snapshot.document,
        readOnly: typeof value.readOnly === 'boolean' ? value.readOnly : snapshot.document.readOnly,
        theme: value.theme === 'light' || value.theme === 'dark' ? value.theme : snapshot.document.theme,
        language: value.language === 'en' || value.language === 'uk' ? value.language : snapshot.document.language,
        saveState: typeof value.saveState === 'string' ? value.saveState : snapshot.document.saveState } };
      notify();
    },
    flush(token) {
      if (!snapshot || typeof token !== 'string' || token.length > 256) return;
      const session = snapshot.session;
      if (flushCurrent(session)) post({ type: 'flushed', id: session.id, token });
    },
    resolveAction(result) {
      if (!result || typeof result.id !== 'string' || !Number.isSafeInteger(result.sequence)) return;
      const pending = pendingActions.get(result.sequence);
      if (!pending || pending.session.id !== result.id || !currentSession(pending.session)) return;
      pendingActions.delete(result.sequence);
      if (snapshot?.document.readOnly) return;
      const path = typeof result.path === 'string' && result.path.length <= 4096 ? result.path : undefined;
      pending.resolve(result.error ? undefined : path);
    },
  };
  post({ type: 'ready' });
}
