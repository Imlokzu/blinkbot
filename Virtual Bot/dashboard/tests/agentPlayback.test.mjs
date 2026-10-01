import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Schema } from '@tiptap/pm/model';
import { EditorState } from '@tiptap/pm/state';
import { editRange, playbackFrame, playbackStages } from '../src/components/editor/agentPlayback.ts';

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { content: 'inline*', group: 'block' },
    heading: { content: 'inline*', group: 'block', attrs: { level: { default: 1 } } },
    text: { group: 'inline' },
    image: { group: 'block', attrs: { src: {} }, atom: true },
    horizontalRule: { group: 'block', atom: true },
    blockquote: { content: 'block+', group: 'block' },
    table: { content: 'row+', group: 'block' },
    row: { content: 'cell+' },
    cell: { content: 'block+' },
  },
  marks: { bold: {} },
});
const p = (text) => schema.node('paragraph', null, text ? schema.text(text) : null);
const doc = (...nodes) => schema.node('doc', null, nodes);

test('agent updates preserve untouched paragraphs between separate edits', () => {
  const previous = doc(p('Old introduction'), p('Keep this paragraph'), p('Old conclusion'));
  const next = doc(p('New introduction'), p('Keep this paragraph'), p('New conclusion'));
  const stages = playbackStages(previous, next);
  assert.equal(stages.length, 2);
  let baseline = previous;
  for (const stage of stages) {
    assert.equal(stage.child(1), previous.child(1));
    const range = editRange(baseline, stage);
    for (let cursor = range.from; cursor <= range.to; cursor++) {
      const frame = playbackFrame(stage, range, cursor);
      frame.check();
      assert.equal(frame.child(1).textContent, 'Keep this paragraph');
    }
    baseline = stage;
  }
  assert.ok(stages.at(-1).eq(next));
});

test('insertions, deletions and formatting end at the exact target document', () => {
  const cases = [
    [doc(p('abc')), doc(p('abXYZc'))],
    [doc(p('abXYZc')), doc(p('abc'))],
    [doc(p('abc')), doc(schema.node('heading', { level: 2 }, schema.text('abc', [schema.mark('bold')])) )],
    [doc(p('')), doc(p('Hello 👋🏼 world'))],
  ];
  for (const [old, next] of cases) {
    const range = editRange(old, next);
    for (let cursor = range.from; cursor <= range.to; cursor++) {
      const frame = playbackFrame(next, range, cursor);
      frame.check();
      assert.equal(frame.textContent.isWellFormed(), true, 'a frame cannot split an emoji');
    }
    assert.ok(playbackFrame(next, range, range.to).eq(next));
  }
  assert.equal(editRange(cases[0][0], cases[0][0]), null);
});

test('formatted table structure and marks survive every typing frame', () => {
  const table = schema.node('table', null, [schema.node('row', null, [
    schema.node('cell', null, [p('Name')]), schema.node('cell', null, [p('Status')]),
  ])]);
  const next = doc(schema.node('heading', null, schema.text('A real note', [schema.mark('bold')])), table);
  const range = editRange(doc(p('')), next);
  for (let cursor = range.from; cursor <= range.to; cursor++) {
    const frame = playbackFrame(next, range, cursor);
    frame.check();
    assert.equal(frame.child(0).type.name, 'heading');
    assert.equal(frame.child(1).type.name, 'table');
    if (frame.child(0).childCount) assert.equal(frame.child(0).child(0).marks[0].type.name, 'bold');
  }
  assert.ok(playbackFrame(next, range, range.to).eq(next));
});

test('embedded images arrive at their cursor position and long edits are bounded', () => {
  const next = doc(p('Before'), schema.node('image', { src: 'scene.excalidraw' }), p('After'));
  const range = editRange(doc(p('')), next);
  assert.equal(playbackFrame(next, range, range.from).childCount, 2);
  assert.ok(playbackFrame(next, range, range.to).eq(next));
  assert.equal(playbackStages(next, next).length, 0);
  assert.equal(playbackStages(doc(...Array.from({ length: 100 }, () => p('old'))), doc(...Array.from({ length: 100 }, () => p('new')))).length, 1);
});

test('unrevealed block embeds keep documents, quotes and table cells valid', () => {
  const image = schema.node('image', { src: 'scene.png' });
  const targets = [
    doc(image),
    doc(schema.node('horizontalRule')),
    doc(schema.node('blockquote', null, image)),
    doc(schema.node('table', null, schema.node('row', null, schema.node('cell', null, image)))),
  ];
  const previous = doc(p(''));
  for (const next of targets) {
    const range = editRange(previous, next);
    let state = EditorState.create({ schema, doc: previous });
    for (let cursor = range.from; cursor <= range.to; cursor++) {
      const frame = playbackFrame(next, range, cursor);
      // A missing required paragraph must not be left for transaction fitting
      // to repair, especially inside a table cell or quote.
      frame.check();
      state = state.apply(state.tr.replaceWith(0, state.doc.content.size, frame.content));
      assert.ok(state.doc.eq(frame), 'the replay transaction must preserve the intended frame');
      if (cursor === range.from) {
        frame.descendants((node) => {
          assert.notEqual(node.type.name, 'image');
          assert.notEqual(node.type.name, 'horizontalRule');
        });
      }
    }
    assert.ok(state.doc.eq(next), 'the final frame must preserve the exact target');
  }
});
