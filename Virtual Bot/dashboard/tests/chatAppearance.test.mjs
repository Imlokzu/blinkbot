import assert from 'node:assert/strict';
import test from 'node:test';
import {
  APPEARANCE_STORAGE_KEY,
  BackgroundImageError,
  DEFAULT_CHAT_APPEARANCE,
  MAX_BACKGROUND_FILE_BYTES,
  MAX_BACKGROUND_IMAGE_LENGTH,
  createChatAppearanceStore,
  isBackgroundImage,
  normalizeChatAppearance,
  prepareBackgroundImage,
  validateBackgroundFile,
} from '../src/panels/chat/appearancePreferences.ts';

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
    background: 'forest', image: png, opacity: 67, blur: 4, color: 'ocean',
  });
  const low = normalizeChatAppearance({ opacity: -10, blur: -10 });
  assert.equal(low.opacity, 65);
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
  const saved = { background: 'custom', image: png, opacity: 75, blur: 5, color: 'rose' };
  const storage = memoryStorage(JSON.stringify(saved));
  const store = createChatAppearanceStore(() => storage);
  assert.deepEqual(store.getSnapshot(), saved);
  assert.equal(store.getSnapshot(), store.getSnapshot());
  assert.equal(store.setAppearance({ opacity: 82 }), true);
  assert.deepEqual(JSON.parse(storage.value), { ...saved, opacity: 82 });
  assert.deepEqual(createChatAppearanceStore(() => storage).getSnapshot(), store.getSnapshot());
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
