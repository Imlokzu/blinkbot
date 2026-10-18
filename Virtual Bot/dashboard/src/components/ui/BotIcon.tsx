/** The orange cube sits on a solid rounded tile in every theme. */
export function BotIcon({ className = '' }: { className?: string }) {
  return (
    <span
      className={`inline-block size-6 shrink-0 ${className}`}
      style={{
        backgroundImage: `url(${import.meta.env.BASE_URL}blink-tile.svg)`,
        backgroundPosition: 'center', backgroundSize: 'contain', backgroundRepeat: 'no-repeat',
        borderRadius: '23.4375%',
      }}
      aria-hidden="true"
      data-bot-icon=""
    />
  );
}
