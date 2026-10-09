import { useContext, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { get, post } from '@/lib/api';
import { InteractiveSessionContext, ToolOwnerContext } from './InteractiveSessionContext';

export type ToolInteractionState = { revision: number; done: Record<string, boolean>; answer: null | { message_id: string; option_id: string; value: string } };
export type ToolInteractionAction =
  | { action: 'toggle'; item_id: string; done: boolean }
  | { action: 'answer'; option_id: string; value: string; message_id: string };

export function useToolInteraction(callId: string, confirmed: boolean) {
  const sessionId = useContext(InteractiveSessionContext);
  const owner = useContext(ToolOwnerContext);
  const client = useQueryClient();
  const key = ['tool-interaction', owner, sessionId, callId];
  const path = `/api/sessions/${encodeURIComponent(sessionId)}/interactions/${encodeURIComponent(callId)}`;
  const epoch = useRef(0);
  const active = useRef(true);
  const pending = useRef(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(false);
  useEffect(() => { active.current = true; ++epoch.current; return () => { active.current = false; ++epoch.current; }; }, [owner, sessionId, callId]);
  const query = useQuery({ queryKey: key, queryFn: () => get<ToolInteractionState>(path), enabled: Boolean(sessionId && confirmed),
    staleTime: 0, retry: false, refetchOnWindowFocus: true, refetchInterval: confirmed ? 5000 : false });
  const save = async (action: ToolInteractionAction): Promise<boolean> => {
    if (!active.current || !query.data || pending.current) return false;
    const version = epoch.current;
    pending.current = true;
    setSaving(true);
    setError(false);
    try {
      const state = await post<ToolInteractionState>(path, { ...action, owner_id: owner, expected_revision: query.data.revision });
      client.setQueryData(key, state);
      return true;
    } catch {
      if (version === epoch.current) {
        setError(true);
        void query.refetch();
      }
      return false;
    } finally {
      pending.current = false;
      if (version === epoch.current) setSaving(false);
    }
  };
  return { owner, sessionId, state: query.data, ready: confirmed && Boolean(query.data), saving, error: error || query.isError, save };
}
