import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, Copy, RotateCcw, Square, Volume2 } from '../../vendor/solar-icons/compat.ts';
import { authHeaders } from '@/lib/auth';
import { copyText } from '@/lib/clipboard';
import { get } from '@/lib/api';
import { useToast } from '@/components/ui/Toaster';
import { Tip } from '@/components/ui/Tip';
import { cn } from '@/lib/cn';
import { t } from '@/locales/chat';
import { speakableText } from './speech';
import { ReactionControl, type ReactionControlProps } from './Bubbles';

/*
 * What you can do with a finished reply.
 *
 * Reactions, copying, voice playback and retry share the space beneath a
 * finished answer, keeping the bubbles themselves clear.
 *
 * The row is quiet by default and only gains contrast on hover or keyboard
 * focus: it sits under every reply in the thread, and bright icons on every
 * message would fight the text for attention (DESIGN.md, rule 3). On touch
 * there is no hover, so it stays legible there without the reveal.
 */

/** One reply speaks at a time: starting another stops the previous one. */
let playing: HTMLAudioElement | null = null;

function stopPlayback(): void {
  if (!playing) return;
  playing.pause();
  URL.revokeObjectURL(playing.src);
  playing = null;
}

function Action({ label, onClick, active, children }: {
  label: string;
  onClick: () => void;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <Tip content={label}>
      <button
        type="button"
        aria-label={label}
        onClick={onClick}
        className={cn(
          'grid size-7 place-items-center rounded-sm text-ink-3 transition-colors',
          'hover:bg-surface-2 hover:text-ink focus-visible:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent',
          'max-[759px]:size-11 pointer-coarse:size-11',
          active && 'text-accent',
        )}
      >
        {children}
      </button>
    </Tip>
  );
}

export function MessageActions({ text, onRetry, reaction }: { text: string; onRetry?: () => void; reaction?: ReactionControlProps }) {
  const toast = useToast();
  const [copied, setCopied] = useState(false);
  const [speaking, setSpeaking] = useState(false);
  const mounted = useRef(true);

  // Hide the speech button when the bot has no voice at all, rather than
  // offering an action that can only answer 503.
  const tts = useQuery({
    queryKey: ['tts-status'],
    queryFn: () => get<{ enabled?: boolean }>('/api/tts/status'),
    staleTime: 5 * 60_000,
  });

  // Unmount only — an effect keyed on `speaking` would tear the component
  // down on its own state change and leave every later action inert.
  const ours = useRef<HTMLAudioElement | null>(null);
  useEffect(() => () => {
    mounted.current = false;
    // Stop the audio this reply started, but never a different reply's.
    if (ours.current && playing === ours.current) stopPlayback();
  }, []);

  const copy = async () => {
    try {
      await copyText(text);
      setCopied(true);
      window.setTimeout(() => mounted.current && setCopied(false), 1600);
    } catch (error) {
      toast.error(t('reply.copyFailed'), (error as Error).message);
    }
  };

  const speak = async () => {
    if (speaking) {
      stopPlayback();
      setSpeaking(false);
      return;
    }
    const spoken = speakableText(text);
    if (!spoken) return;
    stopPlayback();
    setSpeaking(true);
    try {
      // Not the shared `api()` helper: that one reads the body as text, and
      // this endpoint answers MP3 or WAV depending on which voice replied.
      const headers = await authHeaders({ 'Content-Type': 'application/json' });
      const response = await fetch('/api/tts', {
        method: 'POST',
        headers,
        body: JSON.stringify({ text: spoken }),
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const audio = new Audio(URL.createObjectURL(await response.blob()));
      audio.addEventListener('ended', () => {
        stopPlayback();
        if (mounted.current) setSpeaking(false);
      });
      playing = audio;
      ours.current = audio;
      await audio.play();
    } catch (error) {
      stopPlayback();
      if (mounted.current) setSpeaking(false);
      toast.error(t('reply.speakFailed'), (error as Error).message);
    }
  };

  return (
    <div
      className="mt-2 flex items-center gap-0.5 opacity-60 transition-opacity focus-within:opacity-100 group-hover/reply:opacity-100 motion-reduce:transition-none max-[759px]:opacity-100"
      data-reply-actions
    >
      {reaction ? <ReactionControl {...reaction} /> : null}
      <Action label={copied ? t('reply.copied') : t('reply.copy')} onClick={() => void copy()} active={copied}>
        {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
      </Action>
      {tts.data?.enabled ? (
        <Action
          label={speaking ? t('reply.speakStop') : t('reply.speak')}
          onClick={() => void speak()}
          active={speaking}
        >
          {speaking ? <Square className="size-3.5" /> : <Volume2 className="size-3.5" />}
        </Action>
      ) : null}
      {onRetry ? (
        <Action label={t('reply.retry')} onClick={onRetry}>
          <RotateCcw className="size-3.5" />
        </Action>
      ) : null}
    </div>
  );
}
