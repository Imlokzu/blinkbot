const uk = {
  'drop.title': 'Перетягни файли сюди',
  'drop.hint': 'Зображення й документи додадуться до чернетки.',
  'upload.filePrompt': 'Опрацюй прикріплені файли.',
  'upload.busy': 'Завантажую файли…',
  'upload.failed': 'Не вдалося завантажити файл',
  'upload.unsupported_type': 'Цей формат не підтримується. Обери зображення, текст, PDF або DOCX.',
  'upload.file_too_large': 'Файл завеликий. Максимум — 20 МБ.',
  'upload.empty_file': 'Файл порожній.',
  'upload.unreadable_document': 'Не вдалося прочитати текст документа. Для скану прикріпи зображення.',
  'upload.encrypted_document': 'Спочатку зніми пароль із документа.',
  'upload.invalid_attachment': 'Прикріплений файл недоступний. Завантаж його ще раз.',
  'upload.attachment_forbidden': 'Цей файл належить іншому користувачу.',
  'upload.limit': 'Можна прикріпити до 8 файлів.',
  'upload.image_too_large': 'Зображення завелике. Максимум — 10 МБ.',
  'upload.invalid_image': 'Не вдалося прочитати зображення.',
  'upload.truncated': 'Великий документ: агент отримає початковий уривок.',
  'preview.open': 'Переглянути',
  'preview.remove': 'Прибрати',
  'preview.loading': 'Завантажую перегляд…',
  'preview.unavailable': 'Перегляд недоступний. Спробуй завантажити файл.',
  'preview.partial': 'Показано початковий уривок документа. Завантаж файл, щоб відкрити його повністю.',
  'preview.download': 'Завантажити файл',
} as const;
const en: Record<keyof typeof uk, string> = {
  'drop.title': 'Drop files into this chat',
  'drop.hint': 'Images and documents will be added to your draft.',
  'upload.filePrompt': 'Use the attached files.',
  'upload.busy': 'Uploading files…',
  'upload.failed': 'Could not upload the file',
  'upload.unsupported_type': 'Unsupported format. Choose an image, text, PDF or DOCX file.',
  'upload.file_too_large': 'The file is too large. Maximum size is 20 MB.',
  'upload.empty_file': 'The file is empty.',
  'upload.unreadable_document': 'Could not read the document text. Attach scans as images.',
  'upload.encrypted_document': 'Remove the document password first.',
  'upload.invalid_attachment': 'The attachment is unavailable. Upload it again.',
  'upload.attachment_forbidden': 'This file belongs to another user.',
  'upload.limit': 'You can attach up to 8 files.',
  'upload.image_too_large': 'The image is too large. Maximum size is 10 MB.',
  'upload.invalid_image': 'Could not read the image.',
  'upload.truncated': 'Large document: the agent will receive its opening excerpt.',
  'preview.open': 'Preview',
  'preview.remove': 'Remove',
  'preview.loading': 'Loading preview…',
  'preview.unavailable': 'Preview unavailable. Try downloading the file.',
  'preview.partial': 'Showing the opening excerpt. Download the file to open the complete document.',
  'preview.download': 'Download file',
};
export function t(key: keyof typeof uk): string {
  return (document.documentElement.lang === 'uk' ? uk : en)[key];
}
export function uploadError(code: string): string {
  return localizeUploadError(code) ?? t('upload.failed');
}
export function localizeUploadError(code: string): string | undefined {
  const key = `upload.${code}`;
  return key in en ? t(key as keyof typeof uk) : undefined;
}
