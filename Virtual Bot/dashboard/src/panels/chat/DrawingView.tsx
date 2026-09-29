import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Excalidraw,
  convertToExcalidrawElements,
  getSceneVersion,
  serializeAsJSON,
} from '@excalidraw/excalidraw';
import type { ExcalidrawElementSkeleton } from '@excalidraw/excalidraw/data/transform';
import type { ExcalidrawImperativeAPI } from '@excalidraw/excalidraw/types';
import '@excalidraw/excalidraw/index.css';
import { Empty, SkeletonList } from '@/components/ui/Feedback';
import { t } from '@/locales/workbench';

/*
 * Excalidraw inside the workbench. Loaded lazily: the editor and Mermaid
 * together are a few megabytes, and most chats never draw anything.
 *
 * Fonts are served from our own bundle (copied into public/excalidraw by
 * scripts/copy-excalidraw-fonts.mjs), not from Excalidraw's CDN: the
 * dashboard has to work with no network, and a hand-drawn diagram that falls
 * back to a system font stops looking hand-drawn.
 */
declare global {
  interface Window { EXCALIDRAW_ASSET_PATH?: string | string[] }
}
window.EXCALIDRAW_ASSET_PATH = `${import.meta.env.BASE_URL}excalidraw/`;

type Scene = { elements: readonly unknown[]; appState?: Record<string, unknown>; files?: Record<string, unknown> };

/*
 * The bot rarely writes a complete scene — every element carries a dozen
 * bookkeeping fields (seed, version, nonce…) that a model gets wrong. It may
 * write the short "skeleton" form instead (`{type: "rectangle", x, y, label:
 * {text}}`, arrows with `start`/`end`), which Excalidraw expands itself. A
 * scene with any element lacking `version` is treated as a skeleton.
 */
function readDrawing(source: string): Scene {
  const data = JSON.parse(source) as Partial<Scene> | unknown[];
  const raw = Array.isArray(data) ? data : data.elements;
  if (!Array.isArray(raw)) throw new Error('no elements');
  const skeleton = raw.some((item) => !item || typeof item !== 'object' || !('version' in item));
  const elements = skeleton
    ? convertToExcalidrawElements(raw as ExcalidrawElementSkeleton[], { regenerateIds: false })
    : raw;
  return Array.isArray(data) ? { elements } : { ...data, elements };
}

/* Mermaid is what models draw best; the converter turns it into a sketch. */
async function readMermaid(source: string): Promise<Scene> {
  const { parseMermaidToExcalidraw } = await import('@excalidraw/mermaid-to-excalidraw');
  const { elements, files } = await parseMermaidToExcalidraw(source, { themeVariables: { fontSize: '16px' } });
  /*
   * Models break labels with `<br>`, which Mermaid itself renders — and sizes
   * the boxes for — as a new line. The converter passes the tag through as
   * text, so it is turned back into the line break the box was measured for.
   */
  const lineBreaks = (text: unknown) => (typeof text === 'string' ? text.replace(/<br\s*\/?>/gi, '\n') : text);
  for (const element of elements as { label?: { text?: unknown }; text?: unknown }[]) {
    if (element.label) element.label.text = lineBreaks(element.label.text);
    if ('text' in element) element.text = lineBreaks(element.text);
  }
  return { elements: convertToExcalidrawElements(elements), files: files as Record<string, unknown> | undefined };
}

type SaveState = 'idle' | 'saving' | 'saved' | 'error';

export default function DrawingView({
  kind,
  source,
  dark,
  onSave,
  onSaveAs,
}: {
  kind: 'drawing' | 'mermaid';
  /**
   * The file as it was when the view opened. Excalidraw reads a scene once,
   * on mount, so a later value is ignored here too: to show a rewritten file,
   * remount the view (the workbench keys it by path and revision).
   */
  source: string;
  dark: boolean;
  /** Writes the scene back to the same file (drawings only). */
  onSave?: (json: string) => Promise<void>;
  /** Writes a converted Mermaid diagram next to its source as `.excalidraw`. */
  onSaveAs?: (json: string) => Promise<void>;
}) {
  const [scene, setScene] = useState<Scene | null>(null);
  const [failed, setFailed] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>('idle');
  const baseline = useRef<number | null>(null);
  const latest = useRef('');
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const api = useRef<ExcalidrawImperativeAPI | null>(null);
  const fitted = useRef(false);
  // The parent passes a fresh arrow on every render; the effects below must
  // not treat that as a reason to run.
  const saveRef = useRef(onSave);
  saveRef.current = onSave;
  const [opened] = useState(source);

  useEffect(() => {
    let alive = true;
    (kind === 'mermaid' ? readMermaid(opened) : Promise.resolve().then(() => readDrawing(opened)))
      .then((next) => { if (alive) setScene(next); })
      .catch(() => { if (alive) setFailed(true); });
    return () => { alive = false; };
  }, [kind, opened]);

  /*
   * A pending autosave is flushed, not dropped, when the view goes away.
   * Unmount only: keyed on the callback this would fire on every parent
   * render, and since the timer handle stayed set, write the file again on
   * each of them.
   */
  useEffect(() => () => {
    if (timer.current && latest.current && saveRef.current) {
      clearTimeout(timer.current);
      timer.current = undefined;
      void saveRef.current(latest.current);
    }
  }, []);

  const initialData = useMemo(() => scene && ({
    elements: scene.elements as never,
    files: scene.files as never,
    // Our theme wins over whatever the file was saved with.
    appState: { ...(scene.appState ?? {}), theme: dark ? 'dark' : 'light', collaborators: new Map() } as never,
    scrollToContent: true,
  }), [scene, dark]);

  if (failed) return <Empty title={t('wb.drawingError')} hint={t('wb.drawingHint')} />;
  if (!initialData) return <div className="p-4"><SkeletonList rows={8} /></div>;

  const lang = document.documentElement.lang.startsWith('en') ? 'en' : 'uk-UA';

  return (
    <div className="wb-drawing flex size-full flex-col">
      {kind === 'mermaid' && onSaveAs ? (
        <div className="flex shrink-0 items-center gap-2 border-b border-line px-3 py-1.5">
          <span className="min-w-0 flex-1 truncate text-[12px] text-ink-3">{t('wb.fromMermaid')}</span>
          <button
            type="button"
            className="wb-drawing-action"
            onClick={() => { if (latest.current) void onSaveAs(latest.current); }}
          >
            {t('wb.saveAsDrawing')}
          </button>
        </div>
      ) : null}
      <div className="relative min-h-0 flex-1">
      <Excalidraw
        excalidrawAPI={(instance) => { api.current = instance; }}
        initialData={initialData}
        theme={dark ? 'dark' : 'light'}
        langCode={lang}
        UIOptions={{ canvasActions: { loadScene: false, saveToActiveFile: false, toggleTheme: false } }}
        onChange={(elements, appState, files) => {
          /*
           * Fit once, on the first frame that has a size. `scrollToContent`
           * in initialData only centres, and a diagram wider than the column
           * would open with half of it off-screen.
           */
          if (!fitted.current && elements.length && api.current) {
            fitted.current = true;
            const instance = api.current;
            requestAnimationFrame(() => instance.scrollToContent(undefined, { fitToViewport: true, viewportZoomFactor: 0.8 }));
          }
          if (kind !== 'drawing' || !onSave) {
            latest.current = serializeAsJSON(elements, appState, files, 'local');
            return;
          }
          /*
           * onChange also fires for scrolling, zooming and the first render.
           * Only a change to the elements themselves is an edit worth writing
           * back — otherwise opening a file would rewrite it on disk.
           */
          const version = getSceneVersion(elements);
          if (baseline.current === null) {
            baseline.current = version;
            return;
          }
          if (version === baseline.current) return;
          baseline.current = version;
          latest.current = serializeAsJSON(elements, appState, files, 'local');
          clearTimeout(timer.current);
          timer.current = setTimeout(() => {
            timer.current = undefined;
            const save = saveRef.current;
            if (!save) return;
            setSaveState('saving');
            save(latest.current).then(() => setSaveState('saved'), () => setSaveState('error'));
          }, 1200);
        }}
      />
      {saveState !== 'idle' ? (
        <span className="wb-drawing-state" role="status">
          {saveState === 'saving' ? t('wb.saving') : saveState === 'saved' ? t('wb.saved') : t('wb.saveError')}
        </span>
      ) : null}
      </div>
    </div>
  );
}
