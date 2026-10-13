import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

const session = `intelligent-ui-${process.pid}`;
const origin = process.env.INTELLIGENT_UI_ORIGIN || 'http://127.0.0.1:5319';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = code => JSON.parse(browser('--json', 'eval', code)).data.result;
// agent-browser's snapshot stops at a second opaque srcdoc. Use its own
// isolated CDP connection only for controls inside that generated document.
async function inside(expression) {
  const socket = new WebSocket(browser('get', 'cdp-url').trim());
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let sequence = 0;
  const calls = new Map(), contexts = [];
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id) { calls.get(message.id)?.(message); calls.delete(message.id); }
    if (message.method === 'Runtime.executionContextCreated') contexts.push({ ...message.params.context, session: message.sessionId });
  };
  const send = (method, params = {}, sessionId) => new Promise(resolve => {
    const id = ++sequence; calls.set(id, resolve); socket.send(JSON.stringify({ id, method, params, sessionId }));
  });
  try {
    const targets = (await send('Target.getTargets')).result.targetInfos;
    for (const target of targets.filter(target => target.type === 'iframe' && target.url === 'about:srcdoc')) {
      const sessionId = (await send('Target.attachToTarget', { targetId: target.targetId, flatten: true })).result.sessionId;
      await send('Runtime.enable', {}, sessionId);
    }
    for (const context of contexts.filter(context => context.auxData?.isDefault)) {
      const check = await send('Runtime.evaluate', { contextId: context.id, expression: 'Boolean(document.getElementById("blink-ui-root"))', returnByValue: true }, context.session);
      if (!check.result?.result?.value) continue;
      const result = await send('Runtime.evaluate', { contextId: context.id, expression, returnByValue: true, awaitPromise: true }, context.session);
      if (result.result?.exceptionDetails) throw new Error(JSON.stringify(result.result.exceptionDetails));
      return result.result?.result?.value;
    }
    return null;
  } finally { socket.close(); }
}
function ref(label) {
  const line = browser('snapshot', '-i').split('\n').find(line => line.includes(`"${label}"`) && /ref=e\d+/.test(line));
  assert.ok(line, `Missing control: ${label}`);
  return '@' + line.match(/ref=(e\d+)/)[1];
}
async function until(condition) {
  const deadline = Date.now() + 10000;
  while (!await condition()) {
    if (Date.now() > deadline) throw new Error('Browser condition timed out');
    await setTimeout(100);
  }
}
const ready = () => until(async () => await inside('document.getElementById("share")?.textContent') === '€40.00');
const changeBill = value => inside(`document.getElementById('bill').value = ${JSON.stringify(value)}; document.getElementById('bill').dispatchEvent(new Event('input', {bubbles:true})); true`);
const propose = () => inside('document.getElementById("follow").click(); true');
try {
  browser('open', `${origin}/static/dash/tests/intelligent-ui.fixture.html`);
  browser('set', 'viewport', '1440', '1000');
  await ready();
  assert.equal(await inside('document.querySelectorAll(".wake-slider__bar").length'), 28, 'use the actual Settings WakeSlider');
  assert.equal(await inside('getComputedStyle(document.getElementById("people")).display'), 'none', 'the stock browser range is a hidden backing value');
  await inside('document.querySelector(".wake-slider__handle").dispatchEvent(new KeyboardEvent("keydown", {key:"ArrowRight", bubbles:true})); true');
  await until(async () => await inside('document.getElementById("share").textContent') === '€30.00');
  await inside('document.querySelector(".wake-slider__handle").dispatchEvent(new KeyboardEvent("keydown", {key:"ArrowLeft", bubbles:true})); true');
  await ready();
  const frameBox = evaluate('document.querySelector("[data-intelligent-ui] iframe").getBoundingClientRect().toJSON()');
  const sliderBox = await inside('document.querySelector(".wake-slider__track").getBoundingClientRect().toJSON()');
  const sliderY = String(Math.round(frameBox.top + sliderBox.top + sliderBox.height / 2));
  browser('mouse', 'move', String(Math.round(frameBox.left + sliderBox.left + sliderBox.width * .25)), sliderY);
  browser('mouse', 'down');
  browser('mouse', 'move', String(Math.round(frameBox.left + sliderBox.left + sliderBox.width * .75)), sliderY);
  browser('mouse', 'up');
  await until(async () => Number(await inside('document.getElementById("people").value')) >= 8);
  browser('click', ref('Reset'));
  await ready();
  assert.equal(evaluate('document.body.dataset.compromised'), null);
  assert.match(evaluate('document.querySelector("[data-intelligent-ui] iframe").getAttribute("sandbox")'), /^allow-scripts$/);
  await inside('var field = document.querySelector("[data-blink-control=number] input"); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value").set.call(field,"240"); field.dispatchEvent(new Event("input",{bubbles:true})); true');
  await until(async () => await inside('document.getElementById("share")?.textContent') === '€80.00');
  await propose();
  assert.equal(evaluate('window.intelligentFixture.state.posts.length'), 0, 'a generated button can only propose a draft');
  browser('fill', ref('Continue with the assistant'), 'Explain my edited split.');
  evaluate('window.intelligentFixture.state.busy = true');
  browser('click', ref('Send'));
  await until(() => evaluate('document.body.textContent.includes("still working")'));
  assert.equal(evaluate('document.querySelector(".intelligent-ui-followup textarea").value'), 'Explain my edited split.');
  evaluate('window.intelligentFixture.state.busy = false');
  browser('click', ref('Send'));
  await until(() => evaluate('window.intelligentFixture.state.posts.length === 1'));
  assert.deepEqual(evaluate('window.intelligentFixture.state.posts'), [{ text: 'Explain my edited split.', session: 'intelligent-fixture' }]);
  browser('click', ref('Reset'));
  await ready();
  browser('click', ref('Pause'));
  assert.equal(evaluate('document.querySelectorAll("[data-intelligent-ui] iframe").length'), 0);
  browser('click', ref('Open'));
  await ready();
  evaluate('document.documentElement.dataset.theme = "dark"');
  await changeBill('180');
  await until(async () => await inside('document.getElementById("share")?.textContent') === '€60.00');
  browser('screenshot', '/tmp/blink-intelligent-ui-dark.png');
  browser('set', 'viewport', '390', '844');
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  browser('screenshot', '/tmp/blink-intelligent-ui-phone.png');
  await inside('document.getElementById("people").hidden = true; true');
  await until(async () => await inside('getComputedStyle(document.getElementById("people").nextElementSibling).display') === 'none');
  await inside('document.getElementById("people").hidden = false; document.getElementById("people").step = "any"; true');
  await until(async () => await inside('getComputedStyle(document.getElementById("people").nextElementSibling).display') === 'contents');
  await inside('document.querySelector(".wake-slider__handle").dispatchEvent(new KeyboardEvent("keydown", {key:"ArrowRight", bubbles:true})); true');
  assert.ok(Number(await inside('document.getElementById("people").value')) > 3, 'continuous ranges retain keyboard access');
  await inside('var range = document.getElementById("people"); range.min = "0"; range.max = "0.000009"; range.step = "0.000001"; range.value = "0.000003"; true');
  await until(async () => await inside('document.querySelector(".wake-slider__handle").getAttribute("aria-valuenow")') === '0.000003');
  await inside('document.querySelector(".wake-slider__handle").dispatchEvent(new KeyboardEvent("keydown", {key:"ArrowRight", bubbles:true})); true');
  assert.equal(Number(await inside('document.getElementById("people").value')), .000004);
  await inside('var range = document.getElementById("people"); range.min = "1"; range.max = "12"; range.step = "1"; range.value = "3"; document.getElementById("bill").valueAsNumber = 210; document.getElementById("bill").stepUp(); true');
  await until(async () => await inside('document.querySelector("[data-blink-control=number] input").value') === '211');
  await inside('window.delegatedInputs = 0; document.getElementById("blink-ui-root").addEventListener("input", () => window.delegatedInputs++); var field = document.querySelector("[data-blink-control=number] input"); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value").set.call(field,"180"); field.dispatchEvent(new Event("input",{bubbles:true})); true');
  assert.equal(await inside('window.delegatedInputs'), 1, 'only the original input event reaches model delegation');
  // Existing and dynamically added controls keep their model-authored listeners.
  await inside(`document.getElementById('blink-ui-root').insertAdjacentHTML('beforeend', '<label for="check-fixture">Round totals</label><input id="check-fixture" type="checkbox"><label for="select-fixture">Currency</label><select id="select-fixture"><option value="EUR">Euro</option><option value="CAD" disabled>Unavailable</option><option value="USD">Dollar</option></select>'); window.controlChanges = 0; document.getElementById('check-fixture').addEventListener('change', () => window.controlChanges++); true`);
  await until(async () => await inside('document.querySelectorAll(".squish-switch,.glide-select").length') === 2);
  await inside('document.querySelector(".squish-switch").click(); true');
  await until(async () => await inside('document.getElementById("check-fixture").checked') === true);
  assert.equal(await inside('window.controlChanges'), 1);
  await inside('window.checkboxClicks = 0; document.getElementById("check-fixture").addEventListener("click", () => window.checkboxClicks++); document.querySelector("label[for=check-fixture]").click(); true');
  assert.equal(await inside('window.checkboxClicks'), 1, 'label activation retains the original checkbox click handler');
  assert.equal(await inside('document.getElementById("check-fixture").checked'), false);
  await inside('document.getElementById("check-fixture").checked = false; document.getElementById("people").disabled = true; true');
  await until(async () => await inside('document.querySelector(".squish-switch").getAttribute("aria-checked")') === 'false');
  await inside('document.querySelector(".wake-slider__handle").dispatchEvent(new KeyboardEvent("keydown", {key:"ArrowRight", bubbles:true})); true');
  assert.equal(await inside('document.getElementById("people").value'), '3');
  await inside('document.querySelector(".glide-select__trigger").dispatchEvent(new KeyboardEvent("keydown", {key:"Enter", bubbles:true})); true');
  await until(async () => await inside('document.querySelectorAll(".glide-select__option").length') === 2);
  await inside('document.querySelector(".glide-select__trigger").dispatchEvent(new KeyboardEvent("keydown", {key:"End", bubbles:true})); true');
  await inside('document.querySelector(".glide-select__trigger").dispatchEvent(new KeyboardEvent("keydown", {key:"Enter", bubbles:true})); true');
  await until(async () => await inside('document.getElementById("select-fixture").value') === 'USD');
  await inside('document.getElementById("select-fixture").selectedIndex = 0; true');
  await until(async () => await inside('document.querySelector(".glide-select__label").textContent') === 'Euro');
  await inside('document.getElementById("check-fixture").remove(); document.getElementById("select-fixture").remove(); true');
  await until(async () => await inside('document.querySelectorAll(".squish-switch,.glide-select").length') === 0);
  await inside(`document.getElementById('blink-ui-root').insertAdjacentHTML('beforeend', '<form id="reset-fixture"><label for="reset-range">Resettable</label><input id="reset-range" type="range" min="0" max="10" value="2"></form>'); true`);
  await until(async () => await inside('document.getElementById("reset-range").nextElementSibling?.dataset.blinkControl') === 'range');
  await inside('document.getElementById("reset-range").value = "8"; true');
  await until(async () => await inside('document.getElementById("reset-range").nextElementSibling.querySelector("[role=slider]").getAttribute("aria-valuenow")') === '8');
  await inside('document.getElementById("reset-fixture").reset(); true');
  await until(async () => await inside('document.getElementById("reset-range").nextElementSibling.querySelector("[role=slider]").getAttribute("aria-valuenow")') === '2');
  // A fresh mount replays the saved artifact, not the old iframe's local inputs.
  evaluate('window.intelligentFixture.setVisible(false)');
  await until(() => evaluate('!document.querySelector("[data-intelligent-ui]")'));
  evaluate('window.intelligentFixture.setVisible(true)');
  await ready();
  evaluate('window.intelligentFixture.setStatus("active")');
  await until(() => evaluate('!document.querySelector("iframe")'));
  assert.ok(evaluate('document.body.textContent.includes("Building your interactive answer")'));
  evaluate('window.intelligentFixture.setStatus("done")');
  await ready();
  evaluate('window.intelligentFixture.state.corrupt = true; window.intelligentFixture.reload(); true');
  await until(() => evaluate('document.body.textContent.includes("could not load")'));
  assert.equal(evaluate('document.querySelectorAll("[data-intelligent-ui] iframe").length'), 0);
  assert.ok(evaluate('document.body.textContent.includes("Change the total or group size")'));
  evaluate('window.intelligentFixture.state.corrupt = false');
  browser('click', ref('Try again'));
  await ready();
  await propose();
  evaluate('window.intelligentFixture.setSession("other-conversation")');
  await until(() => evaluate('!document.querySelector(".intelligent-ui-followup textarea")'));
  assert.equal(evaluate('window.intelligentFixture.state.posts.length'), 1);
  evaluate('document.documentElement.lang = "uk"; window.intelligentFixture.setOwner("another-owner")');
  await until(() => evaluate('document.body.textContent.includes("Інтерактивна відповідь")'));
  assert.ok(evaluate('window.intelligentFixture.state.fetches.some(url => url.startsWith("/api/workspace/file?"))'));
  await inside('location.href = "https://example.invalid/sandbox-navigation-probe"; true');
  await until(() => evaluate('document.querySelector("[data-intelligent-ui] [role=alert]") !== null'));
  assert.equal(evaluate('document.querySelectorAll("[data-intelligent-ui] iframe").length'), 0, 'frame navigation must fail closed');
  console.log('PASS: real Thread and Settings React controls, keyboard/disabled/dynamic controls, DOM value/event compatibility, confirmed follow-ups, reset/replay, integrity/retry, session switch, locales/themes, phone layout and blocked navigation.');
} finally {
  browser('close');
}
