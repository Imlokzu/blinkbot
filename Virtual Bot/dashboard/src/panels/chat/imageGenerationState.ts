import type { ToolStep } from './types';

export interface GenerationView {
  prompt: string;
  url: string;
  status: 'generating' | 'complete' | 'failed' | 'interrupted';
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

/** Older MCP traces can store a JSON result inside a text content block. */
function resultRecord(value: unknown): Record<string, unknown> {
  if (typeof value === 'string') {
    if (value.length > 64_000) return {};
    try { return resultRecord(JSON.parse(value)); } catch { return {}; }
  }
  const result = record(value);
  if (Array.isArray(result.content)) {
    const text = result.content.find((item) => record(item).type === 'text');
    if (typeof record(text).text === 'string') return { ...result, ...resultRecord(record(text).text) };
  }
  return result;
}

export function isImageGeneration(step: ToolStep): boolean {
  return step.label.replace(/^tools__/, '') === 'image_generate';
}

export function generationView(step: ToolStep): GenerationView {
  const input = record(step.input);
  const result = resultRecord(step.result);
  const prompt = typeof input.prompt === 'string' ? input.prompt.slice(0, 8000) : '';
  const image = Array.isArray(result.images) ? record(result.images[0]) : {};
  const url = typeof image.url === 'string' && /^\/uploads\/[A-Za-z0-9_-]+\.(?:png|jpg|jpeg|webp)$/.test(image.url) ? image.url : '';
  if (step.status === 'interrupted') return { prompt, url: '', status: 'interrupted' };
  if (step.status === 'failed' || result.error || result.isError || result.ok === false) return { prompt, url: '', status: 'failed' };
  if (step.status === 'active') return { prompt, url: '', status: 'generating' };
  return { prompt, url, status: url ? 'complete' : 'failed' };
}

/** Suppress image-only replies only when every image has a generation surface. */
export function imageOnlyDelivery(text: string, sources: ReadonlySet<string>): boolean {
  let remaining = text.trim();
  if (!remaining) return false;
  while (remaining) {
    if (!remaining.startsWith('![')) return false;
    // Labels may contain escaped or nested brackets, including prompt text.
    let brackets = 1;
    let at = 2;
    for (; at < remaining.length && brackets; at++) {
      if (remaining[at] === '\\') { at++; continue; }
      if (remaining[at] === '[') brackets++;
      if (remaining[at] === ']') brackets--;
    }
    if (brackets) return false;
    const image = remaining.slice(at).match(/^\([ \t]*(\/uploads\/[A-Za-z0-9_-]+\.(?:png|jpg|jpeg|webp))(?:[ \t]+(?:"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|\((?:\\.|[^)\\\r\n])*\)))?[ \t]*\)/);
    if (!image || !sources.has(image[1])) return false;
    remaining = remaining.slice(at + image[0].length).trim();
  }
  return true;
}
