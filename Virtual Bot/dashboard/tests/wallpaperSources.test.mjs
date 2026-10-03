import assert from 'node:assert/strict';
import test from 'node:test';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DEFAULT_CHAT_APPEARANCE, createChatAppearanceStore, normalizeChatAppearance,
  normalizeWallpaperSourceURL } from '../src/panels/chat/appearancePreferences.ts';
import { WALLPAPER_PRESETS, getWallpaperPreset } from '../src/panels/chat/wallpaperPresets.ts';

test('ready photos and videos are local, previewable and uniquely identified', () => {
  assert.equal(new Set(WALLPAPER_PRESETS.map(preset => preset.id)).size, 4);
  assert.deepEqual(WALLPAPER_PRESETS.map(preset => preset.kind), ['image', 'image', 'video', 'video']);
  for (const preset of WALLPAPER_PRESETS) {
    assert.ok(existsSync(fileURLToPath(preset.src)), preset.id);
    assert.ok(existsSync(fileURLToPath(preset.thumbnail)), preset.id);
    assert.match(preset.thumbnail, /\.jpg$/);
    assert.equal(getWallpaperPreset(preset.id), preset);
  }
});

test('old choices retain their recipe, placements and uploads when presets are added', () => {
  const old = normalizeChatAppearance({ background: 'forest', material: 'solid', opacity: 73, blur: 4, targets: ['chat', 'panels'] });
  assert.equal(old.background, 'forest');
  assert.equal(old.opacity, 73);
  assert.equal(old.material, 'solid');
  assert.deepEqual(old.targets, ['chat', 'panels']);
  assert.equal(old.presetId, null);
  assert.equal(old.sourceUrl, null);
});

test('preset and direct-source selection persist without replacing stored local media', () => {
  let raw = JSON.stringify({ ...DEFAULT_CHAT_APPEARANCE, videoId: 'wallpaper-local-12345678' });
  const storage = { getItem: () => raw, setItem: (_key, value) => { raw = value; } };
  const store = createChatAppearanceStore(() => storage);
  assert.equal(store.setAppearance({ background: 'preset', presetId: 'stars-video' }), true);
  assert.equal(store.getSnapshot().videoId, 'wallpaper-local-12345678');
  assert.equal(createChatAppearanceStore(() => storage).getSnapshot().presetId, 'stars-video');
  store.setAppearance({ background: 'remote', sourceUrl: 'https://media.example.org/loop.mp4', sourceType: 'video' });
  const restored = createChatAppearanceStore(() => storage).getSnapshot();
  assert.equal(restored.background, 'remote');
  assert.equal(restored.sourceType, 'video');
  assert.equal(restored.sourceUrl, 'https://media.example.org/loop.mp4');
  assert.equal(restored.presetId, 'stars-video');
  assert.equal(restored.videoId, 'wallpaper-local-12345678');
});

test('only explicit web media URLs are reusable as direct sources', () => {
  const credentialURL = new URL('https://example.org/p.jpg');
  credentialURL.username = 'synthetic-fixture-user';
  credentialURL.password = 'synthetic-fixture-password';
  assert.equal(normalizeWallpaperSourceURL(' https://media.example.org/scene.jpg '), 'https://media.example.org/scene.jpg');
  assert.equal(normalizeWallpaperSourceURL('http://192.168.1.2/loop.mp4'), 'http://192.168.1.2/loop.mp4');
  for (const value of ['javascript:alert(1)', 'data:image/svg+xml,<svg/>', 'file:///secret', '/photo.jpg',
    'https:example.org', credentialURL.href, 'https://example.org/\nphoto.jpg',
    'https://example.org/' + 'x'.repeat(2000), null, 42]) {
    assert.equal(normalizeWallpaperSourceURL(value), null);
  }
});

test('malformed persisted source IDs cannot become active backgrounds', () => {
  assert.equal(normalizeChatAppearance({ background: 'preset', presetId: '../../file' }).background, 'sky');
  assert.equal(normalizeChatAppearance({ background: 'remote', sourceUrl: 'javascript:alert(1)' }).background, 'sky');
  assert.equal(getWallpaperPreset('invalid'), null);
});
