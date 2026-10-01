import { useEffect, useRef } from 'react';
import { EditorContent, Extension, useEditor } from '@tiptap/react';
import { Plugin, PluginKey, TextSelection } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { t } from '@/locales/workbench';
import { DocumentWorkspace } from './DocumentImage';
import { NoteEditorToolbar } from './NoteEditorToolbar';
import { noteExtensions } from './noteExtensions';
import { editRange, playbackFrame, playbackStages } from './agentPlayback';
import './note-editor.css';

type Cursor = { from: number; to: number; selecting: boolean } | null;
const cursorKey = new PluginKey<Cursor>('agent-document-cursor');
const AgentCursor = Extension.create({
  name: 'agentDocumentCursor',
  addProseMirrorPlugins() {
    return [new Plugin<Cursor>({
      key: cursorKey,
      state: { init: () => null, apply: (tr, state) => tr.getMeta(cursorKey) !== undefined ? tr.getMeta(cursorKey) : state },
      props: {
        decorations(state) {
          const cursor = cursorKey.getState(state);
          if (!cursor) return DecorationSet.empty;
          const size = state.doc.content.size;
          const from = Math.min(cursor.from, size);
          const to = Math.min(cursor.to, size);
          const decorations = from < to ? [Decoration.inline(from, to, { class: cursor.selecting ? 'agent-edit-selection' : 'agent-edit-insert' })] : [];
          decorations.push(Decoration.widget(to, () => {
            const caret = document.createElement('span');
            caret.className = 'agent-editor-caret';
            caret.contentEditable = 'false';
            caret.setAttribute('aria-hidden', 'true');
            const flag = document.createElement('span');
            flag.className = 'agent-editor-cursor-label';
            flag.textContent = t('wb.agentCursor');
            caret.append(flag);
            return caret;
          }, { side: -1, key: 'agent-caret' }));
          return DecorationSet.create(state.doc, decorations);
        },
      },
    })];
  },
});

/** A visual replay of actual file edits; transactions never enter user history. */
export function AgentNoteEditor({ content, previous = '', busy, onRevealed, workspace }: {
  content: string; previous?: string; busy: boolean; onRevealed?: () => void;
  workspace?: React.ContextType<typeof DocumentWorkspace>;
}) {
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)');
  const done = useRef(onRevealed);
  done.current = onRevealed;
  const finished = useRef(false);
  const notified = useRef(false);
  const busyRef = useRef(busy);
  busyRef.current = busy;
  const editor = useEditor({
    extensions: [...noteExtensions(), AgentCursor],
    content: previous, contentType: 'markdown', editable: false, immediatelyRender: false,
    editorProps: { attributes: { class: 'prose-note min-h-full outline-none' } },
  });
  useEffect(() => {
    if (!editor) return;
    finished.current = false;
    notified.current = false;
    const next = editor.schema.nodeFromJSON(editor.storage.markdown.manager.parse(content));
    const stages = playbackStages(editor.state.doc, next);
    let frame = 0;
    let stage = 0;
    let started = 0;
    let old = editor.state.doc;
    const duration = Math.min(4200, Math.max(1100, content.length * 3)) / Math.max(1, stages.length);
    const paint = (doc: typeof next, cursor: Cursor) => {
      const tr = editor.state.tr;
      if (!tr.doc.eq(doc)) tr.replaceWith(0, tr.doc.content.size, doc.content);
      const pos = Math.min(cursor?.to ?? 1, tr.doc.content.size);
      tr.setSelection(TextSelection.near(tr.doc.resolve(pos)));
      const decoration = cursor ? { ...cursor, to: cursor.selecting ? cursor.to : tr.selection.head } : null;
      editor.view.dispatch(tr.setMeta(cursorKey, decoration).setMeta('preventUpdate', true).setMeta('addToHistory', false));
      const caret = editor.view.dom.querySelector('.agent-editor-caret');
      // Follow only within the document scroll area; never scroll the chat/page.
      const scroller = editor.view.dom.closest('.note-editor-content');
      if (caret && scroller) {
        const bounds = scroller.getBoundingClientRect();
        const point = caret.getBoundingClientRect();
        if (point.bottom > bounds.bottom - 32) scroller.scrollTop += point.bottom - bounds.bottom + 64;
        else if (point.top < bounds.top + 32) scroller.scrollTop += point.top - bounds.top - 64;
      }
    };
    const complete = () => {
      paint(next, busyRef.current && !reduced ? { from: next.content.size, to: next.content.size, selecting: false } : null);
      finished.current = true;
      if (!busyRef.current) { notified.current = true; done.current?.(); }
    };
    if (reduced || !stages.length || content.length > 80_000) { complete(); return; }
    const tick = (now: number) => {
      if (editor.isDestroyed) return;
      started ||= now;
      const target = stages[stage];
      const range = editRange(old, target)!;
      const elapsed = now - started;
      const selecting = range.oldTo > range.from && elapsed < Math.min(240, duration * 0.25);
      if (selecting) paint(old, { from: range.from, to: range.oldTo, selecting: true });
      else {
        const delay = range.oldTo > range.from ? Math.min(240, duration * 0.25) : 0;
        const progress = Math.min(1, Math.max(0, (elapsed - delay) / Math.max(1, duration - delay)));
        const cursor = Math.floor(range.from + (range.to - range.from) * progress);
        paint(playbackFrame(target, range, cursor), { from: range.from, to: cursor, selecting: false });
      }
      if (elapsed >= duration) {
        paint(target, null);
        old = target;
        stage++;
        started = 0;
        if (stage === stages.length) { complete(); return; }
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [editor, content, reduced]);

  useEffect(() => {
    if (!busy && finished.current && !notified.current) { notified.current = true; done.current?.(); }
  }, [busy]);
  return <DocumentWorkspace.Provider value={workspace ? { ...workspace, canSave: () => false, captureSaveGuard: () => () => false } : null}>
    <div className="note-editor agent-note-editor" inert>
      {editor ? <div aria-hidden="true"><NoteEditorToolbar editor={editor} /></div> : null}
      <EditorContent editor={editor} className="note-editor-content" />
    </div>
  </DocumentWorkspace.Provider>;
}
