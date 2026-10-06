import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

// Start an isolated Vite server first; this fixture never calls the bot API.
const origin = process.env.MATH_FIXTURE_ORIGIN || 'http://127.0.0.1:5308';
const session = 'claude-math-check';
function browser(...args) {
  const raw = execFileSync('agent-browser', ['--session', session, '--json', ...args], { encoding: 'utf8' });
  const result = JSON.parse(raw);
  if (!result.success) throw new Error(JSON.stringify(result));
  return result.data;
}
const evaluate = source => browser('eval', source).result;
async function until(source) {
  const deadline = Date.now() + 10000;
  while (!evaluate(source)) {
    if (Date.now() > deadline) throw new Error(`Timed out: ${source}`);
    await setTimeout(50);
  }
}
async function setText(text, count) {
  evaluate(`window.mathFixture.setText(${JSON.stringify(text)}); true`);
  await until(`document.querySelector('main')?.dataset.source === ${JSON.stringify(text)} && document.querySelectorAll('.katex').length === ${count}`);
}
try {
  browser('open', `${origin}/static/dash/tests/math.fixture.html`);
  await until("document.querySelectorAll('.katex').length === 5");
  assert.equal(evaluate("document.querySelectorAll('.katex-display').length"), 3);
  assert.equal(evaluate("document.querySelectorAll('.markdown-table-scroll .katex').length"), 1);
  assert.equal(evaluate("document.body.textContent.includes('Price $5 or $10.')"), true);
  assert.equal(evaluate("[...document.querySelectorAll('code')].some(node => node.textContent === '$x^2$')"), true);
  await setText(String.raw`Before \(\frac{1}{2}\) after.

\[\sqrt{9}\]`, 2);
  assert.equal(evaluate("document.querySelectorAll('.katex-display').length"), 1);
  await setText('Code: `$x^2$`. Price $5 or $10.', 0);
  await setText('Pending $' + String.raw`\frac{1}{2}`, 0);
  await setText('Pending $' + String.raw`\frac{1}{2}` + '$', 1);
  await setText('$$\n' + String.raw`\frac{1}{2}`, 0);
  await setText('$$\n' + String.raw`\frac{1}{2}` + '\n$$', 1);
  await setText('$$' + Array.from({ length: 80 }, (_, i) => `a_{${i}}`).join('+') + '$$', 1);
  await until("document.querySelector('.katex-display')?.scrollWidth > document.querySelector('.katex-display')?.clientWidth");
  assert.equal(evaluate('document.documentElement.scrollWidth <= window.innerWidth'), true);
  await setText(String.raw`$\href{javascript:window.mathAttack=true}{click}$`, 1);
  await until("document.querySelector('.katex')?.textContent.includes('javascript:window.mathAttack') === true");
  assert.equal(evaluate("document.querySelector('a[href^=\"javascript:\"]') === null && window.mathAttack !== true"), true);
  evaluate('window.mathFixture.setText(window.mathFixture.example); true');
  await until("document.querySelectorAll('.katex').length === 5");
  browser('screenshot', '/tmp/claude-math-web-final.png');
  console.log('Math browser fixture passed: formulas, tables, currency, code, partial text, overflow and link safety.');
} finally {
  browser('close');
}
