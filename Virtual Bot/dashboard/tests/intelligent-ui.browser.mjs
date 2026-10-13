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
  assert.equal(evaluate('document.body.dataset.compromised'), null);
  assert.match(evaluate('document.querySelector("[data-intelligent-ui] iframe").getAttribute("sandbox")'), /^allow-scripts$/);
  await changeBill('240');
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
  console.log('PASS: real Thread rendering, calculator inputs, confirmed follow-ups/busy retention, reset/pause/replay, integrity errors/retry, session switch, both locales/themes, phone overflow and blocked navigation.');
} finally {
  browser('close');
}
