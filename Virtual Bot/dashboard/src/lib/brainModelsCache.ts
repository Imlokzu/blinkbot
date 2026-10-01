import type { BrainModel, BrainModelsResponse } from './queries';

const record = (value: unknown): value is Record<string, unknown> =>
  Boolean(value) && typeof value === 'object' && !Array.isArray(value);

function validModel(value: unknown): value is BrainModel {
  if (!record(value) || typeof value.id !== 'string' || !value.id.trim() || typeof value.label !== 'string') return false;
  for (const key of ['available', 'vision', 'fast', 'is_default', 'auto']) {
    if (value[key] !== undefined && typeof value[key] !== 'boolean') return false;
  }
  for (const key of ['context', 'seconds']) {
    if (value[key] !== undefined && (typeof value[key] !== 'number' || !Number.isFinite(value[key]) || value[key] < 0)) return false;
  }
  return true;
}

/** A browser cache must satisfy the same shape as the live catalog before use. */
export function parseBrainModelsCache(value: unknown): { data: BrainModelsResponse; savedAt: number } | undefined {
  if (!record(value) || typeof value.savedAt !== 'number' || !Number.isFinite(value.savedAt)
    || value.savedAt < 0 || value.savedAt > Date.now() || !record(value.data)) return undefined;
  const data = value.data;
  if (!Array.isArray(data.models) || !data.models.length || !data.models.every(validModel)
    || !Array.isArray(data.thinking_levels) || !data.thinking_levels.every((level) => typeof level === 'string')
    || typeof data.available !== 'boolean'
    || !['selected', 'default', 'thinking'].every((key) => typeof data[key] === 'string')) return undefined;
  return { data: data as unknown as BrainModelsResponse, savedAt: value.savedAt };
}
