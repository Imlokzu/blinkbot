const uk = {
  'effects.title': 'Додаткові ефекти',
  'effects.sendBubble': 'Бульбашка відправлення',
  'effects.sendBubbleHint': 'Підлітає з кнопки й розчиняється з розмитим шлейфом. Зберігається в цьому браузері; вимикається зі зменшенням руху в системі.',
} as const;

const en: Record<keyof typeof uk, string> = {
  'effects.title': 'Extra effects',
  'effects.sendBubble': 'Send bubble',
  'effects.sendBubbleHint': 'Lifts off the button and dissolves with a blurred trail. Saved in this browser; disabled when the system reduces motion.',
};

export const effectsLocales = { uk, en };

export function t(key: keyof typeof uk): string {
  return (document.documentElement.lang === 'uk' ? uk : en)[key];
}
