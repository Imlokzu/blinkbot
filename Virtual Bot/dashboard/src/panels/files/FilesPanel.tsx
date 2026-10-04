import { useCallback, useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronRight, Code, ExternalLink, Eye, File, Folder, FolderOpen, RotateCw, Save } from '../../vendor/solar-icons/compat.ts';
import { Panel, PanelHead } from '@/components/ui/Panel';
import { Button } from '@/components/ui/Button';
import { Empty, SkeletonList } from '@/components/ui/Feedback';
import { useToast } from '@/components/ui/Toaster';
import { CodeEditor } from '@/components/editor/CodeEditor';
import { get, post } from '@/lib/api';
import { cn } from '@/lib/cn';
import { glue } from '@/lib/glue';
import { useIsDesk } from '@/hooks/useMediaQuery';
import { useRouteParam } from '@/app/useRoute';
import { t as workbenchText } from '@/locales/workbench';
import { editDraft, EMPTY_FILE_DRAFT, receiveFile, saveCompleted, type FileDraftState } from './fileDraft';

/*
 * Робоча тека бота.
 *
 * Тека вантажиться лінькувато, по кліку: бот кладе сюди все, що завантажив і
 * згенерував, і рекурсивне дерево на старті було б і повільним, і марним —
 * дивляться зазвичай у дві-три теки.
 */

interface Entry {
  name: string;
  path: string;
  type: 'dir' | 'file';
  size: number;
  mtime: number;
}

interface FileData {
  path: string;
  size: number;
  binary?: boolean;
  too_large?: boolean;
  content: string;
}

const HTML_EXTENSIONS = new Set(['html', 'htm']);

function previewUrl(path: string): string {
  return `/preview/${path.split('/').map(encodeURIComponent).join('/')}`;
}

function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`;
  return `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
}

export default function FilesPanel() {
  const isDesk = useIsDesk();
  const toast = useToast();
  const client = useQueryClient();
  const requestedPath = useRouteParam('path');

  const [dir, setDir] = useState('');
  const [openPath, setOpenPath] = useState<string | null>(null);
  const [draftState, setDraftState] = useState<FileDraftState>(EMPTY_FILE_DRAFT);
  const [saving, setSaving] = useState(false);
  const draft = draftState.draft;
  const dirty = draftState.dirty;
  /* HTML-файли мають два обличчя: код і відрендерена сторінка. Типовий
     режим — перегляд: саме заради нього бот ці файли й пише. */
  const [showPreview, setShowPreview] = useState(true);

  useEffect(() => {
    if (!requestedPath) return;
    setOpenPath(requestedPath);
    const slash = requestedPath.lastIndexOf('/');
    setDir(slash > 0 ? requestedPath.slice(0, slash) : '');
  }, [requestedPath]);

  const listing = useQuery({
    queryKey: ['workspace', dir],
    queryFn: () => get<{ path: string; entries: Entry[] }>(`/api/workspace/list?path=${encodeURIComponent(dir)}`),
  });

  const file = useQuery({
    queryKey: ['workspace-file', openPath],
    queryFn: () => get<FileData>(`/api/workspace/file?path=${encodeURIComponent(openPath!)}`),
    enabled: !!openPath,
  });

  useEffect(() => {
    if (file.data && file.data.path === openPath) {
      setDraftState((state) => receiveFile(state, file.data!.path, file.data!.content ?? ''));
    }
  }, [file.data]);

  useEffect(() => {
    setDraftState((state) => state.path === openPath ? state : { ...EMPTY_FILE_DRAFT, path: openPath });
  }, [openPath]);

  const save = useCallback(async () => {
    if (!openPath || saving) return;
    const submittedPath = openPath;
    const submittedDraft = draft;
    setSaving(true);
    try {
      await post('/api/workspace/file', { path: submittedPath, content: submittedDraft });
      // A reload that was already in flight must not put its older response
      // over the confirmed save. Cancel it and make the query cache durable.
      await client.cancelQueries({ queryKey: ['workspace-file', submittedPath] });
      client.setQueryData<FileData>(['workspace-file', submittedPath], (current) => current
        ? { ...current, content: submittedDraft }
        : current);
      setDraftState((state) => saveCompleted(state, submittedPath, submittedDraft));
      toast.ok('Збережено', submittedPath);
      void client.invalidateQueries({ queryKey: ['workspace', dir] });
    } catch (error) {
      toast.error('Не вдалося зберегти', (error as Error).message);
    } finally {
      setSaving(false);
    }
  }, [client, dir, draft, openPath, saving, toast]);

  // Ctrl/Cmd+S — очікувана дія в будь-якому редакторі; без неї правку легко
  // загубити, перемкнувши файл.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key === 's') {
        event.preventDefault();
        void save();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [save]);

  const crumbs = dir ? dir.split('/') : [];

  const browser = (
    <div className="flex min-h-0 flex-col">
      <div className="flex shrink-0 flex-wrap items-center gap-1 px-3 py-3 text-[12px]">
        <button
          type="button"
          onClick={() => setDir('')}
          className={cn('rounded-xs px-1 py-0.5 hover:bg-surface-2', dir ? 'text-ink-2' : 'text-ink')}
        >
          тека бота
        </button>
        {crumbs.map((part, index) => (
          <span key={index} className="flex items-center gap-1">
            <ChevronRight className="size-3 text-ink-3" />
            <button
              type="button"
              onClick={() => setDir(crumbs.slice(0, index + 1).join('/'))}
              className={cn(
                'rounded-xs px-1 py-0.5 hover:bg-surface-2',
                index === crumbs.length - 1 ? 'text-ink' : 'text-ink-2',
              )}
            >
              {part}
            </button>
          </span>
        ))}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {listing.isPending ? (
          <SkeletonList rows={8} className="px-1" />
        ) : listing.isError ? (
          <Empty title="Тека недоступна" hint={(listing.error as Error).message} />
        ) : listing.data?.entries.length === 0 ? (
          <Empty icon={FolderOpen} title="Порожня тека" />
        ) : (
          <ul className="space-y-0.5">
            {listing.data?.entries.map((entry) => {
              const active = entry.path === openPath;
              const Icon = entry.type === 'dir' ? Folder : File;
              return (
                <li key={entry.path}>
                  <button
                    type="button"
                    onClick={() => (entry.type === 'dir' ? setDir(entry.path) : setOpenPath(entry.path))}
                    className={cn(
                      'flex w-full items-center gap-2.5 rounded-md px-2.5 py-1.5 text-left transition-colors',
                      active ? 'bg-accent-soft' : 'hover:bg-surface-2',
                    )}
                  >
                    <Icon
                      className="size-4 shrink-0 text-ink-3"
                      strokeWidth={1.75}
                      style={entry.type === 'dir' ? { color: 'var(--c-accent)' } : undefined}
                    />
                    <span className="min-w-0 flex-1 truncate text-[13px] text-ink">{entry.name}</span>
                    {entry.type === 'file' ? (
                      <span className="u-data shrink-0 text-[10px] text-ink-3">{humanSize(entry.size)}</span>
                    ) : null}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );

  const isHtml = openPath ? HTML_EXTENSIONS.has(openPath.split('.').pop()?.toLowerCase() ?? '') : false;

  const editor = (
    <Panel flush className="min-h-0 flex-1 overflow-hidden">
      {!openPath ? (
        <Empty
          icon={File}
          title="Файл не вибрано"
          hint={glue('Обери файл ліворуч — текстові відкриються в редакторі.')}
        />
      ) : file.isPending ? (
        <div className="p-4">
          <SkeletonList rows={10} />
        </div>
      ) : file.isError ? (
        <Empty title="Не вдалося відкрити" hint={(file.error as Error).message} />
      ) : file.data?.binary ? (
        <Empty title="Двійковий файл" hint={glue('Показати як текст не вийде. Розмір: ') + humanSize(file.data.size)} />
      ) : file.data?.too_large ? (
        <Empty title="Завеликий файл" hint={glue('Редактор його не відкриє. Розмір: ') + humanSize(file.data.size)} />
      ) : (
        <>
          <div className="shrink-0 border-b border-line px-3 py-2">
            <PanelHead
              className="mb-0"
              label={openPath.split('/').pop() ?? ''}
              hint={draftState.external ? workbenchText('wb.externalChange') : dirty ? workbenchText('wb.unsaved') : humanSize(file.data?.size ?? 0)}
              actions={
                <>
                  {isHtml ? (
                    <>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={showPreview ? 'Показати код' : 'Показати сторінку'}
                        onClick={() => setShowPreview((v) => !v)}
                      >
                        {showPreview ? <Code /> : <Eye />}
                      </Button>
                      {/* Відкриття у новій вкладці йде через наш /preview/ —
                          так файл працює і з телефона, і через тунель, бо
                          адреса лишається на цьому ж домені. */}
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label="Відкрити в новій вкладці"
                        onClick={() => window.open(previewUrl(openPath!), '_blank', 'noopener')}
                      >
                        <ExternalLink />
                      </Button>
                    </>
                  ) : null}
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label="Перечитати"
                    disabled={saving}
                    onClick={() => void file.refetch()}
                  >
                    <RotateCw />
                  </Button>
                  <Button variant={dirty ? 'solid' : 'ghost'} size="sm" disabled={!dirty || saving} onClick={() => void save()}>
                    <Save />
                    Зберегти
                  </Button>
                </>
              }
            />
            {draftState.external ? <p role="status" className="mt-2 text-[11px] text-warn">{workbenchText('wb.externalChange')}</p> : null}
          </div>
          {isHtml && showPreview ? (
            <div className="min-h-0 flex-1 overflow-hidden">
              <iframe
                title={openPath.split('/').pop()}
                src={previewUrl(openPath)}
                sandbox="allow-scripts"
                className="size-full border-0 bg-white"
              />
            </div>
          ) : (
            <div className="min-h-0 flex-1 overflow-auto">
              <CodeEditor
                path={openPath}
                value={draft}
                onChange={(next) => {
                  setDraftState((state) => editDraft(state, next));
                }}
              />
            </div>
          )}
        </>
      )}
    </Panel>
  );

  if (!isDesk) {
    return (
      <div className="flex min-h-0 flex-1 flex-col gap-3 p-4">
        {openPath ? (
          <>
            <Button variant="ghost" size="sm" className="self-start" onClick={() => setOpenPath(null)}>
              ← До списку
            </Button>
            {editor}
          </>
        ) : (
          <Panel flush className="min-h-0 flex-1">
            {browser}
          </Panel>
        )}
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1">
      <aside className="w-[280px] shrink-0 border-r border-line bg-surface">{browser}</aside>
      <div className="flex min-h-0 min-w-0 flex-1 flex-col p-4">{editor}</div>
    </div>
  );
}
