/** Real SSE frames through the chat adapter, with browser-only network fixtures. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
const session = `workbench-writing-test-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
const emit = (name, data) => evaluate(`window.__emit(${JSON.stringify(name)}, ${JSON.stringify(data)})`);
const done = (reply = 'Done') => ({ reply, session_id: 'writing-fixture', emotion: 'idle', mode: 'test', model: 'test', tool_results: [] });
const tool = (id, name, path, content) => ({ call_id: id, tool: name, detail: path,
  input: { path, ...(content === undefined ? {} : { content }) } });
const send = (message) => {
  evaluate(`window.__controller = null; window.__vbotSendMessage(${JSON.stringify(message)})`);
  browser('wait', '--fn', 'window.__controller !== null');
};
try {
  browser('open', `${origin}/dash/#/chat`);
  browser('set', 'viewport', '1440', '960');
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [{ id: 'writing-fixture', title: 'Writing check', count: 1 }] });
  route('**/api/sessions/writing-fixture', { id: 'writing-fixture', messages: [{ role: 'assistant', content: 'An old saved note.', steps: [
    { id: 'old-write', label: 'workspace_write', detail: 'session/old.md', status: 'done', input: { path: 'session/old.md' } },
    { id: 'old-sketch', label: 'workspace_write', detail: 'session/sketch.excalidraw', status: 'done', input: { path: 'session/sketch.excalidraw' } },
  ] }] });
  route('**/api/workspace/info**', { root: '/fixture/workspace', session_path: 'sessions/writing-fixture' });
  route('**/api/brain/models', { models: [{ id: 'test', label: 'Test', context: 200000 }], selected: 'test', thinking: 'high', thinking_levels: ['high'] });
  evaluate("localStorage.setItem('claudeBotLang', 'en'); localStorage.setItem('claudeBotWorkbench', 'open')");
  browser('reload');
  browser('wait', '[data-session-id="writing-fixture"]');
  assert.equal(evaluate('document.querySelector(".workbench") === null'), true, 'a remembered open state must not auto-open on fresh visits');
  browser('click', '[data-session-id="writing-fixture"]');
  browser('wait', '--text', 'An old saved note.');
  assert.equal(evaluate('document.querySelector(".workbench") === null'), true, 'opening saved chat history is not a new write');
  evaluate(`window.__reads = 0; window.__scenePosts = 0; window.__fileText = '# Actual note\\n\\nThis is the real file content being written.';
    const savedFetch = window.fetch;
    window.__frame = (name, data) => 'event: ' + name + '\\ndata: ' + JSON.stringify(data) + '\\n\\n';
    window.__emit = (name, data) => window.__controller.enqueue(new TextEncoder().encode(window.__frame(name, data)));
    window.fetch = async (url, options) => {
      if (String(url).startsWith('/api/chat')) return new Response(new ReadableStream({
        start(controller) { window.__controller = controller; }, cancel() {},
      }), { headers: { 'Content-Type': 'text/event-stream' } });
      if (String(url).includes('/api/workspace/file?')) {
        window.__reads++;
        if (window.__holdRead) await new Promise(resolve => { window.__releaseRead = resolve; });
        return new Response(JSON.stringify({ content: String(url).includes('sketch.excalidraw') ? JSON.stringify({ elements: [] }) : window.__fileText }), { headers: { 'Content-Type': 'application/json' } });
      }
      if (String(url) === '/api/workspace/file' && options?.method === 'POST') {
        window.__scenePosts++;
        return new Response(JSON.stringify({ ok: true }), { headers: { 'Content-Type': 'application/json' } });
      }
      return savedFetch(url, options);
    };`);

  send('Read a note');
  for (const name of ['workspace_read', 'workspace_show']) {
    const data = tool(name, name, 'session/old.md');
    emit('tool_start', data); emit('tool_done', { ...data, result: { ok: true, path: 'session/old.md' } });
  }
  emit('delta', { chunk: 'Read it.' }); emit('done', done('Read it.'));
  browser('wait', '--text', 'Read it.');
  assert.equal(evaluate('document.querySelector(".workbench") === null'), true, 'reading/showing must not auto-open');

  send('Write a note');
  const creating = tool('write-1', 'workspace__workspace_write', 'session/live.md', '# Actual note\n\nThis is the real file content being written.');
  emit('tool_start', creating);
  browser('wait', '[data-agent-file-writing] [data-bot-icon]');
  browser('wait', '--fn', 'document.querySelector(".agent-file-paper .prose-note h1")?.textContent === "Actual note"');
  browser('wait', '.agent-editor-caret');
  assert.equal(evaluate('document.querySelector(".agent-file-paper > pre") === null'), true, 'Markdown is edited as a formatted document');
  assert.equal(evaluate('document.querySelector(".agent-file-paper .ProseMirror").contentEditable'), 'false');
  assert.equal(evaluate('window.__reads'), 0, 'an active write must not fetch a missing or half-written file');
  browser('mouse', 'move', '1200', '350');
  browser('press', 'Escape');
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/agent-writing.png`);
  browser('click', '[data-chat-toolbar] button[aria-label="Hide the workbench"]');
  emit('tool_progress', creating);
  emit('tool_done', { ...creating, result: { ok: true, path: 'sessions/writing-fixture/live.md' } });
  emit('delta', { chunk: 'Created the note.' }); emit('done', done('Created the note.'));
  browser('wait', '--text', 'Created the note.');
  assert.equal(evaluate('document.querySelector(".workbench") === null'), true, 'closing stays respected for the current reply');

  // All events may arrive in one chunk: a successful update still opens.
  send('Update the note');
  evaluate(`window.__fileText = '# Updated actual note\\n\\nThe updated text comes from the confirmed file.';
    const data = { call_id: 'write-1', tool: 'workspace__workspace_write', detail: 'session/live.md', input: { path: 'session/live.md', content: window.__fileText } };
    window.__controller.enqueue(new TextEncoder().encode(
      window.__frame('tool_start', data) + window.__frame('tool_done', { ...data, result: { ok: true, path: 'sessions/writing-fixture/live.md' } })
      + window.__frame('delta', { chunk: 'Updated it.' }) + window.__frame('done', ${JSON.stringify(done('Updated it.'))})
    ));`);
  browser('wait', '--fn', 'document.querySelector(".workbench .prose-note h1")?.textContent === "Updated actual note" && !document.querySelector("[data-agent-file-writing]")');
  assert.equal(evaluate('document.querySelector(".workbench .prose-note h1").textContent'), 'Updated actual note');
  assert.equal(evaluate('document.querySelector(".workbench [data-agent-file-writing]") === null'), true);
  browser('click', '[data-chat-toolbar] button[aria-label="Hide the workbench"]');
  browser('click', '[data-chat-toolbar] button[aria-label="Show the workbench"]');
  browser('wait', '.workbench .prose-note h1');
  assert.equal(evaluate('document.querySelector(".workbench [data-agent-file-writing]") === null'), true, 'manual reopen must not replay old creation');

  // Observe real editor mutations: a replacement selects old prose while the
  // unchanged heading stays in place, then releases the exact confirmed text.
  send('Edit inside the document');
  evaluate(`window.__samples = []; window.__focusBefore = document.activeElement;
    window.__observer = new MutationObserver(() => {
      const paper = document.querySelector('.agent-file-paper .prose-note');
      if (paper) window.__samples.push({ heading: paper.querySelector('h1')?.textContent,
        selected: Boolean(paper.querySelector('.agent-edit-selection')), caret: Boolean(paper.querySelector('.agent-editor-caret')) });
    }); window.__observer.observe(document.querySelector('.workbench'), { childList: true, subtree: true, characterData: true });`);
  const edited = '# Updated actual note\n\nA **polished** document with real formatting.\n\n| Task | Status |\n| --- | --- |\n| Editor | Ready |\n| Drawings | Inline |\n\n- [x] Keep the actual file\n- [ ] Review the next draft';
  evaluate(`window.__fileText = ${JSON.stringify(edited)}`);
  const editing = tool('human-edit', 'workspace_write', 'session/live.md', edited);
  emit('tool_start', editing);
  browser('wait', '.agent-file-paper table');
  browser('wait', '--fn', 'document.querySelector(".agent-file-paper strong")?.textContent === "polished"');
  assert.equal(evaluate('window.__samples.some(sample => sample.selected)'), true, 'old changed text is visibly selected');
  assert.equal(evaluate('window.__samples.every(sample => sample.heading === "Updated actual note")'), true, 'unchanged heading must never be erased or retyped');
  assert.equal(evaluate('document.activeElement === window.__focusBefore'), true, 'agent cursor must not steal composer focus');
  browser('wait', '--fn', 'document.querySelector(".agent-file-paper .prose-note")?.textContent.includes("Review the next draft")');
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/agent-editor.png`);
  emit('tool_done', { ...editing, result: { ok: true, path: 'sessions/writing-fixture/live.md' } });
  emit('delta', { chunk: 'Edited in the document.' }); emit('done', done('Edited in the document.'));
  browser('wait', '--fn', '!document.querySelector("[data-agent-file-writing]") && document.querySelector(".workbench .prose-note strong")?.textContent === "polished"');
  assert.deepEqual(evaluate('document.querySelector(".workbench .prose-note").editor.getJSON()'),
    evaluate(`const editor = document.querySelector('.workbench .prose-note').editor;
      const expected = editor.schema.nodeFromJSON(editor.storage.markdown.manager.parse(${JSON.stringify(edited)})).toJSON();
      // StarterKit adds a final empty paragraph after a table or list.
      expected.content.push({ type: 'paragraph' }); expected;`),
    'playback releases the exact parsed confirmed document');
  assert.equal(evaluate('window.__scenePosts'), 0, 'visual playback must never save or create an unsaved draft');
  evaluate('window.__observer.disconnect()');

  evaluate(`window.__fileText = ${JSON.stringify(edited.replace('real formatting', 'refreshed formatting'))}`);
  browser('click', '.workbench button[aria-label="Reload"]');
  browser('wait', '--fn', 'document.querySelector(".workbench .prose-note")?.textContent.includes("refreshed formatting")');
  browser('click', '[data-chat-toolbar] button[aria-label="Hide the workbench"]');

  // Some adapters omit the write input. Keep the confirmed document visible
  // until the new read resolves, including through the active/done transition.
  send('Update without an input snapshot');
  const withoutInput = tool('missing-input', 'workspace_write', 'session/live.md');
  const beforeRead = evaluate('window.__reads');
  emit('tool_start', withoutInput);
  browser('wait', '.agent-file-paper strong');
  assert.equal(evaluate('document.querySelector(".agent-file-paper strong").textContent'), 'polished');
  assert.equal(evaluate('document.querySelector(".agent-file-paper .prose-note").textContent.includes("refreshed formatting")'), true, 'reopened playback must use the latest confirmed reload regardless of nonce');
  assert.equal(evaluate('window.__reads'), beforeRead);
  evaluate('window.__holdRead = true; window.__heldEditor = document.querySelector(".agent-file-paper .ProseMirror"); true;');
  emit('tool_done', { ...withoutInput, result: { ok: true, path: 'sessions/writing-fixture/live.md' } });
  browser('wait', '--fn', 'Boolean(window.__releaseRead)');
  assert.equal(evaluate('document.querySelector(".agent-file-paper .ProseMirror") === window.__heldEditor'), true, 'confirmation must not remount/replay the editor');
  browser('set', 'viewport', '390', '844');
  browser('click', 'button[aria-label="Show the workbench"]');
  browser('wait', '[role="dialog"] .agent-file-paper .prose-note');
  assert.equal(evaluate('document.documentElement.scrollWidth <= 392'), true, 'phone playback must fit the viewport');
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/agent-editor-phone.png`);
  browser('set', 'viewport', '1440', '960');
  evaluate('window.__holdRead = false; window.__releaseRead();');
  emit('delta', { chunk: 'Confirmed the file.' }); emit('done', done('Confirmed the file.'));
  browser('wait', '--fn', '!document.querySelector("[data-agent-file-writing]") && Boolean(document.querySelector(".workbench .prose-note strong"))');

  // A pending canvas autosave must not overwrite an agent's replacement.
  browser('click', '.workbench nav button[title="session/sketch.excalidraw"]');
  browser('wait', '.workbench .excalidraw canvas');
  send('Replace the drawing');
  evaluate(`const savedTimeout = window.setTimeout; window.setTimeout = (fn, ms, ...args) => savedTimeout(fn, ms === 1200 ? 5000 : ms, ...args);`);
  browser('click', '.workbench .excalidraw');
  browser('press', 'r');
  const canvas = evaluate('document.querySelector(".workbench .excalidraw canvas").getBoundingClientRect().toJSON()');
  browser('mouse', 'move', String(Math.round(canvas.left + 100)), String(Math.round(canvas.top + 160)));
  browser('mouse', 'down');
  browser('mouse', 'move', String(Math.round(canvas.left + 200)), String(Math.round(canvas.top + 230)));
  browser('mouse', 'up');
  assert.equal(evaluate('window.__scenePosts'), 0);
  const replacement = tool('replace-sketch', 'workspace_write', 'session/sketch.excalidraw', '{"elements":[]}');
  emit('tool_start', replacement);
  browser('wait', '[data-agent-file-writing]');
  assert.equal(evaluate('window.__scenePosts'), 0, 'outgoing canvas flush must respect active writer ownership');
  const reads = evaluate('window.__reads');
  const showing = tool('show-sketch', 'workspace_show', 'session/sketch.excalidraw');
  emit('tool_done', { ...showing, result: { shown: 'sessions/writing-fixture/sketch.excalidraw' } });
  browser('wait', '[data-agent-file-writing]');
  assert.equal(evaluate('window.__reads'), reads, 'show cannot enable reads while replacement is active');
  console.log('PASS: editor typing/selection, formatted table/tasks, unchanged prose/focus, missing input, held confirmation/phone, no playback writes, prior navigation and canvas guards');
} catch (error) {
  console.error(browser('errors'));
  if (process.env.CHAT_NAV_SHOTS) browser('screenshot', `${process.env.CHAT_NAV_SHOTS}/writing-failure.png`);
  throw error;
} finally { browser('close'); }
