import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowUpRight, Code2, FileText, Globe, Image as ImageIcon, PenTool, RefreshCw, Save, Workflow, X,
} from 'lucide-react';
import { get, post } from '@/lib/api';
import { cn } from '@/lib/cn';
import { useTheme } from '@/hooks/useTheme';
import { Button } from '@/components/ui/Button';
import { Empty, SkeletonList } from '@/components/ui/Feedback';
import { Segmented } from '@/components/ui/Segmented';
import { useToast } from '@/components/ui/Toaster';
import { t } from '@/locales/workbench';
import { collectFiles, currentStep, fileKind, hasPreview, shortPath, type FileKind, type WorkFile } from './workFiles';
import type { ChatMessage } from './types';

/*
 * The workbench: chat on the left, what the bot is making on the right.
 *
 * Every file the bot writes in this conversation gets a tab, and the newest
 * one comes forward on its own — you watch a site or a diagram appear while
 * the bot is still talking about it. A tab is a file on disk, not a copy:
 * saving here writes the same file the bot will read next turn.
 */

const CodeEditor = lazy(() => import('@/components/editor/CodeEditor').then((m) => ({ default: m.CodeEditor })));
const NoteEditor = lazy(() => import('@/components/editor/NoteEditor').then((m) => ({ default: m.NoteEditor })));
const DrawingView = lazy(() => import('./DrawingView'));

const ICONS: Record<FileKind, typeof FileText> = {
  html: Globe, image: ImageIcon, markdown: FileText, drawing: PenTool, mermaid: Workflow, code: Code2, text: FileText,
};

interface FileData { path: string; content: string; binary?: boolean; too_large?: boolean }
type Mode = 'preview' | 'source';

function previewUrl(path: string, sessionId: string, revision: number): string {
  const encoded = path.split('/').map(encodeURIComponent).join('/');
  return `/preview/${encoded}?session_id=${encodeURIComponent(sessionId)}&r=${revision}`;
}

function fileQuery(sessionId: string, path: string) {
  return `/api/workspace/file?path=${encodeURIComponent(path)}&session_id=${encodeURIComponent(sessionId)}`;
}

const fallback = <div className="p-4"><SkeletonList rows={8} /></div>;

function FileView({ file, sessionId, mode, nonce }: { file: WorkFile; sessionId: string; mode: Mode; nonce: number }) {
  const { resolved } = useTheme();
  const client = useQueryClient();
  const toast = useToast();
  const revision = file.revision + nonce;
  const media = file.kind === 'html' || file.kind === 'image';
  const needsText = !(media && mode === 'preview');
  const data = useQuery({
    queryKey: ['wb-file', sessionId, file.path, revision],
    queryFn: () => get<FileData>(fileQuery(sessionId, file.path)),
    enabled: needsText,
    staleTime: Infinity,
  });
  const [draft, setDraft] = useState<string | null>(null);
  useEffect(() => setDraft(null), [data.data]);

  const write = async (path: string, content: string) => {
    await post('/api/workspace/file', { path, content, session_id: sessionId });
  };
  const save = async () => {
    if (draft === null) return;
    try {
      await write(file.path, draft);
      toast.ok(t('wb.saved'), file.path);
      void client.invalidateQueries({ queryKey: ['wb-file', sessionId, file.path] });
    } catch (error) {
      toast.error(t('wb.saveError'), (error as Error).message);
    }
  };

  if (media && mode === 'preview') {
    const src = previewUrl(file.path, sessionId, revision);
    return file.kind === 'image' ? (
      <div className="grid size-full place-items-center overflow-auto bg-bg p-4">
        <img src={src} alt={file.path} className="max-h-full max-w-full object-contain" />
      </div>
    ) : (
      // Scripts run, but without same-origin: a page the bot made must not
      // reach the dashboard's session or its API.
      <iframe key={src} title={file.path} src={src} sandbox="allow-scripts allow-forms allow-modals"
              className="size-full border-0 bg-white" />
    );
  }
  if (data.isPending) return fallback;
  if (data.isError) return <Empty title={t('wb.openError')} hint={(data.error as Error).message} />;
  if (data.data.binary) return <Empty title={t('wb.binary')} />;
  if (data.data.too_large) return <Empty title={t('wb.tooLarge')} />;
  const content = data.data.content ?? '';

  if (mode === 'preview' && (file.kind === 'drawing' || file.kind === 'mermaid')) {
    return (
      <Suspense fallback={fallback}>
        <DrawingView
          kind={file.kind}
          source={content}
          dark={resolved === 'dark'}
          onSave={file.kind === 'drawing' ? (json) => write(file.path, json) : undefined}
          onSaveAs={file.kind === 'mermaid' ? async (json) => {
            const target = file.path.replace(/\.(mmd|mermaid)$/i, '') + '.excalidraw';
            try {
              await write(target, json);
              toast.ok(t('wb.savedAs', { path: target }));
            } catch (error) {
              toast.error(t('wb.saveError'), (error as Error).message);
            }
          } : undefined}
        />
      </Suspense>
    );
  }
  if (mode === 'preview' && file.kind === 'markdown') {
    return <Suspense fallback={fallback}><NoteEditor value={content} editable={false} /></Suspense>;
  }
  return (
    <div className="flex size-full flex-col">
      {draft !== null && draft !== content ? (
        <div className="flex shrink-0 items-center justify-end gap-2 border-b border-line px-3 py-1.5">
          <span className="text-[11px] text-accent">{t('wb.unsaved')}</span>
          <Button variant="solid" size="sm" onClick={() => void save()}><Save />{t('wb.save')}</Button>
        </div>
      ) : null}
      <div className="min-h-0 flex-1">
        <Suspense fallback={fallback}>
          <CodeEditor path={file.path} value={draft ?? content} onChange={setDraft} className="h-full" />
        </Suspense>
      </div>
    </div>
  );
}

/** What the bot is doing this second — one quiet mono line, only while it works. */
function LiveLine({ messages }: { messages: ChatMessage[] }) {
  const step = currentStep(messages);
  if (!step) return null;
  return (
    <div role="status" className="flex shrink-0 items-center gap-2 border-b border-line px-3 py-1.5 font-mono text-[11.5px] text-ink-2">
      <span className="wb-live-dot size-1.5 shrink-0 rounded-full bg-accent" aria-hidden="true" />
      <span className="shrink-0 text-ink-3">{t('wb.working')}</span>
      <span className="shrink-0">{step.label.replace(/^\w+__/, '')}</span>
      {step.detail ? <span className="min-w-0 truncate text-ink-3">{step.detail}</span> : null}
    </div>
  );
}

export interface WorkbenchFocus { path: string; nonce: number }

export function Workbench({
  messages,
  sessionId,
  focus,
  embedded = false,
  onClose,
}: {
  messages: ChatMessage[];
  sessionId: string;
  /** A file the bot asked to show (workspace_show / `preview` event). */
  focus?: WorkbenchFocus | null;
  embedded?: boolean;
  onClose: () => void;
}) {
  const info = useQuery({
    queryKey: ['workspace-info', sessionId],
    queryFn: () => get<{ session_path?: string }>(`/api/workspace/info?session_id=${encodeURIComponent(sessionId)}`),
    staleTime: Infinity,
  });
  const folder = info.data?.session_path ?? '';
  const stepFiles = useMemo(() => collectFiles(messages, folder), [messages, folder]);

  // Files the bot only asked to show, without writing them in this chat.
  const [shown, setShown] = useState<string[]>([]);
  useEffect(() => setShown([]), [sessionId]);
  const focusPath = focus ? shortPath(focus.path, folder) : '';
  useEffect(() => {
    if (focusPath) setShown((list) => [focusPath, ...list.filter((path) => path !== focusPath)]);
  }, [focusPath, focus?.nonce]);

  const files = useMemo(() => {
    const known = new Set(stepFiles.map((file) => file.path));
    const extra = shown.filter((path) => !known.has(path))
      .map((path): WorkFile => ({ path, kind: fileKind(path), revision: 0, active: false }));
    return [...extra, ...stepFiles];
  }, [stepFiles, shown]);

  const [selected, setSelected] = useState('');
  const [modes, setModes] = useState<Record<string, Mode>>({});
  const [nonce, setNonce] = useState(0);

  /*
   * Follow the bot: whenever a newer write lands (a new file, or a new
   * revision of one), bring it forward. Picking another tab by hand holds
   * only until the bot writes again — that is the point of the column.
   */
  const newest = stepFiles[0] ? `${stepFiles[0].path}#${stepFiles[0].revision}` : '';
  const seen = useRef(newest);
  useEffect(() => {
    if (newest && newest !== seen.current) setSelected(stepFiles[0].path);
    seen.current = newest;
  }, [newest, stepFiles]);
  useEffect(() => { if (focusPath) setSelected(focusPath); }, [focusPath, focus?.nonce]);

  const file = files.find((item) => item.path === selected) ?? files[0];
  const mode: Mode = file && hasPreview(file.kind) ? modes[file.path] ?? 'preview' : 'source';

  return (
    <section
      aria-label={t('wb.title')}
      className={cn('workbench flex min-h-0 min-w-0 flex-1 flex-col bg-surface', embedded ? 'size-full' : 'h-full')}
    >
      <header className="flex shrink-0 items-center gap-2 border-b border-line px-3 py-2">
        {/* In a sheet the dialog already carries the title and the close button. */}
        {embedded ? null : (
          <span className="u-label whitespace-nowrap text-ink-3">
            {t('wb.title')}{files.length ? <span className="font-mono"> · {files.length}</span> : null}
          </span>
        )}
        <span className="flex-1" />
        {file ? (
          <>
            {hasPreview(file.kind) ? (
              <Segmented
                size="sm"
                ariaLabel={t('wb.title')}
                value={mode}
                onChange={(next) => setModes((all) => ({ ...all, [file.path]: next }))}
                items={[{ value: 'preview', label: t('wb.preview') }, { value: 'source', label: t('wb.source') }]}
              />
            ) : null}
            <Button variant="ghost" size="icon-sm" aria-label={t('wb.reload')} onClick={() => setNonce((n) => n + 1)}>
              <RefreshCw />
            </Button>
            <Button variant="ghost" size="icon-sm" aria-label={t('wb.newTab')}
                    onClick={() => window.open(previewUrl(file.path, sessionId, file.revision + nonce), '_blank', 'noopener')}>
              <ArrowUpRight />
            </Button>
          </>
        ) : null}
        {embedded ? null : (
          <Button variant="ghost" size="icon-sm" aria-label={t('wb.close')} onClick={onClose}><X /></Button>
        )}
      </header>

      {files.length ? (
        <nav aria-label={t('wb.files', { count: files.length })} className="wb-tabs flex shrink-0 gap-1 overflow-x-auto border-b border-line px-2 py-1.5">
          {files.map((item) => {
            const Icon = ICONS[item.kind];
            const current = item.path === file?.path;
            return (
              <button
                key={item.path}
                type="button"
                title={item.path}
                aria-current={current ? 'page' : undefined}
                onClick={() => setSelected(item.path)}
                className={cn(
                  'flex min-h-8 max-w-[200px] shrink-0 items-center gap-1.5 rounded-sm px-2 text-[12.5px] transition-colors',
                  current ? 'bg-surface-3 text-ink' : 'text-ink-3 hover:bg-surface-2 hover:text-ink-2',
                )}
              >
                <Icon size={13} className={cn('shrink-0', current && 'text-accent')} />
                <span className="truncate">{item.path.split('/').pop()}</span>
                {item.active ? <span className="wb-live-dot size-1.5 shrink-0 rounded-full bg-accent" aria-label={t('wb.writing')} /> : null}
              </button>
            );
          })}
        </nav>
      ) : null}

      <LiveLine messages={messages} />

      <div className="relative min-h-0 flex-1 overflow-hidden">
        {file ? (
          <>
            <FileView key={`${file.path}#${file.revision}`} file={file} sessionId={sessionId} mode={mode} nonce={nonce} />
          </>
        ) : (
          <Empty icon={PenTool} title={t('wb.emptyTitle')} hint={t('wb.emptyHint')} className="h-full" />
        )}
      </div>
    </section>
  );
}
