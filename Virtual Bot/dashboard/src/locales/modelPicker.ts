/** Recovery and capability controls share keys in both supported languages. */
const uk = {
  filter: 'Можливості моделі',
  all: 'Усі',
  count: 'Моделей: {count}',
  saving: 'Зберігаю вибір…',
  failed: 'Не вдалося завантажити моделі.',
  stale: 'Не вдалося оновити моделі. Показано останній список.',
  unavailable: 'OpenClaw недоступний. Перевірте підключення й повторіть.',
  empty: 'Каталог моделей порожній. Спробуйте завантажити знову.',
  retry: 'Повторити',
  retrying: 'Завантажую…',
  noCapability: 'У каталозі немає моделей із цією можливістю.',
};

const en: Record<keyof typeof uk, string> = {
  filter: 'Model capabilities',
  all: 'All',
  count: '{count} models',
  saving: 'Saving choice…',
  failed: 'Could not load models.',
  stale: 'Could not refresh models. Showing the last catalog.',
  unavailable: 'OpenClaw is unavailable. Check the connection and retry.',
  empty: 'The model catalog is empty. Try loading it again.',
  retry: 'Retry',
  retrying: 'Loading…',
  noCapability: 'No models in the catalog report this capability.',
};

export function t(key: keyof typeof uk, values: Record<string, string | number> = {}): string {
  const locale = typeof document !== 'undefined' && document.documentElement.lang.startsWith('en') ? en : uk;
  return locale[key].replace(/\{(\w+)\}/g, (match, name: string) => String(values[name] ?? match));
}
