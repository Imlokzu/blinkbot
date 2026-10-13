import { sha256 as hashSha256 } from '@noble/hashes/sha2.js';
import type { ToolStep } from './types';
import { resultRecord } from './interactiveToolData.ts';

export type IntelligentUiReceipt = { id: string; title: string; summary: string; path: string; sha256: string; height: number };
export type IntelligentUiSource = { version: 1; html: string; css: string; javascript: string };
const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const text = (value: unknown, limit: number): value is string => typeof value === 'string' && Array.from(value).length <= limit;

/** A successful nested receipt cannot override a failed MCP transport. */
export function failedResult(value: unknown, depth = 0): boolean {
  if (depth > 5) return false;
  const result = record(value);
  if (result.error || result.isError || result.is_error || result.ok === false || result.success === false) return true;
  if (failedResult(result.result, depth + 1) || failedResult(result.structuredContent, depth + 1)) return true;
  return Array.isArray(result.content) && result.content.some(value => {
    const block = record(value);
    if (block.type !== 'text' || !text(block.text, 8000)) return false;
    try { return failedResult(JSON.parse(block.text), depth + 1); }
    catch { return false; }
  });
}

export function isIntelligentUi(step: ToolStep): boolean {
  return step.label.replace(/^tools__/, '') === 'render_interactive_ui';
}

/** Only a completed, canonical receipt can select a saved artifact. */
export function intelligentUiReceipt(step: ToolStep): IntelligentUiReceipt | null {
  if (!isIntelligentUi(step) || step.status !== 'done' || failedResult(step.result)) return null;
  const result = resultRecord(step.result);
  if (!result || result.ok !== true || result.error || result.isError) return null;
  const ui = record(result.ui), data = record(ui.data);
  if (ui.version !== 1 || ui.kind !== 'intelligent_ui' || !text(ui.id, 32) || !/^[a-f0-9]{32}$/.test(ui.id)
    || !text(data.title, 120) || !data.title || !text(data.summary, 600) || !data.summary
    || !text(data.path, 160) || !new RegExp(`^sessions/[a-zA-Z0-9_-]{1,40}/interactive/${ui.id}\\.json$`).test(data.path)
    || !text(data.sha256, 64) || !/^[a-f0-9]{64}$/.test(data.sha256)
    || typeof data.height !== 'number' || !Number.isInteger(data.height) || data.height < 180 || data.height > 720) return null;
  return { id: ui.id, title: data.title, summary: data.summary, path: data.path, sha256: data.sha256, height: data.height };
}

export async function readIntelligentUiSource(content: unknown, sha256: string): Promise<IntelligentUiSource> {
  if (typeof content !== 'string' || content.length > 800_000) throw new Error('invalid_artifact');
  // Local HTTP dashboards have no SubtleCrypto, but must still verify every byte.
  const digest = Array.from(hashSha256(new TextEncoder().encode(content)), byte => byte.toString(16).padStart(2, '0')).join('');
  if (digest !== sha256) throw new Error('artifact_changed');
  const source = record(JSON.parse(content));
  if (source.version !== 1 || !text(source.html, 64000) || !source.html.trim() || !text(source.css, 64000)
    || !text(source.javascript, 64000) || Array.from(source.html + source.css + source.javascript).length > 64000) throw new Error('invalid_artifact');
  return { version: 1, html: source.html, css: source.css, javascript: source.javascript };
}

export type SandboxMessage = { type: 'ready' | 'error' } | { type: 'resize'; height: number } | { type: 'followup'; text: string };
export function sandboxMessage(value: unknown, channel: string): SandboxMessage | null {
  const data = record(value);
  if (data.channel !== channel) return null;
  if (data.type === 'ready' || data.type === 'error') return { type: data.type };
  if (data.type === 'resize' && typeof data.height === 'number' && Number.isFinite(data.height)) return { type: 'resize', height: Math.max(180, Math.min(720, Math.ceil(data.height))) };
  if (data.type === 'followup' && text(data.text, 2000) && data.text.trim()) return { type: 'followup', text: data.text.trim() };
  return null;
}
