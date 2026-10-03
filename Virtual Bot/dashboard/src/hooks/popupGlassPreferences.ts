export type PopupGlassKind = 'models' | 'attachments' | 'context' | 'menus';
export const POPUP_GLASS_STORAGE_KEY = 'claudeBotPopupGlassTargets';
export const LEGACY_POPUP_STORAGE_KEY = 'claudeBotPopup';
export const POPUP_GLASS_KINDS: readonly PopupGlassKind[] = Object.freeze(['models', 'attachments', 'context', 'menus']);
export const DEFAULT_POPUP_GLASS_TARGETS: readonly PopupGlassKind[] = Object.freeze([]);
export const POPUP_SURFACE_SELECTOR = '.popup-shell, .prompt-bar__menu, [data-popup-root]';

/** Keep only known categories, in a stable order, without duplicates. */
export function normalizePopupGlassTargets(value: unknown): readonly PopupGlassKind[] {
  if (!Array.isArray(value)) return DEFAULT_POPUP_GLASS_TARGETS;
  return Object.freeze(POPUP_GLASS_KINDS.filter(kind => value.includes(kind)));
}

/** An explicit preference, even malformed, takes priority over the old all-or-none switch. */
export function readPopupGlassTargets(raw: string | null, legacy: string | null): readonly PopupGlassKind[] {
  if (raw === null) return legacy === 'glass' ? POPUP_GLASS_KINDS : DEFAULT_POPUP_GLASS_TARGETS;
  try { return normalizePopupGlassTargets(JSON.parse(raw)); }
  catch { return DEFAULT_POPUP_GLASS_TARGETS; }
}

export interface PopupGlassStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}
export type PopupGlassUpdate = readonly PopupGlassKind[] | ((previous: readonly PopupGlassKind[]) => readonly PopupGlassKind[]);

function equalTargets(a: readonly PopupGlassKind[], b: readonly PopupGlassKind[]): boolean {
  return a.length === b.length && a.every((kind, index) => kind === b[index]);
}

/** Persist first so denied storage never makes an unsaved choice appear saved. */
export function createPopupGlassStore(
  getStorage: () => PopupGlassStorage | null,
  listenStorage?: (refresh: () => void) => () => void,
) {
  let snapshot = DEFAULT_POPUP_GLASS_TARGETS;
  let initialized = false;
  let stopListening: (() => void) | undefined;
  const listeners = new Set<() => void>();
  const emit = () => { for (const listener of listeners) listener(); };
  const refresh = () => {
    initialized = true;
    let next: readonly PopupGlassKind[];
    try {
      const storage = getStorage();
      if (!storage) return;
      next = readPopupGlassTargets(storage.getItem(POPUP_GLASS_STORAGE_KEY), storage.getItem(LEGACY_POPUP_STORAGE_KEY));
    } catch { return; }
    if (!equalTargets(snapshot, next)) { snapshot = next; emit(); }
  };
  const getSnapshot = () => { if (!initialized) refresh(); return snapshot; };
  const setTargets = (update: PopupGlassUpdate): boolean => {
    const previous = getSnapshot();
    const next = normalizePopupGlassTargets(typeof update === 'function' ? update(previous) : update);
    if (equalTargets(previous, next)) return true;
    try {
      const storage = getStorage();
      if (!storage) return false;
      storage.setItem(POPUP_GLASS_STORAGE_KEY, JSON.stringify(next));
    } catch { return false; }
    snapshot = next;
    emit();
    return true;
  };
  const subscribe = (listener: () => void): (() => void) => {
    listeners.add(listener);
    if (listeners.size === 1) stopListening = listenStorage?.(refresh);
    // Another tab can change storage between a React render and its subscription.
    refresh();
    return () => {
      listeners.delete(listener);
      if (!listeners.size) { stopListening?.(); stopListening = undefined; }
    };
  };
  return { getSnapshot, setTargets, subscribe, refresh };
}

interface PopupSurface {
  matches(selector: string): boolean;
  closest(selector: string): { getAttribute(name: string): string | null } | null;
}

/** Markers classify a surface; a marker on an arbitrary wrapper is never a surface itself. */
export function popupGlassKind(surface: PopupSurface): PopupGlassKind | null {
  if (!surface.matches(POPUP_SURFACE_SELECTOR)) return null;
  const marker = surface.closest('[data-popup-kind]');
  if (marker) {
    const kind = marker.getAttribute('data-popup-kind');
    return POPUP_GLASS_KINDS.includes(kind as PopupGlassKind) ? kind as PopupGlassKind : null;
  }
  if (surface.matches('.model-menu, .model-effort-menu, .effort-menu, .prompt-bar__menu[data-kind="model"], .prompt-bar__menu[data-kind="effort"]')) return 'models';
  if (surface.matches('.context-popover')) return 'context';
  if (surface.matches('.prompt-bar__menu')) return 'attachments';
  return surface.matches('.popup-shell') ? 'menus' : null;
}

/** Retain the existing CSS gate while individual surfaces choose whether to carry a lens. */
export function applyPopupGlassTargets(root: { dataset: DOMStringMap }, targets: readonly PopupGlassKind[]): void {
  if (targets.length) root.dataset.popup = 'glass';
  else delete root.dataset.popup;
  root.dataset.popupGlassTargets = targets.join(' ');
}
