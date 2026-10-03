const en = {
  rightPanel: 'Right panel',
  current: 'Right panel: {mode}',
  hidden: 'Hidden',
  panels: 'Panels',
  workbench: 'Workbench',
} as const;
const uk: Record<keyof typeof en, string> = {
  rightPanel: 'Права панель',
  current: 'Права панель: {mode}',
  hidden: 'Приховано',
  panels: 'Панелі',
  workbench: 'Робоче місце',
};

export function t(key: keyof typeof en, values: Record<string, string | number> = {}): string {
  const locale = typeof document !== 'undefined' && document.documentElement.lang.startsWith('en') ? en : uk;
  return locale[key].replace(/\{(\w+)\}/g, (match, name: string) => String(values[name] ?? match));
}
