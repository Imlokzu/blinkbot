import assert from 'node:assert/strict';
import test from 'node:test';
import { generationView, imageOnlyDelivery, isImageGeneration } from '../src/panels/chat/imageGenerationState.ts';

const step = { id: 'one', label: 'image_generate', status: 'active', detail: '', input: { prompt: 'A blue crab' } };
const output = { provider: 'codex', images: [{ url: '/uploads/local-123.png', type: 'image/png' }] };

test('generation states follow actual tool outcomes and never time-based refinement', () => {
  assert.equal(isImageGeneration({ ...step, label: 'tools__image_generate' }), true);
  assert.equal(isImageGeneration({ ...step, label: 'image_search' }), false);
  assert.deepEqual(generationView({ ...step, result: output }), { prompt: 'A blue crab', url: '', status: 'generating' });
  assert.equal(generationView({ ...step, status: 'done', result: output }).status, 'complete');
  assert.equal(generationView({ ...step, status: 'done', result: { error: 'codex_login_required', ...output } }).status, 'failed');
  assert.equal(generationView({ ...step, status: 'interrupted', result: output }).url, '');
  assert.equal(generationView({ ...step, status: 'done', result: {} }).status, 'failed');
});

test('native and saved MCP outputs share a private image delivery contract', () => {
  for (const result of [output, JSON.stringify(output), { content: [{ type: 'text', text: JSON.stringify(output) }] }]) {
    assert.equal(generationView({ ...step, status: 'done', result }).url, '/uploads/local-123.png');
  }
  for (const url of ['https://example.com/image.png', '/uploads/../private.png', '/uploads/local-1.png?token=secret', 'javascript:alert(1)', '/uploads/a/b.png']) {
    assert.equal(generationView({ ...step, status: 'done', result: { images: [{ url }] } }).status, 'failed');
  }
  assert.equal(generationView({ ...step, input: null, status: 'done', result: null }).prompt, '');
});

test('only an exact delivered image loses its duplicate bubble; prose and other media remain', () => {
  const delivered = new Set(['/uploads/local-123.png']);
  assert.equal(imageOnlyDelivery('![A crab](/uploads/local-123.png)', delivered), true);
  assert.equal(imageOnlyDelivery('  ![A crab](/uploads/local-123.png)\n', delivered), true);
  for (const text of ['Here it is.\n![A crab](/uploads/local-123.png)', '![Another](/uploads/local-other.png)',
    '[Download](/uploads/local-123.png)', '```md\n![A crab](/uploads/local-123.png)\n```',
    '![A crab](/uploads/local-123.png)\n![Another](/uploads/local-other.png)']) assert.equal(imageOnlyDelivery(text, delivered), false);
});
