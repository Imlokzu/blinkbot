/** The product mark follows the text color in either theme, without an animation loop. */
export function BotIcon({ className = '' }: { className?: string }) {
  const mask = `url(${import.meta.env.BASE_URL}blink-mark.svg) center / contain no-repeat`;
  return (
    <span
      className={`inline-block size-6 shrink-0 bg-current text-ink ${className}`}
      style={{ mask, WebkitMask: mask }}
      aria-hidden="true"
      data-bot-icon=""
    />
  );
}
