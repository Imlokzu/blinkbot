import { Component, Suspense, lazy, useEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { action, editorError, flushCurrent, getSnapshot, installBridge, subscribe, type EditorSnapshot } from './bridge';
import { text, type Label } from './locale';
import './style.css';

// This runs before the lazy drawing module evaluates, including its font loader.
window.EXCALIDRAW_ASSET_PATH = new URL('./excalidraw/', document.baseURI).href;
const SourceEditor = lazy(() => import('./CodeEditor'));
const RichEditor = lazy(() => import('./RichEditor'));
const DrawingEditor = lazy(() => import('./DrawingEditor'));

class EditorBoundary extends Component<{ context: EditorSnapshot; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { editorError(this.props.context.session, 'editor_failed'); }
  render() {
    const { document: doc, session } = this.props.context;
    return this.state.failed ? <div className="empty-state" role="alert">
      <p>{text(doc.language, 'editorFailed')}</p><button className="text-button" onClick={() => action(session, 'retry')}>{text(doc.language, 'retry')}</button>
    </div> : this.props.children;
  }
}

function DocumentEditor(context: EditorSnapshot) {
  const { document: doc } = context;
  const rendered = ['markdown', 'drawing', 'mermaid'].includes(doc.kind);
  const [mode, setMode] = useState<'preview' | 'source'>(rendered ? 'preview' : 'source');
  const t = (key: Label) => text(doc.language, key);
  useEffect(() => {
    document.documentElement.dataset.theme = doc.theme;
    document.documentElement.lang = doc.language;
    document.title = t('title');
  }, [doc.theme, doc.language]);
  const status: Label = doc.readOnly ? 'readonly' : doc.saveState === 'failed' ? 'error'
    : ['saved', 'saving', 'pending', 'error', 'conflict'].includes(doc.saveState) ? doc.saveState as Label : 'pending';
  const switchMode = (next: 'preview' | 'source') => { if (flushCurrent(context.session)) setMode(next); };
  return <main className="workspace-editor">
    <header className="editor-heading">
      {rendered ? <div role="tablist" aria-label={t('view')} className="view-tabs">
        <button role="tab" aria-selected={mode === 'preview'} onClick={() => switchMode('preview')}>{t(doc.kind === 'markdown' ? 'document' : 'preview')}</button>
        <button role="tab" aria-selected={mode === 'source'} onClick={() => switchMode('source')}>{t('source')}</button>
      </div> : <span className="file-extension">{doc.path.split('.').pop()?.toUpperCase()}</span>}
      <span className={`save-state ${status}`} role="status">{t(status)}</span>
    </header>
    <EditorBoundary key={mode} context={context}>
      <Suspense fallback={<div className="empty-state" role="status">{t('loading')}</div>}>
        {mode === 'source' ? <SourceEditor {...context} /> : doc.kind === 'markdown' ? <RichEditor {...context} /> :
          <DrawingEditor {...context} onSource={() => switchMode('source')} />}
      </Suspense>
    </EditorBoundary>
  </main>;
}

function App() {
  const context = useSyncExternalStore(subscribe, getSnapshot);
  return context ? <DocumentEditor key={context.session.generation} {...context} /> : null;
}
createRoot(document.getElementById('root')!).render(<App />);
installBridge();
