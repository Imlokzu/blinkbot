import { useEffect, useMemo, useRef, useState } from 'react';
import { AssistantRuntimeProvider } from '@assistant-ui/react';
import { createPortal } from 'react-dom';
import { List, PanelLeft, PanelRight, Paperclip, Plus, X } from 'lucide-react';
import { Thread } from './Thread';
import { Composer } from './Composer';
import { SessionList } from './SessionList';
import { PinnedPanels } from './PinnedPanels';
import { Workbench, type WorkbenchFocus } from './Workbench';
import { RightPanelMenu } from './RightPanelMenu';
import { collectFiles, workspaceWrites, workspaceWriteId, workbenchHost } from './workFiles';
import { WorkspaceLinksProvider } from './WorkspaceFileLink';
import { ModelMenu } from './ModelMenu';
import { SelectionActions } from './SelectionActions';
import { useChatAppearance } from './useChatAppearance';
import './chat-appearance.css';
import { useChatRuntime } from './useChatRuntime';
import { useLiquidGlass } from './useLiquidGlass';
import { useIsDesk, useIsPhone } from '@/hooks/useMediaQuery';
import { useDrawer } from '@/hooks/useDrawer';
import { useRouteParam } from '@/app/useRoute';
import { useBotEvents } from '@/hooks/useBotEvents';
import { useQuery } from '@tanstack/react-query';
import { get } from '@/lib/api';
import { Button } from '@/components/ui/Button';
import { PhoneNavigationMenu } from '@/components/shell/PhoneNavigationMenu';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { t as workspaceT } from '@/locales/workspace';
import { t as benchT } from '@/locales/workbench';
import { t } from '@/lib/i18n';
import { t as chatT } from '@/locales/chat';
import { t as uploadT } from '@/locales/attachments';
import { useChatFileDrop } from './useChatFileDrop';
import './file-drop.css';

/*
 * Desktop chat keeps conversations, the thread and optional panels separate.
 * Narrow layouts use drawers so controls retain usable touch targets.
 */
/** Плашка «зараз показані розмови проєкту» з виходом назад до всіх. */
function ProjectChip({ name }: { name: string }) {
  return (
    <div className="flex items-center gap-2 border-b border-line px-3 py-2">
      <span className="u-label truncate text-accent">{name}</span>
      <button
        type="button"
        aria-label={t('chat.showAll')}
        className="ml-auto rounded-xs p-0.5 text-ink-3 transition-colors hover:text-ink"
        onClick={() => {
          window.location.hash = '#/chat';
        }}
      >
        <X className="size-3.5" />
      </button>
    </div>
  );
}

const BENCH_WIDTH_KEY = 'claudeBotWorkbenchWidth';
const SESSIONS_KEY = 'claudeBotConversationList';

function readStored(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function store(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* The choice just will not survive a reload. */ }
}

/*
 * The workbench column's width, dragged by its left edge. The chat keeps at
 * least a readable measure: below ~420 px a reply wraps every few words.
 * The same bound is repeated as a CSS max on the column (BENCH_MAX_WIDTH), so
 * a width remembered from a wide screen, or a window narrowed after the
 * drag, cannot push the chat off-screen.
 */
const BENCH_MIN_WIDTH = 360;
const BENCH_MAX_WIDTH = 'calc(100vw - 640px)';
const clampBenchWidth = (value: number) =>
  Math.round(Math.min(Math.max(value, BENCH_MIN_WIDTH), window.innerWidth - 220 - 420));

function useBenchWidth() {
  const [width, setWidth] = useState(() => clampBenchWidth(Number(readStored(BENCH_WIDTH_KEY)) || 560));
  const clamp = clampBenchWidth;
  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    const handle = event.currentTarget;
    handle.setPointerCapture(event.pointerId);
    const startX = event.clientX;
    const startWidth = width;
    let next = startWidth;
    const move = (e: PointerEvent) => { next = clamp(startWidth + startX - e.clientX); setWidth(next); };
    const up = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
      handle.removeEventListener('pointercancel', up);
      store(BENCH_WIDTH_KEY, String(next));
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
    handle.addEventListener('pointercancel', up);
  };
  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const step = event.key === 'ArrowLeft' ? 32 : event.key === 'ArrowRight' ? -32 : 0;
    if (!step) return;
    event.preventDefault();
    setWidth((value) => { const next = clamp(value + step); store(BENCH_WIDTH_KEY, String(next)); return next; });
  };
  return { width, onPointerDown, onKeyDown };
}

export default function ChatPanel() {
  const { appearance, setAppearance } = useChatAppearance();
  const isPhone = useIsPhone();
  const isDesk = useIsDesk();
  const project = useRouteParam('project');
  const chat = useChatRuntime(project);
  const fileDrop = useChatFileDrop(chat.composerEpoch);
  const glassRoot = useRef<HTMLDivElement>(null);
  useLiquidGlass(glassRoot, appearance.blur, appearance.material === 'glass');
  const listDrawer = useDrawer();
  const [panelsOpen, setPanelsOpen] = useState(false);
  const [sessionsOpen, setSessionsOpen] = useState(() => readStored(SESSIONS_KEY) !== 'closed');
  const sessionsToggle = useRef<HTMLButtonElement>(null);
  const panelsToggle = useRef<HTMLButtonElement>(null);
  const toggleSessions = () => setSessionsOpen((open) => {
    store(SESSIONS_KEY, open ? 'closed' : 'open');
    return !open;
  });

  /*
   * The workbench. On the desk it takes the right column's place; narrower,
   * it is a full sheet. It opens by itself the first time a reply writes a
   * file — unless it was closed during that same reply: closing is an answer.
   */
  const [bench, setBench] = useState(false);
  // Narrow layouts: a sheet opened on request, never restored on load.
  const [benchSheet, setBenchSheet] = useState(false);
  const [benchFocus, setBenchFocus] = useState<WorkbenchFocus | null>(null);
  // A "show me" from one chat must not open that file in the next one. A new
  // chat gets its id mid-reply (`onSession`), which is not a switch.
  const lastSession = useRef(chat.sessionId);
  useEffect(() => {
    if (lastSession.current) setBenchFocus(null);
    lastSession.current = chat.sessionId;
  }, [chat.sessionId]);
  const benchWidth = useBenchWidth();
  const closedThisTurn = useRef(false);
  const observedWrites = useRef(new Set<string>());
  const revealedWrites = useRef(new Set<string>());
  const writes = useMemo(() => workspaceWrites(chat.steps), [chat.steps]);
  const recentWriteIds = useMemo(() => new Set(writes.map(workspaceWriteId)), [writes]);
  useEffect(() => {
    closedThisTurn.current = false;
    observedWrites.current.clear();
    revealedWrites.current.clear();
  }, [chat.turnId]);
  useEffect(() => {
    const unseen = writes.some((step) => !observedWrites.current.has(workspaceWriteId(step)));
    for (const step of writes) observedWrites.current.add(workspaceWriteId(step));
    if (!unseen || closedThisTurn.current) return;
    if (isDesk) setBench(true);
    else setBenchSheet(true);
  }, [writes, isDesk]);
  const closeBench = () => { closedThisTurn.current = true; setBench(false); };
  const toggleBench = () => {
    if (isDesk) {
      if (bench) closeBench();
      else setBench(true);
    } else {
      if (benchSheet) closedThisTurn.current = true;
      setBenchSheet((open) => !open);
    }
  };

  // While the chat is on screen, "show me the file" lands in the workbench
  // instead of the floating preview dock.
  useEffect(() => workbenchHost.claim(), []);
  useBotEvents((event) => {
    if (event.type !== 'preview' || !event.path) return;
    setBenchFocus((old) => ({ path: String(event.path), nonce: (old?.nonce ?? 0) + 1 }));
    // Showing/reading an existing file only changes the selection. Automatic
    // opening belongs exclusively to writes in this conversation's stream.
  });
  const benchFiles = useMemo(() => collectFiles(chat.visibleMessages).length, [chat.visibleMessages]);

  useEffect(() => {
    window.__vbotSendMessage = (text: string) => {
      // UI questions can arrive while the originating tool turn is still
      // winding down. Cancel that turn first so the selected answer is not
      // silently rejected by the single-flight send guard.
      if (chat.running) {
        void chat.cancel().then(() => chat.send(text));
      } else {
        void chat.send(text);
      }
    };
    return () => { delete window.__vbotSendMessage; };
  }, [chat.send]);

  /*
   * Проєкт із адреси (`#/chat?project=cats`) — так тека проєктів з «Огляду»
   * справді відкривається, а не просто веде в спільний список.
   */
  const projects = useQuery({
    queryKey: ['projects'],
    queryFn: () => get<{ projects: { id: string; name: string }[] }>('/api/projects'),
    enabled: Boolean(project),
    staleTime: 60_000,
  });
  const projectName =
    projects.data?.projects.find((item) => item.id === project)?.name || project;

  const sessions = useMemo(
    () => (project ? chat.sessions.filter((item) => item.project === project) : chat.sessions),
    [chat.sessions, project],
  );

  /*
   * Which reply may be asked again: the last one, and only once it is
   * finished. Retrying mid-stream would race the answer still arriving, and
   * retrying an older reply would quietly replace a different question.
   */
  const last = chat.messages[chat.messages.length - 1];
  const retryId = !chat.running && last?.role === 'assistant' ? last.id : '';



  const list = (
    <SessionList
      sessions={sessions}
      loading={chat.sessionsLoading}
      current={chat.sessionId}
      onOpen={(id) => {
        void chat.openSession(id);
        listDrawer.setOpen(false);
      }}
      onNew={() => {
        chat.newSession();
        listDrawer.setOpen(false);
      }}
    />
  );

  return (
    <AssistantRuntimeProvider runtime={chat.runtime}>
      <WorkspaceLinksProvider sessionId={chat.sessionId} onOpen={(path) => {
        setBenchFocus((old) => ({ path, nonce: (old?.nonce ?? 0) + 1 }));
        if (isDesk) setBench(true);
        else setBenchSheet(true);
      }}>
      <div data-chat-session={chat.sessionId} className={`chat-layout min-h-0 flex-1 ${isDesk ? 'grid' : 'flex'}`}
        style={isDesk ? { gridTemplateColumns: `${sessionsOpen ? '220px ' : ''}minmax(0, 1fr) auto`, gridTemplateRows: '44px minmax(0, 1fr)' } : undefined}>
        {isDesk ? (
          <header data-chat-toolbar className="flex min-w-0 items-center gap-2 border-b border-line bg-surface px-3"
            style={{ gridColumn: sessionsOpen ? '2 / -1' : '1 / -1', gridRow: 1 }}>
            <Button ref={sessionsToggle} variant={sessionsOpen ? 'quiet' : 'ghost'} size="icon-sm" onClick={toggleSessions}
              aria-label={chatT(sessionsOpen ? 'sessions.hideList' : 'sessions.showList')}
              title={chatT(sessionsOpen ? 'sessions.hideList' : 'sessions.showList')} aria-expanded={sessionsOpen}>
              <PanelLeft />
            </Button>
            <span className="min-w-0 flex-1 truncate text-[13px] text-ink-2">
              {chat.sessions.find((session) => session.id === chat.sessionId)?.title || chatT('chat.newSession')}
            </span>
            <Button variant="ghost" size="icon-sm" onClick={chat.newSession}
              aria-label={chatT('chat.newSession')} title={chatT('chat.newSession')}><Plus /></Button>
            <RightPanelMenu ref={panelsToggle} files={benchFiles}
              mode={bench ? 'workbench' : appearance.sidebarVisible ? 'panels' : 'hidden'}
              onChange={(mode) => {
                if (mode === 'workbench') setBench(true);
                else {
                  closeBench();
                  setAppearance({ sidebarVisible: mode === 'panels' });
                }
              }} />
          </header>
        ) : null}
        {isDesk && sessionsOpen ? (
          <aside className="chat-sessions chat-session-glass flex min-h-0 w-[220px] shrink-0 flex-col border-r border-line bg-surface"
            style={{ gridColumn: 1, gridRow: '1 / -1' }}>
            {project ? <ProjectChip name={projectName} /> : null}
            {list}
          </aside>
        ) : null}

        {/*
         * Планшет: колонка 220 px з'їдала б третину вузького вікна, тож
         * список розмов — та сама ліва шухляда, що й на телефоні.
         */}

        <div ref={glassRoot} {...fileDrop.props} data-file-drop-active={fileDrop.active ? '' : undefined}
          data-wallpaper={appearance.background} data-chat-color={appearance.color}
          className="chat-conversation relative flex min-h-0 min-w-0 flex-1 flex-col"
          style={isDesk ? { gridColumn: sessionsOpen ? 2 : 1, gridRow: 2 } : undefined}>
          {/*
           * Narrow header: conversations | model | new conversation.
           *
           * The model is the title because on a phone it is the setting you
           * change most and the one the prompt bar has no room for. The
           * conversation's own name is one tap away in the list, and the
           * compact face that used to sit here is dropped: at this width it
           * was a 64 px ornament competing with the model name.
           */}
          {!isDesk ? (
            <div className={`chat-narrow-toolbar relative grid shrink-0 items-center gap-1 border-b border-line px-2 py-1.5 ${isPhone ? 'chat-phone-toolbar grid-cols-[auto_auto_minmax(0,1fr)_auto]' : 'grid-cols-[auto_minmax(0,1fr)_auto_auto]'}`}>
              {isPhone ? <PhoneNavigationMenu /> : null}
              <Button
                variant="ghost"
                size="icon"
                aria-label={t('chat.sessions')}
                aria-expanded={listDrawer.open}
                onClick={() => listDrawer.setOpen((open) => !open)}
              >
                <List />
              </Button>
              <div className="flex min-w-0 items-center justify-center gap-1">
                <ModelMenu />
              </div>
              {!isPhone ? <Button
                variant="ghost"
                size="icon"
                data-workbench-trigger=""
                aria-label={benchT(benchSheet ? 'wb.close' : 'wb.open')}
                aria-expanded={benchSheet}
                className="relative"
                onClick={toggleBench}
              >
                <PanelRight />
                {benchFiles ? <span className="wb-badge" aria-hidden="true">{benchFiles}</span> : null}
              </Button> : null}
              <Button
                variant="ghost"
                size="icon"
                aria-label={t('chat.newSession')}
                onClick={() => chat.newSession()}
              >
                <Plus />
              </Button>
              {listDrawer.open
                ? createPortal(
                    <>
                      <div
                        {...listDrawer.veilProps}
                        className="u-veil fixed inset-0"
                        style={{ background: 'var(--c-overlay)', zIndex: 'var(--z-drawer)' }}
                      />
                      <div
                        {...listDrawer.panelProps}
                        aria-label={t('chat.sessions')}
                        className="chat-session-drawer chat-session-glass u-sheet-l u-safe-t u-safe-b fixed inset-y-0 left-0 flex w-[300px] max-w-[85vw] flex-col border-r border-line bg-surface"
                        style={{ zIndex: 'var(--z-drawer)' }}
                      >
                        <header className="flex items-center justify-between border-b border-line px-4 py-3">
                          <span className="text-[15px] font-semibold text-ink">{t('chat.sessions')}</span>
                          <div className="flex items-center gap-1">
                            <Button variant="ghost" size="icon" aria-label={chatT('chat.newSession')} onClick={() => {
                              chat.newSession();
                              listDrawer.setOpen(false);
                            }}><Plus /></Button>
                          <button
                            type="button"
                            aria-label={t('chat.close')}
                            onClick={() => listDrawer.setOpen(false)}
                            className="flex min-h-11 min-w-11 items-center justify-center rounded-full text-ink-3"
                          >
                            <X size={18} />
                          </button>
                          </div>
                        </header>
                        {project ? <ProjectChip name={projectName} /> : null}
                        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">{list}</div>
                      </div>
                    </>,
                    document.body,
                  )
                : null}
              <Dialog open={panelsOpen} onOpenChange={setPanelsOpen}>
                <DialogContent
                  title={workspaceT('pins.title')}
                  side={isPhone ? 'bottom' : 'center'}
                  className="h-[min(78dvh,680px)] p-0"
                  bodyClassName="p-0 sm:p-0"
                >
                  <PinnedPanels embedded messages={chat.messages} sessionId={chat.sessionId} />
                </DialogContent>
              </Dialog>
              <Dialog open={benchSheet} onOpenChange={(open) => {
                if (!open) closedThisTurn.current = true;
                setBenchSheet(open);
              }}>
                <DialogContent
                  title={benchT('wb.title')}
                  side={isPhone ? 'bottom' : 'center'}
                  className="h-[min(92dvh,900px)] p-0 sm:max-w-[min(960px,calc(100vw-32px))]"
                  bodyClassName="p-0 sm:p-0"
                  onCloseAutoFocus={(event) => {
                    const trigger = glassRoot.current?.querySelector<HTMLButtonElement>('[data-workbench-trigger]');
                    if (!trigger) return;
                    event.preventDefault();
                    trigger.focus({ preventScroll: true });
                  }}
                >
                  <Workbench embedded messages={chat.visibleMessages} sessionId={chat.sessionId} focus={benchFocus}
                    recentWriteIds={recentWriteIds} revealedWrites={revealedWrites}
                    onClose={() => { closedThisTurn.current = true; setBenchSheet(false); }} />
                </DialogContent>
              </Dialog>
            </div>
          ) : null}

          <Thread
            compactedFrom={chat.compactedFrom}
            retryId={retryId}
            onRetry={chat.retry}
            onReact={chat.react}
            composer={
              <Composer
                key={chat.composerEpoch}
                fileDrop={fileDrop.handler}
                lean={!isDesk}
                onOpenPanels={() => setPanelsOpen(true)}
                onOpenWorkbench={isPhone ? toggleBench : undefined}
                busy={chat.running || chat.queuedSend}
                queued={chat.queuedSend}
                usedTokens={chat.usedTokens}
                sessionId={chat.sessionId}
                onSend={chat.send}
                onStop={chat.cancel}
                // Після стискання на диску лежить уже переказ — перечитуємо
                // розмову, інакше на екрані лишились би репліки, яких у
                // контексті бота вже немає.
                onCompacted={() => void chat.openSession(chat.sessionId, true, false)}
              />
            }
          />
          {fileDrop.active ? <div data-chat-file-drop-overlay className="chat-file-drop-overlay" role="status">
            <div>
              <Paperclip size={28} strokeWidth={1.75} aria-hidden="true" />
              <strong>{uploadT('drop.title')}</strong>
              <p>{uploadT('drop.hint')}</p>
            </div>
          </div> : null}
        </div>

        {isDesk && bench ? (
          <div className="relative flex min-h-0 shrink-0 border-l border-line"
            style={{ width: benchWidth.width, maxWidth: BENCH_MAX_WIDTH, gridColumn: sessionsOpen ? 3 : 2, gridRow: 2 }}>
            <div
              role="separator"
              aria-orientation="vertical"
              aria-label={benchT('wb.resize')}
              tabIndex={0}
              className="wb-resize"
              onPointerDown={benchWidth.onPointerDown}
              onKeyDown={benchWidth.onKeyDown}
            />
            <Workbench messages={chat.visibleMessages} sessionId={chat.sessionId} focus={benchFocus} onClose={() => {
              closeBench();
              panelsToggle.current?.focus({ preventScroll: true });
            }}
              recentWriteIds={recentWriteIds} revealedWrites={revealedWrites} />
          </div>
        ) : isDesk && appearance.sidebarVisible ? (
          <PinnedPanels messages={chat.messages} sessionId={chat.sessionId} onClose={() => {
            setAppearance({ sidebarVisible: false });
            panelsToggle.current?.focus({ preventScroll: true });
          }} />
        ) : null}

        {/* Selecting text in a reply turns it into the next question. */}
        <SelectionActions onAsk={(text) => void chat.send(text)} />
      </div>
      </WorkspaceLinksProvider>
    </AssistantRuntimeProvider>
  );
}
