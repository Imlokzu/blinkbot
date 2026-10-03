import assert from 'node:assert/strict';
import test from 'node:test';
import {
  APPEARANCE_STORAGE_KEY,
  BACKGROUND_TARGETS,
  BackgroundImageError,
  DEFAULT_CHAT_APPEARANCE,
  MAX_BACKGROUND_FILE_BYTES,
  MAX_BACKGROUND_IMAGE_LENGTH,
  createChatAppearanceStore,
  isBackgroundImage,
  hasFullAppBackground,
  isWallpaperVideoId,
  normalizeChatAppearance,
  prepareBackgroundImage,
  validateBackgroundFile,
} from '../src/panels/chat/appearancePreferences.ts';
import { MAX_BACKGROUND_POSTER_BYTES, MAX_BACKGROUND_VIDEO_BYTES, WallpaperMediaError, loadWallpaperVideo, matchesBackgroundVideoSignature,
  deleteWallpaperVideo, prepareWallpaperVideo, saveWallpaperVideo, validateBackgroundVideoFile } from '../src/panels/chat/wallpaperMedia.ts';

// A real raster header matters: a data URL alone does not make content safe.
const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lN8AAAAASUVORK5CYII=';

function memoryStorage(initial = null) {
  let value = initial;
  let failWrites = false;
  const writes = [];
  return {
    getItem(key) {
      assert.equal(key, APPEARANCE_STORAGE_KEY);
      return value;
    },
    setItem(key, next) {
      assert.equal(key, APPEARANCE_STORAGE_KEY);
      if (failWrites) throw new Error('Storage quota exceeded');
      value = next;
      writes.push(next);
    },
    get value() { return value; },
    set value(next) { value = next; },
    set failWrites(next) { failWrites = next; },
    writes,
  };
}

test('malformed saved settings fall back to usable defaults', () => {
  for (const value of [undefined, null, [], 'sky', 5, true, {}]) {
    assert.deepEqual(normalizeChatAppearance(value), DEFAULT_CHAT_APPEARANCE);
  }
  assert.deepEqual(normalizeChatAppearance({
    background: 'unknown', image: {}, opacity: '90', blur: null, color: 'unknown',
  }), DEFAULT_CHAT_APPEARANCE);
});

test('appearance values are bounded without discarding a valid saved image', () => {
  assert.deepEqual(normalizeChatAppearance({
    background: 'forest', image: png, opacity: 66.7, blur: 3.8, color: 'ocean', extra: true,
  }), {
    ...DEFAULT_CHAT_APPEARANCE, background: 'forest', image: png, opacity: 67, blur: 4, color: 'ocean',
  });
  const low = normalizeChatAppearance({ opacity: -10, blur: -10 });
  assert.equal(low.opacity, 0);
  assert.equal(low.blur, 0);
  const high = normalizeChatAppearance({ opacity: 200, blur: 200 });
  assert.equal(high.opacity, 100);
  assert.equal(high.blur, 16);
  for (const value of [NaN, Infinity, -Infinity]) {
    const normalized = normalizeChatAppearance({ opacity: value, blur: value });
    assert.equal(normalized.opacity, DEFAULT_CHAT_APPEARANCE.opacity);
    assert.equal(normalized.blur, DEFAULT_CHAT_APPEARANCE.blur);
  }
});

test('custom backgrounds require a local raster image with a valid signature', () => {
  assert.equal(normalizeChatAppearance({ background: 'custom', image: png }).image, png);
  for (const image of [
    null,
    '',
    'https://example.com/image.png',
    'javascript:alert(1)',
    'data:image/svg+xml;base64,PHN2Zy8+',
    'data:text/html;base64,PHNjcmlwdD4=',
    'data:image/png;base64,PHN2Zy8+',
    'data:image/png;base64,',
    'data:image/png;base64,not-base64!',
    png + 'A'.repeat(MAX_BACKGROUND_IMAGE_LENGTH),
  ]) {
    const normalized = normalizeChatAppearance({ background: 'custom', image });
    assert.equal(normalized.image, null, String(image).slice(0, 70));
    assert.equal(normalized.background, DEFAULT_CHAT_APPEARANCE.background);
  }
});

test('saved image MIME types must agree with their raster signatures', () => {
  const jpeg = 'data:image/jpeg;base64,' + Buffer.from([0xff, 0xd8, 0xff, 0xe0]).toString('base64');
  const webp = 'data:image/webp;base64,' + Buffer.from('RIFF\x04\x00\x00\x00WEBP').toString('base64');
  assert.equal(isBackgroundImage(png), true);
  assert.equal(isBackgroundImage(jpeg), true);
  assert.equal(isBackgroundImage(webp), true);
  assert.equal(isBackgroundImage(png.replace('image/png', 'image/jpeg')), false);
  assert.equal(isBackgroundImage(jpeg.replace('image/jpeg', 'image/png')), false);
  assert.equal(isBackgroundImage(webp.replace('image/webp', 'image/png')), false);
  assert.equal(isBackgroundImage(png.replace('image/png', 'image/webp')), false);
});

test('background uploads reject unsupported or empty files and enforce the size limit', () => {
  for (const type of ['image/png', 'image/jpeg', 'image/webp']) {
    assert.equal(validateBackgroundFile({ type, size: 1 }), null);
    assert.equal(validateBackgroundFile({ type, size: MAX_BACKGROUND_FILE_BYTES }), null);
  }
  for (const type of ['image/svg+xml', 'image/gif', 'text/html', '', 'application/octet-stream']) {
    assert.equal(validateBackgroundFile({ type, size: 1 }), 'fileType');
  }
  for (const size of [0, -1, NaN, Infinity, MAX_BACKGROUND_FILE_BYTES + 1]) {
    assert.equal(validateBackgroundFile({ type: 'image/png', size }), 'fileSize');
  }
});

test('spoofed SVG uploads are rejected before creating an image decoder', async t => {
  const createUrl = t.mock.method(URL, 'createObjectURL', () => {
    throw new Error('Unsupported image content reached the decoder');
  });
  const file = new File(['<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>'],
    'pretend.png', { type: 'image/png' });
  await assert.rejects(prepareBackgroundImage(file), error =>
    error instanceof BackgroundImageError && error.code === 'fileType');
  assert.equal(createUrl.mock.callCount(), 0);
});

test('wrong raster MIME types and truncated headers never reach the decoder', async t => {
  const createUrl = t.mock.method(URL, 'createObjectURL', () => {
    throw new Error('Unsupported image content reached the decoder');
  });
  const files = [
    new File([Uint8Array.from([0xff, 0xd8, 0xff, 0xe0])], 'jpeg-as-png.png', { type: 'image/png' }),
    new File([Uint8Array.from([0x89, 0x50, 0x4e, 0x47])], 'truncated.png', { type: 'image/png' }),
  ];
  for (const file of files) {
    await assert.rejects(prepareBackgroundImage(file), error =>
      error instanceof BackgroundImageError && error.code === 'fileType');
  }
  assert.equal(createUrl.mock.callCount(), 0);
});

test('saved settings round trip and snapshots stay stable until a change', () => {
  const saved = { ...DEFAULT_CHAT_APPEARANCE, background: 'custom', image: png, opacity: 75, blur: 5, color: 'rose' };
  const storage = memoryStorage(JSON.stringify(saved));
  const store = createChatAppearanceStore(() => storage);
  assert.deepEqual(store.getSnapshot(), saved);
  assert.equal(store.getSnapshot(), store.getSnapshot());
  assert.equal(store.setAppearance({ opacity: 82 }), true);
  assert.deepEqual(JSON.parse(storage.value), { ...saved, opacity: 82 });
  assert.deepEqual(createChatAppearanceStore(() => storage).getSnapshot(), store.getSnapshot());
});

test('former stock frosting migrates once to a clear lens; deliberate settings survive', () => {
  const stock = normalizeChatAppearance({ background: 'sky', opacity: 88, blur: 8, color: 'theme' });
  assert.equal(stock.opacity, 0);
  assert.equal(stock.blur, 0);
  assert.equal(stock.material, 'glass');
  for (const saved of [{ opacity: 88, blur: 7 }, { opacity: 80, blur: 8 }, { opacity: 88, blur: 8, material: 'solid' },
    { opacity: 35, blur: 12, material: 'glass', glassRecipe: 2 }]) {
    const normalized = normalizeChatAppearance(saved);
    assert.equal(normalized.opacity, saved.opacity);
    assert.equal(normalized.blur, saved.blur);
  }
  // Migrating the display must still retain the local image and the chosen accent.
  const migratedImage = normalizeChatAppearance({ background: 'custom', image: png, opacity: 88, blur: 8, color: 'rose' });
  assert.equal(migratedImage.image, png);
  assert.equal(migratedImage.background, 'custom');
  assert.equal(migratedImage.color, 'rose');
  const formerGlass = normalizeChatAppearance({ material: 'glass', opacity: 35, blur: 12 });
  assert.equal(formerGlass.opacity, 0);
  assert.equal(formerGlass.blur, 0);
  assert.equal(formerGlass.glassRecipe, 2);
  const storage = memoryStorage(JSON.stringify(formerGlass));
  const store = createChatAppearanceStore(() => storage);
  assert.equal(store.setAppearance({ opacity: 35, blur: 12 }), true);
  assert.equal(store.getSnapshot().opacity, 35, 'new custom choices must not be migrated repeatedly');
  assert.equal(store.getSnapshot().blur, 12);
  assert.equal(createChatAppearanceStore(() => storage).getSnapshot().opacity, 35);
  assert.equal(createChatAppearanceStore(() => storage).getSnapshot().blur, 12);
});

test('wallpaper placement deduplicates allowed targets in a stable order', () => {
  assert.deepEqual(normalizeChatAppearance({ targets: ['pages', 'chat', 'chat', 'unknown', 'panels'] }).targets,
    ['chat', 'panels', 'pages']);
  assert.deepEqual(normalizeChatAppearance({ targets: [] }).targets, []);
  for (const targets of [null, 'all', {}, true]) assert.deepEqual(normalizeChatAppearance({ targets }).targets, ['chat']);
  assert.equal(hasFullAppBackground({ targets: BACKGROUND_TARGETS }), true);
  assert.equal(hasFullAppBackground({ targets: ['chat', 'navigation'] }), false);
  assert.equal(normalizeChatAppearance({ sidebarVisible: false, material: 'solid' }).sidebarVisible, false);
  assert.equal(normalizeChatAppearance({ sidebarVisible: 'false', material: 'unknown' }).sidebarVisible, true);
  assert.equal(normalizeChatAppearance({ material: 'unknown' }).material, 'glass');
});

test('placement, material and sidebar participate in saved snapshots', () => {
  const storage = memoryStorage();
  const store = createChatAppearanceStore(() => storage);
  let notifications = 0;
  const stop = store.subscribe(() => notifications++);
  assert.equal(store.setAppearance({ targets: ['panels', 'chat'], sidebarVisible: false, material: 'solid' }), true);
  assert.equal(notifications, 1);
  assert.deepEqual(store.getSnapshot().targets, ['chat', 'panels']);
  assert.equal(store.setAppearance({ targets: ['chat', 'panels', 'chat'] }), true);
  assert.equal(notifications, 1);
  assert.deepEqual(createChatAppearanceStore(() => storage).getSnapshot(), store.getSnapshot());
  storage.failWrites = true;
  const previous = store.getSnapshot();
  assert.equal(store.setAppearance({ targets: [...BACKGROUND_TARGETS], sidebarVisible: true, material: 'glass' }), false);
  assert.equal(store.getSnapshot(), previous);
  stop();
});

test('video preferences only accept opaque local IDs, never remote or blob URLs', () => {
  const videoId = 'wallpaper-2d2753ab-9c10-48e5-b970-5d2e11625d8f';
  assert.equal(isWallpaperVideoId(videoId), true);
  assert.equal(normalizeChatAppearance({ background: 'video', videoId }).background, 'video');
  for (const videoId of [null, '', 'https://example.com/video.mp4', 'blob:https://example.com/video', '../video', 'wallpaper-short']) {
    assert.equal(isWallpaperVideoId(videoId), false);
    const normalized = normalizeChatAppearance({ background: 'video', videoId });
    assert.equal(normalized.videoId, null);
    assert.equal(normalized.background, 'sky');
  }
});

test('video uploads enforce formats, finite nonzero sizes and the 40 MB boundary', () => {
  for (const type of ['video/mp4', 'video/webm']) {
    assert.equal(validateBackgroundVideoFile({ type, size: 1 }), null);
    assert.equal(validateBackgroundVideoFile({ type, size: MAX_BACKGROUND_VIDEO_BYTES }), null);
  }
  for (const type of ['', 'video/quicktime', 'video/x-matroska', 'image/svg+xml', 'text/html']) {
    assert.equal(validateBackgroundVideoFile({ type, size: 1 }), 'videoFileType');
  }
  for (const size of [0, -1, NaN, Infinity, MAX_BACKGROUND_VIDEO_BYTES + 1]) {
    assert.equal(validateBackgroundVideoFile({ type: 'video/mp4', size }), 'videoFileSize');
  }
});

test('video signatures distinguish MP4 and WebM from renamed or truncated content', () => {
  const mp4 = Uint8Array.from([0, 0, 0, 24, ...Buffer.from('ftypisom'), 0, 0, 0, 0, ...Buffer.from('isommp42')]);
  const webm = Uint8Array.from([0x1a, 0x45, 0xdf, 0xa3, 0x87, 0x42, 0x82, 0x84, ...Buffer.from('webm')]);
  assert.equal(matchesBackgroundVideoSignature('video/mp4', mp4), true);
  assert.equal(matchesBackgroundVideoSignature('video/webm', webm), true);
  assert.equal(matchesBackgroundVideoSignature('video/mp4', webm), false);
  assert.equal(matchesBackgroundVideoSignature('video/webm', mp4), false);
  assert.equal(matchesBackgroundVideoSignature('video/mp4', mp4.slice(0, 12)), false);
  assert.equal(matchesBackgroundVideoSignature('video/mp4', new Uint8Array([0, 0, 0, 4, ...Buffer.from('ftypisom')])), false);
  assert.equal(matchesBackgroundVideoSignature('video/webm', Uint8Array.from([0x1a, 0x45, 0xdf, 0xa3, ...Buffer.from('matroska')])), false);
  assert.equal(matchesBackgroundVideoSignature('video/webm', Uint8Array.from([0x1a, 0x45, 0xdf, 0xa3, ...Buffer.from('webm')])), false);
});

test('spoofed video files never reach a decoder or object URL', async t => {
  const createUrl = t.mock.method(URL, 'createObjectURL', () => { throw new Error('Spoof reached decoder'); });
  const files = [
    new File(['<svg><script>alert(1)</script></svg>'], 'pretend.mp4', { type: 'video/mp4' }),
    new File(['<html>pretend</html>'], 'pretend.webm', { type: 'video/webm' }),
  ];
  for (const file of files) await assert.rejects(prepareWallpaperVideo(file), error =>
    error instanceof WallpaperMediaError && error.code === 'videoFileType');
  assert.equal(createUrl.mock.callCount(), 0);
});

test('unavailable IndexedDB reports a storage failure and invalid video IDs never load', async () => {
  const record = { id: 'wallpaper-2d2753ab-9c10-48e5-b970-5d2e11625d8f', blob: new Blob(['video'], { type: 'video/mp4' }),
    poster: new Blob(['poster'], { type: 'image/jpeg' }), width: 320, height: 240 };
  await assert.rejects(saveWallpaperVideo(record), error => error instanceof WallpaperMediaError && error.code === 'videoStorage');
  await assert.rejects(loadWallpaperVideo('https://example.com/video.mp4'), error =>
    error instanceof WallpaperMediaError && error.code === 'videoMissing');
});

test('video decoding remains muted, saves a local still and releases its decoder URL', async t => {
  const previousDocument = globalThis.document;
  const listeners = new Map();
  const decodedURLs = [];
  const revokedURLs = [];
  const video = {
    videoWidth: 320, videoHeight: 180, duration: 2, src: '',
    canPlayType: () => 'probably',
    addEventListener: (type, listener) => listeners.set(type, listener),
    removeEventListener: (type, listener) => { if (listeners.get(type) === listener) listeners.delete(type); },
    load() { if (this.src) queueMicrotask(() => listeners.get('loadeddata')?.()); },
    pause() {},
    play() { throw new Error('Upload preparation must never play video'); },
    removeAttribute(name) { if (name === 'src') this.src = ''; },
  };
  const canvas = { width: 0, height: 0, getContext: () => ({ drawImage() {} }),
    toBlob: callback => callback(new Blob([Uint8Array.from([0xff, 0xd8, 0xff, 0xe0])], { type: 'image/jpeg' })) };
  globalThis.document = { createElement: tag => tag === 'video' ? video : canvas };
  t.mock.method(URL, 'createObjectURL', () => { decodedURLs.push('blob:decoder'); return 'blob:decoder'; });
  t.mock.method(URL, 'revokeObjectURL', url => revokedURLs.push(url));
  try {
    const file = new File([Uint8Array.from([0, 0, 0, 24, ...Buffer.from('ftypisom'), 0, 0, 0, 0, ...Buffer.from('isommp42')])],
      'private-filename.mp4', { type: 'video/mp4' });
    const record = await prepareWallpaperVideo(file);
    assert.equal(isWallpaperVideoId(record.id), true);
    assert.equal(record.blob.type, 'video/mp4');
    assert.equal(record.blob.size, file.size);
    assert.equal('name' in record.blob, false);
    assert.equal(record.poster.type, 'image/jpeg');
    assert.equal(record.width, 320);
    assert.equal(record.height, 180);
    assert.equal(video.muted, true);
    assert.equal(video.defaultMuted, true);
    assert.equal(video.playsInline, true);
    assert.equal(video.src, '');
    assert.deepEqual(decodedURLs, ['blob:decoder']);
    assert.deepEqual(revokedURLs, decodedURLs);
    assert.equal(listeners.size, 0);
  } finally { if (previousDocument === undefined) delete globalThis.document; else globalThis.document = previousDocument; }
});

test('detailed video posters shrink to the durable loader limit before saving', async t => {
  const previousDocument = globalThis.document;
  const listeners = new Map();
  const sizes = [];
  const video = {
    videoWidth: 1280, videoHeight: 1280, duration: 2, src: '',
    canPlayType: () => 'probably',
    addEventListener: (type, listener) => listeners.set(type, listener),
    removeEventListener: type => listeners.delete(type),
    load() { if (this.src) queueMicrotask(() => listeners.get('loadeddata')?.()); },
    pause() {},
    removeAttribute(name) { if (name === 'src') this.src = ''; },
  };
  let alwaysOversized = false;
  const canvas = { width: 0, height: 0, getContext: () => ({ drawImage() {} }),
    toBlob(callback, type, quality) {
      sizes.push({ width: this.width, height: this.height, quality });
      const bytes = alwaysOversized || sizes.length === 1 ? MAX_BACKGROUND_POSTER_BYTES + 1 : 200;
      callback(new Blob([new Uint8Array(bytes)], { type }));
    } };
  globalThis.document = { createElement: tag => tag === 'video' ? video : canvas };
  t.mock.method(URL, 'createObjectURL', () => 'blob:poster-limit');
  const revoke = t.mock.method(URL, 'revokeObjectURL', () => {});
  const file = new File([Uint8Array.from([0, 0, 0, 24, ...Buffer.from('ftypisom'), 0, 0, 0, 0, ...Buffer.from('isommp42')])],
    'detailed-frame.mp4', { type: 'video/mp4' });
  try {
    const record = await prepareWallpaperVideo(file);
    assert.equal(record.poster.size, 200);
    assert.equal(sizes.length, 2);
    assert.ok(sizes[1].width < sizes[0].width && sizes[1].height < sizes[0].height);
    assert.ok(sizes[1].quality < sizes[0].quality);
    const invalidRecord = { ...record, poster: new Blob([new Uint8Array(MAX_BACKGROUND_POSTER_BYTES + 1)], { type: 'image/jpeg' }) };
    await assert.rejects(saveWallpaperVideo(invalidRecord), error => error.code === 'videoFailed');
    alwaysOversized = true;
    await assert.rejects(prepareWallpaperVideo(file), error => error.code === 'videoFailed');
    assert.equal(revoke.mock.callCount(), 2, 'both accepted and rejected decoding releases its object URL');
    assert.equal(video.src, '');
  } finally { if (previousDocument === undefined) delete globalThis.document; else globalThis.document = previousDocument; }
});

test('durable video storage waits for commit and preference snapshots only contain its ID', async () => {
  const previousIndexedDB = globalThis.indexedDB;
  const records = new Map();
  let abortWrites = false;
  let closed = 0;
  const database = {
    close() { closed++; },
    transaction() {
      const transaction = { objectStore() {
        const request = (operation) => {
          const result = {};
          queueMicrotask(() => {
            if (abortWrites) transaction.onabort();
            else { result.result = operation(); transaction.oncomplete(); }
          });
          return result;
        };
        return {
          put: record => request(() => { records.set(record.id, record); return record.id; }),
          get: id => request(() => records.get(id)),
          delete: id => request(() => { records.delete(id); return undefined; }),
        };
      } };
      return transaction;
    },
  };
  globalThis.indexedDB = { open() {
    const request = { result: database };
    queueMicrotask(() => request.onsuccess());
    return request;
  } };
  const record = { id: 'wallpaper-2d2753ab-9c10-48e5-b970-5d2e11625d8f', blob: new Blob(['video'], { type: 'video/mp4' }),
    poster: new Blob(['poster'], { type: 'image/jpeg' }), width: 320, height: 240 };
  try {
    const saving = saveWallpaperVideo(record);
    assert.equal(records.size, 0);
    await saving;
    assert.equal((await loadWallpaperVideo(record.id)).blob, record.blob);
    const storage = memoryStorage();
    const store = createChatAppearanceStore(() => storage);
    assert.equal(store.setAppearance({ background: 'video', videoId: record.id }), true);
    const saved = JSON.parse(storage.value);
    assert.equal(saved.videoId, record.id);
    assert.equal('blob' in saved, false);
    assert.equal('poster' in saved, false);
    abortWrites = true;
    await assert.rejects(saveWallpaperVideo({ ...record, id: 'wallpaper-another-opaque-id' }), error =>
      error instanceof WallpaperMediaError && error.code === 'videoStorage');
    assert.equal(records.size, 1);
    abortWrites = false;
    await deleteWallpaperVideo(record.id);
    await assert.rejects(loadWallpaperVideo(record.id), error => error.code === 'videoMissing');
    assert.equal(closed, 5);
  } finally { if (previousIndexedDB === undefined) delete globalThis.indexedDB; else globalThis.indexedDB = previousIndexedDB; }
});

test('failed writes preserve the last saved appearance and do not notify subscribers', () => {
  const storage = memoryStorage();
  const store = createChatAppearanceStore(() => storage);
  assert.equal(store.setAppearance({ background: 'dusk', color: 'lavender' }), true);
  const previous = store.getSnapshot();
  const saved = storage.value;
  let notifications = 0;
  const unsubscribe = store.subscribe(() => notifications++);
  storage.failWrites = true;
  assert.equal(store.setAppearance({ background: 'forest' }), false);
  assert.equal(store.resetAppearance(), false);
  assert.equal(store.getSnapshot(), previous);
  assert.equal(storage.value, saved);
  assert.equal(notifications, 0);
  unsubscribe();
});

test('unavailable storage cannot silently replace a previously saved preference', () => {
  const storage = memoryStorage();
  let available = true;
  const store = createChatAppearanceStore(() => available ? storage : null);
  assert.equal(store.setAppearance({ color: 'sage' }), true);
  const previous = store.getSnapshot();
  available = false;
  assert.equal(store.setAppearance({ color: 'ocean' }), false);
  assert.equal(store.getSnapshot(), previous);
  const blocked = createChatAppearanceStore(() => { throw new Error('Storage denied'); });
  assert.deepEqual(blocked.getSnapshot(), DEFAULT_CHAT_APPEARANCE);
  assert.equal(blocked.setAppearance({ color: 'rose' }), false);
});

test('same-tab changes notify subscribers once and unchanged values avoid writes', () => {
  const storage = memoryStorage();
  const store = createChatAppearanceStore(() => storage);
  let first = 0;
  let second = 0;
  const unsubscribeFirst = store.subscribe(() => first++);
  const unsubscribeSecond = store.subscribe(() => second++);
  assert.equal(store.setAppearance(previous => ({ ...previous, color: 'ocean' })), true);
  assert.equal(first, 1);
  assert.equal(second, 1);
  const writes = storage.writes.length;
  const snapshot = store.getSnapshot();
  assert.equal(store.setAppearance({ color: 'ocean' }), true);
  assert.equal(storage.writes.length, writes);
  assert.equal(store.getSnapshot(), snapshot);
  assert.equal(first, 1);
  unsubscribeFirst();
  assert.equal(store.setAppearance({ color: 'sage' }), true);
  assert.equal(first, 1);
  assert.equal(second, 2);
  unsubscribeSecond();
});

test('external refresh applies changed settings and clears corrupt or removed settings', () => {
  const storage = memoryStorage();
  const store = createChatAppearanceStore(() => storage);
  let notifications = 0;
  const unsubscribe = store.subscribe(() => notifications++);
  storage.value = JSON.stringify({ background: 'none', opacity: 70, color: 'rose' });
  store.refresh();
  assert.equal(store.getSnapshot().background, 'none');
  assert.equal(store.getSnapshot().color, 'rose');
  assert.equal(notifications, 1);
  store.refresh();
  assert.equal(notifications, 1);
  storage.value = '{broken';
  store.refresh();
  assert.deepEqual(store.getSnapshot(), DEFAULT_CHAT_APPEARANCE);
  assert.equal(notifications, 2);
  assert.equal(store.setAppearance({ background: 'dusk' }), true);
  storage.value = null;
  store.refresh();
  assert.deepEqual(store.getSnapshot(), DEFAULT_CHAT_APPEARANCE);
  assert.equal(notifications, 4);
  unsubscribe();
});

test('storage listeners follow active subscriptions and close the read-to-subscribe gap', () => {
  const storage = memoryStorage();
  let externalRefresh;
  let installed = 0;
  let stopped = 0;
  const store = createChatAppearanceStore(() => storage, callback => {
    externalRefresh = callback;
    installed++;
    return () => { externalRefresh = undefined; stopped++; };
  });
  assert.deepEqual(store.getSnapshot(), DEFAULT_CHAT_APPEARANCE);
  assert.equal(installed, 0);
  // Another tab can save after React reads the snapshot but before it subscribes.
  storage.value = JSON.stringify({ color: 'rose' });
  let first = 0;
  let second = 0;
  const unsubscribeFirst = store.subscribe(() => first++);
  assert.equal(store.getSnapshot().color, 'rose');
  assert.equal(first, 1);
  const unsubscribeSecond = store.subscribe(() => second++);
  assert.equal(installed, 1);
  storage.value = JSON.stringify({ color: 'ocean' });
  externalRefresh();
  assert.equal(store.getSnapshot().color, 'ocean');
  assert.equal(first, 2);
  assert.equal(second, 1);
  unsubscribeFirst();
  assert.equal(stopped, 0);
  unsubscribeSecond();
  assert.equal(stopped, 1);
  assert.equal(externalRefresh, undefined);
  const unsubscribeAgain = store.subscribe(() => {});
  assert.equal(installed, 2);
  unsubscribeAgain();
  assert.equal(stopped, 2);
});

test('reset removes the custom background and restores every appearance control', () => {
  const storage = memoryStorage();
  const store = createChatAppearanceStore(() => storage);
  assert.equal(store.setAppearance({ background: 'custom', image: png, opacity: 65, blur: 16, color: 'rose' }), true);
  assert.equal(store.resetAppearance(), true);
  assert.deepEqual(store.getSnapshot(), DEFAULT_CHAT_APPEARANCE);
  assert.deepEqual(JSON.parse(storage.value), DEFAULT_CHAT_APPEARANCE);
});
