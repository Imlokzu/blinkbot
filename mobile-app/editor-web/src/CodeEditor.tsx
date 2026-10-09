import { useEffect, useMemo, useRef } from 'react';
import CodeMirror, { type ReactCodeMirrorRef } from '@uiw/react-codemirror';
import { EditorView } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { undo, redo } from '@codemirror/commands';
import { openSearchPanel } from '@codemirror/search';
import { javascript } from '@codemirror/lang-javascript';
import { python } from '@codemirror/lang-python';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { changeDocument, registerFlush, type EditorSnapshot } from './bridge';
import { text } from './locale';

export default function SourceEditor({ document: doc, session }: EditorSnapshot) {
  const editor = useRef<ReactCodeMirrorRef>(null);
  const touched = useRef(false);
  const locked = doc.readOnly || doc.saveState === 'writing';
  const t = (key: Parameters<typeof text>[1]) => text(doc.language, key);
  const extensions = useMemo(() => {
    const ext = doc.path.split('.').pop()?.toLowerCase();
    const language = doc.kind === 'html' || ['html', 'htm'].includes(ext ?? '') ? html()
      : doc.kind === 'markdown' ? markdown()
      : doc.kind === 'drawing' || ext === 'json' ? json()
      : ['js', 'jsx', 'ts', 'tsx'].includes(ext ?? '') ? javascript({ jsx: true, typescript: ext === 'ts' || ext === 'tsx' })
      : ext === 'py' ? python() : ext === 'css' ? css() : [];
    return [language, EditorView.lineWrapping, EditorState.phrases.of({
      'Find': t('find'), 'next': t('next'), 'previous': t('previous'), 'all': t('all'),
      'match case': t('caseSensitive'), 'regexp': t('regexp'), 'by word': t('wholeWord'),
      'replace': t('replace'), 'replace all': t('replaceAll'), 'close': t('closeFind'),
      'Replace': t('replace'), 'Go to line': t('goToLine'), 'go': t('go'),
      'Selection': t('selection'), 'No matches': t('noMatches'),
    }), EditorView.theme({
      '&': { color: 'var(--ink)', backgroundColor: 'var(--surface)', fontSize: '14px', height: '100%' },
      '.cm-scroller': { fontFamily: 'BlinkMono, monospace', lineHeight: '1.65' },
      '.cm-content': { padding: '14px 0', caretColor: 'var(--accent)' },
      '.cm-gutters': { backgroundColor: 'var(--surface)', color: 'var(--muted)', border: 'none' },
      '.cm-activeLine,.cm-activeLineGutter': { backgroundColor: 'var(--secondary)' },
      '.cm-cursor': { borderLeftColor: 'var(--accent)' },
      '&.cm-focused': { outline: 'none' },
      '.cm-selectionBackground,&.cm-focused .cm-selectionBackground': { backgroundColor: 'var(--selection)' },
    }, { dark: doc.theme === 'dark' })];
  }, [doc.path, doc.kind, doc.theme, doc.language]);
  useEffect(() => registerFlush(session, () => {
    if (touched.current && editor.current?.view) changeDocument(session, editor.current.view.state.doc.toString());
  }), [session]);

  return <div className="editor-pane" data-testid="source-editor">
    <div className="toolbar" role="toolbar" aria-label={t('toolbar')}>
      {!doc.readOnly && <>
        <button aria-label={t('undo')} title={t('undo')} disabled={locked} onClick={() => { if (!locked && editor.current?.view) undo(editor.current.view); }}>↶</button>
        <button aria-label={t('redo')} title={t('redo')} disabled={locked} onClick={() => { if (!locked && editor.current?.view) redo(editor.current.view); }}>↷</button>
      </>}
      <button className="text-button" aria-label={t('find')} onClick={() => { if (editor.current?.view) openSearchPanel(editor.current.view); }}>{t('find')}</button>
    </div>
    <CodeMirror ref={editor} value={doc.content} readOnly={locked} editable={!locked}
      theme={doc.theme} extensions={extensions} height="100%" className="source-content"
      basicSetup={{ foldGutter: false, highlightActiveLine: !locked }}
      onChange={(value) => { touched.current = true; changeDocument(session, value); }} />
  </div>;
}
