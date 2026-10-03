import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react';

export type FileDropHandler = (files: File[]) => void;

/** File names are protected until drop; the advertised type is enough for feedback. */
export function isFileTransfer(transfer: DataTransfer | null): boolean {
  return Boolean(transfer && Array.from(transfer.types).includes('Files'));
}

/** Never follow dragged URLs or recursively read dropped directories. */
export function transferredFiles(transfer: DataTransfer): File[] {
  const items = Array.from(transfer.items ?? []);
  if (items.length) {
    return items.filter(item => item.kind === 'file' && !item.webkitGetAsEntry?.()?.isDirectory)
      .map(item => item.getAsFile()).filter((file): file is File => file !== null);
  }
  return Array.from(transfer.files ?? []);
}

export function useChatFileDrop(scope: number) {
  const handler = useRef<FileDropHandler | null>(null);
  const depth = useRef(0);
  const [active, setActive] = useState(false);
  const reset = useCallback(() => { depth.current = 0; setActive(false); }, []);
  useEffect(reset, [reset, scope]);
  useEffect(() => {
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') reset(); };
    const visibility = () => { if (document.hidden) reset(); };
    // Reject unclaimed file drops without letting the browser replace this chat tab.
    const rejectOutside = (event: globalThis.DragEvent) => {
      if (!event.defaultPrevented && isFileTransfer(event.dataTransfer)) {
        event.preventDefault();
        if (event.dataTransfer) event.dataTransfer.dropEffect = 'none';
      }
    };
    document.addEventListener('dragover', rejectOutside);
    document.addEventListener('drop', rejectOutside);
    document.addEventListener('dragend', reset);
    document.addEventListener('keydown', escape);
    document.addEventListener('visibilitychange', visibility);
    window.addEventListener('blur', reset);
    return () => {
      document.removeEventListener('dragover', rejectOutside);
      document.removeEventListener('drop', rejectOutside);
      document.removeEventListener('dragend', reset);
      document.removeEventListener('keydown', escape);
      document.removeEventListener('visibilitychange', visibility);
      window.removeEventListener('blur', reset);
    };
  }, [reset]);
  // React portal events bubble logically; only the actual conversation DOM accepts files.
  const within = (event: DragEvent<HTMLDivElement>) => event.currentTarget.contains(event.target as Node);
  return {
    handler,
    active,
    props: {
      onDragEnterCapture: (event: DragEvent<HTMLDivElement>) => {
        if (!within(event) || !isFileTransfer(event.dataTransfer)) return;
        event.preventDefault(); event.stopPropagation();
        depth.current += 1; setActive(true);
      },
      onDragOverCapture: (event: DragEvent<HTMLDivElement>) => {
        if (!within(event) || !isFileTransfer(event.dataTransfer)) return;
        event.preventDefault(); event.stopPropagation();
        event.dataTransfer.dropEffect = 'copy'; setActive(true);
      },
      onDragLeaveCapture: (event: DragEvent<HTMLDivElement>) => {
        if (!within(event)) return;
        depth.current = Math.max(0, depth.current - 1);
        if (event.relatedTarget && event.currentTarget.contains(event.relatedTarget as Node)) return;
        if (!depth.current || event.relatedTarget) reset();
      },
      onDropCapture: (event: DragEvent<HTMLDivElement>) => {
        if (!within(event) || !isFileTransfer(event.dataTransfer)) return;
        event.preventDefault(); event.stopPropagation(); reset();
        const files = transferredFiles(event.dataTransfer);
        if (files.length) handler.current?.(files);
      },
    },
  };
}
