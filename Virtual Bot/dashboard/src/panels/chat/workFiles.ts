import type { ChatMessage, ToolStep } from './types';

/*
 * The workbench is the column beside the chat that shows what the bot is
 * making. It has no store of its own: the files it lists are read back out of
 * the tool steps already saved with every reply. That keeps it honest — a file
 * appears because a `workspace_write` really ran — and it survives reloads
 * and old conversations for free.
 */

export type FileKind = 'html' | 'image' | 'markdown' | 'drawing' | 'mermaid' | 'code' | 'text';

export interface WorkFile {
  /** Workspace-relative path as the bot wrote it (may start with `session/`). */
  path: string;
  kind: FileKind;
  /**
   * Bumped on every finished write, so previews reload after the bot edits a
   * file. A write still in flight does not count: reading the file then would
   * cache a 404 or half of it.
   */
  revision: number;
  /** Still being written: the step has not finished yet. */
  active: boolean;
}

const IMAGE = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'avif']);
const CODE = new Set(['js', 'jsx', 'ts', 'tsx', 'py', 'json', 'css', 'sh', 'yaml', 'yml', 'toml', 'sql']);

export function fileKind(path: string): FileKind {
  const name = path.toLowerCase();
  // `.excalidraw.json` is what excalidraw.com's "save as" produces too.
  if (name.endsWith('.excalidraw') || name.endsWith('.excalidraw.json')) return 'drawing';
  const ext = name.split('.').pop() ?? '';
  if (ext === 'html' || ext === 'htm') return 'html';
  if (IMAGE.has(ext)) return 'image';
  if (ext === 'md' || ext === 'markdown') return 'markdown';
  if (ext === 'mmd' || ext === 'mermaid') return 'mermaid';
  if (CODE.has(ext)) return 'code';
  return 'text';
}

/** Kinds that have a rendered view as well as their source. */
export function hasPreview(kind: FileKind): boolean {
  return kind === 'html' || kind === 'markdown' || kind === 'drawing' || kind === 'mermaid' || kind === 'image';
}

/*
 * Tool names differ by brain: the local one calls `workspace_write`, OpenClaw
 * reaches the same tool through its MCP bridge as `workspace__workspace_write`.
 */
const TOOL = /(?:^|__)workspace_(write|show|delete)$/;

function object(value: unknown): Record<string, unknown> | null {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>;
  if (typeof value !== 'string' || !value.trim().startsWith('{')) return null;
  try {
    return object(JSON.parse(value));
  } catch {
    return null;
  }
}

/*
 * The path the backend actually used. A local tool returns `{ path }`;
 * through MCP the same JSON arrives as text inside `content[0]`. When the
 * result is missing (older saves kept `{}`), fall back to what was asked for.
 */
function stepPath(step: ToolStep): string {
  const result = object(step.result);
  const wrapped = Array.isArray(result?.content) ? object((result!.content as { text?: unknown }[])[0]?.text) : null;
  const done = (wrapped ?? result)?.path ?? (wrapped ?? result)?.shown;
  const asked = object(step.input)?.path;
  const path = [done, asked, step.detail].find((value) => typeof value === 'string' && value.trim());
  return typeof path === 'string' ? path.trim().replace(/^\/+/, '') : '';
}

/*
 * The bot says `session/plan.md`; the backend answers with the real
 * `sessions/<slug>/plan.md`. Both must land on one tab, so the real folder
 * is folded back to the short form the bot uses.
 */
export function shortPath(path: string, sessionFolder: string): string {
  const prefix = sessionFolder ? `${sessionFolder.replace(/\/+$/, '')}/` : '';
  return prefix && path.startsWith(prefix) ? `session/${path.slice(prefix.length)}` : path;
}

/** Files the bot touched in this conversation, the most recent first. */
export function collectFiles(messages: ChatMessage[], sessionFolder = ''): WorkFile[] {
  const files = new Map<string, WorkFile>();
  for (const message of messages) {
    if (message.role !== 'assistant') continue;
    for (const step of message.steps ?? []) {
      const tool = TOOL.exec(step.label)?.[1];
      // A failed write changed nothing on disk; showing it would point at a
      // file that is not there.
      if (!tool || step.status === 'failed') continue;
      const path = shortPath(stepPath(step), sessionFolder);
      if (!path) continue;
      const old = files.get(path);
      files.delete(path);
      if (tool === 'delete') continue;
      files.set(path, {
        path,
        kind: fileKind(path),
        revision: (old?.revision ?? 0) + (tool === 'write' && step.status !== 'active' ? 1 : 0),
        active: step.status === 'active',
      });
    }
  }
  return [...files.values()].reverse();
}

/** The step the bot is busy with right now, for the live line. */
export function currentStep(messages: ChatMessage[]): ToolStep | null {
  const last = messages[messages.length - 1];
  if (last?.role !== 'assistant' || last.id !== 'draft') return null;
  const steps = last.steps ?? [];
  return [...steps].reverse().find((step) => step.status === 'active') ?? null;
}

/* Hosting the workbench takes over `preview` events from the floating dock. */
let hosts = 0;
export const workbenchHost = {
  get active() {
    return hosts > 0;
  },
  claim(): () => void {
    hosts += 1;
    return () => {
      hosts -= 1;
    };
  },
};
