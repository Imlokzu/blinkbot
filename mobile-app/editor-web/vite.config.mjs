import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Upstream always appends esm.sh font URLs, even with an explicit local asset
// path. The trusted mobile pack must never contact that fallback. This narrow
// pinned-source transform fails a build if an upgrade changes the code shape.
function localExcalidrawFonts() {
  let patched = 0;
  return {
    name: 'blink-local-excalidraw-fonts',
    transform(code, id) {
      if (!id.includes('/@excalidraw/excalidraw/dist/prod/')) return;
      const fallback = /return ([\w$]+)\.push\(new URL\(([\w$]+),([\w$]+)\.ASSETS_FALLBACK_URL\)\),\1/g;
      const result = code.replace(fallback, (_, urls) => { patched++; return `return ${urls}`; });
      return result === code ? undefined : { code: result, map: null };
    },
    buildEnd(error) {
      if (!error && patched !== 1) throw new Error(`Expected one Excalidraw font fallback; found ${patched}`);
    },
  };
}

// The package ships an unused hosted-app Firebase configuration. This editor
// has no cloud integration, so retain the expected JSON-string shape with no
// credentials. Never log the original literal or permit it in output assets.
function stripUnusedFirebaseConfig() {
  let patched = 0;
  return {
    name: 'blink-strip-unused-firebase-config',
    transform(code, id) {
      if (!id.endsWith('/@excalidraw/excalidraw/dist/prod/chunk-ZUYEQ4TG.js')) return;
      const config = /(["']?VITE_APP_FIREBASE_CONFIG["']?\s*:\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')/g;
      const result = code.replace(config, (_, property) => { patched++; return `${property}"{}"`; });
      return result === code ? undefined : { code: result, map: null };
    },
    buildEnd(error) {
      if (!error && patched !== 1) throw new Error(`Expected one unused Firebase configuration; found ${patched}`);
    },
  };
}

export default defineConfig({
  base: './',
  plugins: [localExcalidrawFonts(), stripUnusedFirebaseConfig(), react()],
  build: {
    outDir: '.build',
    target: 'es2020',
    sourcemap: false,
    chunkSizeWarningLimit: 1600,
  },
});
