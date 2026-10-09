import { useEffect, useRef, useState } from 'react';
import { Excalidraw, MainMenu, exportToBlob, getSceneVersion, serializeAsJSON } from '@excalidraw/excalidraw';
import type { ExcalidrawImperativeAPI } from '@excalidraw/excalidraw/types';
import '@excalidraw/excalidraw/index.css';
import { action, changeDocument, currentSession, editorError, registerFlush, type EditorSnapshot } from './bridge';
import { text } from './locale';
import { readDrawing, readMermaid, type Scene } from './drawing';
import { MAX_EXPORT_DIMENSION, pngDataUrl } from './drawingExport';
import { isExternal, workspacePath } from './paths';

export default function DrawingEditor({ document: doc, session, embedded = false, source, scenePath, onSource }: EditorSnapshot & {
  embedded?: boolean; source?: string; scenePath?: string; onSource?: () => void;
}) {
  const [opened] = useState(source ?? doc.content);
  const [scene, setScene] = useState<Scene | null>(null);
  const [failed, setFailed] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<'exportFailed' | 'exportTooLarge' | null>(null);
  const mounted = useRef(true);
  const exportPending = useRef(false);
  const api = useRef<ExcalidrawImperativeAPI | null>(null);
  const baseline = useRef<number | null>(null);
  const latest = useRef('');
  const dirty = useRef(false);
  const fitted = useRef(false);
  const mermaid = !embedded && doc.kind === 'mermaid';
  // A temporary writer lock freezes controls, but an already queued edit still
  // belongs to this document and must reach native recovery before close.
  const canPersist = !embedded && !doc.readOnly && !mermaid;
  const interactive = canPersist && doc.saveState !== 'writing';
  const t = (key: Parameters<typeof text>[1]) => text(doc.language, key);
  useEffect(() => () => { mounted.current = false; api.current = null; }, []);
  useEffect(() => {
    let alive = true;
    (mermaid ? readMermaid(opened) : Promise.resolve().then(() => readDrawing(opened)))
      .then((value) => {
        if (alive) {
          // An empty scene starts at its natural zoom. Fitting the first tiny
          // drag would zoom into a half-created shape while the user draws.
          fitted.current = !value.elements?.length;
          setScene(value);
        }
      })
      .catch(() => { if (alive) { setFailed(true); editorError(session, mermaid ? 'invalid_mermaid' : 'invalid_drawing'); } });
    return () => { alive = false; };
  }, [opened, mermaid, session]);
  useEffect(() => {
    if (embedded) return;
    return registerFlush(session, () => { if (canPersist && dirty.current) changeDocument(session, latest.current); });
  }, [session, canPersist, embedded]);
  const exportPng = async () => {
    const instance = api.current;
    if (!instance || exportPending.current || doc.saveState === 'writing' || !currentSession(session)) return;
    exportPending.current = true; setExporting(true); setExportError(null);
    try {
      const blob = await exportToBlob({
        elements: instance.getSceneElements(), files: instance.getFiles(), mimeType: 'image/png',
        maxWidthOrHeight: MAX_EXPORT_DIMENSION,
        appState: { ...instance.getAppState(), exportScale: 1, exportBackground: true, exportEmbedScene: false,
          exportWithDarkMode: doc.theme === 'dark' },
      });
      const content = await pngDataUrl(blob);
      if (mounted.current && currentSession(session) && api.current === instance) action(session, 'exportDrawing', { content });
    } catch (error) {
      // Export is optional. A renderer/size failure must never invalidate the
      // editable scene or prevent native from flushing ordinary document edits.
      if (mounted.current && currentSession(session)) setExportError(error instanceof Error && error.message === 'export_too_large' ? 'exportTooLarge' : 'exportFailed');
    } finally {
      exportPending.current = false;
      if (mounted.current && currentSession(session)) setExporting(false);
    }
  };
  if (failed) return <div className="empty-state" role="status">
    <p>{t(mermaid ? 'mermaidFailed' : 'drawingFailed')}</p>
    {onSource && <button className="text-button" onClick={onSource}>{t('editSource')}</button>}
    {!embedded && <button className="text-button" onClick={() => action(session, 'retry')}>{t('retry')}</button>}
  </div>;
  if (!scene) return <div className="empty-state" role="status">{t('loading')}</div>;
  return <div className={embedded ? 'embedded-canvas' : 'editor-pane'} data-testid="drawing-editor">
    {!embedded && <div className="toolbar">
      <button className="text-button" onClick={() => api.current?.scrollToContent(undefined, { fitToViewport: true, viewportZoomFactor: .8 })}>{t('fit')}</button>
      <button className="text-button" disabled={exporting || doc.saveState === 'writing'} onClick={() => { void exportPng(); }}>{t(exporting ? 'exporting' : 'exportPng')}</button>
      {mermaid && <button className="text-button" disabled={doc.saveState === 'writing'} onClick={() => {
        if (latest.current) action(session, 'convertMermaid', { content: latest.current });
      }}>{t('convert')}</button>}
    </div>}
    {!embedded && exportError && <p className="resource-error" role="alert">{t(exportError)}</p>}
    <div className="drawing-content">
      <Excalidraw initialData={{ ...scene, appState: { ...scene.appState, theme: doc.theme, collaborators: new Map() }, scrollToContent: true }}
        excalidrawAPI={(instance) => { api.current = instance; }}
        theme={doc.theme} langCode={doc.language === 'uk' ? 'uk-UA' : 'en'} viewModeEnabled={!interactive}
        detectScroll={false} handleKeyboardGlobally={false} validateEmbeddable={false}
        UIOptions={{ canvasActions: { loadScene: false, saveToActiveFile: false, toggleTheme: false, export: false, saveAsImage: false } }}
        onLinkOpen={(element, event) => {
          event.preventDefault();
          if (typeof element.link !== 'string') return;
          const path = workspacePath(element.link, scenePath ?? doc.path);
          if (path) action(session, 'openWorkspace', { path });
          else if (isExternal(element.link)) action(session, 'openExternal', { path: element.link });
        }}
        onChange={(elements, appState, files) => {
          if (!fitted.current && elements.length && api.current) {
            fitted.current = true;
            const instance = api.current;
            requestAnimationFrame(() => { if (api.current === instance) instance.scrollToContent(undefined, { fitToViewport: true, viewportZoomFactor: .8, maxZoom: 1 }); });
          }
          if (embedded) return;
          if (mermaid) { latest.current = serializeAsJSON(elements, appState, files, 'local'); return; }
          const version = getSceneVersion(elements);
          if (baseline.current === null) { baseline.current = version; return; }
          if (version === baseline.current || !canPersist) return;
          baseline.current = version;
          dirty.current = true;
          latest.current = serializeAsJSON(elements, appState, files, 'local');
          changeDocument(session, latest.current);
        }}>
        <MainMenu>
          <MainMenu.DefaultItems.SearchMenu />
          {interactive && <MainMenu.DefaultItems.ClearCanvas />}
        </MainMenu>
      </Excalidraw>
    </div>
  </div>;
}
