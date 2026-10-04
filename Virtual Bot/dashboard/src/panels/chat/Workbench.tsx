import { lazy, Suspense, useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowUpRight, Code2, FileText, FolderOpen, Globe, Image as ImageIcon, PenTool, RefreshCw, Save, Workflow, X,
} from '../../vendor/solar-icons/compat.ts';
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
import type { WorkspaceLocation } from './workspaceLinks';
import { AgentFilePreview } from './AgentFilePreview';

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

function FileView({
  file,
  sessionId,
  mode,
  nonce,
  draft,
  onDraft,
  live,
  location,
  onSave,
  saving,
  recentWriteIds,
  revealedWrites,
  writingPaths,
}: {
  file: WorkFile;
  sessionId: string;
  mode: Mode;
  nonce: number;
  /** Unsaved editor text, held by the workbench so a tab switch keeps it. */
  draft: string | null;
  onDraft: (next: string | null) => void;
  /** Revision on show for each path — see `live` in Workbench. */
  live: RefObject<Map<string, { revision: number; writing: boolean }>>;
  location: WorkspaceLocation;
  onSave: () => Promise<void>;
  saving: boolean;
  recentWriteIds: ReadonlySet<string>;
  revealedWrites: RefObject<Set<string>>;
  writingPaths: ReadonlySet<string>;
}) {
  const { resolved } = useTheme();
  const client = useQueryClient();
  const toast = useToast();
  const [revealing, setRevealing] = useState(() => Boolean(file.writeId && recentWriteIds.has(file.writeId) && !revealedWrites.current.has(file.writeId)));
  const revision = file.revision + nonce;
  // Capture confirmed prior text once, without reading a file owned by a writer.
  const [previous] = useState(() => {
    const cached = client.getQueryCache().findAll({ queryKey: ['wb-file', sessionId, file.path] })
      .filter((query) => Number(query.queryKey[3]) < file.revision + (file.writing ? 1 : 0))
      .sort((a, b) => b.state.dataUpdatedAt - a.state.dataUpdatedAt || Number(b.queryKey[4] ?? 0) - Number(a.queryKey[4] ?? 0));
    const confirmed = cached.map((query) => query.state.data as FileData | undefined)
      .find((value) => value && !value.binary && !value.too_large);
    return confirmed?.content ?? '';
  });
  const media = file.kind === 'html' || file.kind === 'image';
  const needsText = !(media && mode === 'preview');
  // Reload is separate from the agent revision: adding them could reuse a
  // refreshed old document's cache entry for a new write.
  const queryKey = ['wb-file', sessionId, file.path, file.revision, nonce];
  const data = useQuery({
    queryKey,
    queryFn: () => get<FileData>(fileQuery(sessionId, file.path)),
    enabled: needsText && !file.writing,
    staleTime: Infinity,
  });

  const write = async (path: string, content: string) => {
    await post('/api/workspace/file', { path, content, session_id: sessionId });
  };
  // What was just written is what is on disk: the other view of this file
  // (Code after Preview, or the reverse) must show it, not the fetched copy.
  const remember = (content: string) => {
    client.setQueryData<FileData>(queryKey, (old) => (old ? { ...old, content } : old));
  };
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
        event.preventDefault(); if (!file.writing) void onSave();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onSave, file.writing]);
  /*
   * The drawing autosaves on a delay. If the bot rewrote the file meanwhile
   * (this view is being replaced by the newer revision), a write now would
   * put an edit of the old drawing over the bot's new one, unseen. A path
   * that is no longer listed at all (another chat opened) still saves.
   */
  const autosave = async (json: string) => {
    const showing = live.current.get(`${sessionId}:${file.path}`);
    if (showing && (showing.writing || showing.revision !== revision)) return;
    await write(file.path, json);
    remember(json);
  };

  const agentPreview = (content: string | undefined, busy: boolean) => <AgentFilePreview path={file.path} content={content}
    previous={previous} rich={file.kind === 'markdown' && mode === 'preview'}
    workspace={{ sessionId, path: file.path, location, isWriting: (path) => writingPaths.has(path) }}
    busy={busy} updated={previous !== ''} onRevealed={busy ? undefined : () => {
      if (file.writeId) revealedWrites.current.add(file.writeId);
      setRevealing(false);
    }} />;
  if (file.writing || (revealing && data.isPending && needsText)) {
    const text = ['markdown', 'code', 'text', 'html', 'mermaid'].includes(file.kind) ? file.writeContent : '';
    return agentPreview(text, true);
  }
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
  if (revealing && ['markdown', 'code', 'text', 'html'].includes(file.kind)) {
    return agentPreview(content, false);
  }

  if (mode === 'preview' && (file.kind === 'drawing' || file.kind === 'mermaid')) {
    return (
      <Suspense fallback={fallback}>
        <DrawingView
          kind={file.kind}
          source={content}
          dark={resolved === 'dark'}
          onSave={file.kind === 'drawing' ? autosave : undefined}
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
  return (
    <div className="flex size-full flex-col">
      {draft !== null && draft !== content ? (
        <div className="flex shrink-0 items-center justify-end gap-2 border-b border-line px-3 py-1.5">
          <span className="text-[11px] text-accent">{t('wb.unsaved')}</span>
          <Button variant="solid" size="sm" disabled={saving} onClick={() => void onSave()}><Save />{t(saving ? 'wb.saving' : 'wb.save')}</Button>
        </div>
      ) : null}
      <div className="min-h-0 flex-1">
        <Suspense fallback={fallback}>
          {mode === 'preview' && file.kind === 'markdown' ? (
            <NoteEditor value={draft ?? content} onChange={(next) => onDraft(next === content ? null : next)}
              workspace={{ sessionId, path: file.path, location,
                isWriting: (path) => writingPaths.has(path),
                canSave: (path) => !live.current.get(`${sessionId}:${path}`)?.writing,
                captureSaveGuard: (path) => {
                  const key = `${sessionId}:${path}`;
                  const baseline = live.current.get(key)?.revision;
                  return () => {
                    const current = live.current.get(key);
                    return !current?.writing && current?.revision === baseline;
                  };
                },
              }} createDrawing={async () => {
                const path = `${file.path.replace(/\.(md|markdown)$/i, '')}.drawings/${crypto.randomUUID()}.excalidraw`;
                await write(path, JSON.stringify({ type: 'excalidraw', version: 2, source: 'claude-bot', elements: [], appState: {}, files: {} }));
                return path;
              }} />
          ) : <CodeEditor path={file.path} value={draft ?? content} onChange={onDraft} className="h-full" />}
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
  recentWriteIds = new Set<string>(),
  revealedWrites: suppliedRevealedWrites,
}: {
  messages: ChatMessage[];
  sessionId: string;
  /** A file the bot asked to show (workspace_show / `preview` event). */
  focus?: WorkbenchFocus | null;
  embedded?: boolean;
  onClose: () => void;
  recentWriteIds?: ReadonlySet<string>;
  revealedWrites?: RefObject<Set<string>>;
}) {
  const toast = useToast();
  const ownRevealedWrites = useRef(new Set<string>());
  const revealedWrites = suppliedRevealedWrites ?? ownRevealedWrites;
  const client = useQueryClient();
  const [revealing, setRevealing] = useState(false);
  const pending = useRef(new Set<string>());
  const [savingViews, setSavingViews] = useState<Record<string, boolean>>({});
  const info = useQuery({
    queryKey: ['workspace-info', sessionId],
    queryFn: () => get<WorkspaceLocation & { reveal_available?: boolean }>(`/api/workspace/info?session_id=${encodeURIComponent(sessionId)}`),
    staleTime: Infinity,
  });
  const folder = info.data?.session_path ?? '';
  const stepFiles = useMemo(() => collectFiles(messages, folder), [messages, folder]);

  /*
   * Files the bot only asked to show, without writing them in this chat.
   * Kept as the event named them (the real `sessions/<slug>/…`): the folder
   * that folds that into `session/…` arrives later, and shortening before it
   * did would leave the long form behind as a second tab for the same file.
   */
  const [shown, setShown] = useState<string[]>([]);
  // Cleared on a switch to another chat; a new chat getting its id mid-reply
  // is not one, and the file it just showed stays.
  const lastSession = useRef(sessionId);
  useEffect(() => {
    if (lastSession.current) setShown([]);
    lastSession.current = sessionId;
  }, [sessionId]);
  const [selected, setSelected] = useState('');
  useEffect(() => {
    if (!focus) return;
    setShown((list) => [focus.path, ...list.filter((path) => path !== focus.path)]);
    setSelected(focus.path);
  }, [focus]);

  const files = useMemo(() => {
    const known = new Set(stepFiles.map((file) => file.path));
    const extra = shown
      .map((path) => shortPath(path, folder))
      .filter((path, index, all) => !known.has(path) && all.indexOf(path) === index)
      .map((path): WorkFile => ({ path, kind: fileKind(path), revision: 0, active: false }));
    return [...extra, ...stepFiles];
  }, [stepFiles, shown, folder]);

  const [modes, setModes] = useState<Record<string, Mode>>({});
  const [nonce, setNonce] = useState(0);
  // Unsaved editor text per view (path and revision): a tab switch keeps it,
  // a rewrite by the bot or a reload starts clean, as the file did.
  const [drafts, setDrafts] = useState<Record<string, string>>({});

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

  // `selected` may hold the long form from a show event; folding is a no-op
  // on an already short path.
  const file = files.find((item) => item.path === shortPath(selected, folder)) ?? files[0];
  const mode: Mode = file && hasPreview(file.kind) ? modes[file.path] ?? 'preview' : 'source';
  const viewKey = file ? `${sessionId}:${file.path}#${file.revision + nonce}` : '';
  const saveKey = file ? `${sessionId}:${file.path}` : '';
  const saveDraft = async () => {
    const submitted = drafts[viewKey];
    if (!file || file.writing || submitted === undefined || pending.current.has(saveKey)) return;
    const queryKey = ['wb-file', sessionId, file.path, file.revision, nonce];
    pending.current.add(saveKey);
    setSavingViews((all) => ({ ...all, [saveKey]: true }));
    try {
      await post('/api/workspace/file', { path: file.path, content: submitted, session_id: sessionId });
      client.setQueryData<FileData>(queryKey, (old) => old ? { ...old, content: submitted } : old);
      // Compare against current parent state, including edits in a remounted tab.
      setDrafts((all) => {
        if (all[viewKey] !== submitted) return all;
        const { [viewKey]: _saved, ...rest } = all;
        return rest;
      });
      toast.ok(t('wb.saved'), file.path);
    } catch (error) {
      toast.error(t('wb.saveError'), (error as Error).message);
    } finally {
      pending.current.delete(saveKey);
      setSavingViews((all) => ({ ...all, [saveKey]: false }));
    }
  };

  /*
   * The revision each path is showing right now. A view that is on its way
   * out (the bot rewrote its file) reads this from its unmount cleanup, which
   * React runs after this commit's layout effects, so it sees the new value.
   */
  const live = useRef(new Map<string, { revision: number; writing: boolean }>());
  const writingPaths = useMemo(() => new Set(files.filter((item) => item.writing).map((item) => item.path)), [files]);
  useLayoutEffect(() => {
    live.current = new Map(files.map((item) => [`${sessionId}:${item.path}`, { revision: item.revision + nonce, writing: Boolean(item.writing) }]));
  }, [files, nonce, sessionId]);

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
                items={[{ value: 'preview', label: t(file.kind === 'markdown' ? 'wb.document' : 'wb.preview') }, { value: 'source', label: t('wb.source') }]}
              />
            ) : null}
            <Button variant="ghost" size="icon-sm" aria-label={t('wb.reload')} onClick={() => setNonce((n) => n + 1)}>
              <RefreshCw />
            </Button>
            {info.data?.reveal_available ? (
              <Button variant="ghost" size="icon-sm" aria-label={t('wb.finder')} title={t('wb.finder')} disabled={revealing}
                onClick={() => {
                  setRevealing(true);
                  void post('/api/workspace/reveal', { path: file.path, session_id: sessionId })
                    .catch(() => toast.error(t('wb.finderError'))).finally(() => setRevealing(false));
                }}><FolderOpen /></Button>
            ) : null}
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
            <FileView
              // Keep a write's editor mounted through active -> confirmed. A
              // following write gets a fresh baseline, even if its id is reused.
              key={`${sessionId}:${file.path}:${nonce}:${file.revision - (!file.writing && file.writeId ? 1 : 0)}:${file.writeId ?? ''}`}
              file={file}
              sessionId={sessionId}
              mode={mode}
              nonce={nonce}
              draft={drafts[viewKey] ?? null}
              onDraft={(next) => setDrafts((all) => {
                if (next === null) {
                  const { [viewKey]: _gone, ...rest } = all;
                  return rest;
                }
                return { ...all, [viewKey]: next };
              })}
              live={live}
              location={info.data ?? {}}
              onSave={saveDraft}
              saving={savingViews[saveKey] ?? false}
              recentWriteIds={recentWriteIds}
              revealedWrites={revealedWrites}
              writingPaths={writingPaths}
            />
          </>
        ) : (
          <Empty icon={PenTool} title={t('wb.emptyTitle')} hint={t('wb.emptyHint')} className="h-full" />
        )}
      </div>
    </section>
  );
}
