import { useCallback } from 'react';
import { useMutationState, useQueryClient } from '@tanstack/react-query';
import { useToast } from '@/components/ui/Toaster';
import { useBrainModels, useSelectBrainModel, useSetThinking } from '@/lib/queries';
import { t } from '@/locales/chat';
import { effortProfile } from './effortProfile';

/*
 * Which model answers, and how hard it thinks.
 *
 * Two places set these now: the model menu in the phone chat header and the
 * pickers inside the desktop prompt bar. Both must write the same OpenClaw
 * settings and say the same thing when they do, so the logic lives here
 * rather than being copied into each.
 */

/** Thinking levels exactly as OpenClaw knows them (agents.defaults.thinkingDefault). */
type EffortKey = 'off' | 'minimal' | 'low' | 'medium' | 'high' | 'xhigh' | 'adaptive' | 'max' | 'ultra';
const EFFORT_KEYS = new Set<string>(['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'adaptive', 'max', 'ultra']);

export const thinkingLabel = (level: string): string =>
  EFFORT_KEYS.has(level) ? t(`effort.${level as EffortKey}`) : level;

export function useBrainChoice() {
  const brain = useBrainModels();
  const selectModel = useSelectBrainModel();
  const setThinking = useSetThinking();
  const toast = useToast();
  const client = useQueryClient();
  // Composer and both menus observe the same writes, including while remounting.
  const pending = useMutationState({
    filters: { mutationKey: ['brain-choice'], status: 'pending' },
    select: (mutation) => ({
      kind: mutation.options.mutationKey?.[1],
      value: typeof mutation.state.variables === 'string' ? mutation.state.variables
        : (mutation.state.variables as { level?: string } | undefined)?.level ?? '',
    }),
  });

  const models = brain.data?.models ?? [];
  // An empty `selected` means "keep the agent's default model" — show that
  // one, because that is the one that will answer.
  // While a change is in flight, show the requested value rather than the
  // old one: the config round-trip takes a moment, and a control that snaps
  // back on release reads as "it did not take".
  const current = pending.find((write) => write.kind === 'model')?.value
    || brain.data?.selected || brain.data?.default || '';
  const currentModel = models.find((model) => model.id === current);

  const pickModel = useCallback(
    async (id: string): Promise<boolean> => {
      // Check the live cache too: two events may arrive before React rerenders.
      if (!id || client.isMutating({ mutationKey: ['brain-choice'] })) return false;
      if (id === current) return true;
      try {
        await selectModel.mutateAsync(id);
        return true;
      } catch (error) {
        toast.error(t('composer.modelFailed'), (error as Error).message);
        return false;
      }
    },
    [current, selectModel, toast, client],
  );

  // A global saved alias may not exist for the selected model. Show the
  // model's resolved default rather than inventing another selectable mode.
  const { levels, thinking } = effortProfile(currentModel,
    pending.find((write) => write.kind === 'thinking')?.value ?? brain.data?.thinking ?? '');
  const pickThinking = useCallback(
    async (level: string) => {
      if (!current || !levels.includes(level) || level === thinking || client.isMutating({ mutationKey: ['brain-choice'] })) return;
      const label = thinkingLabel(level);
      try {
        await setThinking.mutateAsync({ level, model: current });
        toast.toast(t('composer.effortToast', { level: label }), {
          description: t('composer.effortSaved'),
        });
      } catch (error) {
        toast.error(t('composer.effortFailed'), (error as Error).message);
      }
    },
    [current, levels, thinking, setThinking, toast, client],
  );

  return {
    loading: brain.isPending && !brain.data,
    refreshing: brain.isFetching,
    failed: brain.isError,
    unavailable: brain.data?.available === false,
    saving: pending.length > 0,
    retry: () => brain.refetch({ cancelRefetch: false }),
    models,
    current,
    currentModel,
    contextSize: currentModel?.context ?? 0,
    pickModel,
    levels,
    thinking,
    pickThinking,
  };
}
