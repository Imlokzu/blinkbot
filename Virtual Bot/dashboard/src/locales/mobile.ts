const uk = {
  title: 'Підключити телефон',
  server: 'Адреса API бота',
  placeholder: 'https://api-bot.waveio.me',
  create: 'Створити QR',
  working: 'Створюємо…',
  scan: 'Відскануй у застосунку «Клод Бот». Код діє 5 хвилин і працює один раз.',
  qr: 'QR-код підключення телефона',
  expired: 'Термін дії коду минув. Створи новий QR.',
  devices: 'Підключені телефони',
  empty: 'Поки немає підключених телефонів.',
  revoke: 'Відкликати доступ',
  failed: 'Не вдалося виконати дію. Перевір адресу API та доступ до налаштувань.',
  close: 'Закрити QR',
} as const;

const en: Record<keyof typeof uk, string> = {
  title: 'Connect your phone',
  server: 'Bot API address',
  placeholder: 'https://api-bot.waveio.me',
  create: 'Create QR',
  working: 'Creating…',
  scan: 'Scan in Claude Bot. The code expires in 5 minutes and works once.',
  qr: 'Phone connection QR code',
  expired: 'This code has expired. Create a new QR.',
  devices: 'Connected phones',
  empty: 'No phones connected yet.',
  revoke: 'Revoke access',
  failed: 'Could not complete this action. Check the API address and settings access.',
  close: 'Close QR',
};

export const mobileLocales = { uk, en };
export function t(key: keyof typeof uk): string {
  return (document.documentElement.lang === 'uk' ? uk : en)[key];
}
