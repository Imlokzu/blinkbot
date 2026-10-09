import en from './locales/en.json';
import uk from './locales/uk.json';
export type Label = keyof typeof en;
export const text = (language: string, key: Label): string => (language === 'uk' ? uk : en)[key];
