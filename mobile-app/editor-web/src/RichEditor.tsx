import { createContext, lazy, Suspense, useContext, useEffect, useRef, useState } from 'react';
import { EditorContent, NodeViewWrapper, ReactNodeViewRenderer, useEditor, useEditorState, type Editor, type NodeViewProps } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { Markdown } from '@tiptap/markdown';
import { TableKit } from '@tiptap/extension-table';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import Image from '@tiptap/extension-image';
import { action, changeDocument, currentSession, editorError, registerFlush, type EditorSnapshot } from './bridge';
import { documentLink, isDrawing, isExternal, resourceUrl, workspacePath } from './paths';
import { text, type Label } from './locale';

const DocumentContext = createContext<EditorSnapshot | null>(null);
const DrawingEditor = lazy(() => import('./DrawingEditor'));

function DocumentImage({ node }: NodeViewProps) {
  const context = useContext(DocumentContext)!;
  const source = String(node.attrs.src ?? '');
  const path = workspacePath(source, context.document.path);
  const drawing = Boolean(path && isDrawing(path));
  const [scene, setScene] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const t = (key: Label) => text(context.document.language, key);
  useEffect(() => {
    setScene(null); setFailed(false);
    if (!drawing || !path) return;
    const abort = new AbortController();
    fetch(resourceUrl(path), { method: 'GET', credentials: 'omit', signal: abort.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error('resource_unavailable');
        const content = await response.text();
        if (new TextEncoder().encode(content).length > 2_000_000) throw new Error('document_too_large');
        if (!abort.signal.aborted) setScene(content);
      }).catch(() => { if (!abort.signal.aborted) { setFailed(true); editorError(context.session, 'resource_unavailable'); } });
    return () => abort.abort();
  }, [path, drawing, context.session]);
  if (drawing && path) return <NodeViewWrapper className="document-drawing" contentEditable={false}>
    <div className="embedded-heading"><span>{String(node.attrs.alt || path.split('/').pop())}</span>
      <button className="text-button" onClick={() => action(context.session, 'openWorkspace', { path })}>{t('openDrawing')}</button></div>
    {failed ? <p className="resource-error">{t('drawingFailed')}</p> : scene ?
      <Suspense fallback={<p className="resource-error">{t('loading')}</p>}>
        <DrawingEditor key={path} {...context} embedded source={scene} />
      </Suspense> : <p className="resource-error">{t('loading')}</p>}
  </NodeViewWrapper>;
  if (!path) return <NodeViewWrapper className="resource-error" contentEditable={false}>
    {isExternal(source) ? <button className="text-button" onClick={() => action(context.session, 'openExternal', { path: source })}>{t('externalImage')}</button> : t('unavailableImage')}
  </NodeViewWrapper>;
  return <NodeViewWrapper contentEditable={false} className="document-image">
    {failed ? <span className="resource-error">{t('unavailableImage')}</span> :
      <button aria-label={t('openImage')} onClick={() => action(context.session, 'openWorkspace', { path })}>
        <img src={resourceUrl(path)} alt={String(node.attrs.alt ?? '')} onError={() => setFailed(true)} />
      </button>}
  </NodeViewWrapper>;
}

const WorkspaceImage = Image.extend({
  // ProseMirror can create a fallback DOM node before the React view mounts.
  // A raw img there would request a relative/remote source before our local
  // resolver runs. Keep that inert, including clipboard HTML serialization.
  renderHTML({ HTMLAttributes }) {
    return ['span', { 'data-workspace-image': '', 'data-src': HTMLAttributes.src,
      'data-alt': HTMLAttributes.alt, 'data-title': HTMLAttributes.title }, HTMLAttributes.alt || ''];
  },
  parseHTML() {
    return [{ tag: 'span[data-workspace-image]', getAttrs: (node) => ({
      src: node.getAttribute('data-src'), alt: node.getAttribute('data-alt'), title: node.getAttribute('data-title'),
    }) }, ...(this.parent?.() ?? [])];
  },
  addNodeView() {
    return ReactNodeViewRenderer(DocumentImage, { stopEvent: ({ event }) => event.target instanceof Element && Boolean(event.target.closest('.document-drawing,.document-image')) });
  },
});

function Toolbar({ editor, context }: { editor: Editor; context: EditorSnapshot }) {
  const { document: doc, session } = context;
  const [dialog, setDialog] = useState<'link' | 'image' | null>(null);
  const [url, setUrl] = useState('');
  const [invalid, setInvalid] = useState(false);
  const [creating, setCreating] = useState(false);
  const [creationFailed, setCreationFailed] = useState(false);
  const t = (key: Label) => text(doc.language, key);
  const state = useEditorState({ editor, selector: ({ editor: e }) => ({
    bold: e.isActive('bold'), italic: e.isActive('italic'), strike: e.isActive('strike'), code: e.isActive('code'),
    bullets: e.isActive('bulletList'), numbers: e.isActive('orderedList'), tasks: e.isActive('taskList'), quote: e.isActive('blockquote'), table: e.isActive('table'),
    heading: ([1, 2, 3] as const).find((level) => e.isActive('heading', { level })) ?? 0,
    undo: e.can().undo(), redo: e.can().redo(),
  }) });
  const button = (label: Label, icon: string, run: () => void, active?: boolean, disabled = false) => <button key={label}
    aria-label={t(label)} title={t(label)} aria-pressed={active} disabled={disabled}
    onMouseDown={(event) => event.preventDefault()} onClick={run}><span aria-hidden="true">{icon}</span></button>;
  return <>
    <div className="toolbar" role="toolbar" aria-label={t('toolbar')}>
      {button('undo', '↶', () => { editor.chain().focus().undo().run(); }, undefined, !state.undo)}
      {button('redo', '↷', () => { editor.chain().focus().redo().run(); }, undefined, !state.redo)}
      <select aria-label={t('paragraph')} value={state.heading} onChange={(event) => {
        const level = Number(event.target.value) as 1 | 2 | 3;
        if (level) editor.chain().focus().setHeading({ level }).run(); else editor.chain().focus().setParagraph().run();
      }}><option value="0">{t('paragraph')}</option>{([1, 2, 3] as const).map((level) => <option key={level} value={level}>{t(`heading${level}`)}</option>)}</select>
      {button('bold', 'B', () => { editor.chain().focus().toggleBold().run(); }, state.bold)}
      {button('italic', '𝑰', () => { editor.chain().focus().toggleItalic().run(); }, state.italic)}
      {button('strike', 'S̶', () => { editor.chain().focus().toggleStrike().run(); }, state.strike)}
      {button('code', '</>', () => { editor.chain().focus().toggleCode().run(); }, state.code)}
      {button('bullets', '•≡', () => { editor.chain().focus().toggleBulletList().run(); }, state.bullets)}
      {button('numbers', '1≡', () => { editor.chain().focus().toggleOrderedList().run(); }, state.numbers)}
      {button('tasks', '☑', () => { editor.chain().focus().toggleTaskList().run(); }, state.tasks)}
      {button('quote', '❝', () => { editor.chain().focus().toggleBlockquote().run(); }, state.quote)}
      {button('table', '⊞', () => { editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(); })}
      {button('link', '↗', () => { setUrl(editor.getAttributes('link').href ?? ''); setInvalid(false); setDialog('link'); })}
      {button('image', '▧', () => { setUrl(''); setInvalid(false); setDialog('image'); })}
      {button('drawing', '◇', () => {
        setCreating(true); setCreationFailed(false);
        action(session, 'createDrawing', {}, (path) => {
          if (!currentSession(session) || editor.isDestroyed) return;
          setCreating(false);
          if (path) editor.chain().focus().setImage({ src: documentLink(path, doc.path), alt: t('openDrawing') }).run();
          else setCreationFailed(true);
        });
      }, undefined, creating)}
      {state.table && <>
        {button('addRow', '+≡', () => { editor.chain().focus().addRowAfter().run(); })}
        {button('addColumn', '+Ⅱ', () => { editor.chain().focus().addColumnAfter().run(); })}
        {button('deleteTable', '⊟', () => { editor.chain().focus().deleteTable().run(); })}
      </>}
    </div>
    {creationFailed && <p className="resource-error" role="alert">{t('drawingFailed')}</p>}
    {dialog && <div className="url-panel" role="dialog" aria-label={t(dialog)}>
      <form onSubmit={(event) => {
        event.preventDefault(); const value = url.trim();
        if ((!isExternal(value) && !workspacePath(value, doc.path)) || /\s/.test(value)) { setInvalid(true); return; }
        if (dialog === 'image') editor.chain().focus().setImage({ src: value }).run();
        else editor.chain().focus().extendMarkRange('link').setLink({ href: value }).run();
        setDialog(null);
      }}><label>{t('url')}<input autoFocus value={url} onChange={(event) => setUrl(event.target.value)} /></label>
        {invalid && <p role="alert">{t('invalidUrl')}</p>}
        <div><button type="button" className="text-button" onClick={() => setDialog(null)}>{t('cancel')}</button><button className="text-button primary" type="submit">{t('insert')}</button></div>
      </form>
    </div>}
  </>;
}

export default function RichEditor(context: EditorSnapshot) {
  const { document: doc, session } = context;
  const [opened] = useState(doc.content);
  const touched = useRef(false);
  const editor = useEditor({
    extensions: [StarterKit.configure({ link: { openOnClick: false } }), Markdown, TableKit.configure({ table: { resizable: true } }), TaskList, TaskItem.configure({ nested: true }), WorkspaceImage],
    content: opened, contentType: 'markdown', editable: !doc.readOnly, immediatelyRender: false,
    editorProps: { attributes: { class: 'prose-note', 'aria-label': text(doc.language, 'document') } },
    onUpdate: ({ editor: instance }) => { touched.current = true; changeDocument(session, instance.getMarkdown()); },
  });
  useEffect(() => { editor?.setEditable(!doc.readOnly, false); }, [editor, doc.readOnly]);
  useEffect(() => registerFlush(session, () => {
    if (editor && !editor.isDestroyed && touched.current) changeDocument(session, editor.getMarkdown());
  }), [editor, session]);
  return <DocumentContext.Provider value={context}><div className="editor-pane" data-testid="rich-editor">
    {editor && !doc.readOnly && <Toolbar editor={editor} context={context} />}
    <div className="document-content" onClick={(event) => {
      const link = event.target instanceof Element ? event.target.closest('a[href]') : null;
      if (!link) return;
      event.preventDefault();
      const href = link.getAttribute('href') ?? '';
      const path = workspacePath(href, doc.path);
      if (path) action(session, 'openWorkspace', { path });
      else if (isExternal(href)) action(session, 'openExternal', { path: href });
    }}><EditorContent editor={editor} /></div>
  </div></DocumentContext.Provider>;
}
