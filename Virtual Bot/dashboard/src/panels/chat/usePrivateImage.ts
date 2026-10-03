import { useEffect, useState } from 'react';
import { authHeaders } from '@/lib/auth';

/** Owned generated images need bearer headers; ordinary web images stay direct. */
export function usePrivateImage(source: string): string {
  const privateImage = source.startsWith('/uploads/');
  const [resolved, setResolved] = useState({ source: '', url: '' });
  useEffect(() => {
    if (!privateImage) return;
    const controller = new AbortController();
    let objectUrl = '';
    void (async () => {
      const headers = await authHeaders();
      if (controller.signal.aborted) return;
      const response = await fetch(source, { headers, signal: controller.signal, cache: 'no-store', redirect: 'error' });
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('image/')) return;
      const blob = await response.blob();
      if (controller.signal.aborted) return;
      objectUrl = URL.createObjectURL(blob);
      setResolved({ source, url: objectUrl });
    })().catch(() => {});
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [source, privateImage]);
  return privateImage ? (resolved.source === source ? resolved.url : '') : source;
}
