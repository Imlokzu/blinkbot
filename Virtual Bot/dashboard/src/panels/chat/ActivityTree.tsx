import { useId, useState } from 'react';
import { Check, ChevronRight, CircleAlert, CircleDashed, CloudSun, Coins, FileText, Folder,
  Image as ImageIcon, ListTodo, MessageCircleQuestion, Music, Play, Search, Terminal,
  Wrench, type LucideIcon } from 'lucide-react';
import { t as activityT } from '@/lib/i18n';
import { t, type ChatKey } from '@/locales/chat';
import type { ToolStep } from './types';
import './activity-tree.css';

/** The gateway prefixes MCP names; native coding tools use shorter aliases. */
function bareTool(label: string): string {
  return label.replace(/^(?:tools|workspace|emotions)__/, '');
}

const TOOL_ICONS: Record<string, LucideIcon> = {
  web_search: Search, image_search: ImageIcon, facts: FileText, weather: CloudSun,
  currency: Coins, memory_search: Search, workspace_read: FileText,
  workspace_write: FileText, workspace_list: Folder, workspace_show: FileText,
  workspace_info: FileText, ask_question: MessageCircleQuestion, todo_list: ListTodo,
  show_choice: ListTodo, play_music: Music, stop_music: Music, play_video: Play,
  listen_to_video: Play, video_control: Play, read: FileText, write: FileText,
  edit: FileText, grep: Search, glob: Folder, bash: Terminal, exec: Terminal,
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
function ToolBranch({ step }: { step: ToolStep }) {
  const [open, setOpen] = useState(false);
  const detailsId = useId();
  const name = bareTool(step.label);
  const Icon = TOOL_ICONS[name] ?? Wrench;
  const title = toolTitle(step.label);
  const hasLog = step.input !== undefined || step.result !== undefined;
  const signedOut = step.status === 'failed'
    && /clerk|sign-?in|потрібен вхід|нужен вход|unauthorized/i.test(
      typeof step.result === 'string' ? step.result : JSON.stringify(step.result ?? ''),
    );
  const content = <>
    <Icon className="chat-activity-tool-icon" aria-hidden="true" strokeWidth={1.75} />
    <span className="chat-activity-tool-name" title={title}>{title}</span>
    {step.detail ? <span className="chat-activity-detail" title={step.detail}>{step.detail}</span> : null}
    <ToolState step={step} />
    {hasLog ? <ChevronRight className="chat-activity-log-chevron" aria-hidden="true" strokeWidth={1.75} /> : null}
  </>;
  return <li className="chat-activity-branch" data-tool-step={step.id} data-tool-status={step.status}>
    {hasLog ? <button type="button" className="chat-activity-row" data-tool-row
      aria-expanded={open} aria-controls={detailsId}
      aria-label={activityT('activity.logs', { tool: title, detail: step.detail, status: activityT(`activity.${step.status}`) })}
      onClick={() => setOpen(value => !value)}>{content}</button>
      : <div className="chat-activity-row" data-tool-row>{content}</div>}
    {signedOut ? <p className="chat-activity-sign-in">{t('tool.signIn')}</p> : null}
    {hasLog ? <div id={detailsId} data-tool-details className="chat-activity-fold"
      data-open={open ? '' : undefined} inert={!open} aria-hidden={!open}>
      <div className="chat-activity-clip"><ToolDetails step={step} /></div>
    </div> : null}
  </li>;
}

/** Adjacent real calls share a trunk; the reply may continue after they finish. */
export function ActivityLine({ steps }: { steps: ToolStep[]; running: boolean }) {
  const [open, setOpen] = useState(true);
  const branchesId = useId();
  if (!steps.length) return null;
  const active = steps.some(step => step.status === 'active');
  const summary = activityT(active ? 'activity.runningCount' : 'activity.summary', { count: steps.length });
  return <div className="chat-activity-tree" data-agent-activity data-running={active ? '' : undefined}>
    <button type="button" className="chat-activity-toggle" data-activity-toggle
      aria-expanded={open} aria-controls={branchesId}
      aria-label={`${activityT(open ? 'activity.collapse' : 'activity.expand')} · ${summary}`}
      onClick={() => setOpen(value => !value)}>
      <ChevronRight aria-hidden="true" strokeWidth={1.75} /><span>{summary}</span>
      {active ? <span className="chat-activity-pulse" aria-hidden="true" /> : null}
    </button>
    <div id={branchesId} data-activity-branches className="chat-activity-fold"
      data-open={open ? '' : undefined} inert={!open} aria-hidden={!open}>
      <div className="chat-activity-clip">
        <ol className="chat-activity-list">{steps.map(step => <ToolBranch key={step.id} step={step} />)}</ol>
      </div>
    </div>
  </div>;
}
