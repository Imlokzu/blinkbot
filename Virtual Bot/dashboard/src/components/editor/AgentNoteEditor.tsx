import { useEffect, useRef, useState } from 'react';
import { MousePointer2 } from 'lucide-react';
import { EditorContent, Extension, useEditor } from '@tiptap/react';
import { Plugin, PluginKey, TextSelection } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { t } from '@/locales/workbench';
import { Button } from '@/components/ui/Button';
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
  const [following, setFollowing] = useState(true);
  const followRef = useRef(true);
  const dragging = useRef(false);
  const notify = useRef(() => {});
  const contentRoot = useRef<HTMLDivElement>(null);
  const editor = useEditor({
    extensions: [...noteExtensions(), AgentCursor],
    content: previous, contentType: 'markdown', editable: false, immediatelyRender: false,
    editorProps: { attributes: { class: 'prose-note min-h-full outline-none', tabindex: '0', 'aria-readonly': 'true' } },
  });
  const reading = () => {
    if (!editor || editor.isDestroyed) return false;
    const selection = window.getSelection();
    return dragging.current || Boolean(selection && !selection.isCollapsed && selection.rangeCount
      && selection.getRangeAt(0).intersectsNode(editor.view.dom));
  };
  notify.current = () => {
    if (!editor || editor.isDestroyed) return;
    if (!busyRef.current && finished.current && !notified.current && !reading()) {
      notified.current = true;
      done.current?.();
    }
  };
  const scrollToCursor = () => {
    if (!editor || editor.isDestroyed) return;
    const caret = editor.view.dom.querySelector('.agent-editor-caret');
    const scroller = editor.view.dom.closest('.note-editor-content');
    if (!caret || !scroller) return;
    const bounds = scroller.getBoundingClientRect();
    const point = caret.getBoundingClientRect();
    if (point.bottom > bounds.bottom - 32) scroller.scrollTop += point.bottom - bounds.bottom + 64;
    else if (point.top < bounds.top + 32) scroller.scrollTop += point.top - bounds.top - 64;
  };
  useEffect(() => {
    const root = contentRoot.current;
    if (!root) return;
    const stopFollowing = () => { followRef.current = false; setFollowing(false); };
    const pointerDown = () => { dragging.current = true; stopFollowing(); };
    const keyDown = (event: KeyboardEvent) => {
      if (['PageUp', 'PageDown', 'Home', 'End', 'ArrowUp', 'ArrowDown'].includes(event.key)) stopFollowing();
    };
    // Node-view portals are React siblings, but native capture follows their DOM.
    root.addEventListener('wheel', stopFollowing, { capture: true, passive: true });
    root.addEventListener('touchstart', stopFollowing, { capture: true, passive: true });
    root.addEventListener('pointerdown', pointerDown, true);
    root.addEventListener('keydown', keyDown, true);
    return () => {
      root.removeEventListener('wheel', stopFollowing, true);
      root.removeEventListener('touchstart', stopFollowing, true);
      root.removeEventListener('pointerdown', pointerDown, true);
      root.removeEventListener('keydown', keyDown, true);
    };
  }, [editor]);
  useEffect(() => {
    const release = () => { dragging.current = false; notify.current(); };
    const selected = () => notify.current();
    // Embedded canvases can stop bubbling; losing the window can lose pointerup.
    document.addEventListener('pointerup', release, true);
    document.addEventListener('pointercancel', release, true);
    document.addEventListener('selectionchange', selected);
    window.addEventListener('blur', release);
    return () => {
      document.removeEventListener('pointerup', release, true);
      document.removeEventListener('pointercancel', release, true);
      document.removeEventListener('selectionchange', selected);
      window.removeEventListener('blur', release);
    };
  }, []);
  useEffect(() => {
    if (!editor || editor.isDestroyed) return;
    finished.current = false;
    notified.current = false;
    const next = editor.schema.nodeFromJSON(editor.storage.markdown.manager.parse(content));
    const stages = playbackStages(editor.state.doc, next);
    let frame = 0;
    let stage = 0;
    let started = 0;
    let pausedAt = 0;
    let old = editor.state.doc;
    const duration = Math.min(4200, Math.max(1100, content.length * 3)) / Math.max(1, stages.length);
    const paint = (doc: typeof next, cursor: Cursor) => {
      const tr = editor.state.tr;
      if (!tr.doc.eq(doc)) tr.replaceWith(0, tr.doc.content.size, doc.content);
      const pos = Math.min(cursor?.to ?? 1, tr.doc.content.size);
      // The agent caret is a decoration, independent of the reader's selection.
      const head = TextSelection.near(tr.doc.resolve(pos)).head;
      const decoration = cursor ? { ...cursor, to: cursor.selecting ? cursor.to : head } : null;
      editor.view.dispatch(tr.setMeta(cursorKey, decoration).setMeta('preventUpdate', true).setMeta('addToHistory', false));
      if (followRef.current) scrollToCursor();
    };
    const complete = () => {
      paint(next, busyRef.current && !reduced ? { from: next.content.size, to: next.content.size, selecting: false } : null);
      finished.current = true;
      notify.current();
    };
    const immediate = reduced || !stages.length || content.length > 80_000;
    if (immediate && !reading()) { complete(); return; }
    const tick = (now: number) => {
      if (editor.isDestroyed) return;
      // Hold visual transactions while the reader drags/selects text. The real
      // file write continues; clearing the selection reveals its latest content.
      if (reading()) {
        pausedAt ||= now;
        frame = requestAnimationFrame(tick);
        return;
      }
      if (pausedAt) { if (started) started += now - pausedAt; pausedAt = 0; }
      if (immediate) { complete(); return; }
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
    notify.current();
  }, [busy]);
  return <DocumentWorkspace.Provider value={workspace ? { ...workspace, readOnly: true, canSave: () => false, captureSaveGuard: () => () => false } : null}>
    <div className="note-editor agent-note-editor">
      {editor ? <div aria-hidden="true" inert><NoteEditorToolbar editor={editor} /></div> : null}
      <EditorContent ref={contentRoot} editor={editor} className="note-editor-content" />
      {!following ? <Button className="agent-follow-button" variant="quiet" size="sm" onClick={() => {
        window.getSelection()?.removeAllRanges();
        dragging.current = false;
        followRef.current = true;
        setFollowing(true);
        scrollToCursor();
        notify.current();
      }}><MousePointer2 />{t('wb.followAgent')}</Button> : null}
    </div>
  </DocumentWorkspace.Provider>;
}
