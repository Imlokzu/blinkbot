const en = {
  label: 'Interactive answer', loading: 'Building your interactive answer…',
  failed: 'This interactive answer could not load. You can still read its summary.',
  interrupted: 'Interactive answer stopped.', reset: 'Reset', retry: 'Try again',
  hide: 'Pause', show: 'Open', temporary: 'Controls stay in this view. Reset or reopen to start again.',
  followup: 'Continue with the assistant', send: 'Send', cancel: 'Dismiss',
  sending: 'Sending…', sent: 'Sent', busy: 'The assistant is still working. Try sending again when it finishes.',
  stale: 'Reopen this conversation to send the follow-up.', sendFailed: 'Could not send. Your draft is kept here.',
} as const;
const uk: Record<keyof typeof en, string> = {
  label: 'Інтерактивна відповідь', loading: 'Створюю інтерактивну відповідь…',
  failed: 'Не вдалося завантажити інтерактивну відповідь. Короткий опис залишається доступним.',
  interrupted: 'Створення інтерактивної відповіді зупинено.', reset: 'Скинути', retry: 'Спробувати ще раз',
  hide: 'Призупинити', show: 'Відкрити', temporary: 'Зміни діють у цьому вікні. Скинь або відкрий знову, щоб почати спочатку.',
  followup: 'Продовжити з асистентом', send: 'Надіслати', cancel: 'Відхилити',
  sending: 'Надсилаю…', sent: 'Надіслано', busy: 'Асистент ще працює. Спробуй надіслати, коли він завершить.',
  stale: 'Відкрий цю розмову знову, щоб надіслати відповідь.', sendFailed: 'Не вдалося надіслати. Чернетка залишилася тут.',
};
export function t(key: keyof typeof en): string {
  return (document.documentElement.lang === 'uk' ? uk : en)[key];
}
