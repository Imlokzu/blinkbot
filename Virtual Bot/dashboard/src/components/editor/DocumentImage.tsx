import { createContext, lazy, Suspense, useContext, useRef, useState } from 'react';
import Image from '@tiptap/extension-image';
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from '@tiptap/react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { PenTool, ChevronDown, ChevronUp } from 'lucide-react';
import { get, post } from '@/lib/api';
import { useTheme } from '@/hooks/useTheme';
import { Button } from '@/components/ui/Button';
import { useToast } from '@/components/ui/Toaster';
import { Empty, SkeletonList } from '@/components/ui/Feedback';
import { t } from '@/locales/editor';
import { workspaceLinkPath, type WorkspaceLocation } from '@/panels/chat/workspaceLinks';

export const DocumentWorkspace = createContext<{ sessionId: string; path: string; location: WorkspaceLocation; isWriting?: (path: string) => boolean; canSave?: (path: string) => boolean; captureSaveGuard?: (path: string) => () => boolean } | null>(null);
const DrawingView = lazy(() => import('@/panels/chat/DrawingView'));

function DocumentImageView({ node }: NodeViewProps) {
  const context = useContext(DocumentWorkspace);
  const { resolved } = useTheme();
  const [expanded, setExpanded] = useState(false);
  const client = useQueryClient();
  const toast = useToast();
  const pendingSave = useRef<Promise<void>>(Promise.resolve());
  const source = String(node.attrs.src ?? '');
  const path = context ? workspaceLinkPath(source, context.location, context.path) : null;
  const drawing = /\.excalidraw(?:\.json)?$/i.test(source);
  const writing = Boolean(path && context?.isWriting?.(path));
  const queryKey = ['document-drawing', context?.sessionId, path];
  const query = useQuery({
    queryKey,
    queryFn: async () => {
      // A collapsed editor flushes its last edit; read after that write settles.
      await pendingSave.current.catch(() => undefined);
      return get<{ content: string }>(`/api/workspace/file?path=${encodeURIComponent(path!)}&session_id=${encodeURIComponent(context!.sessionId)}`);
    },
    enabled: drawing && expanded && !writing && Boolean(context && path),
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  if (!drawing || !context || !path) {
    const src = path && context ? `/preview/${path.split('/').map(encodeURIComponent).join('/')}?session_id=${encodeURIComponent(context.sessionId)}` : source;
    return <NodeViewWrapper><img src={src} alt={String(node.attrs.alt ?? '')} title={node.attrs.title ?? undefined} /></NodeViewWrapper>;
  }
  return <NodeViewWrapper className="document-drawing" contentEditable={false} tabIndex={0}
    onPointerDownCapture={(event: React.PointerEvent<HTMLElement>) => {
      // Canvas shortcuts belong to Excalidraw, not the surrounding document.
      if ((event.target as HTMLElement).tagName === 'CANVAS') event.currentTarget.focus({ preventScroll: true });
    }}>
    <header className="flex items-center gap-2 border-b border-line px-3 py-2">
      <PenTool className="size-4 text-accent" />
      <span title={source} className="min-w-0 flex-1 truncate text-[12px] text-ink-2">{node.attrs.alt || t('drawingTitle')}</span>
      <Button variant="ghost" size="sm" onClick={() => setExpanded((open) => !open)}>
        {expanded ? <ChevronUp /> : <ChevronDown />}{t(expanded ? 'closeDrawing' : 'openDrawing')}
      </Button>
    </header>
    {expanded ? <div className="h-[420px] min-h-0">
      {writing ? <SkeletonList rows={6} /> : query.isError ? <Empty title={t('drawingFailed')} /> : query.data && !query.isFetching ? (
        <Suspense fallback={<SkeletonList rows={6} />}>
          <DrawingView key={path} kind="drawing" source={query.data.content} dark={resolved === 'dark'}
            onSave={(content) => {
              // A scene the agent is replacing must not receive an old editor's
              // unmount flush. The writing view already owns this file.
              if (context.canSave && !context.canSave(path)) return Promise.resolve();
              const stillCurrent = context.captureSaveGuard?.(path);
              const request = pendingSave.current.catch(() => undefined).then(async () => {
                if (stillCurrent && !stillCurrent()) return;
                if (context.canSave && !context.canSave(path)) return;
                await post('/api/workspace/file', { path, content, session_id: context.sessionId });
                client.setQueryData(queryKey, { content });
              }).catch((error) => { toast.error(t('drawingFailed')); throw error; });
              pendingSave.current = request;
              return request;
            }} />
        </Suspense>
      ) : <SkeletonList rows={6} />}
    </div> : null}
  </NodeViewWrapper>;
}

/** Drawings use ordinary Markdown image references to separate scene files. */
export const DocumentImage = Image.extend({
  addNodeView() {
    return ReactNodeViewRenderer(DocumentImageView, {
      stopEvent: ({ event }) => event.target instanceof Element && Boolean(event.target.closest('.document-drawing')),
    });
  },
});
