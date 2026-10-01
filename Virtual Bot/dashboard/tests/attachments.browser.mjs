/** The real multipart endpoint is exercised; chat writes stay inside this browser. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const session = `attachment-test-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5181';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const folder = mkdtempSync(join(tmpdir(), 'chat-upload-browser-'));
const file = join(folder, 'upload-fixture.md');
writeFileSync(file, '# Actual upload\n\nThe deadline is October 15.\n');

try {
  browser('open', `${origin}/static/dash/#/chat`);
  browser('network', 'route', '**/api/sessions', '--body', '{"sessions":[]}');
  browser('reload');
  browser('wait', '.prompt-bar__send');
  evaluate("localStorage.setItem('claudeBotLang','en')");
  browser('reload');
  browser('wait', '.prompt-bar__send');
  evaluate(`window.__uploads = []; window.__sends = []; const originalFetch = window.fetch;
    window.fetch = async (url, options) => {
      const path = new URL(String(url), location.href).pathname;
      if (path === '/api/chat') {
        window.__sends.push(JSON.parse(options.body));
        return new Response('event: done\\ndata: {"reply":""}\\n\\n', { headers: { 'Content-Type': 'text/event-stream' } });
      }
      const response = await originalFetch(url, options);
      if (path === '/api/chat/upload' && response.ok) window.__uploads.push(await response.clone().json());
      return response;
    };`);

  for (const [width, height] of [[1280, 850], [390, 844]]) {
    browser('set', 'viewport', String(width), String(height));
    evaluate('(async () => { await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); return true; })()');
    browser('wait', '--fn', 'document.querySelector(".prompt-bar__send").getAttribute("aria-label") === "Send"');
    browser('click', '.prompt-bar__tool[aria-label="Add to conversation"]');
    browser('wait', '[role=dialog][data-state=open]');
    browser('upload', '[data-attachment-picker=files]', file);
    browser('wait', '--fn', 'document.querySelector(".prompt-bar__chip")?.textContent.includes("upload-fixture.md")');
    assert.equal(evaluate('document.querySelector(".prompt-bar__chip").textContent.includes("upload-fixture.md")'), true);
    assert.equal(evaluate('document.querySelector(".prompt-bar__send").disabled'), false, 'files can be sent without a typed draft');
    browser('click', '.prompt-bar__send');
    browser('wait', '--fn', 'document.querySelectorAll(".prompt-bar__chip").length === 0');
    browser('wait', '--fn', 'document.querySelector(".prompt-bar__send").getAttribute("aria-label") === "Send"');
    assert.ok(evaluate('document.querySelector("[data-user-message]").textContent.includes("upload-fixture.md")'));
  }
  assert.equal(evaluate('window.__uploads.length'), 2);
  assert.equal(evaluate('window.__sends.length'), 2);
  assert.equal(evaluate('typeof window.__sends[0].attachments[0].size'), 'number');
  const bytes = evaluate(`(async () => {
    const response = await fetch(window.__uploads[0].url);
    return await response.text();
  })()`);
  assert.equal(bytes, '# Actual upload\n\nThe deadline is October 15.\n');
  console.log('PASS: real multipart upload, numeric metadata, file-only send, transcript attachments, desktop/phone + menu');
} catch (error) {
  console.error(evaluate('({ uploads: window.__uploads, sends: window.__sends, chips: [...document.querySelectorAll(".prompt-bar__chip")].map(x=>x.textContent) })'));
  throw error;
} finally {
  // Remove only files this fixture created in the test backend's uploads folder.
  if (process.env.DASHBOARD_TEST_UPLOADS_DIR) {
    const uploads = evaluate('window.__uploads || []');
    for (const upload of uploads) {
      const name = upload.url.split('/').at(-1);
      if (/^local-[a-f0-9]+\.md$/.test(name)) rmSync(join(process.env.DASHBOARD_TEST_UPLOADS_DIR, name), { force: true });
    }
  }
  browser('close');
  rmSync(folder, { recursive: true, force: true });
}
