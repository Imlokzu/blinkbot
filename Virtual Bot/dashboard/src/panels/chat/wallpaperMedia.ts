import { useEffect, useState } from 'react';
import { isWallpaperVideoId } from './appearancePreferences.ts';

export const MAX_BACKGROUND_VIDEO_BYTES = 40 * 1024 * 1024;
export const MAX_BACKGROUND_POSTER_BYTES = 1024 * 1024;
const DATABASE_NAME = 'claudeBotWallpaperMedia';
const STORE_NAME = 'videos';
const VIDEO_TYPES = new Set(['video/mp4', 'video/webm']);
export type WallpaperMediaErrorCode = 'videoFileType' | 'videoFileSize' | 'videoFailed' | 'videoStorage' | 'videoMissing';
export class WallpaperMediaError extends Error {
  code: WallpaperMediaErrorCode;
  constructor(code: WallpaperMediaErrorCode) { super(code); this.code = code; }
}

export interface WallpaperVideoRecord {
  id: string;
  blob: Blob;
  poster: Blob | null;
  width: number;
  height: number;
}

export function validateBackgroundVideoFile(file: { type: string; size: number }): WallpaperMediaErrorCode | null {
  if (!VIDEO_TYPES.has(file.type)) return 'videoFileType';
  return Number.isFinite(file.size) && file.size > 0 && file.size <= MAX_BACKGROUND_VIDEO_BYTES ? null : 'videoFileSize';
}

/** Reject renamed HTML/SVG files before handing their bytes to a media decoder. */
export function matchesBackgroundVideoSignature(type: string, bytes: Uint8Array): boolean {
  const ascii = (start: number, end: number) => String.fromCharCode(...bytes.slice(start, end));
  if (type === 'video/mp4') {
    if (bytes.length < 16 || ascii(4, 8) !== 'ftyp') return false;
    const boxSize = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(0);
    if (boxSize < 16 || boxSize > bytes.length || boxSize % 4 !== 0) return false;
    const brands = new Set(['isom', 'iso2', 'iso3', 'iso4', 'iso5', 'iso6', 'mp41', 'mp42', 'avc1', 'M4V ', 'MSNV', 'qt  ']);
    for (let offset = 8; offset < boxSize; offset += 4) {
      if (offset !== 12 && brands.has(ascii(offset, offset + 4))) return true;
    }
    return false;
  }
  if (type !== 'video/webm' || bytes[0] !== 0x1a || bytes[1] !== 0x45 || bytes[2] !== 0xdf || bytes[3] !== 0xa3) return false;
  // The EBML DocType identifies WebM; Matroska uses the same container header.
  for (let offset = 4; offset + 7 <= bytes.length; offset++) {
    if (bytes[offset] === 0x42 && bytes[offset + 1] === 0x82 && bytes[offset + 2] === 0x84
      && ascii(offset + 3, offset + 7) === 'webm') return true;
  }
  return false;
}

/** Decode without playback, then keep a still frame for reduced motion and data saving. */
export async function prepareWallpaperVideo(file: File): Promise<WallpaperVideoRecord> {
  const invalid = validateBackgroundVideoFile(file);
  if (invalid) throw new WallpaperMediaError(invalid);
  let header: Uint8Array;
  try { header = new Uint8Array(await file.slice(0, 4096).arrayBuffer()); }
  catch { throw new WallpaperMediaError('videoFailed'); }
  if (!matchesBackgroundVideoSignature(file.type, header)) throw new WallpaperMediaError('videoFileType');
  const video = document.createElement('video');
  video.muted = true;
  video.defaultMuted = true;
  video.playsInline = true;
  video.preload = 'auto';
  if (!video.canPlayType(file.type)) throw new WallpaperMediaError('videoFailed');
  const url = URL.createObjectURL(file);
  try {
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => {
        clearTimeout(timeout);
        video.removeEventListener('loadeddata', ready);
        video.removeEventListener('error', failed);
      };
      const ready = () => {
        cleanup();
        if (!video.videoWidth || !video.videoHeight || !Number.isFinite(video.duration) || video.duration <= 0) {
          reject(new WallpaperMediaError('videoFailed'));
        } else resolve();
      };
      const failed = () => { cleanup(); reject(new WallpaperMediaError('videoFailed')); };
      const timeout = setTimeout(failed, 15000);
      video.addEventListener('loadeddata', ready, { once: true });
      video.addEventListener('error', failed, { once: true });
      video.src = url;
      video.load();
    });
    const canvas = document.createElement('canvas');
    const scale = Math.min(1, 1280 / Math.max(video.videoWidth, video.videoHeight));
    let width = Math.max(1, Math.round(video.videoWidth * scale));
    let height = Math.max(1, Math.round(video.videoHeight * scale));
    const context = canvas.getContext('2d');
    if (!context) throw new WallpaperMediaError('videoFailed');
    let poster: Blob | null = null;
    // Detailed frames can exceed the read limit even at the bounded dimensions.
    for (let attempt = 0; attempt < 8; attempt++) {
      canvas.width = width;
      canvas.height = height;
      context.drawImage(video, 0, 0, width, height);
      const encoded = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', Math.max(0.45, 0.8 - attempt * 0.08)));
      if (!encoded) break;
      if (encoded.type === 'image/jpeg' && encoded.size > 0 && encoded.size <= MAX_BACKGROUND_POSTER_BYTES) {
        poster = encoded;
        break;
      }
      width = Math.max(1, Math.round(width * 0.8));
      height = Math.max(1, Math.round(height * 0.8));
    }
    if (!poster) throw new WallpaperMediaError('videoFailed');
    return { id: `wallpaper-${crypto.randomUUID()}`, blob: file.slice(0, file.size, file.type), poster,
      width: video.videoWidth, height: video.videoHeight };
  } catch (error) {
    throw error instanceof WallpaperMediaError ? error : new WallpaperMediaError('videoFailed');
  } finally {
    video.pause();
    video.removeAttribute('src');
    video.load();
    URL.revokeObjectURL(url);
  }
}

function openMediaDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') { reject(new WallpaperMediaError('videoStorage')); return; }
    let settled = false;
    const request = indexedDB.open(DATABASE_NAME, 1);
    const fail = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      reject(new WallpaperMediaError('videoStorage'));
    };
    const timeout = setTimeout(fail, 8000);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) request.result.createObjectStore(STORE_NAME, { keyPath: 'id' });
    };
    request.onerror = fail;
    request.onblocked = fail;
    request.onsuccess = () => {
      if (settled) { request.result.close(); return; }
      settled = true;
      clearTimeout(timeout);
      resolve(request.result);
    };
  });
}

async function mediaTransaction<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const database = await openMediaDatabase();
  return new Promise<T>((resolve, reject) => {
    try {
      const transaction = database.transaction(STORE_NAME, mode);
      const request = operation(transaction.objectStore(STORE_NAME));
      transaction.oncomplete = () => { database.close(); resolve(request.result); };
      transaction.onabort = () => { database.close(); reject(new WallpaperMediaError('videoStorage')); };
      transaction.onerror = () => { /* The abort event owns rejection and connection cleanup. */ };
    } catch {
      database.close();
      reject(new WallpaperMediaError('videoStorage'));
    }
  });
}

/** Persist the blob before updating the small localStorage preference that points at it. */
export async function saveWallpaperVideo(record: WallpaperVideoRecord): Promise<void> {
  if (!isWallpaperVideoId(record.id) || validateBackgroundVideoFile(record.blob)) throw new WallpaperMediaError('videoFileType');
  if (!(record.poster instanceof Blob) || record.poster.type !== 'image/jpeg'
    || record.poster.size <= 0 || record.poster.size > MAX_BACKGROUND_POSTER_BYTES) throw new WallpaperMediaError('videoFailed');
  await mediaTransaction('readwrite', store => store.put(record));
}

export async function deleteWallpaperVideo(id: string): Promise<void> {
  if (!isWallpaperVideoId(id)) return;
  await mediaTransaction('readwrite', store => store.delete(id));
}

export async function loadWallpaperVideo(id: string): Promise<WallpaperVideoRecord> {
  if (!isWallpaperVideoId(id)) throw new WallpaperMediaError('videoMissing');
  const record = await mediaTransaction<WallpaperVideoRecord | undefined>('readonly', store => store.get(id));
  if (!record || record.id !== id || !(record.blob instanceof Blob) || validateBackgroundVideoFile(record.blob)
    || !(record.poster instanceof Blob) || record.poster.type !== 'image/jpeg'
    || record.poster.size <= 0 || record.poster.size > MAX_BACKGROUND_POSTER_BYTES) {
    throw new WallpaperMediaError('videoMissing');
  }
  return record;
}

export interface WallpaperMediaState {
  videoURL: string | null;
  posterURL: string | null;
  loading: boolean;
  error: WallpaperMediaErrorCode | null;
}
const EMPTY_MEDIA: WallpaperMediaState = Object.freeze({ videoURL: null, posterURL: null, loading: false, error: null });

/** Object URLs live only as long as the consumer and never enter persisted preferences. */
export function useWallpaperMedia(videoId: string | null): WallpaperMediaState {
  const [state, setState] = useState<WallpaperMediaState & { id: string | null }>({ ...EMPTY_MEDIA, id: null });
  useEffect(() => {
    if (!videoId) return;
    let active = true;
    let videoURL: string | null = null;
    let posterURL: string | null = null;
    setState({ ...EMPTY_MEDIA, id: videoId, loading: true });
    void loadWallpaperVideo(videoId).then(record => {
      if (!active) return;
      videoURL = URL.createObjectURL(record.blob);
      posterURL = record.poster ? URL.createObjectURL(record.poster) : null;
      setState({ id: videoId, videoURL, posterURL, loading: false, error: null });
    }).catch(error => {
      if (active) setState({ ...EMPTY_MEDIA, id: videoId,
        error: error instanceof WallpaperMediaError ? error.code : 'videoStorage' });
    });
    return () => {
      active = false;
      if (videoURL) URL.revokeObjectURL(videoURL);
      if (posterURL) URL.revokeObjectURL(posterURL);
    };
  }, [videoId]);
  if (!videoId) return EMPTY_MEDIA;
  return state.id === videoId ? state : { ...EMPTY_MEDIA, loading: true };
}
