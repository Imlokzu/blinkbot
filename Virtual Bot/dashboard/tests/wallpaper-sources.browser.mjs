/** Real media decoding with browser-only API and external-media fixtures. */
import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const run = promisify(execFile);
const session = `wallpaper-sources-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5183';
const path = process.env.DASHBOARD_TEST_PATH || '/static/dash/';
const imageFile = new URL('../src/panels/chat/assets/wallpapers/coast-photo.jpg', import.meta.url);
const videoFile = new URL('../src/panels/chat/assets/wallpapers/stars-video.mp4', import.meta.url);
const imageBytes = readFileSync(imageFile);
const videoBytes = readFileSync(videoFile);
const browser = async (...args) => (await run('agent-browser', ['--session', session, ...args], {
  encoding: 'utf8', maxBuffer: 10 * 1024 * 1024,
})).stdout;
const evaluate = async code => JSON.parse(await browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const saved = () => evaluate('JSON.parse(localStorage.getItem("claudeBotChatAppearance"))');
const clickText = text => browser('find', 'role', 'button', 'click', '--name', text, '--exact');
const wait = condition => browser('wait', '--fn', condition);
const player = () => evaluate(`(() => {
  const video = document.querySelector('.app-wallpaper video');
  return video && { src:video.currentSrc || video.src, muted:video.muted, loop:video.loop,
    paused:video.paused, ready:video.readyState, visible:getComputedStyle(video).visibility };
})()`);
const openSettings = async () => {
  await evaluate("location.hash='#/settings?tab=look'; true");
  await browser('wait', '.chat-appearance-settings');
};
const openChat = async () => {
  await evaluate("location.hash='#/chat'; true");
  await browser('wait', '.chat-thread');
};
const selectPreset = async id => {
  await browser('scrollintoview', `[data-wallpaper-preset="${id}"]`);
  await evaluate(`new Promise(resolve => { const node=document.querySelector('[data-wallpaper-preset="${id}"]');
    let previous=node.getBoundingClientRect().top, stable=0;
    const sample=()=>{ const top=node.getBoundingClientRect().top;stable=Math.abs(top-previous)<0.1 ? stable+1 : 0;
      previous=top;if(stable>=3)resolve(true);else requestAnimationFrame(sample); };requestAnimationFrame(sample); })`);
  await browser('click', `[data-wallpaper-preset="${id}"]`);
  await wait(`JSON.parse(localStorage.getItem('claudeBotChatAppearance'))?.presetId === ${JSON.stringify(id)}
    && document.documentElement.dataset.wallpaper === 'preset'`);
};
const assertGallery = async () => {
  assert.equal(await evaluate('document.querySelectorAll(".chat-appearance-presets img").length'), 4);
  await wait('[...document.querySelectorAll(".chat-appearance-presets img")].every(image => image.complete && image.naturalWidth > 0)');
  assert.equal(await evaluate('document.querySelectorAll(".chat-appearance-settings video").length'), 0,
    'Settings uses still previews rather than four competing video players');
  assert.equal(await evaluate('document.querySelectorAll(".chat-appearance-preset-video").length'), 2);
};

let socket;
let cdpSession;
let nextCommand = 0;
const pending = new Map();
const remoteRequests = [];
const cdp = (method, params = {}, sessionId = cdpSession) => new Promise((resolve, reject) => {
  const id = ++nextCommand;
  pending.set(id, { resolve, reject });
  socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});
const fixtures = {
  '/api/auth/config': { disabled:true },
  '/api/setup': { configured:true, profile:{ configured:true, name:'Fixture', language:'en', persona:'friendly',
    reply_length:'balanced', use_emoji:true, spontaneous:false }, languages:[], personas:[], reply_lengths:[],
    models:[], selected_model:'', keys_set:{} },
  '/api/sessions': { sessions:[] },
  '/api/brain/models': { available:true, selected:'fixture', default:'fixture',
    models:[{ id:'fixture', label:'Fixture', context:200000 }], thinking:'high', thinking_levels:['high'] },
  '/api/models': { models:[], selected:'fixture', active:'fixture' },
  '/api/tts/status': { enabled:false },
  '/api/projects': { projects:[] },
};
const init = `(() => {
  const fixtures = ${JSON.stringify(fixtures)};
  const original = window.fetch.bind(window);
  window.__wallpaperBlockedWrites = JSON.parse(sessionStorage.getItem('wallpaper-blocked-writes') || '[]');
  window.fetch = async (input, options = {}) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.origin);
    const method = String(options.method || input?.method || 'GET').toUpperCase();
    if (!['GET','HEAD'].includes(method)) {
      window.__wallpaperBlockedWrites.push({ path:url.pathname, method });
      sessionStorage.setItem('wallpaper-blocked-writes', JSON.stringify(window.__wallpaperBlockedWrites));
      return Response.json({}, { status:405 });
    }
    if (url.pathname.startsWith('/api/')) return Response.json(fixtures[url.pathname] || {});
    return original(input, options);
  };
})();`;
const emulateMotion = reduce => cdp('Emulation.setEmulatedMedia', {
  features:[{ name:'prefers-reduced-motion', value:reduce ? 'reduce' : 'no-preference' }],
});
const assertFit = async () => {
  const fit = await evaluate(`(() => {
    const section=document.querySelector('.chat-appearance-settings');
    const buttons=[...section.querySelectorAll('.chat-appearance-source button, .chat-appearance-link-actions button')]
      .filter(button => button.getClientRects().length);
    return { overflow:document.documentElement.scrollWidth > innerWidth,
      section:section.getBoundingClientRect().width,
      width:innerWidth, controls:buttons.map(button => ({width:button.getBoundingClientRect().width,
        height:button.getBoundingClientRect().height})),
      input:getComputedStyle(section.querySelector('.chat-appearance-link > input')).fontSize };
  })()`);
  assert.equal(fit.overflow, false, 'wallpaper controls cannot overflow a narrow phone');
  assert.ok(fit.section <= fit.width);
  assert.ok(fit.controls.every(box => box.width >= 44 && box.height >= 44), 'visible source controls retain 44px targets');
  if (fit.width < 760) assert.equal(fit.input, '16px', 'URL input cannot trigger phone browser zoom');
};

try {
  execFileSync('osascript', ['-e', 'set volume output muted true']);
  await browser('open', 'about:blank');
  await browser('set', 'viewport', '1280', '900');
  socket = new WebSocket((await browser('get', 'cdp-url')).trim());
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once:true });
    socket.addEventListener('error', reject, { once:true });
  });
  socket.addEventListener('message', async event => {
    const message = JSON.parse(event.data);
    if (message.method === 'Fetch.requestPaused') {
      const request=message.params;
      const url=new URL(request.request.url);
      remoteRequests.push(url.href);
      const unavailable=url.pathname === '/missing.jpg';
      const nonMedia=url.pathname === '/non-media.jpg';
      const bytes=unavailable ? Buffer.alloc(0) : nonMedia ? Buffer.from('A media decoding failure fixture.')
        : url.pathname.endsWith('.mp4') ? videoBytes : imageBytes;
      await cdp('Fetch.fulfillRequest', { requestId:request.requestId, responseCode:unavailable ? 404 : 200,
        responseHeaders:[{name:'Content-Type',value:nonMedia ? 'text/plain' : url.pathname.endsWith('.mp4') ? 'video/mp4' : 'image/jpeg'},
          {name:'Content-Length',value:String(bytes.length)}, {name:'Accept-Ranges',value:'bytes'},
          {name:'Cache-Control',value:'no-store'}], body:bytes.toString('base64'),
      }, message.sessionId);
      return;
    }
    const task=pending.get(message.id);
    if (!task) return;
    pending.delete(message.id);
    if (message.error) task.reject(new Error(JSON.stringify(message.error)));
    else task.resolve(message.result);
  });
  const target=(await cdp('Target.getTargets')).targetInfos.find(target => target.type === 'page' && target.url === 'about:blank');
  assert.ok(target, 'fixture setup must stay inside its own browser session');
  cdpSession=(await cdp('Target.attachToTarget', { targetId:target.targetId, flatten:true })).sessionId;
  await cdp('Page.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source:init });
  await cdp('Fetch.enable', { patterns:[{ urlPattern:'https://wallpaper.fixture.test/*', requestStage:'Request' }] });
  await browser('open', `${origin}${path}#/chat`);
  await evaluate("localStorage.setItem('claudeBotLang','en');localStorage.setItem('claudeBotTheme','light');localStorage.removeItem('claudeBotChatAppearance'); true");
  await browser('reload');
  await browser('wait', '.chat-thread');
  await openSettings();
  await assertGallery();
  const initial=await saved();
  await clickText('My files');
  assert.deepEqual(await saved(), initial, 'browsing sources must not change the active wallpaper');
  await clickText('Link');
  assert.deepEqual(await saved(), initial);
  await clickText('Ready backgrounds');
  for (const id of ['coast-photo','forest-photo']) {
    await selectPreset(id);
    assert.equal(await evaluate(`document.querySelector('[data-wallpaper-preset="${id}"]').getAttribute('aria-pressed')`), 'true');
    assert.equal(await evaluate('document.querySelectorAll(".app-wallpaper video").length'), 0);
    assert.match(await evaluate('document.querySelector(".app-wallpaper").style.backgroundImage'), new RegExp(id));
  }
  for (const id of ['clouds-video','stars-video']) {
    await selectPreset(id);
    await assertGallery();
    await openChat();
    await wait('document.querySelector(".app-wallpaper video")?.paused === false && document.querySelector(".app-wallpaper video")?.readyState >= 2');
    assert.equal(await evaluate('document.querySelectorAll("video").length'), 1);
    const state=await player();
    assert.equal(state.muted && state.loop, true);
    assert.match(state.src, new RegExp(id));
    await browser('reload');
    await wait('document.querySelector(".app-wallpaper video")?.paused === false');
    assert.equal((await saved()).presetId, id, 'preset selection survives reload');
    await openSettings();
    await wait('document.querySelector(".app-wallpaper video")?.paused === true');
  }
  await clickText('Entire app');
  assert.equal((await saved()).targets.length, 5);
  await wait('document.querySelector(".app-wallpaper video")?.paused === false');
  await clickText('Selected areas');
  assert.deepEqual((await saved()).targets, ['chat']);
  await wait('document.querySelector(".app-wallpaper video")?.paused === true');
  await openChat();
  await emulateMotion(true);
  await wait('document.querySelector(".app-wallpaper video")?.paused === true');
  assert.equal((await player()).visible, 'hidden');
  assert.match(await evaluate('document.querySelector(".app-wallpaper").style.backgroundImage'), /stars-video-poster/,
    'reduced motion reveals the bundled still poster');
  await emulateMotion(false);
  await wait('document.querySelector(".app-wallpaper video")?.paused === false');

  await openSettings();
  await clickText('My files');
  await browser('upload', '.chat-appearance-settings input[type="file"][accept^="image/"]', fileURLToPath(imageFile));
  await wait('document.documentElement.dataset.wallpaper === "custom"');
  const ownImage=(await saved()).image;
  assert.match(ownImage, /^data:image\/jpeg;base64,/);
  await browser('upload', '.chat-appearance-settings input[type="file"][accept^="video/"]', fileURLToPath(videoFile));
  await wait('document.documentElement.dataset.wallpaper === "video"');
  const ownVideo=(await saved()).videoId;
  assert.match(ownVideo, /^wallpaper-/);
  await clickText('Ready backgrounds');
  assert.equal((await saved()).videoId, ownVideo);
  await selectPreset('coast-photo');
  await clickText('My files');
  await clickText('Your image');
  assert.equal((await saved()).image, ownImage);
  assert.equal((await saved()).background, 'custom');
  await clickText('Your video');
  assert.equal((await saved()).videoId, ownVideo);
  assert.equal((await saved()).background, 'video');

  await clickText('Link');
  const remoteImage='https://wallpaper.fixture.test/photo.jpg?fixture=1';
  await browser('fill', '.chat-appearance-link > input', remoteImage);
  assert.equal(remoteRequests.length, 0, 'typing a URL must not make a remote request before Apply');
  await clickText('Use link');
  await wait('document.documentElement.dataset.wallpaper === "remote"');
  assert.equal((await saved()).sourceUrl, remoteImage);
  assert.equal(await evaluate('document.querySelectorAll(".app-wallpaper video").length'), 0);
  assert.equal(await evaluate(`new Promise(resolve => { const image=new Image();image.onload=()=>resolve(image.naturalWidth > 0);image.onerror=()=>resolve(false);image.src=${JSON.stringify(remoteImage)}; })`), true,
    'direct image URLs decode through the browser media fixture');
  const beforeInvalid=await saved();
  for (const unavailable of ['https://wallpaper.fixture.test/missing.jpg','https://wallpaper.fixture.test/non-media.jpg']) {
    await browser('fill', '.chat-appearance-link > input', unavailable);
    await clickText('Use link');
    await browser('wait', '.chat-appearance-link [role="alert"]');
    assert.deepEqual(await saved(), beforeInvalid, 'missing or undecodable direct media must retain the previous saved choice');
  }
  const credentialURL = new URL('https://wallpaper.fixture.test/image.jpg');
  credentialURL.username = 'synthetic-fixture-user';
  credentialURL.password = 'synthetic-fixture-password';
  for (const invalid of ['javascript:alert(1)','data:image/png;base64,AA==','ftp://wallpaper.fixture.test/image.jpg',credentialURL.href]) {
    await browser('fill', '.chat-appearance-link > input', invalid);
    await clickText('Use link');
    await browser('wait', '.chat-appearance-link [role="alert"]');
    assert.deepEqual(await saved(), beforeInvalid, 'invalid URLs must retain the previous saved choice');
    assert.equal(await evaluate('document.querySelector(".chat-appearance-link > input").getAttribute("aria-invalid")'), 'true');
  }
  await browser('fill', '.chat-appearance-link > input', 'https://wallpaper.fixture.test/loop.mp4');
  await clickText('Video');
  await clickText('Use link');
  await wait(`JSON.parse(localStorage.getItem("claudeBotChatAppearance"))?.sourceType === "video"
    && JSON.parse(localStorage.getItem("claudeBotChatAppearance"))?.sourceUrl === "https://wallpaper.fixture.test/loop.mp4"`);
  await openChat();
  await wait('document.querySelector(".app-wallpaper video")?.readyState >= 2 && document.querySelector(".app-wallpaper video")?.paused === false');
  assert.equal((await player()).muted && (await player()).loop, true);
  assert.equal((await saved()).sourceType, 'video');
  assert.equal((await saved()).image, ownImage);
  assert.equal((await saved()).videoId, ownVideo, 'remote sources must preserve both stored personal files');
  await browser('reload');
  await wait('document.querySelector(".app-wallpaper video")?.paused === false');
  assert.equal((await saved()).background, 'remote');
  await emulateMotion(true);
  await wait('document.querySelector(".app-wallpaper video")?.paused === true');
  assert.equal((await player()).visible, 'hidden');
  assert.match(await evaluate('document.querySelector(".app-wallpaper").style.backgroundImage'), /chat-reference-sky/,
    'a direct video retains a local still when motion is reduced');
  await emulateMotion(false);

  for (const [width,locale] of [[320,'uk'],[390,'en']]) {
    await browser('set', 'viewport', String(width), '844');
    await evaluate(`localStorage.setItem('claudeBotLang',${JSON.stringify(locale)});true`);
    await browser('reload');
    await openSettings();
    await assertFit();
    const beforeBrowsing=await saved();
    await clickText(locale === 'uk' ? 'Готові фони' : 'Ready backgrounds');
    await assertGallery();
    await assertFit();
    await browser('screenshot', join(tmpdir(), `${session}-${width}-${locale}.png`));
    assert.deepEqual(await saved(), beforeBrowsing, 'ready-gallery browsing keeps the current external source');
    await browser('focus', '[data-wallpaper-preset="forest-photo"]');
    await browser('press', 'Enter');
    assert.equal((await saved()).presetId, 'forest-photo', 'keyboard activation selects the actual focused preset');
    assert.equal(await evaluate('document.activeElement.matches("[data-wallpaper-preset=\\"forest-photo\\"]")'), true);
    await clickText(locale === 'uk' ? 'Посилання' : 'Link');
    await assertFit();
    await browser('fill', '.chat-appearance-link > input', 'not a media URL');
    await browser('press', 'Enter');
    await browser('wait', '.chat-appearance-link [role="alert"]');
    assert.equal((await saved()).presetId, 'forest-photo');
    assert.equal(await evaluate('document.activeElement.matches(".chat-appearance-link > input")'), true,
      'invalid keyboard submission keeps focus where the URL can be fixed');
    assert.equal(await evaluate('document.documentElement.lang.startsWith("uk")'), locale === 'uk');
  }
  assert.ok(remoteRequests.some(url => url.includes('photo.jpg')) && remoteRequests.some(url => url.includes('loop.mp4')));
  assert.deepEqual(await evaluate('window.__wallpaperBlockedWrites'), [], 'appearance changes never write to backend, chat, models or speech');
  console.log('Wallpaper sources browser checks passed (presets, personal files, direct links, phones, motion and persistence).');
} catch (failure) {
  console.error(await evaluate(`(() => {
    const saved=JSON.parse(localStorage.getItem('claudeBotChatAppearance'));
    return { href:location.href, background:document.documentElement.dataset.wallpaper,
      preset:saved?.presetId, source:saved?.sourceType,
      alerts:[...document.querySelectorAll('[role="alert"]')].map(node=>node.textContent),
      gallery:[...document.querySelectorAll('[data-wallpaper-preset]')].map(node=>{
        const r=node.getBoundingClientRect();return {id:node.dataset.wallpaperPreset,disabled:node.disabled,
          top:r.top,bottom:r.bottom,hit:document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest('button')?.dataset.wallpaperPreset};}),
      pressed:[...document.querySelectorAll('.chat-appearance-source button')].map(node=>({label:node.textContent,pressed:node.getAttribute('aria-pressed')})) };
  })()`).catch(() => 'Browser diagnostic unavailable'));
  throw failure;
} finally {
  socket?.close();
  await browser('close').catch(() => undefined);
}
