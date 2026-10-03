/** Controlled SSE exercises actual pending, file loading, reveal and interruption. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5188';
const fixture = '/uploads/local-motion.png';
const scenario = { id: 'paint', label: 'image_generate', status: 'active', detail: '', input: { prompt: 'A quiet mountain landscape' } };
const result = { provider: 'codex', images: [{ url: fixture, type: 'image/png' }] };
const reply = `![A quiet mountain landscape](${fixture})`;

for (const [width, theme, language, reduced] of [[1280, 'light', 'en', false], [390, 'dark', 'uk', false], [320, 'light', 'en', true]]) {
  const session = `image-motion-${process.pid}-${width}`;
  const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
  const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
  const emit = (type, data) => evaluate(`window.__emit(${JSON.stringify(type)}, ${JSON.stringify(data)})`);
  const drawCount = () => evaluate('window.__ditherDraws');
  const pause = () => evaluate('(async () => { await new Promise(resolve => setTimeout(resolve, 180)); return true; })()');
  try {
    browser('open', `${origin}/static/dash/#/chat`);
    browser('set', 'viewport', String(width), '850');
    if (reduced) browser('set', 'media', theme, 'reduced-motion');
    evaluate(`localStorage.setItem('claudeBotLang', ${JSON.stringify(language)}); localStorage.setItem('claudeBotTheme', ${JSON.stringify(theme)});`);
    browser('reload');
    browser('wait', '.prompt-bar__send');
    evaluate(`window.__ditherDraws = 0; window.__fileFetches = []; window.__holdImage = true;
      const clear = CanvasRenderingContext2D.prototype.clearRect;
      CanvasRenderingContext2D.prototype.clearRect = function(...args) {
        if (this.canvas.classList.contains('image-generation-dither')) window.__ditherDraws++;
        return clear.apply(this, args);
      };
      const realFetch = window.fetch;
      window.fetch = async (url, options) => {
        const path = new URL(String(url), location.href).pathname;
        if (path === '/api/chat') return new Response(new ReadableStream({start(controller) {
          const encoder = new TextEncoder();
          window.__emit = (type, data) => controller.enqueue(encoder.encode('event: '+type+'\\ndata: '+JSON.stringify(data)+'\\n\\n'));
          window.__end = () => { try { controller.close(); } catch { /* The chat reader cancels after done. */ } };
          options.signal?.addEventListener('abort', () => { try { controller.error(new DOMException('Aborted', 'AbortError')); } catch {} }, {once:true});
          window.__emit('session', {session_id: 'image-motion-fixture'});
        }}), {headers: {'Content-Type': 'text/event-stream'}});
        if (path === ${JSON.stringify(fixture)}) {
          window.__fileFetches.push({cache: options.cache, redirect: options.redirect});
          if (window.__holdImage) await new Promise(resolve => {window.__releaseImage = resolve;});
          if (window.__failImage) return new Response('', {status: 503});
        }
        return realFetch(url, options);
      };`);
    const start = () => {
      evaluate('window.__emit = null; true;');
      browser('fill', 'textarea', '/image:en A quiet mountain landscape');
      browser('click', '.prompt-bar__send');
      browser('wait', '--fn', 'typeof window.__emit === "function"');
      emit('tool_start', { step: scenario });
      browser('wait', '[data-image-generation][data-state=generating]');
    };
    start();
    evaluate('window.__card = document.querySelector("[data-image-generation]"); window.__surface = window.__card.querySelector(".image-generation-surface"); true;');
    const bounds = evaluate('(() => { const r=window.__surface.getBoundingClientRect(); return {width:r.width,height:r.height,left:r.left,right:r.right}; })()');
    assert.ok(bounds.width > 100 && bounds.left >= 0 && bounds.right <= width);
    assert.equal(evaluate('window.__card.getAttribute("aria-busy")'), 'true');
    assert.equal(evaluate('window.__card.querySelectorAll("img").length'), 0, 'never show a fabricated preview while generating');
    pause();
    const before = drawCount(); pause();
    assert.equal(drawCount() > before, !reduced, 'reduced motion keeps a static dither field');

    if (!reduced) {
      evaluate('Object.defineProperty(document, "hidden", {configurable:true, get:()=>true}); document.dispatchEvent(new Event("visibilitychange"));');
      const hidden = drawCount(); pause(); assert.equal(drawCount(), hidden, 'background documents stop canvas work');
      evaluate('delete document.hidden; document.dispatchEvent(new Event("visibilitychange"));');
    }
    const audit = JSON.parse(browser('a11y', '--selector', '[data-image-generation]', '--json'));
    assert.equal(audit.data.counts.violations, 0, JSON.stringify(audit.data.violations));
    if (process.env.IMAGE_MOTION_ARTIFACTS) browser('screenshot', `${process.env.IMAGE_MOTION_ARTIFACTS}/pending-${width}.png`);

    const doneStep = { ...scenario, status: 'done', result };
    emit('tool_done', { step: doneStep });
    emit('done', { reply, session_id: 'image-motion-fixture', emotion: 'idle', mode: 'codex', steps: [doneStep],
      parts: [{ type: 'steps', ids: ['paint'] }, { type: 'text', text: reply }], tool_results: [] });
    evaluate('window.__end()');
    browser('wait', '[data-image-generation][data-state=loading]');
    assert.equal(evaluate('document.querySelector("[data-image-generation]") === window.__card'), true);
    assert.equal(evaluate('window.__card.querySelectorAll(".image-generation-open:not([disabled])").length'), 0, 'viewer waits for the actual file');
    evaluate('window.__holdImage=false; window.__releaseImage()');
    browser('wait', '[data-image-generation][data-state=complete]');
    assert.equal(evaluate('document.querySelector("[data-image-generation]") === window.__card'), true);
    assert.equal(evaluate('[...document.images].filter(image => image.src.startsWith("blob:")).length'), 1, 'one result, no duplicate Markdown image');
    assert.equal(evaluate('window.__card.querySelector(".image-generation-resolution").textContent'), '640 × 480');
    const final = evaluate('(() => {const r=window.__surface.getBoundingClientRect();return {width:r.width,height:r.height};})()');
    assert.deepEqual(final, { width: bounds.width, height: bounds.height }, 'completion preserves the reserved surface');
    assert.equal(evaluate('window.__fileFetches[0].cache'), 'no-store');
    assert.equal(evaluate('window.__fileFetches[0].redirect'), 'error');
    browser('wait', '--fn', '!document.querySelector(".image-generation-field")');
    const stopped = drawCount(); pause(); assert.equal(drawCount(), stopped, 'completed images release the canvas');
    if (process.env.IMAGE_MOTION_ARTIFACTS) browser('screenshot', `${process.env.IMAGE_MOTION_ARTIFACTS}/complete-${width}.png`);
    browser('focus', '.image-generation-open'); browser('press', 'Enter');
    browser('wait', '[role=dialog] img');
    assert.equal(evaluate('document.querySelector("[role=dialog] img").naturalWidth'), 640);
    browser('press', 'Escape');

    // A second real activity in the same thread must stop on cancellation.
    start();
    evaluate('window.__interruptedCard = document.querySelector("[data-image-generation][data-state=generating]"); true;');
    evaluate('const stop = document.querySelector(".prompt-bar__send"); stop.click(); stop.click(); true;');
    browser('wait', '[data-image-generation][data-state=interrupted]');
    browser('wait', '--fn', 'document.querySelectorAll(".image-generation-dither").length === 0');
    assert.equal(evaluate('document.querySelector("[data-state=interrupted][data-image-generation]").getAttribute("aria-busy")'), 'false');
    assert.equal(evaluate('document.querySelector("[data-state=interrupted][data-image-generation]") === window.__interruptedCard'), true);
    assert.equal(evaluate('document.querySelectorAll("[data-image-generation][data-state=interrupted]").length'), 1, 'repeated Stop cannot duplicate the settled reply');

    if (width === 1280) {
      evaluate('window.__failImage = true;');
      start();
      emit('tool_done', { step: doneStep });
      emit('done', { reply, session_id:'image-motion-fixture', emotion:'idle', mode:'codex', steps:[doneStep],
        parts:[{type:'steps',ids:['paint']},{type:'text',text:reply}], tool_results:[] });
      browser('wait', '[data-image-generation][data-state=failed]');
      browser('wait', '--fn', 'document.querySelectorAll(".image-generation-dither").length === 0');
      evaluate('window.__failImage = false;');
      browser('click', '[data-image-generation][data-state=failed] button');
      browser('wait', '--fn', 'document.querySelectorAll("[data-image-generation][data-state=complete]").length === 2');
      assert.equal(evaluate('document.querySelectorAll("[data-image-generation][data-state=failed]").length'), 0, 'retry loads the existing result without regenerating');
    }
    const saved = { id:'saved-image', title:'Saved image', messages:[{ id:'user', role:'user', content:'A quiet mountain landscape' },
      { id:'assistant', role:'assistant', content:reply, steps:[doneStep], parts:[{type:'steps',ids:['paint']},{type:'text',text:reply}] }] };
    browser('network', 'route', '**/api/sessions', '--body', JSON.stringify({sessions:[{id:'saved-image',title:'Saved image',count:2}]}));
    browser('network', 'route', '**/api/sessions/saved-image', '--body', JSON.stringify(saved));
    browser('reload');
    browser('wait', '.prompt-bar__send');
    if (width < 768) browser('click', language === 'uk' ? '.chat-narrow-toolbar button[aria-label="Розмови"]' : '.chat-narrow-toolbar button[aria-label="Conversations"]');
    browser('wait', '[data-session-id="saved-image"]');
    evaluate('document.querySelector("[data-session-id=saved-image] .truncate").click(); true;');
    browser('wait', '[data-image-generation][data-state=complete]');
    browser('wait', '--fn', 'document.querySelectorAll(".image-generation-dither").length === 0');
    assert.equal(evaluate('[...document.images].filter(image => image.src.startsWith("blob:")).length'), 1, 'restored history delivers one image without a new generation');
    console.log(`PASS: real SSE states, stable surface/reveal, one private result, viewer, cleanup, accessibility, ${width}px ${theme}/${language}, reduced=${reduced}`);
  } catch (error) {
    console.error(evaluate('({text:document.body.innerText.slice(-2000),cards:[...document.querySelectorAll("[data-image-generation]")].map(n=>({state:n.dataset.state,html:n.outerHTML.slice(0,600)})),draws:window.__ditherDraws})'));
    throw error;
  } finally { browser('close'); }
}
