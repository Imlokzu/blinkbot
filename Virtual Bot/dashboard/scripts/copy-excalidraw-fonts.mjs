// Copies Excalidraw's fonts into public/excalidraw/fonts before a build.
//
// Excalidraw fetches its fonts from a CDN unless EXCALIDRAW_ASSET_PATH points
// somewhere else, and the dashboard must work with no network (DESIGN.md).
// Xiaolai (the CJK hand-drawn font, ~12 MB) is left out: the bot writes
// Ukrainian and English, and without it CJK text falls back to a system font
// instead of the bundle growing twelvefold.
import { cpSync, existsSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
// The package exports no ./package.json, so it is found through node_modules
// directly; pnpm links it there like any other dependency.
const source = join(here, '..', 'node_modules', '@excalidraw', 'excalidraw', 'dist', 'prod', 'fonts');
const target = join(here, '..', 'public', 'excalidraw', 'fonts');
const SKIP = new Set(['Xiaolai']);

if (!existsSync(source)) throw new Error(`Excalidraw fonts not found at ${source}`);
rmSync(target, { recursive: true, force: true });
for (const family of readdirSync(source)) {
  if (!SKIP.has(family)) cpSync(join(source, family), join(target, family), { recursive: true });
}
console.log(`excalidraw fonts → ${target}`);
