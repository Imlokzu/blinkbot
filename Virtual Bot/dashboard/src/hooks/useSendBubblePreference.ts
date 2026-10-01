import { useSyncExternalStore } from 'react';

const KEY = 'claudeBotSendBubble';
const listeners = new Set<() => void>();

function read() {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return undefined;
  }
}

// Keep the choice between panels even when the browser blocks site storage.
let stored = read();
let enabled = stored !== 'off';
const snapshot = () => enabled;
const notify = () => listeners.forEach((listener) => listener());

function refresh() {
  const next = read();
  // A failed write leaves the readable stored value unchanged. Preserve the
  // in-memory choice until storage actually changes, including across panels.
  if (next === undefined || next === stored) return;
  stored = next;
  enabled = next !== 'off';
  notify();
}

function onStorage(event: StorageEvent) {
  if (event.key !== KEY && event.key !== null) return;
  refresh();
}

function subscribe(listener: () => void) {
  if (!listeners.size) window.addEventListener('storage', onStorage);
  listeners.add(listener);
  // Other tabs can change the preference while both panels are unmounted.
  refresh();
  return () => {
    listeners.delete(listener);
    if (!listeners.size) window.removeEventListener('storage', onStorage);
  };
}

function setEnabled(next: boolean) {
  enabled = next;
  try {
    const value = next ? 'on' : 'off';
    localStorage.setItem(KEY, value);
    stored = value;
  } catch {
    // The in-memory choice still works for this visit.
  }
  notify();
}

export function useSendBubblePreference() {
  return [useSyncExternalStore(subscribe, snapshot), setEnabled] as const;
}
