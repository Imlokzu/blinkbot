/** Neutral cube planes follow the theme; the flash bands retain the brand orange. */
export function BotIcon({ className = '' }: { className?: string }) {
  const mask = `url(${import.meta.env.BASE_URL}blink-mark.svg) center / contain no-repeat`;
  return (
    <span
      className={`inline-block size-6 shrink-0 bg-current text-ink ${className}`}
      style={{
        mask, WebkitMask: mask,
        backgroundImage: `url(${import.meta.env.BASE_URL}blink-flash.svg)`,
        backgroundPosition: 'center', backgroundSize: 'contain', backgroundRepeat: 'no-repeat',
      }}
      aria-hidden="true"
      data-bot-icon=""
    />
  );
}
