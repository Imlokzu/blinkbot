import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { SolarCheck as Check, SolarChevron as ChevronRight, SolarAlert as CircleAlert,
  SolarClock as CircleDashed, SolarWeather as CloudSun, SolarCurrency as Coins,
  SolarDocument as FileText, SolarRead as Book, SolarWrite as Pen, SolarFolder as Folder,
  SolarImage as ImageIcon, SolarTasks as ListTodo, SolarQuestion as MessageCircleQuestion,
  SolarMusic as Music, SolarPlay as Play, SolarSearch as Search, SolarTerminal as Terminal,
  SolarSettings as Wrench, type SolarIconComponent } from '@/vendor/solar-icons';
import { t as activityT } from '@/lib/i18n';
import { t, type ChatKey } from '@/locales/chat';
import type { ToolStep } from './types';
import { collectSources } from './sources';
import { SiteIcon } from './SiteIcon';
import { useDisclosureMotion } from '@/hooks/useDisclosureMotion';
import './activity-tree.css';

/** The gateway prefixes MCP names; native coding tools use shorter aliases. */
function bareTool(label: string): string {
  return label.replace(/^(?:tools|workspace|emotions)__/, '');
}

const TOOL_ICONS: Record<string, SolarIconComponent> = {
  web_search: Search, image_search: ImageIcon, facts: FileText, weather: CloudSun,
  currency: Coins, memory_search: Search, workspace_read: Book,
  workspace_write: Pen, workspace_list: Folder, workspace_show: FileText,
  workspace_info: FileText, ask_question: MessageCircleQuestion, todo_list: ListTodo,
  show_choice: ListTodo, play_music: Music, stop_music: Music, play_video: Play,
  listen_to_video: Play, video_control: Play, read: Book, write: Pen,
  edit: Pen, grep: Search, glob: Folder, bash: Terminal, exec: Terminal,
};

const TOOL_TITLES: Record<string, ChatKey> = {
  web_search: 'tool.web_search', image_search: 'tool.image_search', facts: 'tool.facts',
  weather: 'tool.weather', currency: 'tool.currency', memory_search: 'tool.memory_search',
  workspace_read: 'tool.workspace_read', workspace_write: 'tool.workspace_write',
  workspace_list: 'tool.workspace_list', workspace_show: 'tool.workspace_show',
  ask_question: 'tool.ask_question', todo_list: 'tool.todo_list', show_choice: 'tool.show_choice',
  play_music: 'tool.play_music', play_video: 'tool.play_video',
  read: 'tool.workspace_read', write: 'tool.workspace_write',
};

function toolTitle(label: string): string {
  const name = bareTool(label);
  const key = TOOL_TITLES[name];
  // An unknown tool's name is server data, not a guessed description of its work.
  return key ? t(key) : name.replace(/_/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
}

function Payload({ value }: { value: unknown }) {
  return <pre tabIndex={0} className="chat-activity-payload">{
    typeof value === 'string' ? value : JSON.stringify(value, null, 2)
  }</pre>;
}

function ToolDetails({ step }: { step: ToolStep }) {
  return <div className="chat-activity-log-panel">
    {step.input !== undefined ? <section>
      <p>{activityT('activity.input')}</p><Payload value={step.input} />
    </section> : null}
    {step.result !== undefined ? <section>
      <p>{activityT(step.status === 'active' ? 'activity.partial' : 'activity.result')}</p>
      <Payload value={step.result} />
    </section> : null}
  </div>;
}

function ToolState({ step }: { step: ToolStep }) {
  const label = activityT(`activity.${step.status}`);
  const Icon = step.status === 'done' ? Check : step.status === 'failed' ? CircleAlert : CircleDashed;
  return <span className="chat-activity-state" data-tool-state title={label}>
    {step.status === 'active' ? <span className="chat-activity-pulse" aria-hidden="true" />
      : <Icon aria-hidden="true" strokeWidth={1.75} />}
    <span className={step.status === 'failed' ? 'chat-activity-failure' : 'sr-only'}>{label}</span>
  </span>;
}

/** A row keeps its identity and expanded log through progress and outcome updates. */
function ToolBranch({ step, animateEntry }: { step: ToolStep; animateEntry: boolean }) {
  const { ref, open, phase, toggle } = useDisclosureMotion();
  // Only a live, visible newcomer gets the sequence; updates never restart it.
  const [entering, setEntering] = useState(animateEntry);
  useLayoutEffect(() => {
    if (!entering) return;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    const settle = () => { if (reduced.matches) setEntering(false); };
    settle();
    reduced.addEventListener('change', settle);
    return () => reduced.removeEventListener('change', settle);
  }, [entering]);
  useEffect(() => { if (!animateEntry) setEntering(false); }, [animateEntry]);
  const detailsId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const toggleLog = () => {
    if (open && ref.current?.contains(document.activeElement)) {
      trigger.current?.focus({ preventScroll: true });
    }
    toggle();
  };
  const name = bareTool(step.label);
  const Icon = TOOL_ICONS[name] ?? Wrench;
  const title = toolTitle(step.label);
  const hasLog = step.input !== undefined || step.result !== undefined;
  const entryShape = useRef({ hasLog, Icon });
  const sameEntryShape = entryShape.current.hasLog === hasLog && entryShape.current.Icon === Icon;
  const drawEntry = entering && sameEntryShape;
  const prepareIcon = useCallback((icon: SVGSVGElement | null) => {
    // Normalize Solar's stroke primitives. Gate each shape directly because
    // Chromium can retain SVG animations when only an ancestor selector changes.
    icon?.querySelectorAll('path, circle, line, polyline, polygon, rect, ellipse').forEach(shape => {
      // Filled punctuation follows the icon fade and must retain its original dot.
      if (shape.getAttribute('stroke') === 'none') return;
      shape.setAttribute('pathLength', '1');
      shape.toggleAttribute('data-activity-stroke', drawEntry);
    });
  }, [drawEntry]);
  useLayoutEffect(() => {
    // Late log data changes div to button; show the result immediately instead
    // of replaying the sequence on its newly mounted children.
    if (!sameEntryShape) setEntering(false);
  }, [sameEntryShape]);
  // A source belongs to the completed call that actually returned it.
  const sites = [...new Map(collectSources([step]).map(source => [source.host, source])).values()];
  const signedOut = step.status === 'failed'
    && /clerk|sign-?in|потрібен вхід|нужен вход|unauthorized/i.test(
      typeof step.result === 'string' ? step.result : JSON.stringify(step.result ?? ''),
    );
  const content = <>
    <Icon ref={prepareIcon} className="chat-activity-tool-icon" aria-hidden="true" strokeWidth={1.75} />
    <span className="chat-activity-description">
      <span className="chat-activity-tool-name" title={title}>{title}</span>
      {step.detail ? <span className="chat-activity-detail" title={step.detail}>{step.detail}</span> : null}
      <ToolState step={step} />
      {hasLog ? <ChevronRight className="chat-activity-log-chevron" aria-hidden="true" strokeWidth={1.75} /> : null}
    </span>
  </>;
  return <li className="chat-activity-branch" data-tool-step={step.id} data-tool-status={step.status}
    data-activity-enter={drawEntry ? '' : undefined} onAnimationEnd={event => {
      if (event.animationName === 'chat-activity-text'
        && event.target instanceof Element && event.target.classList.contains('chat-activity-description')) {
        setEntering(false);
      }
    }}>
    {hasLog ? <button ref={trigger} type="button" className="chat-activity-row" data-tool-row
      aria-expanded={open} aria-controls={detailsId}
      aria-label={activityT('activity.logs', { tool: title, detail: step.detail, status: activityT(`activity.${step.status}`) })}
      onClick={toggleLog}>{content}</button>
      : <div className="chat-activity-row" data-tool-row>{content}</div>}
    <div className="chat-activity-followup">
    {sites.length ? <div className="chat-activity-sites" role="group" aria-label={t('sources.label', { count: sites.length })}>
      {sites.map(source => <a key={source.host} href={source.url} target="_blank" rel="noreferrer noopener"
        className="chat-activity-site" title={source.title || source.host}>
        <SiteIcon url={source.url} host={source.host} /><span>{source.host}</span>
      </a>)}
    </div> : null}
    {signedOut ? <p className="chat-activity-sign-in">{t('tool.signIn')}</p> : null}
    {hasLog ? <div ref={ref} id={detailsId} data-tool-details className="chat-activity-fold" data-phase={phase}
      data-open={open ? '' : undefined} inert={!open} aria-hidden={!open}>
      <div className="chat-activity-clip"><ToolDetails step={step} /></div>
    </div> : null}
    </div>
  </li>;
}

/** Adjacent real calls share a trunk; the reply may continue after they finish. */
export function ActivityLine({ steps, running }: { steps: ToolStep[]; running: boolean }) {
  const { ref: branches, open, phase, setOpen, toggle: toggleOpen } = useDisclosureMotion(running);
  const branchesId = useId();
  const toggle = useRef<HTMLButtonElement>(null);
  const toggleActivity = () => {
    if (open && branches.current?.contains(document.activeElement)) {
      toggle.current?.focus({ preventScroll: true });
    }
    toggleOpen();
  };
  useEffect(() => {
    // The complete reply, rather than a gap between calls, ends an activity tree.
    // Manual reopening after settlement is retained through later rerenders.
    if (!running && branches.current?.contains(document.activeElement)) {
      toggle.current?.focus({ preventScroll: true });
    }
    setOpen(running);
  }, [running]);
  if (!steps.length) return null;
  const active = steps.some(step => step.status === 'active');
  const summary = activityT(active ? 'activity.runningCount' : 'activity.summary', { count: steps.length });
  return <div className="chat-activity-tree" data-agent-activity data-running={active ? '' : undefined}>
    <button ref={toggle} type="button" className="chat-activity-toggle" data-activity-toggle
      aria-expanded={open} aria-controls={branchesId}
      aria-label={`${activityT(open ? 'activity.collapse' : 'activity.expand')} · ${summary}`}
      onClick={toggleActivity}>
      <ChevronRight aria-hidden="true" strokeWidth={1.75} /><span>{summary}</span>
      {active ? <span className="chat-activity-pulse" aria-hidden="true" /> : null}
    </button>
    <div ref={branches} id={branchesId} data-activity-branches className="chat-activity-fold" data-phase={phase}
      data-open={open ? '' : undefined} inert={!open} aria-hidden={!open}>
      <div className="chat-activity-clip">
        <ol className="chat-activity-list">{steps.map(step => <ToolBranch key={step.id} step={step} animateEntry={running && open} />)}</ol>
      </div>
    </div>
  </div>;
}
