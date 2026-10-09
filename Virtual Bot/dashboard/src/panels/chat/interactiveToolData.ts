import type { ToolStep } from './types';

export type InteractiveChoice = { id?: string; label: string; description: string };
export type InteractiveTodoItem = { id?: string; text: string; done: boolean };
export type InteractiveToolData =
  | { id?: string; kind: 'question' | 'choice'; title: string; options: InteractiveChoice[]; allowCustom: boolean }
  | { id?: string; kind: 'todo'; title: string; items: InteractiveTodoItem[] };

function cleanText(value: unknown, limit: number): string {
  const whitespace = /[\u0009-\u000d\u001c-\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+/gu;
  return typeof value === 'string' ? Array.from(value.replace(whitespace, ' ').replace(/^ +| +$/g, '')).slice(0, limit).join('') : '';
}
function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function toolName(label: string): string {
  return label.replace(/^(?:tools|workspace|emotions)__/, '');
}

/** Native MCP transports may wrap the successful tool result in a text block. */
function resultRecord(value: unknown): Record<string, unknown> | null {
  const record = asRecord(value);
  if (!record) return null;
  if ('ui' in record) return record;
  for (const nested of [record.result, record.structuredContent]) {
    const data = asRecord(nested);
    if (data && 'ui' in data) return data;
  }
  if (Array.isArray(record.content)) {
    for (const entry of record.content) {
      const block = asRecord(entry);
      if (block?.type !== 'text' || typeof block.text !== 'string' || Array.from(block.text).length > 8000) continue;
      try {
        const data = asRecord(JSON.parse(block.text));
        if (data && 'ui' in data) return data;
        const nested = asRecord(data?.result);
        if (nested && 'ui' in nested) return nested;
      } catch { /* Non-JSON tool output is not a UI receipt. */ }
    }
  }
  return null;
}

function boundedText(value: unknown, limit: number): value is string {
  return typeof value === 'string' && Array.from(value).length <= limit;
}
function canonicalData(value: unknown, expected: string): InteractiveToolData | null {
  const ui = asRecord(value);
  const data = asRecord(ui?.data);
  if (!ui || !data || ui.version !== 1 || ui.kind !== expected || !boundedText(ui.id, 100) || !ui.id) return null;
  if (expected === 'todo') {
    if (!boundedText(data.title, 120) || !Array.isArray(data.items) || !data.items.length || data.items.length > 20) return null;
    const items: InteractiveTodoItem[] = [];
    for (const value of data.items) {
      const item = asRecord(value);
      if (!item || !boundedText(item.id, 40) || !item.id || !boundedText(item.text, 200) || !item.text || typeof item.done !== 'boolean') return null;
      items.push({ id: item.id, text: item.text, done: item.done });
    }
    if (new Set(items.map(item => item.id)).size !== items.length) return null;
    return { id: ui.id, kind: 'todo', title: data.title, items };
  }
  const question = expected === 'question';
  const title = question ? data.question : data.title;
  if (!boundedText(title, question ? 400 : 160) || !Array.isArray(data.options) || data.options.length > 6) return null;
  if (question && (typeof data.allow_custom !== 'boolean' || !Array.isArray(data.option_ids) || data.option_ids.length !== data.options.length)) return null;
  const options: InteractiveChoice[] = [];
  for (const [index, value] of data.options.entries()) {
    const item = question ? { id: (data.option_ids as unknown[])[index], label: value, description: '' } : asRecord(value);
    if (!item || !boundedText(item.id, 40) || !item.id || !boundedText(item.label, question ? 120 : 80) || !item.label || !boundedText(item.description, 200)) return null;
    options.push({ id: item.id, label: item.label, description: item.description });
  }
  if (new Set(options.map(item => item.id)).size !== options.length) return null;
  const allowCustom = question && data.allow_custom === true;
  if (!options.length && !allowCustom) return null;
  return { id: ui.id, kind: question ? 'question' : 'choice', title, options, allowCustom };
}

/** Legacy v0 history has no UI receipt: mirror the Python handler's limits. */
export function interactiveToolData(step: ToolStep): InteractiveToolData | null {
  if (step.status === 'failed' || step.status === 'interrupted') return null;
  const name = toolName(step.label);
  if (!['ask_question', 'show_choice', 'todo_list'].includes(name)) return null;
  const receipt = resultRecord(step.result);
  if (receipt) return canonicalData(receipt.ui, name === 'ask_question' ? 'question' : name === 'show_choice' ? 'choice' : 'todo');
  const input = asRecord(step.input);
  if (!input) return null;
  if (name === 'todo_list') {
    const items = (Array.isArray(input.items) ? input.items : []).slice(0, 20).flatMap((value): InteractiveTodoItem[] => {
      const item = asRecord(value);
      const text = cleanText(item ? item.text : value, 200);
      return text ? [{ text, done: Boolean(item?.done) }] : [];
    });
    return items.length ? { kind: 'todo', title: cleanText(input.title, 120), items } : null;
  }
  const question = name === 'ask_question';
  const values = Array.isArray(input.options) ? input.options : [];
  const options = (question ? values : values.slice(0, 6)).flatMap((value): InteractiveChoice[] => {
    const item = asRecord(value);
    const label = cleanText(item ? item.label || item.title : value, question ? 120 : 80);
    return label ? [{ label, description: question ? '' : cleanText(item?.description, 200) }] : [];
  }).slice(0, 6);
  const allowCustom = question && input.allow_custom !== false;
  if (!options.length && !allowCustom) return null;
  return { kind: question ? 'question' : 'choice', title: cleanText(question ? input.question : input.title, question ? 400 : 160), options, allowCustom };
}
