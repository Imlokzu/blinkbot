#!/usr/bin/env node
/** Compile the approved Blink artwork into offline web and native app assets.
 * Requires ImageMagick 7; iconutil is optional for the macOS bundle icon.
 * Runtime apps consume the checked-in files and need no image tooling.
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync, mkdtempSync, rmSync, readdirSync } from 'node:fs';
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
  // Keep the approved geometric master as vectors; never retrace a raster export.
  const master = readFileSync(join(root, 'assets/brand/blink-master.svg'), 'utf8');
  const tags = [...master.matchAll(/<path\b[^>]*>/g)].map(match => match[0]);
  const attribute = (tag, name) => tag.match(new RegExp(`\\b${name}="([^"]+)"`))?.[1];
  const flashTag = tags.find(tag => attribute(tag, 'data-part') === 'flash');
  const bodyTag = tags.find(tag => attribute(tag, 'data-part') === 'body');
  if (!master.includes('viewBox="0 0 256 256"') || tags.length !== 2 || !flashTag || !bodyTag) {
    throw new Error('The brand master must contain flash and body paths in the 256px viewBox');
  }
  const flash = attribute(flashTag, 'd');
  const body = attribute(bodyTag, 'd');
  const accent = attribute(flashTag, 'fill');
  if (!flash || !body || !/^#[0-9a-f]{6}$/i.test(accent || '')) throw new Error('Invalid brand master paths or accent');
  const d = flash + body;
  const outline = (fill) => `<path fill="${accent}" fill-rule="evenodd" d="${flash}"/><path fill="${fill}" fill-rule="evenodd" d="${body}"/>`;
  const mono = (fill) => `<path fill="${fill}" fill-rule="evenodd" d="${d}"/>`;
  const svg = (content) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">${content}</svg>\n`;
  const mark = svg(mono('currentColor'));
  const color = svg(outline('#191a18'));
  const flashOverlay = svg(`<path fill="${accent}" d="${flash}"/>`);
  const white = svg(outline('#fff'));
  const tileBackground = '#151613';
  const tileRadius = 60;
  const tileScale = .82;
  const tileArtwork = `<g transform="translate(128 128) scale(${tileScale}) translate(-128 -128)">${outline('#fff')}</g>`;
  const tile = svg(`<rect width="256" height="256" fill="${tileBackground}"/>${tileArtwork}`);
  const icon = svg(`<rect width="256" height="256" rx="${tileRadius}" fill="${tileBackground}"/>${tileArtwork}`);
  const maskable = svg(`<rect width="256" height="256" fill="#080909"/><g transform="translate(128 128) scale(.8) translate(-128 -128)">${outline('#fff')}</g>`);
  write('assets/brand/blink-mark.svg', mark);
  write('assets/brand/blink-mark-color.svg', color);
  write('assets/brand/blink-flash.svg', flashOverlay);
  write('assets/brand/blink-mark-white.svg', white);
  write('assets/brand/blink-icon.svg', icon);
  write('assets/brand/blink-tile.svg', icon);
  raster(icon, 'assets/brand/blink-tile.png', 1024);
  write('assets/brand/blink-maskable.svg', maskable);
  raster(tile, 'assets/brand/blink-icon.png', 1024, true);
  raster(tile, 'assets/brand/blink-source.png', 1024, true);
  const publicDirs = ['landing/public', 'landing-3d/public', 'Virtual Bot/dashboard/public', 'blink-display/frontend/public', 'Device Setup Wizard/public', 'Virtual Bot/chat-panel/public', 'Virtual Bot/memory-panel/public', 'Virtual Bot/static/shared'];
  for (const dir of publicDirs) {
    write(`${dir}/blink-mark.svg`, mark);
    write(`${dir}/blink-mark-color.svg`, color);
    write(`${dir}/blink-flash.svg`, flashOverlay);
    write(`${dir}/blink-mark-white.svg`, white);
    write(`${dir}/favicon.svg`, icon);
    write(`${dir}/blink-tile.svg`, icon);
    raster(tile, `${dir}/apple-touch-icon.png`, 180, true);
  }
  write('Virtual Bot/dashboard/public/icon.svg', icon);
  write('Virtual Bot/dashboard/public/icon-maskable.svg', maskable);
  raster(tile, 'Virtual Bot/dashboard/public/icon-192.png', 192, true);
  raster(tile, 'Virtual Bot/dashboard/public/icon-512.png', 512, true);
  raster(maskable, 'Virtual Bot/dashboard/public/icon-maskable-512.png', 512, true);
  // Important rules also update already-built mask-based avatars without a JS rebuild.
  const brandStyles = `/* Generated by scripts/sync_brand_assets.mjs. */\n[data-bot-icon] { mask: none !important; -webkit-mask: none !important; background-color: transparent !important; background-image: url("./blink-tile.svg") !important; background-position: center; background-size: contain; background-repeat: no-repeat; border-radius: ${tileRadius / 256 * 100}%; }\n`;
  write('Virtual Bot/dashboard/public/blink-brand.css', brandStyles);
  // The backend serves these checked-in builds directly. Refresh artwork only,
  // without rebuilding unrelated work in the panels' JavaScript or stylesheets.
  const panelFiles = ['blink-tile.svg', 'blink-mark-color.svg', 'blink-flash.svg', 'blink-mark.svg', 'blink-mark-white.svg', 'favicon.svg', 'apple-touch-icon.png'];
  const dashboardFiles = [...panelFiles, 'blink-brand.css', 'icon.svg', 'icon-maskable.svg', 'icon-192.png', 'icon-512.png', 'icon-maskable-512.png'];
  for (const [source, target, files] of [
    ['Virtual Bot/dashboard/public', 'Virtual Bot/static/dash', dashboardFiles],
    ['Virtual Bot/chat-panel/public', 'Virtual Bot/static/chat-panel', panelFiles],
    ['Virtual Bot/memory-panel/public', 'Virtual Bot/static/memory-panel', panelFiles],
  ]) {
    if (!existsSync(join(root, target))) continue;
    for (const file of files) write(`${target}/${file}`, readFileSync(join(root, source, file)));
  }
  const dashboardIndexPath = 'Virtual Bot/static/dash/index.html';
  const precacheFiles = [...dashboardFiles];
  if (existsSync(join(root, dashboardIndexPath))) {
    const index = readFileSync(join(root, dashboardIndexPath), 'utf8');
    const styleLink = '<link rel="stylesheet" href="/static/dash/blink-brand.css" data-blink-brand />';
    const updatedIndex = index.includes('data-blink-brand') ? index : index.replace(/<\/head>/i, `${styleLink}\n</head>`);
    if (!updatedIndex.includes('data-blink-brand')) throw new Error('Cannot attach brand stylesheet to dashboard index');
    write(dashboardIndexPath, updatedIndex);
    precacheFiles.push('index.html');
  }
  const serviceWorkerPath = 'Virtual Bot/static/dash/sw.js';
  if (existsSync(join(root, serviceWorkerPath))) {
    const serviceWorker = readFileSync(join(root, serviceWorkerPath), 'utf8');
    // Workbox revisions are content MD5s. Change only the generated artwork's
    // entries and the stylesheet link, so installed PWAs replace cached logos.
    const revisionFor = (file) => createHash('md5').update(readFileSync(join(root, 'Virtual Bot/static/dash', file))).digest('hex');
    const cached = new Set();
    let updated = serviceWorker.replace(/(\burl:"([^"]+)",revision:)(?:"[^"]*"|null)/g, (entry, prefix, file) => {
      if (!precacheFiles.includes(file)) return entry;
      cached.add(file);
      return `${prefix}"${revisionFor(file)}"`;
    });
    const missing = precacheFiles.filter(file => !cached.has(file));
    if (missing.length) {
      const entries = missing.map(file => `{url:"${file}",revision:"${revisionFor(file)}"}`).join(',');
      const withEntries = updated.replace(/\bprecacheAndRoute\(\[/, (call) => `${call}${entries},`);
      if (withEntries === updated) throw new Error('Cannot add brand files to dashboard precache');
      updated = withEntries;
    }
    write(serviceWorkerPath, updated);
  }
  const native = (size, scale = 1, colored = false, flashOnly = false) => {
    const path = (fill, data) => `<path android:fillColor="${fill}" android:fillType="evenOdd" android:pathData="${data}"/>`;
    const content = colored ? path(accent, flash) + path('#FFFFFFFF', body) : path('#FFFFFFFF', flashOnly ? flash : d);
    return `<!-- Generated by scripts/sync_brand_assets.mjs. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${size}dp" android:height="${size}dp" android:viewportWidth="256" android:viewportHeight="256"><group android:pivotX="128" android:pivotY="128" android:scaleX="${scale}" android:scaleY="${scale}">${content}</group></vector>\n`;
  };
  // Native in-app logos use the same solid rounded tile; launcher masking stays OS-owned.
  const roundedBackground = `M${tileRadius},0H${256 - tileRadius}A${tileRadius},${tileRadius},0,0,1,256,${tileRadius}V${256 - tileRadius}A${tileRadius},${tileRadius},0,0,1,${256 - tileRadius},256H${tileRadius}A${tileRadius},${tileRadius},0,0,1,0,${256 - tileRadius}V${tileRadius}A${tileRadius},${tileRadius},0,0,1,${tileRadius},0Z`;
  const nativeTile = `<!-- Generated by scripts/sync_brand_assets.mjs. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="64dp" android:height="64dp" android:viewportWidth="256" android:viewportHeight="256"><path android:fillColor="${tileBackground}" android:pathData="${roundedBackground}"/><group android:pivotX="128" android:pivotY="128" android:scaleX="${tileScale}" android:scaleY="${tileScale}"><path android:fillColor="${accent}" android:pathData="${flash}"/><path android:fillColor="#FFFFFFFF" android:pathData="${body}"/></group></vector>\n`;
  write('mobile-app/shared/src/commonMain/composeResources/drawable/blink_tile.xml', nativeTile);
  write('mobile-app/shared/src/commonMain/composeResources/drawable/blink_mark.xml', native(64));
  write('mobile-app/shared/src/commonMain/composeResources/drawable/blink_flash.xml', native(64, 1, false, true));
  for (const variant of ['foreground', 'monochrome']) write(`mobile-app/androidApp/src/main/res/drawable/ic_launcher_${variant}.xml`, native(108, .68, variant === 'foreground'));
  write('mobile-app/androidApp/src/main/res/drawable/ic_native_notification.xml', native(24));
  raster(tile, 'mobile-app/iosApp/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png', 1024, true);
  const adaptiveMono = svg(`<g transform="translate(128 128) scale(.68) translate(-128 -128)">${mono('#fff')}</g>`);
  const adaptive = svg(`<g transform="translate(128 128) scale(.68) translate(-128 -128)">${outline('#fff')}</g>`);
  for (const dir of ['Virtual Bot/mobile-rn/assets', 'blink-app/apps/app/assets']) {
    raster(tile, `${dir}/icon.png`, 1024, true);
    raster(icon, `${dir}/favicon.png`, 48);
    raster(white, `${dir}/splash-icon.png`, 512);
    raster(adaptive, `${dir}/adaptive-icon.png`, 432);
    raster(adaptive, `${dir}/android-icon-foreground.png`, 432);
    raster(adaptiveMono, `${dir}/android-icon-monochrome.png`, 432);
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
    raster(icon, `${dir}/icon.png`, 1024);
    const iconPath = join(root, dir, 'icon.ico');
    execFileSync('magick', [join(root, dir, 'icon.png'), '-define', 'icon:auto-resize=256,128,64,48,32,16', iconPath]);
    paths.push(`${dir}/icon.ico`);
  }
  if (process.platform === 'darwin') {
    const iconset = join(tmp, 'Blink.iconset');
    mkdirSync(iconset);
    for (const size of [16, 32, 128, 256, 512]) for (const scale of [1, 2]) {
      execFileSync('magick', [join(root, 'assets/brand/blink-tile.png'), '-resize', `${size * scale}x${size * scale}`, join(iconset, `icon_${size}x${size}${scale === 2 ? '@2x' : ''}.png`)]);
    }
    const icns = join(tmp, 'icon.icns');
    execFileSync('iconutil', ['-c', 'icns', '-o', icns, iconset]);
    for (const dir of ['Device Setup Wizard/branding', 'blink-app/apps/desktop/branding', 'launcher/macos']) write(`${dir}/icon.icns`, readFileSync(icns));
  }
  write('assets/brand/generated-files.json', JSON.stringify(paths, null, 2) + '\n');
  console.log(`Generated ${paths.length} brand assets from one source.`);
} finally { rmSync(tmp, { recursive: true, force: true }); }
