import { useCallback, useSyncExternalStore } from 'react';
import { useToast } from '@/components/ui/Toaster';
import { t } from '@/locales/popupGlass';
import { createPopupGlassStore, DEFAULT_POPUP_GLASS_TARGETS, LEGACY_POPUP_STORAGE_KEY, POPUP_GLASS_STORAGE_KEY,
  type PopupGlassUpdate } from './popupGlassPreferences';

export const popupGlassStore = createPopupGlassStore(
  () => typeof window === 'undefined' ? null : window.localStorage,
  (refresh) => {
    if (typeof window === 'undefined') return () => {};
    const onStorage = (event: StorageEvent) => {
      if (event.key === POPUP_GLASS_STORAGE_KEY || event.key === LEGACY_POPUP_STORAGE_KEY || event.key === null) refresh();
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  },
);

export function usePopupGlassTargets() {
  return useSyncExternalStore(popupGlassStore.subscribe, popupGlassStore.getSnapshot, () => DEFAULT_POPUP_GLASS_TARGETS);
}

export function usePopupGlassPreference() {
  const targets = usePopupGlassTargets();
  const toast = useToast();
  const setTargets = useCallback((update: PopupGlassUpdate): boolean => {
    const saved = popupGlassStore.setTargets(update);
    if (!saved) toast.error(t('storageError'), t('storageErrorHint'));
    return saved;
  }, [toast]);
  return { targets, setTargets };
}
