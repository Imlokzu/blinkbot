/** Labels shared by the document toolbar and inline drawing blocks. */
const en = {
  toolbar: 'Document formatting', paragraph: 'Paragraph', h1: 'Heading 1', h2: 'Heading 2', h3: 'Heading 3',
  bold: 'Bold', italic: 'Italic', strike: 'Strikethrough', code: 'Inline code', quote: 'Quote',
  bullets: 'Bullet list', numbers: 'Numbered list', tasks: 'Checklist', undo: 'Undo', redo: 'Redo',
  table: 'Insert table', addRow: 'Add row', addColumn: 'Add column', deleteTable: 'Delete table',
  link: 'Insert link', image: 'Insert image', drawing: 'Insert Excalidraw',
  url: 'Address', urlPlaceholder: 'https://…', insert: 'Insert', cancel: 'Cancel',
  drawingTitle: 'Excalidraw', openDrawing: 'Edit drawing', closeDrawing: 'Collapse drawing',
  drawingFailed: 'Could not save the drawing', invalidUrl: 'Enter a valid link or workspace file path',
};
const uk: Record<keyof typeof en, string> = {
  toolbar: 'Форматування документа', paragraph: 'Абзац', h1: 'Заголовок 1', h2: 'Заголовок 2', h3: 'Заголовок 3',
  bold: 'Жирний', italic: 'Курсив', strike: 'Закреслення', code: 'Код у рядку', quote: 'Цитата',
  bullets: 'Маркований список', numbers: 'Нумерований список', tasks: 'Список завдань', undo: 'Скасувати', redo: 'Повторити',
  table: 'Вставити таблицю', addRow: 'Додати рядок', addColumn: 'Додати колонку', deleteTable: 'Видалити таблицю',
  link: 'Вставити посилання', image: 'Вставити зображення', drawing: 'Вставити Excalidraw',
  url: 'Адреса', urlPlaceholder: 'https://…', insert: 'Вставити', cancel: 'Скасувати',
  drawingTitle: 'Excalidraw', openDrawing: 'Редагувати малюнок', closeDrawing: 'Згорнути малюнок',
  drawingFailed: 'Не вдалося зберегти малюнок', invalidUrl: 'Введи коректне посилання або шлях до файла робочої теки',
};
export function t(key: keyof typeof en): string {
  return document.documentElement.lang.startsWith('en') ? en[key] : uk[key];
}
