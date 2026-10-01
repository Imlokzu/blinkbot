import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';

export const CONTROL_KEY = ['openclaw-control'] as const;
export function useControl<T>(view: string, params: Record<string, string | number> = {}) {
  const query = new URLSearchParams(Object.entries(params).map(([key, value]) => [key, String(value)]));
  query.set('refresh', 'true');
  return useQuery({
    queryKey: [...CONTROL_KEY, view, query.toString()],
    queryFn: ({ signal }) => api<T>(`/api/openclaw/control/${view}?${query}`, { signal }),
    staleTime: 10_000, refetchInterval: 30_000, retry: false,
  });
}
