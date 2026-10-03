/** State transitions for a workspace editor draft. */
export interface FileDraftState {
  path: string | null;
  draft: string;
  baseline: string | null;
  dirty: boolean;
  external: boolean;
}

export const EMPTY_FILE_DRAFT: FileDraftState = {
  path: null, draft: '', baseline: null, dirty: false, external: false,
};

/** Load a server version without replacing a dirty draft. */
export function receiveFile(state: FileDraftState, path: string, content: string): FileDraftState {
  if (state.path !== path) return { path, draft: content, baseline: content, dirty: false, external: false };
  if (state.baseline === content) return state;
  if (!state.dirty) return { ...state, draft: content, baseline: content, external: false };
  return { ...state, baseline: content, external: true };
}

export function editDraft(state: FileDraftState, draft: string): FileDraftState {
  return { ...state, draft, dirty: true };
}

/** Reconcile a completed save with edits that happened while it ran. */
export function saveCompleted(state: FileDraftState, path: string, content: string): FileDraftState {
  if (state.path !== path) return state;
  if (state.draft === content) return { ...state, baseline: content, dirty: false, external: false };
  return { ...state, baseline: content, dirty: true };
}
