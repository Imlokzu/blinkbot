/** Product copy shared by the dashboard UI and build-time install metadata. */
export const product = {
  uk: {
    name: 'Blink',
    title: 'Blink — панель',
    description: 'Панель керування Blink',
    notification: 'Blink: збій',
    voiceSample: 'Привіт! Я Blink. Так звучить мій голос.',
  },
  en: {
    name: 'Blink',
    title: 'Blink — dashboard',
    description: 'Blink control dashboard',
    notification: 'Blink: error',
    voiceSample: 'Hi! I am Blink. This is what my voice sounds like.',
  },
} as const;
