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
    options: [{ label: 'PDF', description: '' }, { label: 'Markdown', description: 'Easy to edit' }],
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

test('deduplicates option labels before rendering keyed buttons', () => {
  const data = interactiveToolData({
    id: 'choice-duplicates', label: 'show_choice', status: 'done', detail: '',
    input: { title: 'Pick one', options: ['Same', { label: 'Same', description: 'duplicate' }, 'Other'] },
  });
  assert.deepEqual(data?.options, [{ label: 'Same', description: '' }, { label: 'Other', description: '' }]);
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
  assert.equal(interactiveToolData({ id: 'question', label: 'ask_question', status: 'done', detail: '', input: { question: 'Pick' } }), null);
  assert.equal(interactiveToolData({ id: 'todo', label: 'todo_list', status: 'done', detail: '', input: { items: [] } }), null);
  assert.equal(interactiveToolData({ id: 'failed', label: 'ask_question', status: 'failed', detail: '', input: { question: 'Pick', options: ['A'] } }), null);
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
  assert.equal(data?.title.length, 400);
  assert.equal(data?.options.length, 6);
  assert.equal(data?.options[0].description.length, 200);
});
