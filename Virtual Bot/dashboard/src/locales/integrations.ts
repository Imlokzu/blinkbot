/*
 * Settings → Integrations: Telegram, Discord, Google.
 * Ukrainian is the key source; English must cover the same set.
 */
const uk = {
  'int.google.name': 'Google',
  'int.google.savedClient': '••••.apps.googleusercontent.com',
  'int.intro': 'Бот живе не лише тут. Підключи месенджер — і це буде той самий чат: той самий мозок, історія й інструменти, просто в Telegram чи Discord.',
  'int.connected': 'підключено',
  'int.notConnected': 'не підключено',
  'int.error': 'помилка: {error}',
  'int.token': 'Токен бота',
  'int.tokenHint.telegram': 'Створи бота в @BotFather (/newbot) і встав сюди токен. Зберігається лише на цій машині.',
  'int.tokenHint.discord': 'Discord Developer Portal → New Application → Bot → Reset Token. Зберігається лише на цій машині.',
  'int.tokenSaved': 'Збережений токен: {token}. Порожнє поле — не міняти.',
  'int.connect': 'Підключити',
  'int.connecting': 'Перевіряю…',
  'int.disconnect': 'Відключити',
  'int.disconnectSure': 'Точно відключити? Токен буде забуто.',
  'int.test': 'Тестове повідомлення',
  'int.testSent': 'Надіслано',
  'int.pairing': 'Спарювання',
  'int.pairHint.telegram': 'Бот відповідає лише тим, хто надіслав йому код. Відкрий посилання на телефоні або напиши боту: /start {code}',
  'int.pairHint.discord': 'Напиши боту в особисті: !pair {code}',
  'int.pairOpen': 'Відкрити в Telegram',
  'int.newCode': 'Новий код',
  'int.owners': 'Спарено: {n}',
  'int.invite': 'Додати на сервер',
  'int.howForwards': 'Переслані повідомлення бот збирає мовчки й відповідає, коли ти сам щось напишеш. /go — відповісти на переслане зараз, /new — нова розмова.',
  'int.google.hint': 'Календар і Gmail (лише читання) для інструментів бота. Потрібен OAuth-клієнт типу «Desktop app» у Google Cloud Console.',
  'int.google.clientId': 'Client ID',
  'int.google.clientSecret': 'Client secret',
  'int.google.saveClient': 'Зберегти клієнт',
  'int.google.connect': 'Увійти в Google',
  'int.google.connectHint': 'Відкриється вкладка Google. Вхід треба завершити в браузері на машині з ботом: Google повертає на 127.0.0.1.',
  'int.google.scopes': 'Доступ: календар, Gmail лише читання',
  'int.saved': 'Збережено',
  'int.failed': 'Не вийшло',
  'int.err.bad_token': 'Це не схоже на токен бота',
  'int.err.token_rejected': 'Сервіс не прийняв токен',
  'int.err.bad_client': 'Client ID має закінчуватися на .apps.googleusercontent.com',
  'int.err.not_paired': 'Ще ніхто не спарений',
  'int.err.not_configured': 'Спершу збережи OAuth-клієнт',
  'int.err.network': 'Немає звʼязку з сервісом',
  'int.state.conflict': 'цей токен уже опитує інший процес (OpenClaw чи інший бот)',
  'int.state.unauthorized': 'токен відкликано',
  'int.state.network': 'немає мережі',
  'int.state.refresh_failed': 'вхід протермінувався, увійди знову',
};

const en: Record<keyof typeof uk, string> = {
  'int.google.name': 'Google',
  'int.google.savedClient': '••••.apps.googleusercontent.com',
  'int.intro': 'The bot lives beyond this panel. Connect a messenger and it is the same chat: same brain, history and tools, just in Telegram or Discord.',
  'int.connected': 'connected',
  'int.notConnected': 'not connected',
  'int.error': 'error: {error}',
  'int.token': 'Bot token',
  'int.tokenHint.telegram': 'Create a bot with @BotFather (/newbot) and paste its token. Stored on this machine only.',
  'int.tokenHint.discord': 'Discord Developer Portal → New Application → Bot → Reset Token. Stored on this machine only.',
  'int.tokenSaved': 'Saved token: {token}. Leave empty to keep it.',
  'int.connect': 'Connect',
  'int.connecting': 'Checking…',
  'int.disconnect': 'Disconnect',
  'int.disconnectSure': 'Disconnect for sure? The token will be forgotten.',
  'int.test': 'Test message',
  'int.testSent': 'Sent',
  'int.pairing': 'Pairing',
  'int.pairHint.telegram': 'The bot only answers people who sent it the code. Open the link on your phone or send the bot: /start {code}',
  'int.pairHint.discord': 'DM the bot: !pair {code}',
  'int.pairOpen': 'Open in Telegram',
  'int.newCode': 'New code',
  'int.owners': 'Paired: {n}',
  'int.invite': 'Add to a server',
  'int.howForwards': 'Forwarded messages are collected quietly; the bot answers when you write something yourself. /go answers the forwards now, /new starts a new conversation.',
  'int.google.hint': 'Calendar and Gmail (read-only) for the bot\'s tools. Needs a “Desktop app” OAuth client in Google Cloud Console.',
  'int.google.clientId': 'Client ID',
  'int.google.clientSecret': 'Client secret',
  'int.google.saveClient': 'Save client',
  'int.google.connect': 'Sign in with Google',
  'int.google.connectHint': 'A Google tab opens. Finish the sign-in in a browser on the bot\'s machine: Google redirects to 127.0.0.1.',
  'int.google.scopes': 'Access: calendar, Gmail read-only',
  'int.saved': 'Saved',
  'int.failed': 'Failed',
  'int.err.bad_token': 'That does not look like a bot token',
  'int.err.token_rejected': 'The service rejected the token',
  'int.err.bad_client': 'The client ID must end with .apps.googleusercontent.com',
  'int.err.not_paired': 'Nobody is paired yet',
  'int.err.not_configured': 'Save the OAuth client first',
  'int.err.network': 'Cannot reach the service',
  'int.state.conflict': 'another process is already polling this token (OpenClaw or another bot)',
  'int.state.unauthorized': 'the token was revoked',
  'int.state.network': 'no network',
  'int.state.refresh_failed': 'the sign-in expired, sign in again',
};

export type IntegrationsKey = keyof typeof uk;

export function t(key: IntegrationsKey, values: Record<string, string | number> = {}): string {
  const locale = typeof document !== 'undefined' && document.documentElement.lang.startsWith('en') ? en : uk;
  return locale[key].replace(/\{(\w+)\}/g, (match, name: string) => String(values[name] ?? match));
}

/** A backend error code or state as text; unknown codes pass through. */
export function codeText(prefix: 'int.err.' | 'int.state.', code: string): string {
  const key = `${prefix}${code}` as IntegrationsKey;
  return key in uk ? t(key) : code;
}
