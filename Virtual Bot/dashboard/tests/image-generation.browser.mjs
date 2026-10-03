/** Run against an isolated backend with VBOT_IMAGE_QA_MOCK=1, never a paid provider. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5187';
const session = `image-generation-${process.pid}`;
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const plus = '.prompt-bar__tool[aria-label]';
const open = (label) => {
  browser('click', plus);
  browser('find', 'role', 'button', 'click', '--name', label, '--exact');
  browser('wait', '[role=dialog] textarea');
};

try {
  browser('open', `${origin}/static/dash/#/chat`);
  browser('wait', '.prompt-bar__send');
  evaluate("localStorage.setItem('claudeBotLang', 'en')");
  browser('reload');
  browser('wait', '.prompt-bar__send');
  browser('set', 'viewport', '1280', '850');
  open('Create image');
  browser('wait', '--text', 'Sign in with ChatGPT on the bot server');
  assert.equal(evaluate('document.querySelector("[role=dialog] button[type=submit]").disabled'), true);
  assert.ok(evaluate('document.querySelector("[role=dialog]").textContent.includes("codex login")'));
  browser('press', 'Escape');
  browser('wait', '--fn', 'document.activeElement?.getAttribute("aria-label") === "Add to conversation"');
  assert.equal(evaluate('document.activeElement?.getAttribute("aria-label")'), 'Add to conversation');

  // Readiness is mocked only in this browser. Generation still exercises the
  // real chat route, SSE, owned file publication and persisted conversation.
  evaluate(`window.__imageSends = []; window.__imageFetches = [];
    const realFetch = window.fetch;
    window.fetch = async (url, options) => {
      const path = new URL(String(url), location.href).pathname;
      if (path === '/api/images/status') return new Response(JSON.stringify({available: true, provider: 'codex', code: 'ready'}), {headers: {'Content-Type': 'application/json'}});
      if (path === '/api/chat') window.__imageSends.push(JSON.parse(options.body));
      if (path.startsWith('/uploads/')) window.__imageFetches.push({path, cache: options?.cache, redirect: options?.redirect});
      return realFetch(url, options);
    };`);

  for (const [width, label, description, language] of [[1280, 'Create image', 'A blue crab', 'en'], [390, 'Створити зображення', 'Піксельний краб', 'uk']]) {
    browser('set', 'viewport', String(width), '850');
    if (language === 'uk') {
      // Locale helpers read the current DOM language for every rendered label.
      evaluate("document.documentElement.lang = 'uk'");
    }
    open(label);
    browser('wait', '--fn', '!document.querySelector("[role=dialog] button[type=button]").disabled');
    browser('fill', '[role=dialog] textarea', description);
    assert.equal(evaluate('document.querySelector("[role=dialog] button[type=submit]").disabled'), false);
    browser('wait', '--fn', '(() => { const r = document.querySelector("[role=dialog]").getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight; })()');
    assert.equal(evaluate('(() => { const r = document.querySelector("[role=dialog]").getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight; })()'), true);
    const audit = JSON.parse(browser('a11y', '--selector', '[role=dialog][data-state=open]', '--json'));
    assert.equal(audit.data.counts.violations, 0, JSON.stringify(audit.data.violations));
    browser('click', '[role=dialog] button[type=submit]');
    browser('wait', '--fn', `[...document.images].some(image => image.src.startsWith('blob:'))`);
    browser('wait', '--fn', 'document.querySelector(".prompt-bar__send").disabled');
    const submitted = evaluate('window.__imageSends.at(-1)');
    assert.equal(submitted.message, `/image:${language} ${description}`);
    assert.deepEqual(submitted.attachments ?? [], []);
    assert.equal(evaluate('window.__imageFetches.at(-1).cache'), 'no-store');
    assert.equal(evaluate('window.__imageFetches.at(-1).redirect'), 'error');
    browser('focus', language === 'uk' ? 'button[aria-label="Відкрити: Піксельний краб"]' : 'button[aria-label="Open: A blue crab"]');
    browser('press', 'Enter');
    browser('wait', '[role=dialog] img[src^="blob:"]');
    assert.equal(evaluate('document.querySelector("[role=dialog] img").complete'), true);
    browser('press', 'Escape');
  }
  assert.equal(evaluate('window.__imageSends.length'), 2);
  assert.equal(evaluate(`[...document.images].filter(image => image.src.startsWith('blob:')).length`), 2);
  console.log('PASS: unavailable login, localized creation, operator readiness, private images, real chat SSE, viewport fit, viewer and accessibility on desktop/phone');
} catch (error) {
  console.error(evaluate('({ sends: window.__imageSends, fetches: window.__imageFetches, images: [...document.images].map(i => ({src: i.src, alt: i.alt})), text: document.body.innerText.slice(-2000) })'));
  throw error;
} finally {
  if (!process.env.IMAGE_QA_KEEP_BROWSER) browser('close');
}
