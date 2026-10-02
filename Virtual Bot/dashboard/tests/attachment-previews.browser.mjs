/** Real private uploads and preview reads; model calls stay browser-only. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const session = `attachment-previews-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8102';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const folder = mkdtempSync(join(tmpdir(), 'attachment-preview-fixture-'));
const note = join(folder, 'Project plan.md');
const photo = join(folder, 'Design reference.png');
writeFileSync(note, '# Project plan\n\nLaunch date: October 15.\nThe agent sees this exact document.\n');
writeFileSync(photo, process.env.DASHBOARD_TEST_IMAGE ? readFileSync(process.env.DASHBOARD_TEST_IMAGE)
  : Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5S0AAAAASUVORK5CYII=', 'base64'));
const add = '.prompt-bar__tool[aria-label="Add to conversation"]';
try {
  browser('open', `${origin}/static/dash/#/chat`);
  browser('network', 'route', '**/api/sessions', '--body', '{"sessions":[]}');
  evaluate("localStorage.setItem('claudeBotLang','en')");
  browser('reload');
  browser('wait', '.prompt-bar__send');
  evaluate(`window.__uploads = []; window.__sends = []; window.__history = []; window.__historyReads = 0;
    const original = window.fetch;
    window.fetch = async (url, options) => {
      const path = new URL(String(url), location.href).pathname;
      if (path === '/api/chat') {
        const request = JSON.parse(options.body); window.__sends.push(request);
        window.__history.push({ role:'user', content:request.message, attachments:request.attachments });
        return new Response('event: done\\ndata: {"reply":"","session_id":"preview-fixture"}\\n\\n', { headers: { 'Content-Type': 'text/event-stream' } });
      }
      if (path === '/api/sessions/preview-fixture') { window.__historyReads++; return new Response(JSON.stringify({ id:'preview-fixture', messages:window.__history }), { headers: { 'Content-Type':'application/json' } }); }
      if (path === '/api/sessions') return new Response(JSON.stringify({ sessions:window.__history.length ? [{id:'preview-fixture',title:'Attachment check',count:window.__history.length}] : [] }), { headers: { 'Content-Type':'application/json' } });
      const response = await original(url, options);
      if (path === '/api/chat/upload' && response.ok) window.__uploads.push(await response.clone().json());
      return response;
    };`);
  for (const [width, height] of [[1440, 960], [390, 844]]) {
    browser('set', 'viewport', String(width), String(height));
    browser('wait', '.prompt-bar__send');
    browser('fill', '.prompt-bar textarea', 'Inspect both files');
    const before = evaluate('document.querySelector(".prompt-bar").getBoundingClientRect().top');
    browser('click', add);
    browser('wait', '[data-attachment-menu]');
    assert.equal(evaluate('Boolean(document.activeElement.closest("[data-attachment-menu]"))'), true, 'keyboard navigation starts inside the source menu');
    assert.ok(Math.abs(evaluate('document.querySelector(".prompt-bar").getBoundingClientRect().top') - before) < 1, 'opening the menu cannot shift the draft');
    assert.equal(evaluate('Boolean(document.querySelector(".attach-media-grid"))'), width < 1180, 'media tiles belong to touch layouts');
    assert.equal(evaluate('document.querySelector("[data-attachment-menu]").getBoundingClientRect().top >= 55'), true, 'the menu stays below the header');
    if (process.env.CHAT_NAV_SHOTS) { browser('wait', '250'); browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/attachment-menu-${width}.png`); }
    browser('press', 'Escape');
    assert.equal(evaluate('document.querySelector("[data-attachment-menu]") === null'), true);
    assert.equal(evaluate(`document.activeElement.matches(${JSON.stringify(add)})`), true, 'Escape returns to the source trigger');
    browser('click', add);
    browser('upload', '[data-attachment-picker=files]', note, photo);
    browser('wait', '--fn', 'document.querySelector(".prompt-bar .attachment-excerpt")?.textContent.includes("Launch date: October 15.")');
    browser('wait', '--fn', 'document.querySelector(".prompt-bar .attachment-thumbnail img")?.naturalWidth > 0');
    assert.equal(evaluate('document.querySelectorAll(".prompt-bar .attachment-card").length'), 2);
    assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Inspect both files');
    browser('click', '.prompt-bar .attachment-card-open');
    browser('wait', '.attachment-dialog pre');
    assert.match(evaluate('document.querySelector(".attachment-dialog pre").textContent'), /Launch date: October 15/);
    browser('press', 'Escape');
    browser('wait', '--fn', '!document.querySelector(".attachment-dialog")');
    browser('wait', '--fn', 'document.activeElement.matches(".prompt-bar .attachment-card-open")');
    browser('wait', '--fn', 'document.activeElement.matches(".prompt-bar .attachment-card-open")');
    assert.equal(evaluate('document.activeElement.matches(".prompt-bar .attachment-card-open")'), true);
    browser('click', '.prompt-bar .attachment-card:nth-child(2) .attachment-card-open');
    browser('wait', '.attachment-dialog img');
    browser('wait', '--fn', 'Boolean(document.activeElement.closest(".attachment-dialog"))');
    assert.equal(evaluate('document.querySelector(".attachment-dialog img").alt'), 'Design reference.png');
    browser('press', 'Escape');
    browser('wait', '--fn', '!document.querySelector(".attachment-dialog")');
    if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/attachment-draft-${width}.png`);
    browser('click', '.prompt-bar__send');
    browser('wait', '--fn', '!document.querySelector(".prompt-bar .attachment-card") && document.querySelectorAll("[data-user-message] .attachment-card").length >= 2');
    browser('wait', '--fn', 'document.querySelector("[data-user-message] .attachment-thumbnail img")?.naturalWidth > 0');
    browser('click', '[data-user-message] .attachment-card-open');
    browser('wait', '.attachment-dialog pre');
    browser('wait', '--fn', 'Boolean(document.activeElement.closest(".attachment-dialog"))');
    browser('press', 'Escape');
    browser('wait', '--fn', '!document.querySelector(".attachment-dialog")');
    browser('wait', '--fn', 'document.activeElement.matches("[data-user-message] .attachment-card-open")');
    assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'attachment cards must fit the phone');
  }
  assert.equal(evaluate('window.__sends.length'), 2);
  assert.deepEqual(evaluate('window.__sends[0].attachments.map(file=>file.name)'), ['Project plan.md','Design reference.png']);
  evaluate("window.location.hash = '#/settings'");
  browser('wait', '--fn', '!document.querySelector(".chat-layout")');
  evaluate("window.location.hash = '#/chat'");
  browser('wait', '--fn', 'document.querySelectorAll("[data-user-message] .attachment-card").length === 4');
  browser('wait', '--fn', 'document.querySelector("[data-user-message] .attachment-thumbnail img")?.naturalWidth > 0');
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/attachment-sent-phone.png`);
  browser('click', add);
  browser('upload', '[data-attachment-picker=files]', note);
  browser('wait', '.prompt-bar .attachment-remove');
  browser('click', '.prompt-bar .attachment-remove');
  assert.equal(evaluate('document.querySelectorAll(".prompt-bar .attachment-card").length'), 0);
  browser('set', 'viewport', '1440', '960');
  browser('wait', '[data-chat-toolbar]');
  browser('click', '[data-chat-toolbar] button[aria-label="New conversation"]');
  browser('fill', '.prompt-bar textarea', 'Discarded unsaved draft');
  evaluate(`const previousFetch = window.fetch;
    window.__heldUpload = null;
    window.__delayUpload = true;
    window.fetch = async (url, options) => {
      const response = await previousFetch(url, options);
      if (window.__delayUpload && new URL(String(url), location.href).pathname === '/api/chat/upload') {
        await new Promise(resolve => { window.__heldUpload = resolve; });
      }
      return response;
    };`);
  browser('click', add);
  browser('upload', '[data-attachment-picker=files]', note);
  browser('wait', '--fn', 'typeof window.__heldUpload === "function"');
  // Reset a new unsaved chat to another new chat: both have the same empty id.
  browser('click', '[data-chat-toolbar] button[aria-label="New conversation"]');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), '');
  browser('fill', '.prompt-bar textarea', 'Fresh draft survives the stale upload');
  evaluate('window.__delayUpload = false; window.__heldUpload()');
  browser('wait', '200');
  assert.equal(evaluate('document.querySelectorAll(".prompt-bar .attachment-card").length'), 0, 'late upload completion cannot attach to another draft generation');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Fresh draft survives the stale upload');
  browser('click', add);
  browser('find', 'role', 'button', 'click', '--name', 'Tools', '--exact');
  browser('wait', '[role="dialog"][data-state="open"]');
  browser('press', 'Escape');
  browser('wait', '--fn', '!document.querySelector("[role=dialog]")');
  browser('wait', '--fn', `document.activeElement.matches(${JSON.stringify(add)})`);
  browser('click', '[data-session-id="preview-fixture"]');
  browser('wait', '[data-user-message]');
  browser('fill', '.prompt-bar textarea', 'Keep this unsent compaction draft');
  browser('click', add);
  browser('upload', '[data-attachment-picker=files]', note);
  browser('wait', '.prompt-bar .attachment-card');
  browser('network', 'route', '**/api/chat/context**', '--body', JSON.stringify({ parts: [], chars: 100, dropped: 0, history_limit: 30 }));
  browser('network', 'route', '**/api/sessions/preview-fixture/compact', '--body', JSON.stringify({ summary: 'Fixture summary', before: 4 }));
  evaluate('window.__readsBeforeCompact = window.__historyReads; window.__composerBeforeCompact = document.querySelector(".prompt-bar"); true;');
  browser('find', 'role', 'button', 'click', '--name', 'Conversation context', '--exact');
  browser('find', 'role', 'button', 'click', '--name', 'Compact', '--exact');
  browser('wait', '--fn', 'window.__historyReads > window.__readsBeforeCompact');
  assert.equal(evaluate('document.querySelector(".prompt-bar") === window.__composerBeforeCompact'), true, 'same-chat compaction refresh must not remount the draft');
  assert.equal(evaluate('document.querySelector(".prompt-bar textarea").value'), 'Keep this unsent compaction draft');
  assert.equal(evaluate('document.querySelectorAll(".prompt-bar .attachment-card").length'), 1);
  console.log('PASS: actual upload bytes/names, draft and saved document/image previews, keyboard focus, menu overlay without shifting, removal, desktop/phone, no live model requests');
} catch (error) {
  console.error(browser('errors'));
  console.error(evaluate('({ active: document.activeElement?.outerHTML.slice(0,300), dialog: Boolean(document.querySelector(".attachment-dialog")) })'));
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/attachment-preview-failure.png`);
  throw error;
} finally {
  const uploaded = evaluate('window.__uploads || []');
  if (process.env.DASHBOARD_TEST_UPLOADS_DIR) for (const file of uploaded) {
    const stored = file.url.split('/').at(-1);
    if (/^local-[a-f0-9]+\.(md|png)$/.test(stored)) rmSync(join(process.env.DASHBOARD_TEST_UPLOADS_DIR, stored), { force:true });
  }
  browser('close'); rmSync(folder, {recursive:true,force:true});
}
