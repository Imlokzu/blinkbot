import { t } from '@/locales/chat';

// This is an estimate: Ukrainian is split more finely than Latin text in
// typical BPE vocabularies. Exact counts come from the model's tokenizer.
const CHARS_PER_TOKEN = 2.8;
const PER_MESSAGE_OVERHEAD = 4;
const SYSTEM_PROMPT_GUESS = 400;

/** Keep context character counts and the local conversation estimate comparable. */
export function tokensFromChars(chars: number): number {
  return Math.round(chars / CHARS_PER_TOKEN);
}

export function estimateTokens(messages: { content: string }[]): number {
  const body = messages.reduce(
    (sum, message) => sum + message.content.length / CHARS_PER_TOKEN + PER_MESSAGE_OVERHEAD,
    0,
  );
  return Math.round(body + SYSTEM_PROMPT_GUESS);
}

/** Compact counts follow the dashboard's selected language. */
export function shortNumber(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value >= 10_000_000 ? 0 : 1)}${t('tokens.million')}`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(value >= 10_000 ? 0 : 1)}${t('tokens.thousand')}`;
  return String(value);
}
