import assert from 'node:assert/strict';
import test from 'node:test';
import { MAX_EXPORT_DATA_URL_BYTES, pngDataUrl } from '../src/drawingExport.ts';

test('PNG encoding rejects wrong MIME types and empty results before reading them', async () => {
  for (const blob of [
    new Blob(['not a PNG'], { type: 'image/jpeg' }),
    new Blob(['not a PNG'], { type: 'image/svg+xml' }),
    new Blob([], { type: 'image/png' }),
  ]) await assert.rejects(pngDataUrl(blob), { message: 'export_failed' });
});

test('the PNG payload limit includes base64 expansion and its data URL prefix', async () => {
  // The encoded pixels alone fit; the required MIME prefix makes this too large.
  const bytes = new Uint8Array(MAX_EXPORT_DATA_URL_BYTES / 4 * 3);
  await assert.rejects(pngDataUrl(new Blob([bytes], { type: 'image/png' })), { message: 'export_too_large' });
});
