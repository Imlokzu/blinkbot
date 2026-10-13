import assert from 'node:assert/strict';
import test from 'node:test';
import { mapData, mapLink } from '../src/panels/chat/mapData.ts';

const result = { ok: true, ui: { version: 1, kind: 'map', id: 'a'.repeat(32), data: { title: 'Kharkiv', latitude: 49.9935, longitude: 36.2304, zoom: 6 } } };
const step = { id: 'call', label: 'tools__show_map', status: 'done', detail: '', result };

test('map receipts replay from all supported transport shapes', () => {
  for (const wrapped of [result, { result }, { structuredContent: result }, { content: [{ type: 'text', text: JSON.stringify(result) }] }]) {
    const data = mapData({ ...step, result: wrapped });
    assert.equal(data.latitude, 49.9935);
    assert.equal(mapLink(data), 'https://www.openstreetmap.org/?mlat=49.9935&mlon=36.2304#map=6/49.9935/36.2304');
  }
});

test('unconfirmed maps, failed wrappers and invalid coordinates do not render', () => {
  for (const status of ['active', 'failed', 'interrupted']) assert.equal(mapData({ ...step, status }), null);
  assert.equal(mapData({ ...step, result: { isError: true, result } }), null);
  assert.equal(mapData({ ...step, label: 'image_search' }), null);
  for (const invalid of [{ latitude: NaN }, { longitude: 181 }, { latitude: '49.9' }, { zoom: 19 }, { zoom: 6.5 }, { title: '' }]) {
    assert.equal(mapData({ ...step, result: { ...result, ui: { ...result.ui, data: { ...result.ui.data, ...invalid } } } }), null);
  }
});
