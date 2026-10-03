const en = {
  title: 'Liquid glass for popups',
  hint: 'Choose which popups use liquid glass. Other popups keep their solid surface.',
  local: 'Saved in this browser.',
  all: 'Select all',
  none: 'Clear selection',
  'kind.models': 'Models and thinking',
  'kind.attachments': 'Attachments',
  'kind.context': 'Context details',
  'kind.menus': 'Other menus',
  storageError: 'Could not save popup appearance',
  storageErrorHint: 'Your previous choices are still in use. Allow browser storage and try again.',
} as const;

const uk: Record<keyof typeof en, string> = {
  title: 'Рідке скло для попапів',
  hint: 'Оберіть, які попапи матимуть рідке скло. Решта зберігають суцільну поверхню.',
  local: 'Зберігається в цьому браузері.',
  all: 'Обрати всі',
  none: 'Очистити вибір',
  'kind.models': 'Моделі та мислення',
  'kind.attachments': 'Вкладення',
  'kind.context': 'Деталі контексту',
  'kind.menus': 'Інші меню',
  storageError: 'Не вдалося зберегти вигляд попапів',
  storageErrorHint: 'Попередній вибір залишається чинним. Дозвольте сховище браузера й спробуйте ще раз.',
};

export function t(key: keyof typeof en, language?: 'uk' | 'en'): string {
  const selectedLanguage = language ?? (typeof document !== 'undefined' && document.documentElement.lang.startsWith('uk') ? 'uk' : 'en');
  const locale = selectedLanguage === 'uk' ? uk : en;
  return locale[key];
}
