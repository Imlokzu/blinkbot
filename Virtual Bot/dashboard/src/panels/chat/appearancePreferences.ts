export type ChatBackground = 'none' | 'sky' | 'dusk' | 'forest' | 'custom' | 'video';
export type ChatColor = 'theme' | 'rose' | 'sage' | 'ocean' | 'lavender';
export type BackgroundTarget = 'chat' | 'navigation' | 'sessions' | 'panels' | 'pages';
export type ChatMaterial = 'glass' | 'solid';
export interface ChatAppearance {
  background: ChatBackground;
  image: string | null;
  videoId: string | null;
  targets: readonly BackgroundTarget[];
  sidebarVisible: boolean;
  material: ChatMaterial;
  glassRecipe: 2;
  opacity: number;
  blur: number;
  color: ChatColor;
}

export const APPEARANCE_STORAGE_KEY = 'claudeBotChatAppearance';
export const MAX_BACKGROUND_FILE_BYTES = 8 * 1024 * 1024;
export const MAX_BACKGROUND_IMAGE_LENGTH = 1024 * 1024;
export const BACKGROUND_TARGETS: readonly BackgroundTarget[] = Object.freeze(['chat', 'navigation', 'sessions', 'panels', 'pages']);
export const DEFAULT_CHAT_APPEARANCE: Readonly<ChatAppearance> = Object.freeze({
  background: 'sky', image: null, videoId: null, targets: Object.freeze(['chat'] as BackgroundTarget[]),
  sidebarVisible: true, material: 'glass', glassRecipe: 2, opacity: 0, blur: 0, color: 'theme',
});
const BACKGROUNDS = new Set<ChatBackground>(['none', 'sky', 'dusk', 'forest', 'custom', 'video']);
const COLORS = new Set<ChatColor>(['theme', 'rose', 'sage', 'ocean', 'lavender']);
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp']);

function hasRasterSignature(type: string, bytes: string): boolean {
  if (type === 'image/png') return bytes.startsWith('\x89PNG\r\n\x1a\n');
  if (type === 'image/jpeg') return bytes.startsWith('\xff\xd8\xff');
  return type === 'image/webp' && bytes.startsWith('RIFF') && bytes.slice(8, 12) === 'WEBP';
}

/** Only a local raster image can be reused as a background from storage. */
export function isBackgroundImage(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > MAX_BACKGROUND_IMAGE_LENGTH) return false;
  const header = /^data:image\/(png|jpeg|webp);base64,/.exec(value);
  if (!header) return false;
  const payload = value.slice(header[0].length);
  if (!payload || payload.length % 4 || /[^A-Za-z0-9+/=]/.test(payload)) return false;
  const padding = payload.indexOf('=');
  if (padding >= 0 && !/^={1,2}$/.test(payload.slice(padding))) return false;
  try {
    const bytes = atob(payload.slice(0, 24));
    return hasRasterSignature(`image/${header[1]}`, bytes);
  } catch { return false; }
}

function boundedNumber(value: unknown, fallback: number, min: number, max: number): number {
  return typeof value === 'number' && Number.isFinite(value)
    ? Math.round(Math.max(min, Math.min(max, value))) : fallback;
}

/** A saved video reference is an opaque local key, never a URL or a path. */
export function isWallpaperVideoId(value: unknown): value is string {
  return typeof value === 'string' && /^wallpaper-[A-Za-z0-9-]{8,80}$/.test(value);
}

export function hasFullAppBackground(appearance: Pick<ChatAppearance, 'targets'>): boolean {
  return BACKGROUND_TARGETS.every(target => appearance.targets.includes(target));
}

export function normalizeChatAppearance(value: unknown): ChatAppearance {
  const input = value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {};
  const image = isBackgroundImage(input.image) ? input.image : null;
  const videoId = isWallpaperVideoId(input.videoId) ? input.videoId : null;
  let background = BACKGROUNDS.has(input.background as ChatBackground)
    ? input.background as ChatBackground : DEFAULT_CHAT_APPEARANCE.background;
  if (background === 'custom' && !image) background = DEFAULT_CHAT_APPEARANCE.background;
  if (background === 'video' && !videoId) background = DEFAULT_CHAT_APPEARANCE.background;
  const requestedTargets = input.targets;
  const targets = Array.isArray(requestedTargets)
    ? BACKGROUND_TARGETS.filter(target => requestedTargets.includes(target))
    : [...DEFAULT_CHAT_APPEARANCE.targets];
  // Migrate former stock frosting once; later edits retain their exact chosen values.
  const legacyStockGlass = input.glassRecipe !== 2 && input.material !== 'solid'
    && ((input.opacity === 88 && input.blur === 8) || (input.opacity === 35 && input.blur === 12));
  return {
    background,
    image,
    videoId,
    targets: Object.freeze(targets),
    sidebarVisible: typeof input.sidebarVisible === 'boolean' ? input.sidebarVisible : DEFAULT_CHAT_APPEARANCE.sidebarVisible,
    material: input.material === 'solid' ? 'solid' : 'glass',
    glassRecipe: 2,
    opacity: legacyStockGlass ? DEFAULT_CHAT_APPEARANCE.opacity : boundedNumber(input.opacity, DEFAULT_CHAT_APPEARANCE.opacity, 0, 100),
    blur: legacyStockGlass ? DEFAULT_CHAT_APPEARANCE.blur : boundedNumber(input.blur, DEFAULT_CHAT_APPEARANCE.blur, 0, 16),
    color: COLORS.has(input.color as ChatColor) ? input.color as ChatColor : DEFAULT_CHAT_APPEARANCE.color,
  };
}

export interface AppearanceStorage {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
}
export type AppearanceUpdate = Partial<ChatAppearance> | ((previous: ChatAppearance) => Partial<ChatAppearance>);

function equalAppearance(a: ChatAppearance, b: ChatAppearance): boolean {
  return a.background === b.background && a.image === b.image && a.opacity === b.opacity
    && a.blur === b.blur && a.color === b.color && a.videoId === b.videoId
    && a.material === b.material && a.sidebarVisible === b.sidebarVisible
    && a.targets.length === b.targets.length && a.targets.every((target, index) => target === b.targets[index]);
}

/** Persist first: quota or privacy restrictions must not create a false saved state. */
export function createChatAppearanceStore(
  getStorage: () => AppearanceStorage | null,
  listenStorage?: (callback: () => void) => () => void,
) {
  let snapshot: ChatAppearance = DEFAULT_CHAT_APPEARANCE;
  let initialized = false;
  let stopListening: (() => void) | undefined;
  const listeners = new Set<() => void>();
  const emit = () => { for (const listener of listeners) listener(); };
  const refresh = () => {
    initialized = true;
    let next: ChatAppearance;
    try {
      const storage = getStorage();
      if (!storage) return;
      const raw = storage.getItem(APPEARANCE_STORAGE_KEY);
      try { next = normalizeChatAppearance(raw ? JSON.parse(raw) : null); }
      catch { next = normalizeChatAppearance(null); }
    } catch { return; }
    if (!equalAppearance(next, snapshot)) { snapshot = Object.freeze(next); emit(); }
  };
  const getSnapshot = (): ChatAppearance => { if (!initialized) refresh(); return snapshot; };
  const setAppearance = (update: AppearanceUpdate): boolean => {
    const previous = getSnapshot();
    const patch = typeof update === 'function' ? update(previous) : update;
    const next = normalizeChatAppearance({ ...previous, ...patch });
    if (equalAppearance(previous, next)) return true;
    try {
      const storage = getStorage();
      if (!storage) return false;
      storage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify(next));
    } catch { return false; }
    snapshot = Object.freeze(next);
    emit();
    return true;
  };
  const subscribe = (listener: () => void): (() => void) => {
    listeners.add(listener);
    if (listeners.size === 1) stopListening = listenStorage?.(refresh);
    // An external tab may have changed storage between React's read and subscription.
    refresh();
    return () => {
      listeners.delete(listener);
      if (!listeners.size) { stopListening?.(); stopListening = undefined; }
    };
  };
  return { getSnapshot, subscribe, setAppearance, resetAppearance: () => setAppearance(DEFAULT_CHAT_APPEARANCE), refresh };
}

export type BackgroundImageErrorCode = 'fileType' | 'fileSize' | 'imageFailed';
export function validateBackgroundFile(file: { size: number; type: string }): BackgroundImageErrorCode | null {
  if (!IMAGE_TYPES.has(file.type)) return 'fileType';
  return Number.isFinite(file.size) && file.size > 0 && file.size <= MAX_BACKGROUND_FILE_BYTES ? null : 'fileSize';
}
export class BackgroundImageError extends Error {
  code: BackgroundImageErrorCode;
  constructor(code: BackgroundImageErrorCode) { super(code); this.code = code; }
}

/** Resize and re-encode locally; source filenames and original metadata are never saved. */
export async function prepareBackgroundImage(file: File): Promise<string> {
  const invalid = validateBackgroundFile(file);
  if (invalid) throw new BackgroundImageError(invalid);
  // MIME alone can be spoofed; refuse SVG and other formats before a decoder sees them.
  let header: Uint8Array;
  try { header = new Uint8Array(await file.slice(0, 16).arrayBuffer()); }
  catch { throw new BackgroundImageError('imageFailed'); }
  if (!hasRasterSignature(file.type, String.fromCharCode(...header))) throw new BackgroundImageError('fileType');
  const url = URL.createObjectURL(file);
  try {
    const image = await new Promise<HTMLImageElement>((resolve, reject) => {
      const candidate = new Image();
      candidate.onload = () => resolve(candidate);
      candidate.onerror = () => reject(new BackgroundImageError('imageFailed'));
      candidate.src = url;
    });
    if (!image.naturalWidth || !image.naturalHeight) throw new BackgroundImageError('imageFailed');
    const scale = Math.min(1, 1600 / Math.max(image.naturalWidth, image.naturalHeight));
    const canvas = document.createElement('canvas');
    let width = Math.max(1, Math.round(image.naturalWidth * scale));
    let height = Math.max(1, Math.round(image.naturalHeight * scale));
    for (let attempt = 0; attempt < 8; attempt++) {
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) throw new BackgroundImageError('imageFailed');
      context.fillStyle = '#fff';
      context.fillRect(0, 0, width, height);
      context.drawImage(image, 0, 0, width, height);
      const encoded = canvas.toDataURL('image/jpeg', Math.max(0.45, 0.86 - attempt * 0.08));
      if (isBackgroundImage(encoded)) return encoded;
      width = Math.max(1, Math.round(width * 0.8));
      height = Math.max(1, Math.round(height * 0.8));
    }
    throw new BackgroundImageError('imageFailed');
  } catch (error) {
    throw error instanceof BackgroundImageError ? error : new BackgroundImageError('imageFailed');
  } finally { URL.revokeObjectURL(url); }
}
