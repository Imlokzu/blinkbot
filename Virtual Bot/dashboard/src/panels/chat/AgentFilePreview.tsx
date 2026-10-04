import { lazy, Suspense, useEffect, useRef, useState, type ContextType } from 'react';
import { PenLine } from '../../vendor/solar-icons/compat.ts';
import { BotIcon } from '@/components/ui/BotIcon';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { t } from '@/locales/workbench';
import type { DocumentWorkspace } from '@/components/editor/DocumentImage';
import './agent-file-preview.css';

const AgentNoteEditor = lazy(() => import('@/components/editor/AgentNoteEditor').then((m) => ({ default: m.AgentNoteEditor })));

/** Reveal actual tool/file text; this never fabricates model output or progress. */
export function AgentFilePreview({ path, content, previous = '', rich = false, workspace, busy, updated = false, onRevealed }: {
  path: string; content?: string; busy: boolean; updated?: boolean; onRevealed?: () => void;
  previous?: string; rich?: boolean; workspace?: ContextType<typeof DocumentWorkspace>;
}) {
  const reduced = useMediaQuery('(prefers-reduced-motion: reduce)');
  const [length, setLength] = useState(0);
  const paper = useRef<HTMLDivElement>(null);
  const done = useRef(onRevealed);
  done.current = onRevealed;
  // A bounded excerpt keeps long source files responsive during the reveal.
  const text = (content ?? '').slice(0, 12_000);
  useEffect(() => {
    if (rich) return;
    if (reduced) { setLength(text.length); if (!busy) done.current?.(); return; }
    setLength(0);
    let frame = 0;
    let started = 0;
    const duration = Math.min(1800, Math.max(350, text.length * 1.4));
    const reveal = (now: number) => {
      started ||= now;
      const progress = Math.min(1, (now - started) / duration);
      setLength(Math.ceil(text.length * progress));
      if (progress < 1) frame = requestAnimationFrame(reveal);
      else if (!busy) done.current?.();
    };
    frame = requestAnimationFrame(reveal);
    return () => cancelAnimationFrame(frame);
  }, [text, busy, reduced, rich]);
  useEffect(() => { if (paper.current) paper.current.scrollTop = paper.current.scrollHeight; }, [length]);
  return <section data-agent-file-writing="" className="agent-file-preview">
    <header className="agent-file-status" role="status">
      <span className={busy ? 'agent-file-avatar is-writing' : 'agent-file-avatar'}><BotIcon className="h-6 w-8" /></span>
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-medium text-ink">{t(busy ? 'wb.agentWriting' : updated ? 'wb.agentUpdated' : 'wb.agentCreated')}</p>
        <p className="truncate font-mono text-[10px] text-ink-3">{path}</p>
      </div>
      {busy ? <PenLine className="size-4 text-accent" aria-hidden="true" /> : null}
    </header>
    <div ref={paper} className={rich ? 'agent-file-paper is-document' : 'agent-file-paper'}>
      {rich ? <Suspense fallback={<div className="agent-file-placeholder" aria-hidden="true"><span /><span /><span /></div>}>
        <AgentNoteEditor content={content ?? previous} previous={previous} busy={busy} onRevealed={onRevealed} workspace={workspace} />
      </Suspense> : text ? <pre aria-hidden="true">{text.slice(0, length)}<span className="agent-file-caret" /></pre> : (
        <div className="agent-file-placeholder"><span /><span /><span /></div>
      )}
    </div>
  </section>;
}
