import assert from 'node:assert/strict';
import test from 'node:test';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkMath from 'remark-math';
import katex from 'katex';
import { mathPreprocess, mathOptions, remarkDisplayMath } from '../src/panels/chat/math.ts';

function nodes(source) {
  const processor = unified().use(remarkParse).use(remarkMath).use(remarkDisplayMath);
  const text = mathPreprocess(source);
  const tree = processor.runSync(processor.parse(text), text);
  const result = [];
  const visit = node => { result.push(node); node.children?.forEach(visit); };
  visit(tree);
  return result;
}

test('inline and display math include the common LaTeX bracket spellings', () => {
  const parsed = nodes(String.raw`Half $\frac{1}{2}$ and \(x^2\).

\[\int_0^1 x^2 dx\]

$$
\sqrt{9}
$$`);
  assert.equal(parsed.filter(node => node.type === 'inlineMath').length, 2);
  assert.equal(parsed.filter(node => node.type === 'math').length, 2);
});

test('currency, inline code and fenced code remain literal', () => {
  const source = 'Pay $5 or $10.\n\n`$x^2$`\n\n```latex\n\\[x+y\\]\n```';
  const parsed = nodes(source);
  assert.equal(parsed.filter(node => ['math', 'inlineMath'].includes(node.type)).length, 0);
  assert.equal(parsed.find(node => node.type === 'inlineCode').value, '$x^2$');
  assert.equal(parsed.find(node => node.type === 'code').value, '\\[x+y\\]');
});

test('native web math produces real fraction, root and matrix markup', () => {
  for (const latex of [String.raw`\frac{1}{2}`, String.raw`\sqrt{9}`, String.raw`\begin{pmatrix}1&2\\3&4\end{pmatrix}`]) {
    const html = katex.renderToString(latex, { ...mathOptions, output: 'htmlAndMathml' });
    assert.match(html, /class="katex"/);
    assert.match(html, /<math /);
    assert.doesNotMatch(html, /katex-error/);
  }
});

test('untrusted links and malformed syntax cannot become executable HTML', () => {
  const html = katex.renderToString(String.raw`\href{javascript:alert(1)}{click}`, { ...mathOptions, throwOnError: false });
  assert.doesNotMatch(html, /href="javascript:|onclick=/);
  const invalid = katex.renderToString(String.raw`\frac{`, { ...mathOptions, throwOnError: false });
  assert.match(invalid, /katex-error/);
});


test('an unfinished display block remains source until its closing delimiter arrives', () => {
  const before = '$$\n' + String.raw`\frac{1}{2}`;
  assert.equal(nodes(before).filter(node => ['math', 'inlineMath'].includes(node.type)).length, 0);
  assert.ok(nodes(before).some(node => node.type === 'text' && node.value === before));
  assert.equal(nodes(before + '\n$$').filter(node => node.type === 'math').length, 1);
});


test('complete display formulas stay rendered inside blockquotes', () => {
  const quoted = '> $$\n> x^2\n> $$';
  assert.equal(nodes(quoted).filter(node => node.type === 'math').length, 1);
  assert.equal(nodes('> > $$\n> > x^2\n> > $$').filter(node => node.type === 'math').length, 1);
  assert.equal(nodes('$$\nx^2\n> $$').filter(node => node.type === 'math').length, 0);
});
