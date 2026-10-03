import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const asset = new URL('../src/panels/chat/assets/chat-reference-sky.jpg', import.meta.url);
const digest = 'a029dbe9f74533920249cc9d0a1823f3cd15bc96dfddd4989b6d7b0ffc4bd2e0';

function wallpaperAsset(file, selector) {
  const url = new URL(file, import.meta.url);
  const css = readFileSync(url, 'utf8');
  const rule = css.match(new RegExp(`${selector}\\s*\\{([^}]+)\\}`))?.[1];
  assert.ok(rule, 'the existing sky preset remains selectable');
  const path = rule.match(/background-image:\s*url\(['"]?([^'"\)]+)['"]?\)/)?.[1];
  assert.ok(path, 'the sky surface references a bitmap');
  assert.ok(path.startsWith('.'), 'a local URL keeps the wallpaper independent of the source host');
  assert.doesNotMatch(path, /^(?:https?:)?\/\//);
  return new URL(path, url);
}

test('the bundled reference preserves the original JPEG without recompression', () => {
  const bytes = readFileSync(asset);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), digest);
  assert.deepEqual([...bytes.subarray(0, 2)], [0xff, 0xd8]);
  assert.deepEqual([...bytes.subarray(-2)], [0xff, 0xd9]);
});

test('the wallpaper and Settings sky preview use the same local reference', () => {
  const wallpaper = wallpaperAsset('../src/components/shell/app-wallpaper.css', 'html\\[data-wallpaper="sky"\\] \\.app-wallpaper');
  const preview = wallpaperAsset('../src/panels/chat/chat-appearance-controls.css', '\\.chat-appearance-preview--sky');
  assert.equal(wallpaper.href, asset.href);
  assert.equal(preview.href, wallpaper.href);
  assert.equal(createHash('sha256').update(readFileSync(wallpaper)).digest('hex'), digest);
});

test('the PWA shell cache includes the original reference JPEG', () => {
  const config = readFileSync(new URL('../vite.config.ts', import.meta.url), 'utf8');
  const extensions = config.match(/globPatterns:\s*\['\*\*\/\*\.\{([^}]+)\}'\]/)?.[1].split(',');
  assert.ok(extensions, 'the PWA declares its cached shell assets');
  assert.ok(extensions.includes('jpg'), 'the supplied wallpaper is available offline after install');
  assert.ok(extensions.includes('jpeg'), 'JPEG assets use either standard filename extension');
  assert.ok(extensions.includes('webp'), 'the existing generated wallpaper remains eligible for caching');
});
