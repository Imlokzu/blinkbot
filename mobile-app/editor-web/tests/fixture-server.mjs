import { createServer } from 'node:http';
import { readFile, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { extname, relative, resolve, sep } from 'node:path';

const bundleDirectory = fileURLToPath(new URL('../../shared/src/commonMain/composeResources/files/workspace-editor/', import.meta.url));
const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2',
};
const bridge = `
window.__fixture = { messages: [], networkAttempts: [], violations: [] };
window.BlinkNative = { postMessage(raw) {
  window.__fixture.messages.push(JSON.parse(raw));
} };
window.addEventListener('securitypolicyviolation', event => {
  window.__fixture.violations.push({ directive: event.effectiveDirective, url: event.blockedURI });
});
const fixtureFetch = window.fetch.bind(window);
window.fetch = (...args) => {
  window.__fixture.networkAttempts.push(String(args[0] instanceof Request ? args[0].url : args[0]));
  return fixtureFetch(...args);
};
`;
const pixel = { type: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64') };

/** Serve the built application unchanged except for a native receiver before startup. */
export async function startFixtureServer() {
  let root;
  try {
    root = await realpath(bundleDirectory);
    await readFile(resolve(root, 'index.html'));
  } catch {
    throw new Error('Build the editor pack first: npm run build in mobile-app/editor-web.');
  }
  const requests = [];
  const fixtures = new Map([
    ['/workspace/notes/flow.excalidraw', { type: 'application/json', body: JSON.stringify({ elements: [] }) }],
    ['/workspace/notes/pixel.svg', { type: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="#39a"/></svg>' }],
    ['/workspace/picture.png', pixel],
    ['/workspace/notes/nested/picture.png', pixel],
    ['/workspace/notes/nested/images/a.png', pixel],
    ['/workspace/session/notes/old.png', pixel],
    ['/workspace/notes/shared.png', pixel],
    ['/workspace/notes/nested/note.md.drawings/new.excalidraw', { type: 'application/json', body: JSON.stringify({ elements: [] }) }],
    ['/workspace/notes/nested/note.md.drawings/pending.excalidraw', { type: 'application/json', body: JSON.stringify({ elements: [
      { type: 'text', x: 0, y: 0, text: 'Created drawing', fontSize: 20, fontFamily: 5 },
    ] }) }],
  ]);
  const server = createServer(async (request, response) => {
    const entry = { method: request.method, path: request.url, status: 500,
      referer: request.headers.referer, destination: request.headers['sec-fetch-dest'],
      userAgent: request.headers['user-agent'] };
    requests.push(entry);
    const send = (status, type, body) => {
      entry.status = status;
      response.writeHead(status, {
        'Content-Type': type, 'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
        // Never allow fixture content to reach an external site or the owner's backend.
        'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
      });
      response.end(body);
    };
    try {
      if (request.method !== 'GET') return send(405, 'text/plain', 'Method not allowed');
      const path = decodeURIComponent(request.url.split('?')[0]);
      if (!path.startsWith('/') || path.includes('\\') || path.includes('\0') || path.split('/').some(part => part === '.' || part === '..')) {
        return send(400, 'text/plain', 'Invalid path');
      }
      if (path === '/__fixture__/bridge.js') return send(200, types['.js'], bridge);
      if (path === '/favicon.ico') return send(204, 'image/x-icon', '');
      const fixture = fixtures.get(path);
      if (fixture) return send(200, fixture.type, fixture.body);
      if (!path.startsWith('/editor/')) return send(404, 'text/plain', 'Not found');
      const file = await realpath(resolve(root, path.slice('/editor/'.length) || 'index.html'));
      const local = relative(root, file);
      if (local === '..' || local.startsWith(`..${sep}`)) return send(400, 'text/plain', 'Invalid path');
      let body = await readFile(file);
      if (extname(file) === '.html') {
        body = body.toString('utf8').replace(/<head([^>]*)>/i, '<head$1><script src="/__fixture__/bridge.js"></script>');
      }
      return send(200, types[extname(file)] || 'application/octet-stream', body);
    } catch (error) {
      return send(error instanceof URIError ? 400 : 404, 'text/plain', 'Not found');
    }
  });
  await new Promise((resolveListening, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolveListening);
  });
  const origin = `http://127.0.0.1:${server.address().port}`;
  return {
    origin, requests,
    async close() {
      server.closeAllConnections();
      await new Promise((resolveClosed, reject) => server.close(error => error ? reject(error) : resolveClosed()));
    },
  };
}
