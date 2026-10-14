import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

const session = `blink-map-${process.pid}`;
const origin = process.env.MAP_FIXTURE_ORIGIN || 'http://127.0.0.1:5319';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', code)).data.result;
async function until(code) {
  const deadline = Date.now() + 12000;
  while (!evaluate(code)) {
    if (Date.now() > deadline) throw new Error(`Timed out: ${code}`);
    await setTimeout(100);
  }
}
try {
  browser('open', `${origin}/static/dash/tests/map.fixture.html`);
  browser('set', 'viewport', '1440', '1000');
  await until('document.querySelectorAll(".leaflet-tile-loaded").length > 0');
  assert.equal(evaluate('document.querySelector(".leaflet-tooltip").textContent'), 'Kharkiv');
  assert.ok(evaluate('const r=document.querySelector(".leaflet-tooltip").getBoundingClientRect(); r.width > r.height'), 'a short place name must not wrap one character per line');
  assert.ok(evaluate('document.querySelector(".map-card-attribution a").href === "https://www.openstreetmap.org/copyright"'));
  assert.ok(evaluate('!document.querySelector(".map-card footer") && !document.querySelector(".map-card-status a")'), 'loaded maps have no instruction/coordinate/link footer');
  assert.ok(evaluate('document.getElementById(document.querySelector(".map-card-canvas").getAttribute("aria-describedby")).classList.contains("sr-only")'), 'keyboard help stays available to screen readers');
  browser('click', 'button[aria-label="Zoom in"]');
  await until('document.querySelector("[data-map-card]").dataset.zoom === "7"');
  browser('click', 'button[aria-label="Zoom out"]');
  await until('document.querySelector("[data-map-card]").dataset.zoom === "6"');
  const box = evaluate('document.querySelector(".map-card-canvas").getBoundingClientRect().toJSON()');
  const previous = evaluate('document.querySelector(".leaflet-map-pane").style.transform');
  browser('mouse', 'move', String(Math.round(box.left + box.width * .4)), String(Math.round(box.top + box.height * .4)));
  browser('mouse', 'down');
  browser('mouse', 'move', String(Math.round(box.left + box.width * .6)), String(Math.round(box.top + box.height * .5)));
  browser('mouse', 'up');
  await until(`document.querySelector(".leaflet-map-pane").style.transform !== ${JSON.stringify(previous)}`);
  browser('click', '.map-card-actions button:last-child');
  browser('screenshot', '/tmp/blink-map-desktop.png');
  browser('set', 'viewport', '390', '844');
  evaluate('document.documentElement.dataset.theme = "dark"');
  assert.ok(evaluate('document.documentElement.scrollWidth <= innerWidth'));
  assert.ok(evaluate('var credit=document.querySelector(".map-card-attribution").getBoundingClientRect(); var canvas=document.querySelector(".map-card-canvas").getBoundingClientRect(); credit.left >= canvas.left && credit.right <= canvas.right && credit.top >= canvas.top && credit.bottom <= canvas.bottom'), 'compact credits stay inside the map');
  assert.ok(evaluate('document.querySelector(".map-card").getBoundingClientRect().bottom - document.querySelector(".map-card-canvas").getBoundingClientRect().bottom < 3'), 'the map ends the card');
  browser('screenshot', '/tmp/blink-map-phone.png');
  evaluate('window.mapFixture.setData({...window.mapFixture.initial,title:"<img src=x onerror=alert(1)>"})');
  await until('document.querySelector(".leaflet-tooltip")?.textContent.includes("<img")');
  assert.equal(evaluate('document.querySelectorAll(".leaflet-tooltip img").length'), 0);
  evaluate('window.mapFixture.tileState.failed = true');
  evaluate('window.mapFixture.setData({...window.mapFixture.initial,zoom:8})');
  await until('document.querySelector(".map-card [role=alert]") !== null');
  assert.ok(evaluate('document.querySelector(".map-card [role=alert] a").href.startsWith("https://www.openstreetmap.org/")'), 'only errors offer the external-map fallback');
  evaluate('window.mapFixture.tileState.failed = false');
  browser('click', '.map-card [role=alert] button');
  await until('document.querySelectorAll(".leaflet-tile-loaded").length > 0 && !document.querySelector(".map-card [role=alert]")');
  evaluate('window.mapFixture.setStatus("failed")');
  await until('!document.querySelector(".leaflet-container")');
  evaluate('document.documentElement.lang = "uk"; window.mapFixture.setStatus("done")');
  await until('document.querySelector(".map-card-label")?.textContent === "Інтерактивна карта"');
  evaluate('window.mapFixture.setVisible(false)');
  await until('!document.querySelector(".leaflet-container")');
  evaluate('window.mapFixture.setVisible(true)');
  await until('document.querySelectorAll(".leaflet-tile-loaded").length > 0');
  assert.equal(evaluate('performance.getEntriesByType("resource").filter(entry => entry.name.startsWith("https://tile.openstreetmap.org/")).length'), 0);
  console.log('PASS: real Leaflet with local fixture tiles, marker, shared controls, pointer pan, narrow layout, safe labels, failure/retry, locale and replay cleanup; no public tile requests.');
} finally { browser('close'); }
