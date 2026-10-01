/** Selections live for this page only: navigating back restores, reloading starts new. */
const selections = new WeakMap<object, Map<string, string>>();

export function readChatSelection(page: object, project = ''): string {
  return selections.get(page)?.get(project) ?? '';
}

export function rememberChatSelection(page: object, id: string, project = ''): void {
  let selected = selections.get(page);
  if (!selected) {
    selected = new Map();
    selections.set(page, selected);
  }
  selected.set(project, id);
  // Returning through the main Chat navigation opens the last chosen chat,
  // including one selected from a project's filtered conversation list.
  selected.set('', id);
}
