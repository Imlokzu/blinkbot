import { test } from 'node:test';
import assert from 'node:assert/strict';
import { attachmentInfo, attachmentFormat, isAttachmentImage } from '../src/panels/chat/attachmentInfo.ts';

test('saved upload metadata supports old records and preserves display names', () => {
  const file = attachmentInfo({ url: '/uploads/local-123.pdf', name: 'Project plan.pdf', type: 'application/pdf', size: 4096 });
  assert.equal(file.name, 'Project plan.pdf');
  assert.equal(file.size, 4096);
  assert.equal(attachmentFormat(file), 'PDF');
  assert.equal(isAttachmentImage(file), false);
  assert.equal(attachmentInfo({ url: '/uploads/old-photo.PNG' }).name, 'old-photo.PNG');
  assert.equal(isAttachmentImage(attachmentInfo({ url: '/uploads/old-photo.PNG', type: 'text/plain' })), true, 'the stored suffix determines preview kind');
});

test('attachment previews reject external URLs, malformed records and path escapes', () => {
  for (const value of [null, 'text', {}, { url: 17 }, ...[
    'https://evil.example/photo.png', '//evil.example/photo.png', '/api/settings',
    '/uploads/../notes.md', '/uploads/%2e%2e/notes.md', '/uploads/a/b.png', '/uploads/.', '/uploads/..',
    '/uploads/photo.png?token=secret', 'javascript:alert(1)',
    '/uploads/photo.png\n', '/uploads/photo.png\r', '/uploads/photo.png\u2028',
  ].map(url => ({ url }))]) assert.equal(attachmentInfo(value), null);
});

test('display metadata bounds names and drops invalid byte sizes', () => {
  const file = attachmentInfo({ url: '/uploads/photo.jpg', name: 'Line\nBreak\u0000.jpg', size: -10 });
  assert.equal(file.name, 'Line Break .jpg');
  assert.equal(file.size, undefined);
  assert.equal(attachmentInfo({ url: '/uploads/a.txt', name: 'x'.repeat(500), size: NaN }).name.length, 180);
  assert.equal(attachmentInfo({ url: '/uploads/a.txt', name: '\u0000\n\u007f' }).name, 'a.txt', 'control-only names must still leave a visible file label');
});
