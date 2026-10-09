import assert from 'node:assert/strict';
import test from 'node:test';
import { documentLink, resourceUrl, workspacePath } from '../src/paths.ts';

const note = 'notes/nested/note.md';

test('bare names are relative to the document without an existence-based root fallback', () => {
  assert.equal(workspacePath('picture.png', note), 'notes/nested/picture.png');
  assert.equal(workspacePath('picture.png', 'note.md'), 'picture.png');
  assert.equal(workspacePath('./images/a.png', note), 'notes/nested/images/a.png');
  assert.equal(workspacePath('./picture.png', note), 'notes/nested/picture.png');
  assert.equal(workspacePath('/workspace/picture.png', note), 'picture.png');
});

test('legacy slash-containing workspace paths keep the existing web meaning', () => {
  for (const path of ['session/notes/old.md', 'sessions/chat-1/notes/old.md', 'notes/shared.md', 'projects/demo/index.html', 'images/a.png']) {
    assert.equal(workspacePath(path, note), path, path);
  }
});

test('owned workspace, file and preview prefixes explicitly select the workspace root', () => {
  for (const prefix of ['workspace', 'file', 'preview']) {
    assert.equal(workspacePath(`/${prefix}/root.png`, note), 'root.png');
    assert.equal(workspacePath(`/${prefix}/notes/plan%20%231%3F.md`, note), 'notes/plan #1?.md');
  }
  assert.equal(workspacePath('picture.png?revision=2#image', note), 'notes/nested/picture.png');
});

test('created links round trip reserved characters and retain the correct parent directory', () => {
  const cases = [
    ['notes/nested/picture.png', note, 'picture.png'],
    ['notes/nested/note.md.drawings/new.excalidraw', note, './note.md.drawings/new.excalidraw'],
    ['notes/nested/images/Flow #1?.excalidraw', note, './images/Flow%20%231%3F.excalidraw'],
    ['notes/shared/Plan #1?.md', note, '/workspace/notes/shared/Plan%20%231%3F.md'],
    ['notes/nested-sibling/file.md', note, '/workspace/notes/nested-sibling/file.md'],
    ['images/100% ready #1?.png', 'root.md', './images/100%25%20ready%20%231%3F.png'],
  ];
  for (const [path, document, expected] of cases) {
    const link = documentLink(path, document);
    assert.equal(link, expected);
    assert.equal(workspacePath(link, document), path);
    assert.equal(workspacePath(resourceUrl(path), document), path, 'native resource URLs preserve canonical path bytes');
  }
});

test('unowned absolute paths, URLs and encoded traversal never become local resources', () => {
  for (const path of [
    '', '#heading', '../secret', './images/../secret', '%2e%2e/secret', './images/%2E%2E/secret',
    '/workspace/notes/%2e%2e/secret', '/preview/%2e%2e/secret', '/file/%2e%2e/secret',
    '/etc/passwd', '%2Fetc/passwd', '//example.invalid/image.png', '/workspace//secret',
    'images\\secret.png', 'images%5Csecret.png', 'image%00.png', 'image%0A.png', '%ZZ.png',
    'https://example.invalid/image.png', 'HTTP://example.invalid/image.png', 'javascript:alert(1)',
    'data:image/png;base64,AAAA', 'file:///etc/passwd', 'C:\\private\\file.txt',
  ]) assert.equal(workspacePath(path, note), null, path);
});
