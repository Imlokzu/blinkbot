import type { ToolStep } from './types';

export type InteractiveChoice = { label: string; description: string };
export type InteractiveTodoItem = { text: string; done: boolean };
export type InteractiveToolData =
  | { kind: 'question' | 'choice'; title: string; options: InteractiveChoice[]; allowCustom: boolean }
  | { kind: 'todo'; title: string; items: InteractiveTodoItem[] };

const MAX_CHOICES = 6;
const MAX_TODO_ITEMS = 20;
const MAX_TITLE = 400;
const MAX_LABEL = 120;
const MAX_DESCRIPTION = 200;

function cleanText(value: unknown, limit: number): string {
  return typeof value === 'string' ? value.trim().slice(0, limit) : '';
}

function toolName(label: string): string {
  return label.replace(/^(?:tools|workspace|emotions)__/, '');
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function choices(value: unknown): InteractiveChoice[] {
  if (!Array.isArray(value)) return [];
  const normalized = value.flatMap((item): InteractiveChoice[] => {
    if (typeof item === 'string' && item.trim()) return [{ label: item.trim().slice(0, MAX_LABEL), description: '' }];
    const record = asRecord(item);
    const label = cleanText(record?.label, MAX_LABEL);
    if (!label) return [];
    return [{
      label,
      description: cleanText(record?.description, MAX_DESCRIPTION),
    }];
  });
  const seen = new Set<string>();
  return normalized.filter((item) => {
    if (seen.has(item.label)) return false;
    seen.add(item.label);
    return true;
  }).slice(0, MAX_CHOICES);
}

function todoItems(value: unknown): InteractiveTodoItem[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item): InteractiveTodoItem[] => {
    if (typeof item === 'string' && item.trim()) return [{ text: item.trim().slice(0, MAX_LABEL), done: false }];
    const record = asRecord(item);
    const text = cleanText(record?.text, MAX_LABEL);
    return text ? [{ text, done: record?.done === true }] : [];
  }).slice(0, MAX_TODO_ITEMS);
}

/** Convert persisted tool arguments into the small allowlisted UI vocabulary. */
export function interactiveToolData(step: ToolStep): InteractiveToolData | null {
  if (step.status === 'failed' || step.status === 'interrupted') return null;
  const name = toolName(step.label);
  if (name !== 'ask_question' && name !== 'show_choice' && name !== 'todo_list') return null;
  const input = asRecord(step.input);
  if (!input) return null;

  if (name === 'todo_list') {
    const items = todoItems(input.items);
    return items.length ? { kind: 'todo', title: cleanText(input.title, MAX_TITLE), items } : null;
  }

  const options = choices(input.options);
  const allowCustom = name === 'ask_question' && input.allow_custom !== false;
  if (!options.length && !allowCustom) return null;
  return {
    kind: name === 'ask_question' ? 'question' : 'choice',
    title: cleanText(input.question ?? input.title, MAX_TITLE),
    options,
    allowCustom,
  };
}
