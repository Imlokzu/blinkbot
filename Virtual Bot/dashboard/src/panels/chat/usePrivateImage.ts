import { useCallback, useEffect, useState } from 'react';
import { authHeaders } from '@/lib/auth';

/** Owned generated images need bearer headers; ordinary web images stay direct. */
export function usePrivateImageResource(source: string) {
  const privateImage = source.startsWith('/uploads/');
  const [attempt, setAttempt] = useState(0);
  const [resolved, setResolved] = useState({ source: '', url: '', failed: false });
  const reload = useCallback(() => setAttempt((value) => value + 1), []);
  useEffect(() => {
    if (!privateImage) return;
    setResolved({ source, url: '', failed: false });
    const controller = new AbortController();
    let objectUrl = '';
    void (async () => {
      const headers = await authHeaders();
      if (controller.signal.aborted) return;
      const response = await fetch(source, { headers, signal: controller.signal, cache: 'no-store', redirect: 'error' });
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('image/')) throw new Error('image_unavailable');
      const blob = await response.blob();
      if (controller.signal.aborted) return;
      objectUrl = URL.createObjectURL(blob);
      setResolved({ source, url: objectUrl, failed: false });
    })().catch(() => {
      if (!controller.signal.aborted) setResolved({ source, url: '', failed: true });
    });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [source, privateImage, attempt]);
  return { src: privateImage ? (resolved.source === source ? resolved.url : '') : source,
    failed: privateImage && resolved.source === source && resolved.failed, reload };
}

export function usePrivateImage(source: string): string {
  return usePrivateImageResource(source).src;
}
