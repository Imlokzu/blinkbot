import { createHash } from 'node:crypto';
import { cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.resolve(root, '../shared/src/commonMain/composeResources/files/workspace-editor');
const fonts = path.join(root, 'node_modules/@excalidraw/excalidraw/dist/prod/fonts');
const publicRoot = path.join(root, 'public');
await mkdir(path.join(publicRoot, 'fonts'), { recursive: true });
await cp(path.resolve(root, '../shared/src/commonMain/composeResources/font/manrope_regular.ttf'), path.join(publicRoot, 'fonts/Manrope-Regular.ttf'));
await cp(path.resolve(root, '../shared/src/commonMain/composeResources/font/manrope_semibold.ttf'), path.join(publicRoot, 'fonts/Manrope-Semibold.ttf'));
await cp(path.join(fonts, 'Cascadia/CascadiaCode-Regular.woff2'), path.join(publicRoot, 'fonts/CascadiaCode-Regular.woff2'));
await rm(path.join(publicRoot, 'excalidraw'), { recursive: true, force: true });
for (const family of (await readdir(fonts)).sort()) {
  if (family !== 'Xiaolai') await cp(path.join(fonts, family), path.join(publicRoot, 'excalidraw/fonts', family), { recursive: true });
}
await build({ root, configFile: path.join(root, 'vite.config.mjs') });
await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await cp(path.join(root, '.build'), output, { recursive: true });
const files = {}, sha256 = {};
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.ttf': 'font/ttf', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg' };
async function collect(directory, prefix = '') {
  for (const entry of (await readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
    const relative = prefix + entry.name;
    if (entry.isDirectory()) await collect(path.join(directory, entry.name), `${relative}/`);
    else {
      const type = mime[path.extname(entry.name)];
      if (!type) throw new Error(`Unexpected bundled file type: ${relative}`);
      files[relative] = type;
      sha256[relative] = createHash('sha256').update(await readFile(path.join(directory, entry.name))).digest('hex');
    }
  }
}
await collect(output);
await writeFile(path.join(output, 'manifest.json'), `${JSON.stringify({ entry: 'index.html', files, sha256 }, null, 2)}\n`);
console.log(`Bundled ${Object.keys(files).length} local editor assets.`);
