import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { VitePWA } from 'vite-plugin-pwa';
import { fileURLToPath, URL } from 'node:url';

// Бекенд, до якого ходить дев-сервер. Прод-збірку роздає сам FastAPI,
// тож там усі шляхи вже однопортові й проксі не потрібен.
const BACKEND = process.env.VBOT_URL || 'http://127.0.0.1:8100';

// Шляхи, які належать бекенду, а не фронтенду: у деві їх треба проксіювати,
// інакше панель у `pnpm dev` бачить 404 замість API, стріму й прев'ю файлів.
/*
 * Chunks that belong to drawing: Excalidraw, Mermaid and what Mermaid pulls in
 * (layout engines, KaTeX). Together ~8 MB that most visits never load, so
 * they go to assets/drawing/ and the service worker caches them on first use
 * instead of downloading them all with every new build.
 */
const DRAWING = /[\\/]node_modules[\\/].*(@excalidraw|mermaid|cytoscape|katex|dagre|elkjs|d3-|khroma|roughjs|langium|chevrotain|lodash-es)/;
const isDrawing = (ids: readonly string[]) =>
  ids.some((id) => DRAWING.test(id) || id.includes('DrawingView'))
  && ids.every((id) => id.includes('node_modules') || id.includes('DrawingView'));

const BACKEND_PATHS = ['/api', '/preview', '/file', '/uploads', '/store-apps', '/screen', '/static/screen', '/static/shared', '/docs', '/openapi.json'];

export default defineConfig({
  base: '/static/dash/',
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      // Панель живе поруч із рештою статики, тому маніфест і service worker
      // мають лежати в тій самій теці, що й бандл.
      manifestFilename: 'manifest.webmanifest',
      includeAssets: ['icon.svg'],
      manifest: {
        name: 'Клод Бот — панель',
        short_name: 'Клод Бот',
        description: 'Панель керування віртуальним ботом',
        lang: 'uk',
        start_url: '/static/dash/',
        scope: '/static/dash/',
        display: 'standalone',
        orientation: 'any',
        background_color: '#12100e',
        theme_color: '#12100e',
        icons: [
          { src: 'icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
          { src: 'icon-maskable.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'maskable' },
        ],
      },
      workbox: {
        // Кешуємо лише оболонку. API, стріми й прев'ю файлів кешувати не можна:
        // це стан бота, а не статика — застарілу відповідь показувати гірше,
        // ніж чесну помилку мережі.
        globPatterns: ['**/*.{js,css,html,svg,webp,woff2}'],
        globIgnores: ['assets/drawing/**', 'excalidraw/**'],
        runtimeCaching: [{
          // Hashed file names: a cached copy is never stale, only unused.
          urlPattern: /\/static\/dash\/(assets\/drawing|excalidraw)\//,
          handler: 'CacheFirst',
          options: { cacheName: 'drawing', expiration: { maxEntries: 200 } },
        }],
        navigateFallbackDenylist: BACKEND_PATHS.map((p) => new RegExp(`^${p}`)),
      },
      devOptions: { enabled: false },
    }),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5180,
    proxy: Object.fromEntries(
      BACKEND_PATHS.map((path) => [path, { target: BACKEND, changeOrigin: true, ws: true }]),
    ),
  },
  build: {
    outDir: '../static/dash',
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rolldownOptions: {
      output: {
        chunkFileNames: (chunk) => (isDrawing(chunk.moduleIds) ? 'assets/drawing/[name]-[hash].js' : 'assets/[name]-[hash].js'),
      },
    },
  },
});
