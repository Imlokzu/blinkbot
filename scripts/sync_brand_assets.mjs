#!/usr/bin/env node
/** Compile the approved Blink artwork into offline web and native app assets.
 * Requires ImageMagick 7; iconutil is optional for the macOS bundle icon.
 * Runtime apps consume the checked-in files and need no image tooling.
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, mkdtempSync, rmSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const tmp = mkdtempSync(join(tmpdir(), 'blink-icons-'));
const paths = [];
function write(path, data) {
  const target = join(root, path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, data);
  paths.push(path);
}
function raster(svg, path, size, opaque = false) {
  const [width, height] = Array.isArray(size) ? size : [size, size];
  const input = join(tmp, 'input.svg');
  // Render paths at the output resolution instead of enlarging a 256px bitmap.
  const sizedSvg = svg.replace(/<svg\b([^>]*)>/, (_, attributes) =>
    `<svg${attributes.replace(/\s(?:width|height)="[^"]*"/g, '')} width="${width}" height="${height}">`);
  writeFileSync(input, sizedSvg);
  const args = ['-background', opaque ? '#080909' : 'none', input, '-resize', `${width}x${height}!`];
  if (opaque) args.push('-alpha', 'remove', '-alpha', 'off');
  const buffer = execFileSync('magick', [...args, '-strip', '-depth', '8', opaque ? 'PNG24:-' : 'PNG32:-'], { maxBuffer: 16 * 1024 * 1024 });
  write(path, buffer);
}
try {
  // Trace the source silhouette once so every platform shares real vector paths.
  const n = 512;
  const pixels = execFileSync('magick', [join(root, 'assets/brand/blink-source.png'), '-resize', `${n}x${n}!`, '-colorspace', 'Gray', '-depth', '8', 'gray:-']);
  const filled = (x, y) => x >= 0 && y >= 0 && x < n && y < n && pixels[y * n + x] >= 128;
  const edges = new Map();
  const key = (x, y) => y * (n + 1) + x;
  function edge(x1, y1, x2, y2) {
    const start = key(x1, y1);
    if (!edges.has(start)) edges.set(start, []);
    edges.get(start).push(key(x2, y2));
  }
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (filled(x, y)) {
    if (!filled(x, y - 1)) edge(x, y, x + 1, y);
    if (!filled(x + 1, y)) edge(x + 1, y, x + 1, y + 1);
    if (!filled(x, y + 1)) edge(x + 1, y + 1, x, y + 1);
    if (!filled(x - 1, y)) edge(x, y + 1, x, y);
  }
  const contours = [];
  while (edges.size) {
    const first = edges.keys().next().value;
    let current = first;
    const points = [];
    do {
      points.push([current % (n + 1), Math.floor(current / (n + 1))]);
      const next = edges.get(current);
      if (!next?.length) throw new Error('Open logo contour');
      const previous = current;
      current = next.pop();
      if (!next.length) edges.delete(previous);
    } while (current !== first);
    // Drop subpixel specks; retain all meaningful interior gaps and brush edges.
    const area = points.reduce((a, p, i) => {
      const q = points[(i + 1) % points.length];
      return a + p[0] * q[1] - q[0] * p[1];
    }, 0) / 2;
    if (Math.abs(area) < 3) continue;
    function simplify(points) {
      const a = points[0], b = points.at(-1);
      const dx = b[0] - a[0], dy = b[1] - a[1];
      const length = dx * dx + dy * dy;
      let max = .6 * .6, split = -1;
      for (let i = 1; i < points.length - 1; i++) {
        const p = points[i];
        const t = length ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length)) : 0;
        const distance = (p[0] - a[0] - t * dx) ** 2 + (p[1] - a[1] - t * dy) ** 2;
        if (distance > max) { max = distance; split = i; }
      }
      return split < 0 ? [a, b] : [...simplify(points.slice(0, split + 1)).slice(0, -1), ...simplify(points.slice(split))];
    }
    const corners = simplify([...points, points[0]]).slice(0, -1);
    contours.push(`M${corners.map(p => p.map(v => v / 2).join(',')).join('L')}Z`);
  }
  const d = contours.join('');
  const outline = (fill) => `<path fill="${fill}" fill-rule="evenodd" d="${d}"/>`;
  const svg = (content) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">${content}</svg>\n`;
  const mark = svg(outline('currentColor'));
  const white = svg(outline('#fff'));
  const tile = svg(`<rect width="256" height="256" fill="#080909"/>${outline('#fff')}`);
  const icon = svg(`<rect width="256" height="256" rx="52" fill="#080909"/>${outline('#fff')}`);
  const maskable = svg(`<rect width="256" height="256" fill="#080909"/><g transform="translate(128 128) scale(.8) translate(-128 -128)">${outline('#fff')}</g>`);
  write('assets/brand/blink-mark.svg', mark);
  write('assets/brand/blink-mark-white.svg', white);
  write('assets/brand/blink-icon.svg', icon);
  write('assets/brand/blink-maskable.svg', maskable);
  raster(tile, 'assets/brand/blink-icon.png', 1024, true);
  const publicDirs = ['landing/public', 'landing-3d/public', 'Virtual Bot/dashboard/public', 'blink-display/frontend/public', 'Device Setup Wizard/public', 'Virtual Bot/chat-panel/public', 'Virtual Bot/memory-panel/public', 'Virtual Bot/static/shared'];
  for (const dir of publicDirs) {
    write(`${dir}/blink-mark.svg`, mark);
    write(`${dir}/blink-mark-white.svg`, white);
    write(`${dir}/favicon.svg`, icon);
    raster(tile, `${dir}/apple-touch-icon.png`, 180, true);
  }
  write('Virtual Bot/dashboard/public/icon.svg', icon);
  write('Virtual Bot/dashboard/public/icon-maskable.svg', maskable);
  raster(tile, 'Virtual Bot/dashboard/public/icon-192.png', 192, true);
  raster(tile, 'Virtual Bot/dashboard/public/icon-512.png', 512, true);
  raster(maskable, 'Virtual Bot/dashboard/public/icon-maskable-512.png', 512, true);
  const native = (size, scale = 1) => `<!-- Generated by scripts/sync_brand_assets.mjs. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${size}dp" android:height="${size}dp" android:viewportWidth="256" android:viewportHeight="256"><group android:pivotX="128" android:pivotY="128" android:scaleX="${scale}" android:scaleY="${scale}"><path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="${d}"/></group></vector>\n`;
  write('mobile-app/shared/src/commonMain/composeResources/drawable/blink_mark.xml', native(64));
  for (const variant of ['foreground', 'monochrome']) write(`mobile-app/androidApp/src/main/res/drawable/ic_launcher_${variant}.xml`, native(108, .72));
  write('mobile-app/androidApp/src/main/res/drawable/ic_native_notification.xml', native(24));
  raster(tile, 'mobile-app/iosApp/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png', 1024, true);
  const adaptive = svg(`<g transform="translate(128 128) scale(.72) translate(-128 -128)">${outline('#fff')}</g>`);
  for (const dir of ['Virtual Bot/mobile-rn/assets', 'blink-app/apps/app/assets']) {
    raster(tile, `${dir}/icon.png`, 1024, true);
    raster(icon, `${dir}/favicon.png`, 48);
    raster(white, `${dir}/splash-icon.png`, 512);
    raster(adaptive, `${dir}/adaptive-icon.png`, 432);
    raster(adaptive, `${dir}/android-icon-foreground.png`, 432);
    raster(adaptive, `${dir}/android-icon-monochrome.png`, 432);
    raster(svg('<rect width="256" height="256" fill="#080909"/>'), `${dir}/android-icon-background.png`, 432, true);
  }
  // The older Capacitor shell is still installable; keep its launcher in sync.
  const capacitor = 'Virtual Bot/mobile/android/app/src/main/res';
  for (const [density, size] of [['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]]) {
    for (const suffix of ['', '_round']) raster(tile, `${capacitor}/mipmap-${density}/ic_launcher${suffix}.png`, size, true);
    raster(adaptive, `${capacitor}/mipmap-${density}/ic_launcher_foreground.png`, Math.round(size * 2.25));
  }
  for (const dir of readdirSync(join(root, capacitor)).filter(name => name.startsWith('drawable'))) {
    const target = `${capacitor}/${dir}/splash.png`;
    try {
      const [width, height] = execFileSync('magick', ['identify', '-format', '%w %h', join(root, target)], { stdio: ['ignore', 'pipe', 'ignore'] }).toString().split(' ').map(Number);
      const edge = Math.min(width, height) * .55;
      const splash = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><rect width="100%" height="100%" fill="#080909"/><g transform="translate(${(width - edge) / 2} ${(height - edge) / 2}) scale(${edge / 256})">${outline('#fff')}</g></svg>`;
      raster(splash, target, [width, height], true);
    } catch (error) {
      if (!error.message.includes('identify')) throw error;
    }
  }
  for (const dir of ['Device Setup Wizard/branding', 'blink-app/apps/desktop/branding', 'launcher/macos']) {
    raster(tile, `${dir}/icon.png`, 1024, true);
    const iconPath = join(root, dir, 'icon.ico');
    execFileSync('magick', [join(root, dir, 'icon.png'), '-define', 'icon:auto-resize=256,128,64,48,32,16', iconPath]);
    paths.push(`${dir}/icon.ico`);
  }
  if (process.platform === 'darwin') {
    const iconset = join(tmp, 'Blink.iconset');
    mkdirSync(iconset);
    for (const size of [16, 32, 128, 256, 512]) for (const scale of [1, 2]) {
      execFileSync('magick', [join(root, 'assets/brand/blink-icon.png'), '-resize', `${size * scale}x${size * scale}`, join(iconset, `icon_${size}x${size}${scale === 2 ? '@2x' : ''}.png`)]);
    }
    const icns = join(tmp, 'icon.icns');
    execFileSync('iconutil', ['-c', 'icns', '-o', icns, iconset]);
    for (const dir of ['Device Setup Wizard/branding', 'blink-app/apps/desktop/branding', 'launcher/macos']) write(`${dir}/icon.icns`, readFileSync(icns));
  }
  write('assets/brand/generated-files.json', JSON.stringify(paths, null, 2) + '\n');
  console.log(`Generated ${paths.length} brand assets from one source.`);
} finally { rmSync(tmp, { recursive: true, force: true }); }
