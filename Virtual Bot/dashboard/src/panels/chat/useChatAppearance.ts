import { useCallback, useSyncExternalStore } from 'react';
import { useToast } from '@/components/ui/Toaster';
import { t } from '@/locales/chatAppearance';
import { APPEARANCE_STORAGE_KEY, createChatAppearanceStore, DEFAULT_CHAT_APPEARANCE, type AppearanceUpdate } from './appearancePreferences';

const store = createChatAppearanceStore(
  () => typeof window === 'undefined' ? null : window.localStorage,
  (refresh) => {
    if (typeof window === 'undefined') return () => {};
    const onStorage = (event: StorageEvent) => {
      if (event.key === APPEARANCE_STORAGE_KEY || event.key === null) refresh();
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  },
);

export function useChatAppearance() {
  const toast = useToast();
  const appearance = useSyncExternalStore(store.subscribe, store.getSnapshot, () => DEFAULT_CHAT_APPEARANCE);
  const setAppearance = useCallback((update: AppearanceUpdate): boolean => {
    const saved = store.setAppearance(update);
    if (!saved) toast.error(t('storageError'), t('storageErrorHint'));
    return saved;
  }, [toast]);
  const resetAppearance = useCallback((): boolean => setAppearance(DEFAULT_CHAT_APPEARANCE), [setAppearance]);
  return { appearance, setAppearance, resetAppearance };
}
