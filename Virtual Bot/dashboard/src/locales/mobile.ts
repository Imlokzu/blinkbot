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
  backendOutdated: 'Сервер ще не підтримує підключення телефона. Онови й перезапусти бекенд бота, потім спробуй знову.',
  signInRequired: 'Увійди в обліковий запис знову, щоб підключити телефон.',
  operatorRequired: 'Створити код може власник бота. Відкрий панель на комп’ютері хоста або увійди як власник.',
  invalidOrigin: 'Перевір HTTPS-адресу API: лише домен, без шляху. Вона має збігатися з адресою в налаштуваннях сервера.',
  networkFailed: 'Немає зв’язку із сервером бота. Перевір з’єднання й спробуй знову.',
  loadingDevices: 'Завантажуємо пристрої…',
  deviceMissing: 'Цей телефон уже від’єднано. Список пристроїв невдовзі оновиться.',
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
  backendOutdated: 'This server does not support phone pairing yet. Update and restart the bot backend, then try again.',
  signInRequired: 'Sign in again to connect your phone.',
  operatorRequired: 'Only the bot owner can create a code. Open the dashboard on the host computer or sign in as the owner.',
  invalidOrigin: 'Check the HTTPS API address: use only the domain, without a path. It must match the address configured on the server.',
  networkFailed: 'Cannot reach the bot server. Check the connection and try again.',
  loadingDevices: 'Loading devices…',
  deviceMissing: 'This phone has already been disconnected. The device list will refresh shortly.',
};

export const mobileLocales = { uk, en };
export function t(key: keyof typeof uk): string {
  return (document.documentElement.lang === 'uk' ? uk : en)[key];
}
