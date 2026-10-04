const uk = {
  denied: 'Доступ до мікрофона не дозволено',
  unavailable: 'Мікрофон недоступний',
  unsupported: 'Браузер не вміє записувати звук',
  startFailed: 'Не вдалося почати запис',
  recognitionFailed: 'Не вдалося розпізнати мовлення',
};

const en: Record<keyof typeof uk, string> = {
  denied: 'Microphone access was denied',
  unavailable: 'Microphone unavailable',
  unsupported: 'This browser cannot record audio',
  startFailed: 'Could not start recording',
  recognitionFailed: 'Could not recognize speech',
};

export function t(key: keyof typeof uk): string {
  const locale = typeof document !== 'undefined' && document.documentElement.lang.startsWith('en') ? en : uk;
  return locale[key];
}
