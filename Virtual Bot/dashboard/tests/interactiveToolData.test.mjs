import assert from 'node:assert/strict';
import test from 'node:test';
import { interactiveToolData } from '../src/panels/chat/interactiveToolData.ts';

test('normalizes a question tool into safe choice data', () => {
  const data = interactiveToolData({
    id: 'question-1', label: 'tools__ask_question', status: 'done', detail: '',
    input: { question: 'Which format?', options: ['PDF', { label: 'Markdown', description: 'Easy to edit' }], allow_custom: true },
  });
  assert.deepEqual(data, {
    kind: 'question',
    title: 'Which format?',
    options: [{ label: 'PDF', description: '' }, { label: 'Markdown', description: '' }],
    allowCustom: true,
  });
});

test('normalizes a choice tool and drops malformed options', () => {
  const data = interactiveToolData({
    id: 'choice-1', label: 'show_choice', status: 'done', detail: '',
    input: { title: 'Pick a direction', options: [null, {}, { label: 'Build', description: 3 }, '  Review  '] },
  });
  assert.deepEqual(data, {
    kind: 'choice',
    title: 'Pick a direction',
    options: [{ label: 'Build', description: '' }, { label: 'Review', description: '' }],
    allowCustom: false,
  });
});

test('legacy history preserves the exact published options', () => {
  const data = interactiveToolData({
    id: 'choice-duplicates', label: 'show_choice', status: 'done', detail: '',
    input: { title: 'Pick one', options: ['Same', { label: 'Same', description: 'duplicate' }, 'Other'] },
  });
  assert.deepEqual(data?.options, [{ label: 'Same', description: '' }, { label: 'Same', description: 'duplicate' }, { label: 'Other', description: '' }]);
});

test('normalizes a todo list and preserves checked state', () => {
  const data = interactiveToolData({
    id: 'todo-1', label: 'tools__todo_list', status: 'done', detail: '',
    input: { title: 'Release', items: ['Test', { text: 'Ship', done: true }, { text: '' }, 4] },
  });
  assert.deepEqual(data, {
    kind: 'todo',
    title: 'Release',
    items: [{ text: 'Test', done: false }, { text: 'Ship', done: true }],
  });
});

test('ignores unrelated tools and incomplete payloads', () => {
  assert.equal(interactiveToolData({ id: 'search', label: 'web_search', status: 'done', detail: '', input: {} }), null);
  assert.equal(interactiveToolData({ id: 'question', label: 'ask_question', status: 'done', detail: '', input: { question: 'Pick', allow_custom: false } }), null);
  assert.equal(interactiveToolData({ id: 'todo', label: 'todo_list', status: 'done', detail: '', input: { items: [] } }), null);
  assert.equal(interactiveToolData({ id: 'failed', label: 'ask_question', status: 'failed', detail: '', input: { question: 'Pick', options: ['A'] } }), null);
});

test('valid free-text questions survive history restoration in both locales', () => {
  for (const question of ['What is the name?', 'Як назвати?']) {
    for (const options of [undefined, []]) {
      const data = interactiveToolData({ id: 'custom', label: 'ask_question', status: 'done', detail: '', input: { question, options } });
      assert.equal(data?.kind, 'question');
      assert.equal(data?.title, question);
      assert.equal(data?.allowCustom, true);
      assert.deepEqual(data?.options, []);
    }
  }
});

test('bounds model-controlled card content', () => {
  const data = interactiveToolData({
    id: 'bounded', label: 'show_choice', status: 'done', detail: '',
    input: {
      title: 'x'.repeat(600),
      options: Array.from({ length: 20 }, (_, index) => ({ label: `option-${index}`, description: 'y'.repeat(400) })),
    },
  });
  assert.equal(data?.kind, 'choice');
  assert.equal(data?.title.length, 160);
  assert.equal(data?.options.length, 6);
  assert.equal(data?.options[0].description.length, 200);
});

test('canonical receipts control displayed values and stable option identity', () => {
  const ui = { version: 1, id: 'receipt', kind: 'choice', data: { title: 'Pick', options: [{ id: 'option-0', label: 'a'.repeat(80), description: 'Canonical' }] } };
  const step = { id: 'call', label: 'show_choice', status: 'done', detail: '', input: { title: 'Wrong', options: ['wrong'] }, result: { ok: true, ui } };
  for (const result of [{ ui }, { content: [{ type: 'text', text: JSON.stringify({ result: { ui } }) }] }]) {
    const data = interactiveToolData({ ...step, result });
    assert.equal(data.id, 'receipt');
    assert.equal(data.title, 'Pick');
    assert.deepEqual(data.options, ui.data.options);
  }
  assert.equal(interactiveToolData({ ...step, result: { ui: { ...ui, version: 2 } } }), null);
});

test('canonical checklist text preserves all backend characters', () => {
  const data = interactiveToolData({ id: 'call', label: 'todo_list', status: 'done', detail: '', result: { ui: {
    version: 1, id: 'receipt', kind: 'todo', data: { title: '', items: [{ id: 'item-0', text: 'b'.repeat(180), done: false }] },
  } } });
  assert.equal(data.items[0].text.length, 180);
  assert.equal(data.items[0].id, 'item-0');
});

test('legacy normalization mirrors handler bounds and whitespace', () => {
  const data = interactiveToolData({ id: 'call', label: 'show_choice', status: 'done', detail: '', input: { title: '  Pick\n one  ', options: [{ label: '🙂'.repeat(100), description: '  Easy\n to edit  ' }] } });
  assert.equal(data.title, 'Pick one');
  assert.equal(Array.from(data.options[0].label).length, 80);
  assert.equal(data.options[0].description, 'Easy to edit');
});
