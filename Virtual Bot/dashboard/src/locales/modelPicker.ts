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
  intelShow: 'Показати індекс інтелекту',
  intelHide: 'Сховати індекс інтелекту',
  intelDetail: 'Індекс інтелекту {index} зі 100, бенчмарків: {count} з {total}. {scores}',
  intelPartial: 'Мало бенчмарків, оцінка приблизна.',
  intelMissing: 'Бенчмарків для цієї моделі немає',
  intelSource: 'Індекс із {count} бенчмарків · {source}, {license}',
  intelLoading: 'Завантажую бенчмарки…',
  intelFailed: 'Не вдалося завантажити бенчмарки.',
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
  intelShow: 'Show intelligence index',
  intelHide: 'Hide intelligence index',
  intelDetail: 'Intelligence index {index} of 100, from {count} of {total} benchmarks. {scores}',
  intelPartial: 'Few benchmarks, so the score is approximate.',
  intelMissing: 'No benchmark data for this model',
  intelSource: 'Index from {count} benchmarks · {source}, {license}',
  intelLoading: 'Loading benchmarks…',
  intelFailed: 'Could not load benchmarks.',
};

export function t(key: keyof typeof uk, values: Record<string, string | number> = {}): string {
  const locale = typeof document !== 'undefined' && document.documentElement.lang.startsWith('en') ? en : uk;
  return locale[key].replace(/\{(\w+)\}/g, (match, name: string) => String(values[name] ?? match));
}
