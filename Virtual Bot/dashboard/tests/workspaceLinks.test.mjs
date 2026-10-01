import { test } from 'node:test';
import assert from 'node:assert/strict';
import { workspaceLinkPath, safeMarkdownUrl } from '../src/panels/chat/workspaceLinks.ts';

const location = { root: '/Users/owner/workspace', session_path: 'sessions/current' };

test('old absolute Mac links and file URLs open the same session tab', () => {
  for (const href of ['/Users/owner/workspace/sessions/current/plan.md',
    'file:///Users/owner/workspace/sessions/current/plan.md',
    '/preview/sessions/current/plan.md?session_id=current']) {
    assert.equal(workspaceLinkPath(href, location), 'session/plan.md');
  }
});

test('workspace links and document-relative links stay in the app', () => {
  assert.equal(workspaceLinkPath('session/plan.md', location), 'session/plan.md');
  assert.equal(workspaceLinkPath('notes/a%20b.md', location), 'notes/a b.md');
  assert.equal(workspaceLinkPath('plan.md', location), 'session/plan.md');
  assert.equal(workspaceLinkPath('./drawing.excalidraw', location, 'notes/plan.md'), 'notes/drawing.excalidraw');
  for (const href of ['file:///Users/owner/workspace/README.md', '/Users/owner/workspace/README.md', '/preview/README.md', '/file/README.md']) {
    assert.equal(workspaceLinkPath(href, location), 'README.md');
  }
});

test('outside disk paths, remote file hosts and traversal are not workspace links', () => {
  for (const href of ['file:///etc/passwd', 'file://remote/notes/a.md', '/Users/owner/workspace-other/a.md',
    'https://example.com/notes/a.md', '../outside.md', 'notes/%2e%2e/private.md', '//remote/notes/a.md',
    'javascript:alert(1)', 'notes/%00.md', 'notes/%FF.md']) {
    assert.equal(workspaceLinkPath(href, location), null, href);
  }
  assert.equal(safeMarkdownUrl('javascript:alert(1)'), '');
  assert.equal(safeMarkdownUrl('data:text/html,test'), '');
  assert.equal(safeMarkdownUrl('file:///Users/owner/workspace/a.md'), 'file:///Users/owner/workspace/a.md');
});
