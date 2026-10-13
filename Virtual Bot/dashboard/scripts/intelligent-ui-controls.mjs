import { build } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const publicId = 'virtual:blink-ui-controls';
const resolvedId = '\0' + publicId;

/** Bundle our actual React controls for the opaque frame, with no CDN or host API. */
export function intelligentUiControlsPlugin() {
  let compilation;
  const dependencies = new Set();
  return {
    name: 'blink-intelligent-ui-controls',
    resolveId(id) { if (id === publicId) return resolvedId; },
    async load(id) {
      if (id !== resolvedId) return;
      compilation ||= build({
        root, configFile: false, publicDir: false, logLevel: 'error',
        plugins: [react(), tailwindcss()],
        // The outer dev server sets NODE_ENV=development. This isolated bundle
        // always uses production React, so its JSX transform must match it.
        oxc: { jsx: { development: false } },
        define: { 'process.env.NODE_ENV': JSON.stringify('production') },
        resolve: { alias: { '@': resolve(root, 'src') } },
        build: { write: false, minify: true, cssMinify: true,
          lib: { entry: resolve(root, 'src/panels/chat/intelligentControls/runtime.tsx'), name: 'BlinkControlRuntime', formats: ['iife'] },
        },
      }).catch(error => { compilation = undefined; throw error; });
      const result = await compilation;
      const output = (Array.isArray(result) ? result : [result]).flatMap(item => item.output);
      for (const chunk of output.filter(item => item.type === 'chunk')) {
        for (const path of Object.keys(chunk.modules)) if (path.startsWith(root + '/src/')) dependencies.add(path);
      }
      for (const path of dependencies) this.addWatchFile(path);
      const javascript = output.filter(item => item.type === 'chunk').map(item => item.code).join('\n');
      const css = output.filter(item => item.type === 'asset' && item.fileName.endsWith('.css')).map(item => item.source).join('\n');
      return `export default ${JSON.stringify({ javascript, css })};`;
    },
    handleHotUpdate({ file, server }) {
      if (!dependencies.has(file) && !file.includes('/intelligentControls/')) return;
      compilation = undefined;
      const module = server.moduleGraph.getModuleById(resolvedId);
      if (module) server.moduleGraph.invalidateModule(module);
      server.ws.send({ type: 'full-reload' });
    },
  };
}
