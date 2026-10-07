/** Labels for the notes list and editor actions. */
const uk = {
  notes: 'нотатки',
  newNote: 'Нова нотатка',
  search: 'Пошук…',
  saved: 'збережено',
  unsaved: 'незбережено',
  delete: 'Видалити',
  save: 'Зберегти',
} as const;
const en: Record<keyof typeof uk, string> = {
  notes: 'notes',
  newNote: 'New note',
  search: 'Search…',
  saved: 'saved',
  unsaved: 'unsaved',
  delete: 'Delete',
  save: 'Save',
};
export function memoryT(key: keyof typeof uk): string {
  return (typeof document !== 'undefined' && document.documentElement.lang.startsWith('en') ? en : uk)[key];
}
